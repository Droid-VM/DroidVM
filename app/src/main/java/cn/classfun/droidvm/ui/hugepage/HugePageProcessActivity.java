package cn.classfun.droidvm.ui.hugepage;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellReadFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.SIGKILL;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.SIGTERM;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.shellKillProcess;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
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
    private static final String SYSFS_BASE = "/sys/module/gh_hugepage_reserve";
    private static final String SYSFS_PARAMS = pathJoin(SYSFS_BASE, "parameters");
    private static final String SYSFS_OWNERS = pathJoin(SYSFS_PARAMS, "vm_owners");
    private static final long REFRESH_INTERVAL_MS = 2000;
    /** Acquire progress poll: ~500 ms ticks, capped so a wedged run can't poll forever. */
    private static final long ACQUIRE_POLL_MS = 500;
    private static final int ACQUIRE_POLL_MAX = 1200;   // ~10 min ceiling
    private static final long HUGE_PAGE_BYTES = 2L * 1024 * 1024;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed = false;
    private boolean scanMode = false;
    private boolean firstRefresh = true;
    private volatile boolean acquireWatching = false;
    // The KO-vs-THP fallback is resolved at most once (on entry). After that,
    // and after any manual menu choice, we never auto-switch again - otherwise a
    // single flaky root `test -e` under load would permanently bounce KO->THP.
    private volatile boolean modeResolved = false;
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
        if (ko != null) ko.setIcon(modeIcon(!scanMode));
        if (scan != null) scan.setIcon(modeIcon(scanMode));
    }

    private static int modeIcon(boolean active) {
        return active ? R.drawable.ic_circle_filled : R.drawable.ic_circle_outline;
    }

    /** User picked a mode from the menu (session-only; entry always defaults KO). */
    private void setMode(boolean scan) {
        scanMode = scan;
        modeResolved = true;   // explicit choice: never auto-switch again
        updateModeChecked();
        refresh();
    }

    /**
     * KO was selected but its attribution API is absent (module not loaded, or
     * an old .ko). Switch the effective mode to scan for this session without
     * overwriting any saved preference, and tell the user why. A present-but-
     * empty pool (no VM) never reaches here - that stays in KO.
     */
    private void fallbackToScan() {
        if (!scanMode) {
            scanMode = true;
            updateModeChecked();
            Toast.makeText(this, R.string.hugepage_proc_ko_unavailable,
                LENGTH_SHORT).show();
        }
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
            boolean useScan = scanMode;
            boolean autoSwitched = false;
            // ONE-SHOT: only on the first resolve do we consider falling back to
            // scan when the KO attribution API is absent (module not loaded, or
            // an old .ko without served_summary). After that we never re-probe,
            // so a transient root `test -e` failure can't flip a working KO view
            // to THP. A present-but-empty pool (no VM) stays in KO ("no VM").
            if (!modeResolved) {
                if (!useScan && !koApiPresent() && !koApiPresent()) {
                    // Probed twice (a single root `test -e` can flake under load);
                    // only fall back to scan if the KO API is really absent.
                    useScan = true;
                    autoSwitched = true;
                }
                modeResolved = true;
            }

            List<HugePageProcess> list = new ArrayList<>();
            String emptyText;
            if (useScan) {
                // Scan mode: system-wide THP (Anon + Shmem), no module needed.
                try {
                    list = scanProc(dark);
                } catch (Exception e) {
                    Log.w(TAG, "Scan failed", e);
                }
                emptyText = getString(R.string.hugepage_proc_scan_empty);
            } else {
                // Reconcile the served-page table on every query, then read the
                // reconciled per-owner live page counts.
                var livePages = new HashMap<Integer, Long>();
                try {
                    run("echo 1 > %s/reconcile", SYSFS_PARAMS);
                    var sum = shellReadFile(pathJoin(SYSFS_PARAMS, "served_summary"));
                    for (var ln : sum.split("\n")) {
                        ln = ln.trim();
                        if (!ln.startsWith("owner ")) continue;
                        int pid = -1;
                        long pages = 0;
                        for (var tok : ln.split("\\s+")) {
                            if (tok.startsWith("pid=")) pid = Integer.parseInt(tok.substring(4));
                            else if (tok.startsWith("pages=")) pages = Long.parseLong(tok.substring(6));
                        }
                        if (pid > 0) livePages.put(pid, pages);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "reconcile/served_summary unavailable (old .ko?)", e);
                }
                try {
                    var owners = parseOwners(shellReadFile(SYSFS_OWNERS));
                    if (!owners.isEmpty()) list = buildFromOwners(owners, dark, livePages);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to collect hugepage owners", e);
                }
                // Reached only when the KO API is present (else we auto-switched
                // to scan above), so an empty list genuinely means "no VM".
                emptyText = getString(R.string.hugepage_proc_owner_empty);
            }

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
            long holdKb, wantKb;
            var pool = readPoolPages();
            boolean kernelAcquiring = pool != null && pool.length >= 4 && pool[3] != 0;
            if (pool != null) {
                holdKb = pool[1] * 2048;   // avail
                wantKb = pool[2] * 2048;   // want
            } else {
                holdKb = 0;
                wantKb = tracedKb;
            }
            long deficitKb = wantKb - holdKb - tracedKb;
            if (deficitKb > 0) {
                list.add(new HugePageProcess(
                    0, getString(R.string.hugepage_proc_deficit), -1, deficitKb,
                    '?', null, true, HugePageColor.pending(this), false, true));
            }

            var finalList = list;
            var finalEmpty = emptyText;
            var finalAuto = autoSwitched;
            var fTraced = tracedKb;
            var fHold = holdKb;
            var fWant = wantKb;
            var fAcquiring = kernelAcquiring;
            runOnUiThread(() -> {
                if (finalAuto) fallbackToScan();
                // Re-sync the menu radio every refresh. Doing it once in onCreate
                // (right after inflateMenu, before the menu is laid out) doesn't
                // stick, leaving the radio showing the wrong mode.
                updateModeChecked();
                adapter.setAcquiring(fAcquiring || acquireWatching);
                showResult(finalList, fTraced, fHold, fWant, finalEmpty);
            });
        });
    }

    /** True if the loaded module exposes the KO attribution API (v7+). */
    private boolean koApiPresent() {
        return shellCheckExists(SYSFS_BASE)
            && shellCheckExists(pathJoin(SYSFS_PARAMS, "served_summary"));
    }

    @Nullable
    private long[] readPoolPages() {
        try {
            if (!shellCheckExists(SYSFS_BASE)) return null;
            var raw = shellReadFile(pathJoin(SYSFS_PARAMS, "refill_stat"));
            long total = -1, avail = 0, want = -1, acquiring = 0, served = 0;
            for (var line : raw.split("\n")) {
                line = line.trim();
                if (line.startsWith("pool_total=")) {
                    total = Long.parseLong(line.substring(11).trim());
                } else if (line.startsWith("pool_avail=")) {
                    avail = Long.parseLong(line.substring(11).trim());
                } else if (line.startsWith("pool_want=")) {
                    want = Long.parseLong(line.substring(10).trim());
                } else if (line.startsWith("acquire_active=")) {
                    acquiring = Long.parseLong(line.substring(15).trim());
                } else if (line.startsWith("served=")) {
                    served = Long.parseLong(line.substring(7).trim());
                }
            }
            if (total < 0) return null;
            // Old modules have no pool_want -> treat want == capacity.
            if (want < 0) want = total;
            return new long[]{total, avail, want, acquiring, served};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Batch-read live /proc state (R/S/D + liveness) and THP occupancy
     * (AnonHugePages + ShmemPmdMapped) for the given pids in a single root call.
     * Shared by KO attribution (buildFromOwners) and THP scan (scanProc).
     */
    @NonNull
    private Map<Integer, ProcInfo> readProcInfo(@NonNull Collection<Integer> pids) {
        if (pids.isEmpty()) return new HashMap<>();
        var pidList = new StringBuilder();
        for (var pid : pids) {
            if (pidList.length() > 0) pidList.append(' ');
            pidList.append(pid);
        }
        var procRaw = run(
            "for p in %s; do echo \"@@@ $p\"; " +
                "grep -E '^State:' /proc/$p/status 2>/dev/null; " +
                "grep -E '^AnonHugePages:|^ShmemPmdMapped:' " +
                "/proc/$p/smaps_rollup 2>/dev/null; " +
                "done",
            pidList.toString()
        ).getOutString();
        return parseProcInfo(procRaw);
    }

    @NonNull
    private List<HugePageProcess> buildFromOwners(
        @NonNull List<Owner> owners, boolean dark,
        @NonNull Map<Integer, Long> livePages
    ) {
        var pids = new ArrayList<Integer>();
        for (var o : owners) pids.add(o.pid);
        var procInfo = readProcInfo(pids);

        // Best-effort map pid -> VM name (only running VMs have a live pid).
        var vmMap = fetchVmMap();

        var result = new ArrayList<HugePageProcess>();
        for (var o : owners) {
            var info = procInfo.get(o.pid);
            char state = info != null ? info.state : '?';
            boolean alive = info != null && info.alive;
            // Occupancy = traced (reconciled served-page count for this owner),
            // not the /proc THP scan. /proc is only used for state + liveness.
            Long served = livePages.get(o.pid);
            long tracedKb = (served != null ? served : 0L) * 2048;
            result.add(new HugePageProcess(
                o.pid, o.comm, -1, tracedKb, state,
                vmMap.get(o.pid), alive, HugePageColor.forPid(o.pid, dark),
                false, false));
        }
        result.sort((a, b) -> Long.compare(b.thpKb, a.thpKb));
        return result;
    }

    /**
     * VM-only "scan": list the daemon's running VM processes and their actual
     * THP occupancy (AnonHugePages + ShmemPmdMapped) from smaps. We deliberately
     * do NOT scan all of /proc - system-wide THP (launchers, etc.) is noise that
     * has nothing to do with the reserve pool. Works without the kernel module
     * (ground-truth occupancy straight from each VM's smaps).
     */
    @NonNull
    private List<HugePageProcess> scanProc(boolean dark) {
        // Running VMs (pid -> name) come from the daemon.
        var vmMap = fetchVmMap();
        if (vmMap.isEmpty()) return new ArrayList<>();

        var procInfo = readProcInfo(vmMap.keySet());

        var result = new ArrayList<HugePageProcess>();
        for (var e : vmMap.entrySet()) {
            int pid = e.getKey();
            var info = procInfo.get(pid);
            char state = info != null ? info.state : '?';
            long thp = info != null ? info.thpKb : -1;
            boolean alive = info != null && info.alive;
            result.add(new HugePageProcess(
                pid, e.getValue(), -1, thp, state, e.getValue(), alive,
                HugePageColor.forPid(pid, dark), false, false));
        }
        result.sort((a, b) -> Long.compare(b.thpKb, a.thpKb));
        return result;
    }

    private static final class Owner {
        final int pid;
        final String comm;
        final long served;

        Owner(int pid, String comm, long served) {
            this.pid = pid;
            this.comm = comm;
            this.served = served;
        }
    }

    private static final class ProcInfo {
        final char state;
        final long thpKb;
        final boolean alive;

        ProcInfo(char state, long thpKb, boolean alive) {
            this.state = state;
            this.thpKb = thpKb;
            this.alive = alive;
        }
    }

    @NonNull
    private List<Owner> parseOwners(@NonNull String raw) {
        var list = new ArrayList<Owner>();
        for (var line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // Format: pid=<n> served=<n> comm=<rest>
            int pid = -1;
            long served = 0;
            String comm = "?";
            int commIdx = line.indexOf("comm=");
            if (commIdx >= 0) comm = line.substring(commIdx + 5).trim();
            for (var tok : line.split("\\s+")) {
                if (tok.startsWith("pid=")) {
                    try {
                        pid = Integer.parseInt(tok.substring(4));
                    } catch (NumberFormatException ignored) {
                    }
                } else if (tok.startsWith("served=")) {
                    try {
                        served = Long.parseLong(tok.substring(7));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (pid > 0) list.add(new Owner(pid, comm, served));
        }
        return list;
    }

    @NonNull
    private Map<Integer, ProcInfo> parseProcInfo(@NonNull String raw) {
        var map = new HashMap<Integer, ProcInfo>();
        int curPid = -1;
        char state = '?';
        long thp = -1; // -1 = unknown; becomes sum of Anon + Shmem THP
        boolean alive = false;
        for (var line : raw.split("\n")) {
            if (line.startsWith("@@@ ")) {
                if (curPid > 0)
                    map.put(curPid, new ProcInfo(state, thp, alive));
                try {
                    curPid = Integer.parseInt(line.substring(4).trim());
                } catch (NumberFormatException e) {
                    curPid = -1;
                }
                state = '?';
                thp = -1;
                alive = false;
            } else if (line.startsWith("State:")) {
                var v = line.substring(6).trim();
                if (!v.isEmpty()) state = v.charAt(0);
                alive = true; // status readable -> process exists
            } else if (line.startsWith("AnonHugePages:")
                || line.startsWith("ShmemPmdMapped:")) {
                var digits = line.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    try {
                        thp = (thp < 0 ? 0 : thp) + Long.parseLong(digits);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (curPid > 0) map.put(curPid, new ProcInfo(state, thp, alive));
        return map;
    }

    @NonNull
    private Map<Integer, String> fetchVmMap() {
        var map = new HashMap<Integer, String>();
        var latch = new CountDownLatch(1);
        DaemonConnection.getInstance().buildRequest("vm_list")
            .onResponse(resp -> {
                try {
                    var arr = resp.optJSONArray("data");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            var obj = arr.optJSONObject(i);
                            if (obj == null) continue;
                            int pid = obj.optInt("pid", -1);
                            var name = obj.optString("name", null);
                            if (pid > 0 && name != null) map.put(pid, name);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            })
            .onError(e -> latch.countDown())
            .invoke();
        try {
            //noinspection ResultOfMethodCallIgnored
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return map;
    }

    private void showResult(
        @NonNull List<HugePageProcess> list, long usedKb, long availKb,
        long wantKb, @NonNull String emptyText
    ) {
        if (isFinishing()) return;
        firstRefresh = false;
        adapter.submit(list);

        // Segmented bar: one colored segment per row, including the
        // "waiting for acquire" (deficit) row. Track = available; total = want.
        int n = list.size();
        int[] colors = new int[n];
        float[] values = new float[n];
        long seg = 0;
        for (int i = 0; i < n; i++) {
            colors[i] = list.get(i).color;
            values[i] = Math.max(0, list.get(i).thpKb);
            seg += values[i];
        }
        float total = Math.max(wantKb, seg + Math.max(0, availKb));
        segBar.setData(colors, values, total);

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
    public void onAcquire() {
        if (acquireWatching) return;            // already polling a run
        Toast.makeText(this, R.string.hugepage_proc_acquire_running, LENGTH_SHORT).show();
        acquireWatching = true;
        adapter.setAcquiring(true);             // immediate spinner feedback
        // Fire-and-poll on a dedicated thread so the shared pool executor (and
        // thus the periodic refresh that animates the climbing numbers) is never
        // blocked. The kernel 'acquire' write returns immediately; the worker
        // migrates in the background and we watch acquire_active for completion.
        new Thread(() -> {
            boolean triggered = false;
            try {
                if (shellCheckExists(pathJoin(SYSFS_PARAMS, "acquire"))) {
                    triggered = run("echo 1 > %s/acquire", SYSFS_PARAMS).isSuccess();
                } else if (shellCheckExists(pathJoin(SYSFS_PARAMS, "manual_refill"))) {
                    triggered = run("echo 1 > %s/manual_refill", SYSFS_PARAMS).isSuccess();
                }
            } catch (Exception e) {
                Log.w(TAG, "acquire trigger failed", e);
            }
            if (!triggered) {
                acquireWatching = false;
                runOnUiThread(() -> Toast.makeText(this,
                    R.string.hugepage_refill_failed, LENGTH_SHORT).show());
                return;
            }
            // Poll until the worker clears acquire_active. Each tick nudges the
            // UI to re-read refill_stat so the count visibly climbs.
            long[] pages = null;
            for (int i = 0; i < ACQUIRE_POLL_MAX; i++) {
                try { Thread.sleep(ACQUIRE_POLL_MS); } catch (InterruptedException ignored) {}
                pages = readPoolPages();      // {total, avail, want, acquiring, served}
                runOnUiThread(this::refresh); // animate climbing numbers
                if (pages == null || pages.length < 5 || pages[3] == 0) break;
            }
            var finalPages = pages;
            acquireWatching = false;
            runOnUiThread(() -> {
                if (finalPages != null && finalPages.length >= 5) {
                    // Achieved = owned + traced (pool_avail + served), NOT
                    // pool_total: after a shrink that left served pages out,
                    // pool_total under-counts the pages VMs already hold.
                    long got = finalPages[1] + finalPages[4];
                    long want = finalPages[2] >= 0 ? finalPages[2] : got;
                    String msg = got >= want
                        ? getString(R.string.hugepage_proc_acquire_full,
                            fmtPages(want))
                        : getString(R.string.hugepage_proc_acquire_partial,
                            fmtPages(got), fmtPages(want));
                    Toast.makeText(this, msg, LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, R.string.hugepage_proc_acquire_done,
                        LENGTH_SHORT).show();
                }
                refresh();
            });
        }, "hugepage-acquire").start();
    }

    /** Tapping the acquire spinner stops the run; the poll loop then finishes. */
    @Override
    public void onStopAcquire() {
        runOnPool(() -> {
            try {
                run("echo 0 > %s/acquire", SYSFS_PARAMS);
            } catch (Exception e) {
                Log.w(TAG, "stop acquire failed", e);
            }
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
