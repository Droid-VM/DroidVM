package cn.classfun.droidvm.ui.hugepage;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellReadFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.SIGKILL;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.SIGTERM;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.shellKillProcess;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.size.SizeUtils;

/**
 * Lists the processes the gh_hugepage_reserve module has served pool pages to,
 * sourced from the module's {@code vm_owners} sysfs file and enriched with live
 * /proc state. Each row offers a kill action (SIGTERM/SIGKILL); processes stuck
 * in uninterruptible sleep (D state) expose their kernel stack.
 */
public final class HugePageProcessActivity extends AppCompatActivity
    implements HugePageProcessAdapter.Listener {
    private static final String TAG = "HugePageProcessActivity";
    private static final long REFRESH_INTERVAL_MS = 2000;
    /** Acquire progress poll: ~500 ms ticks, capped so a wedged run can't poll forever. */
    private static final long ACQUIRE_POLL_MS = 500;
    private static final int ACQUIRE_POLL_MAX = 1200;   // ~10 min ceiling
    private static final long HUGE_PAGE_BYTES = 2L * 1024 * 1024;
    // Fake pids for the synthetic list rows (adapter uses pid as a stable id;
    // pid 0 already belongs to the "waiting for acquire" row).
    private static final int PID_AVAIL = -2;
    private static final int PID_CMA = -3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HugePageModel model = new HugePageModel();
    private boolean resumed = false;
    // scanMode = the user's preference; koPresent = whether the KO attribution
    // API is available. The effective mode shown is (scanMode || !koPresent) -
    // forced to scan whenever KO is absent - computed on demand so a menu tap is
    // reflected immediately instead of after the next background refresh.
    private boolean scanMode = false;
    private volatile boolean koPresent = true;
    // Edge-trigger the "KO unavailable" toast on the present->absent transition
    // only, so a stably-unavailable module doesn't re-toast every refresh.
    private boolean koWasPresent = true;
    private boolean firstRefresh = true;
    private volatile boolean acquireWatching = false;
    private volatile int acquireWatchMode = -1;   // mode we optimistically started
    private MaterialToolbar toolbar;
    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private TextView tvUsed;
    private TextView tvAvail;
    private TextView tvTotal;
    private TextView tvPoolSize;
    private SegmentedBar segBar;
    private HugePageProcessAdapter adapter;

    private final Runnable refreshRunnable = () -> {
        refresh();
        scheduleRefresh();
    };

    @Override
    @SuppressLint("RestrictedApi")  // MenuBuilder.setOptionalIconsVisible
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hugepage_process);
        toolbar = findViewById(R.id.toolbar);
        recyclerView = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tv_empty);
        tvUsed = findViewById(R.id.tv_used);
        tvAvail = findViewById(R.id.tv_avail);
        tvTotal = findViewById(R.id.tv_total);
        tvPoolSize = findViewById(R.id.tv_pool_size);
        segBar = findViewById(R.id.seg_bar);
        toolbar.setTitle(R.string.hugepage_proc_title_screen);
        toolbar.setNavigationOnClickListener(v -> finish());
        adapter = new HugePageProcessAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // Always default to KO (module attribution) on entry; refresh() auto
        // switches to THP scan only when the module is unusable.
        scanMode = false;
        toolbar.inflateMenu(R.menu.menu_hugepage_process);
        // Material hides menu-item icons in the overflow popup by default; force
        // them on so the active-mode check icon is visible.
        var menu = toolbar.getMenu();
        if (menu instanceof MenuBuilder) {
            ((MenuBuilder) menu).setOptionalIconsVisible(true);
        }
        updateModeChecked();
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_mode_ko) {
                setMode(false);
                return true;
            }
            if (id == R.id.menu_mode_scan) {
                setMode(true);
                return true;
            }
            return false;
        });
    }

    private void updateModeChecked() {
        // Radio-style: filled circle = active mode, hollow circle = inactive.
        // (overflow icons are forced visible in onCreate; a checkableBehavior
        // radio didn't redraw here, and glyphs would be non-ASCII in code.)
        var menu = toolbar.getMenu();
        var ko = menu.findItem(R.id.menu_mode_ko);
        var scan = menu.findItem(R.id.menu_mode_scan);
        // Effective mode is derived from the user's choice + KO availability, so a
        // just-tapped mode is reflected instantly (not after the next refresh);
        // the KO entry is disabled outright when its attribution API is absent.
        boolean effectiveScan = scanMode || !koPresent;
        if (ko != null) {
            ko.setIcon(modeIcon(!effectiveScan));
            ko.setEnabled(koPresent);
        }
        if (scan != null) scan.setIcon(modeIcon(effectiveScan));
    }

    private static int modeIcon(boolean active) {
        return active ? R.drawable.ic_circle_filled : R.drawable.ic_circle_outline;
    }

    /** User picked a mode from the menu (session-only preference). */
    private void setMode(boolean scan) {
        scanMode = scan;
        updateModeChecked();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
        scheduleRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(refreshRunnable);
    }

    private void scheduleRefresh() {
        if (resumed) handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    /* ================================================================== */
    /*  Data collection                                                   */
    /* ================================================================== */

    private void refresh() {
        // Only the very first load shows a "scanning" placeholder. Every later
        // refresh runs entirely in the background and atomically swaps the
        // results in via showResult(), so the list never blanks mid-scan.
        if (firstRefresh) {
            recyclerView.setVisibility(GONE);
            tvEmpty.setText(R.string.hugepage_proc_loading);
            tvEmpty.setVisibility(VISIBLE);
        }
        runOnPool(() -> {
            boolean dark = HugePageColor.isDark(this);
            // Pull the usage list through the degradation ladder: honour the user's
            // scan preference by pinning SCAN, else auto (KO, degrading to a THP scan
            // when the module's attribution knobs are absent). The winning impl says
            // what's actually shown; koAvailable() says whether KO *could* be used,
            // to keep the mode menu + toast meaningful even while showing a scan.
            var usage = model.usage(scanMode ? HugePageModel.Source.SCAN : null);
            boolean koNow = model.koAvailable();
            boolean useScan = usage.source != HugePageModel.Source.KO;

            List<HugePageProcess> list = buildFromEntries(usage.entries, dark);
            String emptyText = getString(useScan
                ? R.string.hugepage_proc_scan_empty
                : R.string.hugepage_proc_owner_empty);

            // Three-value model: hold (pages in pool) + traced (sum of all
            // applied/served processes) + waiting-to-acquire = want.
            //   traced  = sum of listed rows' occupancy
            //   hold    = pool_avail
            //   want    = pool_want (grow target)
            //   waiting = max(0, want - hold - traced)   <- one segment, no
            //             separate "unattributed"; it absorbs grow-deficit and
            //             orphaned (served-but-owner-gone) pages alike.
            long tracedKb = 0;
            for (var r : list) if (r.thpKb > 0) tracedKb += r.thpKb;
            var snap = model.state();
            boolean kernelAcquiring = snap.acquiring;
            int kernelMode = snap.acquireMode;
            long holdKb = snap.loaded ? snap.free * 2048 : 0;                // avail
            long wantKb = snap.loaded ? snap.targetIdeal * 2048 : tracedKb;  // want
            // v10 reservoir: mirror the status screen - the bar's denominator
            // becomes pool_want_with_cma and the reservoir counts as filled.
            boolean cmaOn = snap.loaded && snap.cmaActive();
            var cmaUsage = cmaOn ? model.cmaUsage() : null;
            long cmaKb = cmaOn ? snap.cmaPool * 2048 : 0;
            long cmaOtherKb = (cmaUsage != null && cmaUsage.ok)
                ? Math.min(cmaKb, cmaUsage.usedMb * 1024) : 0;
            long cmaFreeKb = cmaKb - cmaOtherKb;
            long availCmaAbleKb = (cmaOn && snap.availCmaAble >= 0)
                ? Math.min(holdKb, snap.availCmaAble * 2048) : -1;
            long availNonCmaKb = availCmaAbleKb >= 0 ? holdKb - availCmaAbleKb : 0;
            long barWantKb = cmaOn ? snap.wantWithCma * 2048 : wantKb;
            long deficitKb = barWantKb - holdKb - tracedKb - cmaKb;
            // Synthetic rows mirroring the bar blocks: available (with its
            // cma-able / non-cma-able breakdown) and the CMA reservoir
            // (free / other apps). Distinct negative pids keep the adapter's
            // stable ids unique (the deficit row already owns pid 0).
            // The deficit row owns the acquire buttons; when it is hidden but
            // acquire still has work (a grown pool_want the reservoir can stage
            // in, so nothing is "waiting to be acquired"), the CMA row shows them.
            boolean acquireOnCma = cmaOn && deficitKb <= 0 && snap.deficit > 0;
            if (snap.loaded) {
                list.add(new HugePageProcess(
                    PID_AVAIL, getString(R.string.hugepage_bar_available), -1,
                    holdKb, '?', null, true, HugePageColor.availIcon(dark),
                    true, false,
                    availCmaAbleKb >= 0 ? getString(
                        R.string.hugepage_proc_avail_detail,
                        SizeUtils.formatSize(availCmaAbleKb * 1024),
                        SizeUtils.formatSize(availNonCmaKb * 1024)) : null,
                    false));
            }
            if (cmaOn) {
                list.add(new HugePageProcess(
                    PID_CMA, getString(R.string.hugepage_bar_cma), -1,
                    cmaKb, '?', null, true, HugePageColor.cmaFree(this),
                    true, false,
                    (cmaUsage != null && cmaUsage.ok) ? getString(
                        R.string.hugepage_proc_cma_detail,
                        SizeUtils.formatSize(cmaFreeKb * 1024),
                        SizeUtils.formatSize(cmaOtherKb * 1024)) : null,
                    acquireOnCma));
            }
            if (deficitKb > 0) {
                list.add(new HugePageProcess(
                    0, getString(R.string.hugepage_proc_deficit), -1, deficitKb,
                    '?', null, true, HugePageColor.pending(this), false, true));
            }

            var finalList = list;
            var finalEmpty = emptyText;
            var fKoNow = koNow;
            var fTraced = tracedKb;
            var fHold = holdKb;
            var fAvailNonCma = availCmaAbleKb >= 0 ? availNonCmaKb : 0;
            var fCma = cmaKb;
            var fCmaFree = cmaFreeKb;
            var fBarWant = barWantKb;
            var fWant = wantKb;
            var fAcquiring = kernelAcquiring;
            var fMode = kernelMode;
            runOnUiThread(() -> {
                koPresent = fKoNow;
                // Toast only on the present->absent edge while the user wants KO,
                // so a stably-unavailable module doesn't re-toast every refresh.
                if (koWasPresent && !fKoNow && !scanMode) {
                    Toast.makeText(this, R.string.hugepage_proc_ko_unavailable,
                        LENGTH_SHORT).show();
                }
                koWasPresent = fKoNow;
                // Re-sync the menu radio every refresh. Doing it once in onCreate
                // (right after inflateMenu, before the menu is laid out) doesn't
                // stick, leaving the radio showing the wrong mode.
                updateModeChecked();
                int uiMode = fAcquiring ? fMode
                    : (acquireWatching ? acquireWatchMode : -1);
                adapter.setAcquireState(fAcquiring || acquireWatching, uiMode);
                showResult(finalList, fTraced, fHold, fAvailNonCma,
                    fCma, fCmaFree, fBarWant, fWant, finalEmpty);
            });
        });
    }

    /** Map ladder usage entries to display rows (color + VM-name enrichment). */
    @NonNull
    private List<HugePageProcess> buildFromEntries(
        @NonNull List<HugePageModel.UsageEntry> entries, boolean dark
    ) {
        // Best-effort pid -> VM name (running VMs only); for KO rows this labels
        // the process, for scan rows it echoes the name the entry already carries.
        var vmMap = model.vmNames(false);
        // Rank-based colors over the full entry list (see HugePageColor).
        var pids = new ArrayList<Integer>();
        for (var e : entries) pids.add(e.pid);
        var colorMap = HugePageColor.forPids(pids, dark);
        var result = new ArrayList<HugePageProcess>();
        for (var e : entries) {
            Integer color = colorMap.get(e.pid);
            result.add(new HugePageProcess(
                e.pid, e.comm, -1, e.pages * 2048, e.state,
                vmMap.getOrDefault(e.pid, e.comm), e.alive,
                color != null ? color : HugePageColor.forRank(0, dark),
                false, false));
        }
        result.sort((a, b) -> Long.compare(b.thpKb, a.thpKb));
        return result;
    }

    private void showResult(
        @NonNull List<HugePageProcess> list, long usedKb, long availKb,
        long availNonCmaKb, long cmaKb, long cmaFreeKb, long barWantKb,
        long wantKb, @NonNull String emptyText
    ) {
        if (isFinishing()) return;
        firstRefresh = false;
        adapter.submit(list);

        // Segmented bar, synced with the status screen: VM segments (used), the
        // available block (with its non-cma-able left sub-split), the CMA
        // reservoir block ([free|other apps] sub-split), then the "waiting for
        // acquire" deficit flush right. Still a plain unlabelled meter here.
        // The synthetic available/CMA rows ("unknown") are drawn via those
        // dedicated blocks, not as used segments.
        int used = 0;
        for (var p : list) if (!p.acquire && !p.unknown) used++;
        int[] usedColors = new int[used];
        float[] usedValues = new float[used];
        int deficitColor = HugePageColor.pending(this);
        long deficit = 0;
        int u = 0;
        for (var p : list) {
            if (p.acquire) {                       // deficit rows: colour + value
                deficitColor = p.color;
                deficit += Math.max(0, p.thpKb);
            } else if (!p.unknown) {               // used: one segment per VM
                usedColors[u] = p.color;
                usedValues[u] = Math.max(0, p.thpKb);
                u++;
            }
        }
        var spec = new SegmentedBar.StorageSpec();
        spec.usedColors = usedColors;
        spec.usedValues = usedValues;
        spec.avail = availKb;
        spec.availNonCma = availNonCmaKb;
        spec.availNonCmaColor = HugePageColor.availNonCma(this);
        spec.cmaFree = cmaFreeKb;
        spec.cmaFreeColor = HugePageColor.cmaFree(this);
        spec.cmaOther = cmaKb - cmaFreeKb;
        spec.cmaOtherColor = HugePageColor.cmaUsed(this);
        spec.deficitColor = deficitColor;
        spec.deficit = deficit;
        spec.want = barWantKb;
        segBar.setStorage(spec);

        // 2x2 caption: used / available on top, total / pool-size below.
        // Total = the real held reserve (used + avail = owned + traced), shown
        // raw with no clamp to pool size: if the kernel ever fails to release
        // pages on a shrink, total exceeds the pool size you set, surfacing it
        // instead of hiding it.
        long heldKb = usedKb + availKb;
        tvUsed.setText(getString(R.string.hugepage_stat_pool_used,
            usedKb / 2048, SizeUtils.formatSize(usedKb * 1024)));
        tvAvail.setText(getString(R.string.hugepage_stat_pool_available,
            availKb / 2048, SizeUtils.formatSize(availKb * 1024)));
        tvTotal.setText(getString(R.string.hugepage_stat_pool_total,
            heldKb / 2048, SizeUtils.formatSize(heldKb * 1024)));
        tvPoolSize.setText(getString(R.string.hugepage_stat_pool_size,
            wantKb / 2048, SizeUtils.formatSize(wantKb * 1024)));

        if (list.isEmpty()) {
            tvEmpty.setText(emptyText);
            tvEmpty.setVisibility(VISIBLE);
            recyclerView.setVisibility(GONE);
        } else {
            tvEmpty.setVisibility(GONE);
            recyclerView.setVisibility(VISIBLE);
        }
    }

    /* ================================================================== */
    /*  Actions                                                           */
    /* ================================================================== */

    @Override
    public void onKill(@NonNull HugePageProcess proc) {
        var label = getString(R.string.hugepage_proc_title, proc.comm, proc.pid);
        var signals = new String[]{
            getString(R.string.hugepage_proc_sigterm),
            getString(R.string.hugepage_proc_sigkill),
        };
        final int[] sel = {0}; // default SIGTERM
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.hugepage_proc_kill_title, label))
            .setSingleChoiceItems(signals, 0, (d, w) -> sel[0] = w)
            .setPositiveButton(R.string.hugepage_proc_kill,
                (d, w) -> doKill(proc, sel[0] == 0 ? SIGTERM : SIGKILL))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void doKill(@NonNull HugePageProcess proc, int signal) {
        runOnPool(() -> {
            var ok = shellKillProcess(proc.pid, signal);
            runOnUiThread(() -> {
                Toast.makeText(this, ok ?
                        R.string.hugepage_proc_kill_sent :
                        R.string.hugepage_proc_kill_failed,
                    LENGTH_SHORT).show();
                refresh();
            });
        });
    }

    @Override
    public void onAcquire(int mode) {
        if (acquireWatching) return;            // already polling a run
        Toast.makeText(this, R.string.hugepage_proc_acquire_running, LENGTH_SHORT).show();
        acquireWatching = true;
        acquireWatchMode = mode;                // remember which we just started
        adapter.setAcquireState(true, mode);    // immediate spinner feedback
        // Fire-and-poll on a dedicated thread so the shared pool executor (and
        // thus the periodic refresh that animates the climbing numbers) is never
        // blocked. The kernel 'acquire' write returns immediately; the worker
        // migrates in the background and we watch acquire_active for completion.
        new Thread(() -> {
            // Fill the pool via the acquire ladder: the migrating 'acquire' knob,
            // degrading to 'manual_refill' on any failure (e.g. -ENOSYS on kernels
            // that can't migrate). The trace's last entry says what actually ran.
            var res = model.acquire(mode);
            boolean triggered = res.ok();
            // Only a migrating acquire mode exposes acquire_active to poll; the
            // manual_refill fallback is fire-and-forget with no progress to watch.
            boolean migrating = triggered && !"manual_refill".equals(res.impl);
            final boolean degraded = res.degraded;      // ran below the requested mode
            final String actualImpl = res.impl != null ? res.impl : "";
            if (!triggered) {
                acquireWatching = false;
                runOnUiThread(() -> Toast.makeText(this,
                    R.string.hugepage_refill_failed, LENGTH_SHORT).show());
                return;
            }
            if (!migrating) {
                // Fell all the way to manual_refill: async, no acquire_active/served
                // to poll. Name the fallback (it is itself the degradation).
                acquireWatching = false;
                runOnUiThread(() -> {
                    Toast.makeText(this,
                        getString(R.string.hugepage_acquire_degraded, actualImpl),
                        LENGTH_LONG).show();
                    refresh();
                });
                return;
            }
            // Migrating: poll until the worker clears acquire_active. Each tick nudges
            // the UI to re-read refill_stat so the count visibly climbs.
            boolean running = true;
            for (int i = 0; i < ACQUIRE_POLL_MAX && running; i++) {
                try { Thread.sleep(ACQUIRE_POLL_MS); } catch (InterruptedException ignored) {}
                runOnUiThread(this::refresh);   // animate climbing numbers (reads full state)
                running = model.acquiring();    // cheap acquire_active-only probe
            }
            var finalSnap = model.state();      // one full read for the final got/want
            acquireWatching = false;
            runOnUiThread(() -> {
                if (finalSnap != null && finalSnap.loaded) {
                    // Achieved = owned + traced (pool_avail + served), NOT
                    // pool_total: after a shrink that left served pages out,
                    // pool_total under-counts the pages VMs already hold. With
                    // the reservoir on the message reports the with-CMA total too.
                    Toast.makeText(this,
                        HugePageActivity.acquireDoneMessage(this, finalSnap),
                        LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, R.string.hugepage_proc_acquire_done,
                        LENGTH_SHORT).show();
                }
                // Requested a higher mode than could run (e.g. v3 -> v2) -> name it.
                if (degraded) Toast.makeText(this,
                    getString(R.string.hugepage_acquire_degraded, actualImpl),
                    LENGTH_LONG).show();
                refresh();
            });
        }, "hugepage-acquire").start();
    }

    /** Short-press an acquire button: confirm (unless the user opted out), then run. */
    @Override
    public void onAcquireConfirm(int mode) {
        HugePageActivity.confirmAcquire(this, mode, () -> onAcquire(mode));
    }

    /** Long-press an acquire button: explain the mode, with a Run/Cancel choice. */
    @Override
    public void onAcquireInfo(int mode) {
        HugePageActivity.showAcquireInfo(this, mode, () -> onAcquire(mode));
    }

    /** Tapping the acquire spinner stops the run; the poll loop then finishes. */
    @Override
    public void onStopAcquire() {
        runOnPool(() -> {
            model.stopAcquire();
            runOnUiThread(this::refresh);
        });
    }

    /** Format a huge-page count (2 MiB each) as a human size, e.g. "5.84 GiB". */
    private static String fmtPages(long pages) {
        return SizeUtils.formatSize(pages * HUGE_PAGE_BYTES);
    }

    @Override
    public void onShowStack(@NonNull HugePageProcess proc) {
        runOnPool(() -> {
            String stack;
            try {
                stack = shellReadFile(fmt("/proc/%d/stack", proc.pid));
            } catch (Exception e) {
                stack = "";
            }
            if (stack.trim().isEmpty())
                stack = getString(R.string.hugepage_proc_stack_unavailable);
            var finalStack = stack;
            runOnUiThread(() -> showStackDialog(proc, finalStack));
        });
    }

    private void showStackDialog(@NonNull HugePageProcess proc, @NonNull String stack) {
        if (isFinishing()) return;
        var tv = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setText(stack);
        var scroll = new androidx.core.widget.NestedScrollView(this);
        scroll.addView(tv);
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.hugepage_proc_stack_title, proc.pid))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }
}
