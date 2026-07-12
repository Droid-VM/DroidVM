package cn.classfun.droidvm.ui.hugepage;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellReadFile;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.lib.ui.MaterialMenu.setupToolbarMenu;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import android.text.Editable;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.size.SizeUtils;
import cn.classfun.droidvm.lib.ui.SimpleTextWatcher;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextRowWidget;

public final class HugePageActivity extends AppCompatActivity {
    private static final String TAG = "HugePageActivity";
    private static final String MAGISK_BASE = "/data/adb/modules/gh-hugepage-reserve";
    private static final String SETTINGS_PROP = pathJoin(MAGISK_BASE, "settings.prop");
    private static final String DISABLE_FILE = pathJoin(MAGISK_BASE, "disable");
    private static final String CRASH_FILE = pathJoin(MAGISK_BASE, "crash");
    private static final long PAGE_SIZE = 2L * 1024 * 1024; // 2MiB per page
    // Shared app prefs (same store as Privacy/ApiManager) + the "don't ask again"
    // flag for the acquire confirm dialog.
    private static final String PREFS_NAME = "droidvm_prefs";
    private static final String KEY_SKIP_ACQUIRE_CONFIRM = "hugepage_acquire_skip_confirm";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HugePageModel model = new HugePageModel();
    private boolean resumed = false;
    private MaterialToolbar toolbar;
    private MaterialCardView cardCrashWarning;
    private MaterialCardView cardNotLoaded;
    private TextInputRowWidget inputPoolSize;
    private MaterialButton btnSavePoolSize;
    private View progressSavePoolSize;
    private ColorStateList saveTextColors;
    private MaterialButton btnModuleToggle;
    private boolean moduleInstalled = false;
    private boolean moduleLoaded = false;
    private boolean moduleHasPoolWant = false;
    private boolean moduleSoftDisabled = false;
    private boolean moduleAcquiring = false;
    // Drives the acquire-mode slots (v1/v2/v3): true while a run is in flight.
    // Set optimistically on tap, then reconciled from acquire_active
    // (readPoolPages()[3]) by the periodic status refresh.
    private boolean mainAcquiring = false;
    private int mainAcquireMode = -1;   // running v (1/2/3); -1 = unknown (old module)
    private boolean wasAcquiring = false;   // last-seen acquire_active, for the "done" toast
    // Acquire buttons usable only when the module is loaded and there is a deficit
    // to fill (avail+served < want); disabled at target, unloaded, or soft-disabled.
    private boolean acquireEnabled = false;
    private MaterialButton btnViewProcesses;
    private View btnAcquireV1;
    private View btnAcquireV2;
    private View btnAcquireV3;
    private View progressAcquireV1;
    private View progressAcquireV2;
    private View progressAcquireV3;
    private SegmentedBar segPoolBar;
    private TextView tvPoolUsed;
    private TextView tvPoolAvail;
    private TextView tvPoolTotal;
    private TextView tvPoolSize;
    private SwitchRowWidget rowModuleEnable;
    // v10 CMA reservoir controls. The right input is the TOTAL with-CMA pool
    // size (pool_want_with_cma), not the reservoir delta.
    private TextInputRowWidget inputCmaSize;
    private SwitchRowWidget rowCmaEnable;
    private boolean cmaSwitchSyncing = false;   // programmatic setChecked guard
    private boolean cmaInputLoaded = false;     // seed the CMA size input once per show
    private boolean cmaBusy = false;            // a probe / toggle flow is in flight
    // A reservoir target persisted with no verdict recorded = a probe that asked
    // for a reboot and is now waiting to be finished. Offer it once per screen.
    private boolean probePromptShown = false;
    // Two-way size link (pool_want <= pool_want_with_cma): which input the
    // user touched last decides who yields when they cross.
    private static final int SIZE_EDIT_POOL = 1;
    private static final int SIZE_EDIT_CMA = 2;
    private int lastSizeEdit = SIZE_EDIT_POOL;
    private boolean sizeLinkSyncing = false;    // programmatic setBigValue guard
    /** Balloon floor (MB) for the consumability probe - `balloon 1536`. */
    private static final long BALLOON_FLOOR_MB = 1536;
    private static final long BALLOON_TIMEOUT_S = 600;
    /**
     * How much reservoir the probe wants to measure against:
     * {@code max(RAM - 8G, RAM * 0.4)}. RAM here is MemTotal - physical pages
     * only, so zram/swap capacity (SwapTotal) never inflates it. Advisory, not
     * a precondition: a smaller reservoir still probes, it just makes the
     * verdict less reliable, and the user is warned before continuing.
     */
    private static final long PROBE_KEEP_BYTES = 8L << 30;   // 8 GiB
    private static final double PROBE_MIN_RAM_FRACTION = 0.4;
    /**
     * Pool size a passing probe leaves behind. The probe has just proved apps
     * can consume the reservoir, so holding a large pool would waste memory the
     * reservoir would otherwise lend out: pin the pool small and let
     * pool_want_with_cma (kept at the size the probe assembled) carry the rest
     * as reservoir. A VM start stages pages back in on demand.
     */
    private static final long PROBE_POOL_BYTES = 512L << 20;   // 512 MiB
    private static final long PROBE_POOL_PAGES = PROBE_POOL_BYTES / PAGE_SIZE;
    private TextRowWidget rowStatState;
    private TextRowWidget rowStatTotalServed;
    private TextRowWidget rowStatTotalRefilled;
    private TextRowWidget rowStatActiveVms;

    private final Runnable refreshRunnable = () -> {
        refreshStatus();
        scheduleRefresh();
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hugepage);
        toolbar = findViewById(R.id.toolbar);
        cardCrashWarning = findViewById(R.id.card_crash_warning);
        cardNotLoaded = findViewById(R.id.card_not_installed);
        inputPoolSize = findViewById(R.id.input_pool_size);
        btnSavePoolSize = findViewById(R.id.btn_save_pool_size);
        progressSavePoolSize = findViewById(R.id.progress_save_pool_size);
        saveTextColors = btnSavePoolSize.getTextColors();
        btnModuleToggle = findViewById(R.id.btn_module_toggle);
        btnViewProcesses = findViewById(R.id.btn_view_processes);
        btnAcquireV1 = findViewById(R.id.btn_acquire_v1);
        btnAcquireV2 = findViewById(R.id.btn_acquire_v2);
        btnAcquireV3 = findViewById(R.id.btn_acquire_v3);
        progressAcquireV1 = findViewById(R.id.progress_acquire_v1);
        progressAcquireV2 = findViewById(R.id.progress_acquire_v2);
        progressAcquireV3 = findViewById(R.id.progress_acquire_v3);
        segPoolBar = findViewById(R.id.seg_pool_bar);
        tvPoolUsed = findViewById(R.id.tv_pool_used);
        tvPoolAvail = findViewById(R.id.tv_pool_avail);
        tvPoolTotal = findViewById(R.id.tv_pool_total);
        tvPoolSize = findViewById(R.id.tv_pool_size);
        rowModuleEnable = findViewById(R.id.row_module_enable);
        inputCmaSize = findViewById(R.id.input_cma_size);
        rowCmaEnable = findViewById(R.id.row_cma_enable);
        rowStatState = findViewById(R.id.row_stat_state);
        rowStatTotalServed = findViewById(R.id.row_stat_total_served);
        rowStatTotalRefilled = findViewById(R.id.row_stat_total_refilled);
        rowStatActiveVms = findViewById(R.id.row_stat_active_vms);
        initialize();
    }

    private void initialize() {
        toolbar.setTitle(R.string.hugepage_title);
        toolbar.setNavigationOnClickListener(v -> finish());
        setupToolbarMenu(toolbar, R.menu.menu_hugepage, this::onMenuItemClicked);
        btnSavePoolSize.setOnClickListener(v -> {
            if (moduleAcquiring) interruptAcquire();
            else savePoolSize();
        });
        rowModuleEnable.setOnCheckedChangeListener(this::doToggleModule);
        rowCmaEnable.setOnCheckedChangeListener((btn, checked) -> onCmaSwitchChanged(checked));
        // Two-way link between the pool size and the with-CMA total: track who
        // was edited last, reconcile whenever a field is left (and again at
        // save, since tapping Save doesn't steal the EditText's focus).
        inputPoolSize.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!sizeLinkSyncing) lastSizeEdit = SIZE_EDIT_POOL;
            }
        });
        inputCmaSize.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!sizeLinkSyncing) lastSizeEdit = SIZE_EDIT_CMA;
            }
        });
        inputPoolSize.setOnFocusLostListener(this::reconcileSizeLink);
        inputCmaSize.setOnFocusLostListener(this::reconcileSizeLink);
        // One button:
        //   not installed        -> Install (open releases page)
        //   installed, unloaded  -> Enable (insmod)
        //   loaded, soft-disabled-> Enable (restore pool_want + acquire)
        //   loaded (v7)          -> Disable (shrink pool to 1 page, stay loaded
        //                           so per-VM tracking is never lost; no save)
        //   loaded (v6, no knob) -> Disable (rmmod)
        btnModuleToggle.setOnClickListener(v -> {
            if (!moduleInstalled) openModulePage();
            else if (!moduleLoaded || moduleSoftDisabled) doEnable();
            else if (moduleHasPoolWant) doDisable();   // v7: soft-disable (pool_want=0)
            else confirmUnload();                       // v6: no soft knob -> confirm rmmod
        });
        btnViewProcesses.setOnClickListener(v -> startActivity(
            new Intent(this, HugePageProcessActivity.class)));
        // Acquire-mode slots: each button starts its mode; each spinner (shown
        // while a run is in flight) interrupts it. Listeners are static -- the
        // slots aren't recycled -- and the idle/running visibility toggle is
        // driven by applyAcquireState().
        // Short-press opens the what-does-this-do dialog (or, once the user ticks
        // "don't ask again", runs straight away) - gated to the pressable state.
        // Long-press always opens the dialog, even when the button is greyed (the
        // buttons stay enabled for that), so the prompt can be re-summoned any time.
        btnAcquireV1.setOnClickListener(v -> { if (acquireEnabled && !mainAcquiring) confirmAcquire(this, 1, () -> startAcquire(1)); });
        btnAcquireV2.setOnClickListener(v -> { if (acquireEnabled && !mainAcquiring) confirmAcquire(this, 2, () -> startAcquire(2)); });
        btnAcquireV3.setOnClickListener(v -> { if (acquireEnabled && !mainAcquiring) confirmAcquire(this, 3, () -> startAcquire(3)); });
        btnAcquireV1.setOnLongClickListener(v -> { showAcquireInfo(this, 1, () -> startAcquire(1)); return true; });
        btnAcquireV2.setOnLongClickListener(v -> { showAcquireInfo(this, 2, () -> startAcquire(2)); return true; });
        btnAcquireV3.setOnLongClickListener(v -> { showAcquireInfo(this, 3, () -> startAcquire(3)); return true; });
        View.OnClickListener stopAcquire = v -> stopMainAcquire();
        progressAcquireV1.setOnClickListener(stopAcquire);
        progressAcquireV2.setOnClickListener(stopAcquire);
        progressAcquireV3.setOnClickListener(stopAcquire);
        applyAcquireState();
        cardCrashWarning.setOnClickListener(v -> doDismissCrash());
        loadPoolSize();
    }

    /**
     * Reflect {@link #mainAcquiring}/{@link #mainAcquireMode} on the three slots:
     *   idle                      -> all enabled buttons, no spinners;
     *   running, mode unknown(-1) -> all three spin (module can't report the mode);
     *   running, mode 1/2/3       -> that slot spins, the other two are disabled buttons.
     * Click listeners are static (set in initialize); a disabled button ignores taps.
     */
    private void applyAcquireState() {
        applyAcquireSlot(btnAcquireV1, progressAcquireV1, 1);
        applyAcquireSlot(btnAcquireV2, progressAcquireV2, 2);
        applyAcquireSlot(btnAcquireV3, progressAcquireV3, 3);
    }

    private void applyAcquireSlot(View btn, View spinner, int mode) {
        boolean spin = mainAcquiring && (mainAcquireMode < 0 || mainAcquireMode == mode);
        btn.setVisibility(spin ? GONE : VISIBLE);
        // Left enabled so a long-press always opens the info dialog; the short-press
        // is gated in its click handler, and the icon+badge greys via alpha when it
        // can't run (a run in flight, or no deficit / not loaded).
        btn.setAlpha((!mainAcquiring && acquireEnabled) ? 1f : 0.38f);
        spinner.setVisibility(spin ? VISIBLE : GONE);
    }

    /**
     * Short-press an acquire button: run straight away if the user ticked "don't
     * ask again" in a past prompt, otherwise show the confirm dialog. Long-press
     * bypasses this and always shows the dialog (see {@link #showAcquireInfo}).
     */
    static void confirmAcquire(@NonNull Context ctx, int mode, @NonNull Runnable onRun) {
        if (skipAcquireConfirm(ctx)) onRun.run();
        else showAcquireInfo(ctx, mode, onRun);
    }

    /**
     * Explain what the acquire mode does, with a Run/Cancel choice and a "don't ask
     * again" checkbox. The checkbox seeds from and (on any dismiss) writes back the
     * skip preference, so it doubles as the way to re-enable the prompt after opting
     * out; Run additionally starts the acquire. Shared by both hugepage screens, for
     * short-press (via {@link #confirmAcquire}) and long-press alike. The title is
     * always "Acquire huge pages" - the mode is conveyed by the explanation text.
     */
    static void showAcquireInfo(@NonNull Context ctx, int mode, @NonNull Runnable onRun) {
        int msg = mode == 2 ? R.string.hugepage_acquire_v2_explain
            : mode == 3 ? R.string.hugepage_acquire_v3_explain
            : R.string.hugepage_acquire_v1_explain;
        // "Don't ask again", indented to line up with the dialog's message text.
        float density = ctx.getResources().getDisplayMetrics().density;
        var dontAsk = new MaterialCheckBox(ctx);
        dontAsk.setText(R.string.hugepage_acquire_dont_ask);
        dontAsk.setChecked(skipAcquireConfirm(ctx));
        var holder = new FrameLayout(ctx);
        int padH = Math.round(24 * density);
        holder.setPaddingRelative(padH, Math.round(4 * density), padH, 0);
        holder.addView(dontAsk);
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.hugepage_acquire_pages_title)   // no v1/v2/v3 suffix
            .setMessage(msg)
            .setView(holder)
            .setPositiveButton(R.string.hugepage_acquire_run, (d, w) -> onRun.run())
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener(d -> setSkipAcquireConfirm(ctx, dontAsk.isChecked()))
            .show();
    }

    /**
     * The "acquire finished" bubble, shared by both hugepage screens: how much
     * the pool reached of its target, and - while the v10 reservoir is on - the
     * with-CMA total too. Both matter because acquire's own stop condition
     * covers both (see {@link HugePageModel.Snapshot#deficit}): a pool that hit
     * its target while the reservoir is still short is not "complete", and a
     * grown pool_want is filled by staging reservoir pages in, which moves the
     * pool number without moving the total.
     */
    @NonNull
    static String acquireDoneMessage(
        @NonNull Context ctx, @NonNull HugePageModel.Snapshot snap
    ) {
        long gotPool = snap.free + snap.lent;
        long wantPool = snap.targetIdeal;
        if (!snap.cmaActive()) {
            return gotPool >= wantPool
                ? ctx.getString(R.string.hugepage_proc_acquire_full, pageSize(wantPool))
                : ctx.getString(R.string.hugepage_proc_acquire_partial,
                    pageSize(gotPool), pageSize(wantPool));
        }
        long gotTotal = gotPool + snap.cmaPool;
        long wantTotal = snap.wantWithCma;
        return (gotPool >= wantPool && gotTotal >= wantTotal)
            ? ctx.getString(R.string.hugepage_proc_acquire_full_cma,
                pageSize(wantPool), pageSize(wantTotal))
            : ctx.getString(R.string.hugepage_proc_acquire_partial_cma,
                pageSize(gotPool), pageSize(wantPool),
                pageSize(gotTotal), pageSize(wantTotal));
    }

    @NonNull
    private static String pageSize(long pages) {
        return SizeUtils.formatSize(pages * PAGE_SIZE);
    }

    /** True once the user opted out of the acquire prompt (short-press runs directly). */
    static boolean skipAcquireConfirm(@NonNull Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SKIP_ACQUIRE_CONFIRM, false);
    }

    private static void setSkipAcquireConfirm(@NonNull Context ctx, boolean skip) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SKIP_ACQUIRE_CONFIRM, skip).apply();
    }

    /**
     * Start filling the pool with acquire algorithm {@code mode} (see
     * {@link HugePageModel#acquire}). Optimistically shows the spinners, fires the
     * knob write on a dedicated thread (so the pool executor and its status poll
     * stay free), and lets the periodic refresh reconcile the spinner from
     * acquire_active. A failed trigger reverts immediately.
     */
    private void startAcquire(int mode) {
        if (mainAcquiring) return;              // already running
        Toast.makeText(this, R.string.hugepage_proc_acquire_running, LENGTH_SHORT).show();
        mainAcquiring = true;
        mainAcquireMode = mode;                 // we know which we just started
        applyAcquireState();                    // immediate spinner feedback
        new Thread(() -> {
            var res = model.acquire(mode);
            if (!res.ok()) {
                mainAcquiring = false;
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.hugepage_refill_failed,
                        LENGTH_SHORT).show();
                    applyAcquireState();
                });
                return;
            }
            // Triggered: the periodic refresh reads acquire_active and keeps the
            // spinner up until the worker clears it. This screen doesn't track
            // completion, so if the mode degraded below what was asked, say so now.
            final String actualImpl = res.impl != null ? res.impl : "";
            final boolean degraded = res.degraded;
            runOnUiThread(() -> {
                if (degraded) Toast.makeText(this,
                    getString(R.string.hugepage_acquire_degraded, actualImpl),
                    Toast.LENGTH_LONG).show();
                refreshStatus();
            });
        }, "hugepage-acquire").start();
    }

    /** Tapping an acquire spinner interrupts the run; the refresh clears the flag. */
    private void stopMainAcquire() {
        runOnPool(() -> {
            model.stopAcquire();
            runOnUiThread(this::refreshStatus);
        });
    }

    private void openModulePage() {
        var url = "https://github.com/Droid-VM/gh-hugepage-reserve/releases";
        var intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshStatus();
        scheduleRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(refreshRunnable);
    }

    private void scheduleRefresh() {
        if (resumed) handler.postDelayed(refreshRunnable, 1000);
    }

    private void refreshStatus() {
        runOnPool(() -> {
            // One version-unified read of module + pool state.
            var snap = model.state();
            var crashStamp = shellCheckExists(CRASH_FILE);
            // Per-VM breakdown through the usage ladder (KO attribution, degrading
            // to a THP scan of running VMs), so this bar matches the usage screen.
            // Each segment is labelled with the friendly VM name from vmMap.
            // allPids is the unfiltered owner list - the rank-based color map
            // must see every pid (both screens derive ranks from the same list).
            List<long[]> owners = new ArrayList<>();
            List<Integer> allPids = new ArrayList<>();
            if (snap.loaded) {
                for (var e : model.usage(null).entries) {
                    allPids.add(e.pid);
                    if (e.pages > 0) owners.add(new long[]{e.pid, e.pages});
                }
            }
            // Only fetch VM names when there are rows to label.
            Map<Integer, String> vmMap = owners.isEmpty()
                ? new LinkedHashMap<>() : model.vmNames(false);
            // Reservoir occupancy for the two-tone CMA block (module caches ~1s).
            var cmaUsage = snap.cmaActive() ? model.cmaUsage() : null;
            // A reservoir built (or being built) toward a target nobody ever
            // judged: the "save and reboot" branch of the probe left it here.
            boolean probePending = !probePromptShown && !cmaBusy
                && snap.cmaActive() && model.cmaProbeResult() == null;
            runOnUiThread(() -> {
                updateUI(snap, crashStamp, owners, allPids, vmMap, cmaUsage);
                if (probePending) promptPendingProbe();
            });
        });
    }

    @NonNull
    private Map<String, String> parseProp(@NonNull String raw) {
        var map = new LinkedHashMap<String, String>();
        for (var line : raw.split("\n")) {
            var parts = line.split("=", 2);
            if (parts.length == 2)
                map.put(parts[0].trim(), parts[1].trim());
        }
        return map;
    }

    private void setPagesString(@NonNull TextView tv, @StringRes int str, long pages) {
        tv.setText(getString(str, pages, SizeUtils.formatSize(pages * PAGE_SIZE)));
    }

    private void updateUI(
        @NonNull HugePageModel.Snapshot snap, boolean crashed,
        @NonNull List<long[]> owners,
        @NonNull List<Integer> allPids,
        @NonNull Map<Integer, String> vmMap,
        @Nullable HugePageModel.CmaUsage cmaUsage
    ) {
        if (isFinishing()) return;
        cardCrashWarning.setVisibility(crashed ? VISIBLE : GONE);
        cardNotLoaded.setVisibility(snap.loaded ? GONE : VISIBLE);
        if (snap.loaded && snap.statsOk) {
            rowStatState.setValue(snap.state);
            rowStatTotalServed.setValue(snap.totalServed);
            rowStatTotalRefilled.setValue(snap.totalRefilled);
            rowStatActiveVms.setValue(snap.activeVms);
            var poolAvail = snap.free;
            // "total" shows the desired target - the model's version-unified
            // want (pool_want, else v6 pool_target, else current capacity).
            var poolWant = snap.targetIdeal;
            boolean cmaOn = snap.cmaActive();
            // Apple-storage-bar style: one labelled colored block per VM
            // (used), then the available portion as a track-coloured gap,
            // then (v10) the CMA reservoir split into occupied-by-apps and
            // free halves, then the waiting-to-acquire (deficit) block pinned
            // flush right. Each block draws its label inside if wide enough.
            boolean dark = HugePageColor.isDark(this);
            // Rank-based colors over the full owner list, so adjacent VM
            // segments never land on near-identical hues (see HugePageColor).
            var colorMap = HugePageColor.forPids(allPids, dark);
            int n = owners.size();
            int[] usedColors = new int[n];
            float[] usedValues = new float[n];
            String[] usedLabels = new String[n];
            long seg = 0;
            for (int i = 0; i < n; i++) {
                int pid = (int) owners.get(i)[0];
                long ownerPages = owners.get(i)[1];
                Integer color = colorMap.get(pid);
                usedColors[i] = color != null ? color : HugePageColor.forRank(i, dark);
                usedValues[i] = ownerPages;
                String name = vmMap.get(pid);
                if (name == null) name = getString(R.string.hugepage_proc_pid, pid);
                // Two stacked lines: label over capacity.
                usedLabels[i] = fmt("%s\n%s", name, SizeUtils.formatSize(ownerPages * PAGE_SIZE));
                seg += ownerPages;
            }
            // "used" is the sum of the segments the bar draws, so the caption
            // and the bar always agree (one canonical quantity: the per-VM
            // served/scanned pages). The kernel 'served' counter can include
            // orphaned owner-gone pages that have no segment; those surface in
            // the held/available gap rather than as an invisible caption delta.
            long used = seg;
            // With the reservoir on, the bar's denominator is the overall
            // target pool_want_with_cma and the reservoir counts as filled.
            long cmaPool = cmaOn ? snap.cmaPool : 0;
            long barWant = cmaOn ? snap.wantWithCma : poolWant;
            long deficit = Math.max(0, barWant - seg - poolAvail - cmaPool);
            // Reservoir occupancy split (pages): still-free vs held by other
            // apps right now; unknown occupancy shows one undivided free block.
            boolean cmaUsageOk = cmaOn && cmaUsage != null && cmaUsage.ok;
            long cmaOther = cmaUsageOk
                ? Math.min(cmaPool, cmaUsage.usedMb / (PAGE_SIZE / (1024 * 1024)))
                : 0;
            long cmaFree = cmaPool - cmaOther;
            // Avail sub-split: pages flippable to CMA as whole pageblocks vs
            // not (pool_avail_cma_able); -1 = unreported, no split shown.
            long availCmaAble = (cmaOn && snap.availCmaAble >= 0)
                ? Math.min(poolAvail, snap.availCmaAble) : -1;
            long availNonCma = availCmaAble >= 0 ? poolAvail - availCmaAble : 0;
            // 2x2 caption: used / available on top, total / pool-size below.
            // With the reservoir on, the pool-size cell shows both targets as
            // pool_want/pool_want_with_cma; the detailed cma-able and
            // free/other-apps breakdowns live on the usage screen's synthetic
            // available/CMA rows. Total = real held reserve (used + avail),
            // shown raw - no clamp to the pool size, so a kernel that fails
            // to release on shrink shows up as total > the size you set.
            var held = used + poolAvail;
            setPagesString(tvPoolUsed, R.string.hugepage_stat_pool_used, used);
            setPagesString(tvPoolAvail, R.string.hugepage_stat_pool_available, poolAvail);
            setPagesString(tvPoolTotal, R.string.hugepage_stat_pool_total, held);
            if (cmaOn) {
                tvPoolSize.setText(getString(R.string.hugepage_stat_pool_size_cma,
                    poolWant, snap.wantWithCma,
                    SizeUtils.formatSize(poolWant * PAGE_SIZE),
                    SizeUtils.formatSize(snap.wantWithCma * PAGE_SIZE)));
            } else {
                setPagesString(tvPoolSize, R.string.hugepage_stat_pool_size, poolWant);
            }
            // Bar: [VMs][avail][CMA][CMA lent][waiting]. The CMA parts are two
            // ordinary labelled segments; only the avail block keeps the
            // single-label pure-color sub-split ([non-cma-able|normal]).
            var spec = new SegmentedBar.StorageSpec();
            spec.usedColors = usedColors;
            spec.usedValues = usedValues;
            spec.usedLabels = usedLabels;
            spec.avail = poolAvail;
            spec.availLabel = fmt("%s\n%s", getString(R.string.hugepage_bar_available),
                SizeUtils.formatSize(poolAvail * PAGE_SIZE));
            spec.availNonCma = Math.max(0, availNonCma);
            spec.availNonCmaColor = HugePageColor.availNonCma(this);
            spec.cmaFree = cmaFree;
            spec.cmaFreeLabel = fmt("%s\n%s", getString(R.string.hugepage_bar_cma),
                SizeUtils.formatSize(cmaFree * PAGE_SIZE));
            spec.cmaFreeColor = HugePageColor.cmaFree(this);
            spec.cmaOther = cmaOther;
            spec.cmaOtherLabel = fmt("%s\n%s", getString(R.string.hugepage_bar_cma_lent),
                SizeUtils.formatSize(cmaOther * PAGE_SIZE));
            spec.cmaOtherColor = HugePageColor.cmaUsed(this);
            spec.deficitColor = HugePageColor.pending(this);
            spec.deficit = deficit;
            spec.deficitLabel = fmt("%s\n%s", getString(R.string.hugepage_proc_deficit),
                SizeUtils.formatSize(deficit * PAGE_SIZE));
            spec.want = barWant;
            segPoolBar.setStorage(spec);
        } else {
            rowStatState.setValue(getString(R.string.hugepage_stats_unavailable));
            rowStatTotalServed.setValue(null);
            rowStatTotalRefilled.setValue(null);
            rowStatActiveVms.setValue(null);
            tvPoolUsed.setText("-");
            tvPoolAvail.setText("-");
            tvPoolTotal.setText("-");
            tvPoolSize.setText("-");
            segPoolBar.setData(new int[0], new float[0], 0f);
        }
        // While an acquire runs the Save button shows a spinner (label hidden,
        // floppy icon kept) but stays pressable: tapping it then interrupts the
        // acquire, keeping the current size (see the click handler). pool_want is
        // settable any time now, so neither button needs to be disabled.
        boolean acquiring = snap.acquiring;
        moduleAcquiring = acquiring;
        // Reconcile the acquire slots from acquire_active + acquire_mode so a run
        // that finishes (or was started elsewhere) toggles spinners<->buttons, and
        // only the running mode spins. acquire_mode is -1 on old modules -> makes
        // all three spin.
        int mode = acquiring ? snap.acquireMode : -1;
        // A real acquire_active 1 -> 0 edge = the worker finished; announce the
        // achieved size (this screen is fire-and-forget - unlike the process list,
        // which polls). Track the kernel flag, not the optimistic mainAcquiring, so
        // an acquire that never actually started can't fake a "done".
        if (wasAcquiring && !acquiring) {
            String msg = acquireDoneMessage(this, snap);
            // Append why the acquire stopped (kernel free text from refill_stat's
            // acquire_stop_reason), so the user sees the reason in the bubble.
            String reason = snap.acquireStopReason;
            if (!reason.isEmpty() && !"-".equals(reason))
                msg += "\n" + reason;
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }
        wasAcquiring = acquiring;
        if (mainAcquiring != acquiring || mainAcquireMode != mode) {
            mainAcquiring = acquiring;
            mainAcquireMode = mode;
            applyAcquireState();
        }
        progressSavePoolSize.setVisibility(acquiring ? VISIBLE : GONE);
        if (acquiring) btnSavePoolSize.setTextColor(Color.TRANSPARENT);
        else btnSavePoolSize.setTextColor(saveTextColors);
        btnSavePoolSize.setEnabled(snap.installed);
        // Install / Enable / Disable. "Disable" on v7 shrinks the pool to one
        // page (frees memory) but keeps the module loaded so it never loses
        // per-VM tracking; soft-disabled shows "Enable" again. v6 has no
        // pool_want knob, so Disable falls back to rmmod.
        moduleInstalled = snap.installed;
        moduleLoaded = snap.loaded;
        moduleHasPoolWant = snap.hasPoolWant;
        moduleSoftDisabled = snap.softDisabled;
        btnModuleToggle.setEnabled(true);
        if (!snap.installed) {
            btnModuleToggle.setText(R.string.hugepage_btn_install);
            btnModuleToggle.setIconResource(R.drawable.ic_download);
        } else if (!snap.loaded || snap.softDisabled) {
            btnModuleToggle.setText(R.string.hugepage_btn_enable);
            btnModuleToggle.setIconResource(R.drawable.ic_start);
        } else {
            btnModuleToggle.setText(R.string.hugepage_btn_disable);
            btnModuleToggle.setIconResource(R.drawable.ic_stop);
        }
        rowModuleEnable.setEnabled(snap.installed);
        rowModuleEnable.setChecked(snap.bootEnabled);

        // Acquire buttons usable exactly when there is a deficit to fill. The
        // model's version-unified deficit already folds in v6 (no pool_want) and
        // soft-disable (want 0), so there is no version branching here.
        acquireEnabled = snap.loaded && snap.deficit > 0;
        applyAcquireState();

        // CMA switch: only meaningful on a loaded v10 module. While a probe /
        // toggle flow runs, leave the switch and the size input alone - the flow
        // owns them (the reservoir flips around mid-probe and would flicker).
        rowCmaEnable.setEnabled(snap.loaded && snap.hasCma);
        if (!cmaBusy) {
            boolean cmaActive = snap.cmaActive();
            if (rowCmaEnable.isChecked() != cmaActive) {
                cmaSwitchSyncing = true;
                rowCmaEnable.setChecked(cmaActive);
                cmaSwitchSyncing = false;
            }
            // The size row shows exactly one GiB tag: on the right field while
            // CMA is on (two fields, tight width), on the pool field otherwise.
            inputPoolSize.setUnitButtonVisible(!cmaActive);
            if (cmaActive) {
                // Seed the with-CMA total input once per show (it maps 1:1 to
                // pool_want_with_cma), then leave the user's typing be.
                if (inputCmaSize.getVisibility() != VISIBLE || !cmaInputLoaded) {
                    inputCmaSize.setVisibility(VISIBLE);
                    sizeLinkSyncing = true;
                    try {
                        inputCmaSize.setBigValue(
                            BigInteger.valueOf(snap.wantWithCma * PAGE_SIZE));
                    } finally {
                        sizeLinkSyncing = false;
                    }
                    cmaInputLoaded = true;
                }
            } else {
                inputCmaSize.setVisibility(GONE);
                cmaInputLoaded = false;
            }
        }
    }

    /**
     * Bring the pool up: load the module (if unloaded) or restore the saved target
     * (if soft-disabled), then kick one gentle v1 fill toward it. The model picks
     * the version-appropriate path (insmod / pool_want write) and reads the size
     * from settings.prop itself.
     */
    private void doEnable() {
        btnModuleToggle.setEnabled(false);
        runOnPool(() -> {
            var res = model.setRuntimeEnabled(true);
            if (res.ok()) model.acquire(1);   // GUI drives the fill (v1 = gentlest)
            runOnUiThread(() -> {
                Toast.makeText(this, res.ok()
                    ? R.string.hugepage_loaded : R.string.hugepage_load_failed,
                    LENGTH_SHORT).show();
                refreshStatus();
            });
        });
    }

    /**
     * Take the pool down: soft-disable (shrink pool_want to 0, module stays loaded
     * so per-VM tracking survives) where supported, else rmmod (v6). The module
     * refuses a resize mid-acquire, so the running worker is stopped first.
     */
    private void doDisable() {
        btnModuleToggle.setEnabled(false);
        runOnPool(() -> {
            stopAcquireAndWait();
            var res = model.setRuntimeEnabled(false);
            runOnUiThread(() -> {
                if (!res.ok()) Toast.makeText(this,
                    R.string.hugepage_unload_failed, LENGTH_SHORT).show();
                refreshStatus();
            });
        });
    }

    /**
     * Interrupt a running acquire: write 0 to the acquire knob. pool_want is left
     * intact, so the worker stops at the size reached so far and the remaining
     * deficit keeps showing as "waiting for acquire".
     */
    private void interruptAcquire() {
        runOnPool(() -> {
            model.stopAcquire();
            runOnUiThread(this::refreshStatus);
        });
    }

    /**
     * Interrupt any running acquire and block (on the pool thread) until the
     * worker is quiescent. pool_want can't be set while an acquire runs, so call
     * this before a soft-disable. The worker can be mid-migration, so this may take
     * a moment; bounded so a wedged worker can't hang us forever.
     */
    private void stopAcquireAndWait() {
        if (!model.acquiring()) return;   // nothing running (also the v6 case)
        model.stopAcquire();
        for (int i = 0; i < 60; i++) {
            if (!model.acquiring()) return;
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                return;
            }
        }
    }

    private void loadPoolSize() {
        runOnPool(() -> {
            Map<String, String> settings;
            try {
                var result = shellReadFile(SETTINGS_PROP);
                settings = parseProp(result);
            } catch (Exception e) {
                Log.w(TAG, "Failed to read settings.prop", e);
                return;
            }
            // Prefer pool_want; fall back to legacy pool_target.
            var cur = settings.getOrDefault("pool_want",
                settings.getOrDefault("pool_target", "1024"));
            if (cur == null || cur.isEmpty()) cur = "1024";
            try {
                var pages = Long.parseLong(cur);
                var bytes = BigInteger.valueOf(pages * PAGE_SIZE);
                runOnUiThread(() -> {
                    // Programmatic seed - don't count it as a user edit for
                    // the pool<->with-CMA size link.
                    sizeLinkSyncing = true;
                    try {
                        inputPoolSize.setBigValue(bytes);
                    } finally {
                        sizeLinkSyncing = false;
                    }
                });
            } catch (NumberFormatException e) {
                Log.w(TAG, "Failed to parse pool_want", e);
            }
        });
    }

    /**
     * Sum of configured memory (MiB) over every VM that currently has a live
     * process (state RUNNING/STARTING/SUSPENDED). Blocks briefly on the daemon;
     * call from a background thread. 0 if none / daemon unreachable.
     */
    private long runningVmMemMib() {
        var total = new long[]{0};
        var latch = new CountDownLatch(1);
        DaemonConnection.getInstance().buildRequest("vm_list")
            .onResponse(resp -> {
                try {
                    var arr = resp.optJSONArray("data");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            var vm = arr.optJSONObject(i);
                            if (vm == null) continue;
                            var state = vm.optString("state", "STOPPED");
                            if ("RUNNING".equals(state) || "STARTING".equals(state)
                                || "SUSPENDED".equals(state)) {
                                total[0] += vm.optLong("memory_mb", 0);
                            }
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
        return total[0];
    }

    /**
     * Keep the size pair consistent ({@code pool_want <= pool_want_with_cma}):
     * when they cross, the field the user touched last wins - raising the pool
     * above the total drags the total up; lowering the total under the pool
     * shrinks the pool. Runs on focus-loss of either field and again at save.
     */
    private void reconcileSizeLink() {
        if (inputCmaSize.getVisibility() != VISIBLE) return;
        if (!inputPoolSize.isInputValid() || !inputCmaSize.isInputValid()) return;
        var pool = inputPoolSize.getBigValue();
        var withCma = inputCmaSize.getBigValue();
        if (pool.compareTo(withCma) <= 0) return;
        sizeLinkSyncing = true;
        try {
            if (lastSizeEdit == SIZE_EDIT_CMA) inputPoolSize.setBigValue(withCma);
            else inputCmaSize.setBigValue(pool);
        } finally {
            sizeLinkSyncing = false;
        }
    }

    private void savePoolSize() {
        reconcileSizeLink();
        if (!inputPoolSize.isInputValid()) return;
        var bytes = inputPoolSize.getBigValue();
        var pages = bytes.divide(BigInteger.valueOf(PAGE_SIZE));
        // While the reservoir is on, the right field IS the with-CMA total
        // (pool_want_with_cma); the link above already keeps it >= the pool.
        final long cmaPages;
        if (inputCmaSize.getVisibility() == VISIBLE) {
            if (!inputCmaSize.isInputValid()) return;
            cmaPages = inputCmaSize.getBigValue()
                .divide(BigInteger.valueOf(PAGE_SIZE)).longValue();
        } else {
            cmaPages = -1;
        }
        runOnPool(() -> {
            // The pool must be able to back every running VM's RAM, so it can't
            // be set below the sum of running VMs' configured memory.
            long needMib = runningVmMemMib();
            long wantMib = pages.longValue() * (PAGE_SIZE / (1024 * 1024));
            if (needMib > 0 && wantMib < needMib) {
                runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.hugepage_pool_below_vms,
                        SizeUtils.formatSize(needMib * 1024 * 1024)),
                    LENGTH_SHORT).show());
                return;
            }
            // Persist for the next load and apply to the running pool where the
            // live knob exists (v7); v6's read-only target only lands next boot.
            // One settings.prop rewrite carries the pool target and (while the
            // reservoir is on) the with-CMA total together.
            var res = model.saveTargets(pages.longValue(), cmaPages);
            // A grow leaves a deficit -> kick one fill so the raised target
            // starts filling at once (a shrink is applied by the write itself).
            // The GUI drives acquire; the model's save deliberately doesn't.
            // Only the mode-2/3 sweep runs the reservoir-building Phase R
            // ("mode 1 remains pool-only legacy"), so a CMA-era grow needs v3.
            var snap = model.state();
            if (res.ok() && snap.loaded && snap.deficit > 0)
                model.acquire(snap.cmaActive() ? 3 : 1);
            boolean okSaved = res.ok();
            boolean appliedNow = "pool_want".equals(res.impl);   // live write actually landed
            runOnUiThread(() -> {
                int msg = !okSaved ? R.string.hugepage_pool_size_failed
                    : appliedNow ? R.string.hugepage_pool_size_applied
                    : R.string.hugepage_pool_size_saved;
                Toast.makeText(this, msg, LENGTH_SHORT).show();
                refreshStatus();
            });
        });
    }

    private void doToggleModule() {
        var enabled = rowModuleEnable.isChecked();
        if (shellCheckExists(DISABLE_FILE) != enabled) return;   // ignore the programmatic echo
        runOnPool(() -> {
            var res = model.setBootEnabled(enabled);
            runOnUiThread(() -> {
                if (res.ok()) {
                    var msg = enabled ?
                        R.string.hugepage_module_now_enabled :
                        R.string.hugepage_module_now_disabled;
                    Toast.makeText(this, msg, LENGTH_SHORT).show();
                }
                refreshStatus();
            });
        });
    }

    /* ================================================================== */
    /*  v10 CMA reservoir: switch + consumability probe                   */
    /* ================================================================== */

    private void onCmaSwitchChanged(boolean checked) {
        if (cmaSwitchSyncing) return;
        if (cmaBusy) {                 // a flow already owns the switch
            setCmaSwitch(!checked);
            return;
        }
        if (checked) doCmaEnable();
        else doCmaDisable();
    }

    /** Programmatic switch write that doesn't re-enter the change listener. */
    private void setCmaSwitch(boolean checked) {
        cmaSwitchSyncing = true;
        rowCmaEnable.setChecked(checked);
        cmaSwitchSyncing = false;
    }

    /** End an enable flow without enabling: release the busy lock, switch off. */
    private void cancelCmaEnable() {
        cmaBusy = false;
        setCmaSwitch(false);
        refreshStatus();
    }

    /** Switch off: demolish the reservoir now and persist off for next boot. */
    private void doCmaDisable() {
        cmaBusy = true;
        runOnPool(() -> {
            var res = model.saveCmaTarget(0);
            runOnUiThread(() -> {
                cmaBusy = false;
                Toast.makeText(this, res.ok() ? R.string.hugepage_cma_disabled
                    : R.string.hugepage_cma_toggle_failed, LENGTH_SHORT).show();
                if (!res.ok()) setCmaSwitch(true);
                refreshStatus();
            });
        });
    }

    /**
     * Switch on. The magisk-side {@code cma_probe_result} (settings.prop) says
     * whether the consumability probe ever ran:
     * <ul>
     *   <li>{@code 1} - apps can consume CMA: enable directly, no probe;</li>
     *   <li>{@code 0} - probed unusable: offer a re-probe;</li>
     *   <li>absent - never probed: explain and offer to run it.</li>
     * </ul>
     */
    private void doCmaEnable() {
        cmaBusy = true;
        runOnPool(() -> {
            var snap = model.state();
            if (!snap.loaded || !snap.hasCma) {
                runOnUiThread(() -> {
                    cmaBusy = false;
                    setCmaSwitch(false);
                    Toast.makeText(this, R.string.hugepage_cma_not_supported,
                        LENGTH_SHORT).show();
                });
                return;
            }
            var verdict = model.cmaProbeResult();
            if (snap.cmaPbOrder < 0) {
                // The module disabled its whole CMA side this boot (preflight /
                // symbols / first-block verification) - no write can help now.
                // A recorded denial is one of the causes (the boot script then
                // hands the module -1 preflight values): drop it, so the next
                // boot comes up CMA-capable and the probe can run again.
                boolean stale = verdict != null && verdict == VERDICT_DENIED;
                if (stale) model.clearCmaProbeResult();
                runOnUiThread(() -> {
                    cmaBusy = false;
                    setCmaSwitch(false);
                    if (isFinishing()) return;
                    new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.hugepage_cma_unavailable_title)
                        .setMessage(stale ? R.string.hugepage_cma_unavailable_denied
                            : R.string.hugepage_cma_unavailable_boot)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                });
                return;
            }
            // The threshold only feeds the two dialog branches - don't pay the
            // meminfo shell read when the verdict lets us enable directly.
            var needBytes = (verdict != null && verdict == VERDICT_ALLOWED)
                ? 0 : probeNeedBytes(model.memTotalKb());
            runOnUiThread(() -> {
                if (isFinishing()) {
                    cmaBusy = false;
                    return;
                }
                if (verdict != null && verdict == VERDICT_ALLOWED) {
                    enableCmaDirect(snap);
                } else if (verdict != null && verdict == VERDICT_DENIED) {
                    new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.hugepage_cma_probe_denied_title)
                        .setMessage(R.string.hugepage_cma_probe_denied_msg)
                        .setPositiveButton(R.string.hugepage_cma_probe_rerun,
                            (d, w) -> startCmaProbe())
                        .setNegativeButton(android.R.string.cancel,
                            (d, w) -> cancelCmaEnable())
                        .setOnCancelListener(d -> cancelCmaEnable())
                        .show();
                } else {
                    new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.hugepage_cma_probe_needed_title)
                        .setMessage(getString(R.string.hugepage_cma_probe_needed_msg,
                            SizeUtils.formatSize(Math.max(0, needBytes)),
                            SizeUtils.formatSize(PROBE_POOL_BYTES)))
                        .setPositiveButton(R.string.hugepage_cma_probe_start,
                            (d, w) -> startCmaProbe())
                        .setNegativeButton(android.R.string.cancel,
                            (d, w) -> cancelCmaEnable())
                        .setOnCancelListener(d -> cancelCmaEnable())
                        .show();
                }
            });
        });
    }

    /**
     * Probe already passed: restore the remembered with-CMA total and let a v3
     * acquire build the reservoir (only the mode-2/3 sweep runs Phase R; mode 1
     * is pool-only legacy).
     *
     * <p>The target is clamped to the module's {@code pool_want <=
     * pool_want_with_cma} invariant, so a remembered total at or below the pool
     * size enables with an empty reservoir - that is a real state (the probe
     * itself produces it when the pool already holds everything), and the user
     * then raises the now-visible with-CMA field. Only {@code 0} is impossible:
     * it is the off sentinel.
     */
    private void enableCmaDirect(@NonNull HugePageModel.Snapshot snap) {
        runOnPool(() -> {
            long target = Math.max(model.lastCmaTargetPages(), snap.targetIdeal);
            if (target <= 0)   // pool soft-disabled: fall back to the probe floor
                target = pagesFor(Math.max(0, probeNeedBytes(model.memTotalKb())));
            boolean ok = target > 0 && model.saveCmaTarget(target).ok();
            if (ok) {
                var s2 = model.state();
                if (s2.loaded && s2.deficit > 0) model.acquire(3);
            }
            boolean fOk = ok;
            runOnUiThread(() -> {
                cmaBusy = false;
                Toast.makeText(this, fOk ? R.string.hugepage_cma_enabled
                    : R.string.hugepage_cma_toggle_failed, LENGTH_SHORT).show();
                if (!fOk) setCmaSwitch(false);
                cmaInputLoaded = false;   // reseed the CMA size input
                refreshStatus();
            });
        });
    }

    /* ---- probe orchestration ---- */

    /** Progress dialog handle for the probe worker thread. */
    private static final class ProbeUi {
        @NonNull final androidx.appcompat.app.AlertDialog dialog;
        @NonNull final TextView text;

        ProbeUi(@NonNull androidx.appcompat.app.AlertDialog dialog, @NonNull TextView text) {
            this.dialog = dialog;
            this.text = text;
        }
    }

    private static final int VERDICT_DENIED = 0;
    private static final int VERDICT_ALLOWED = 1;
    private static final int VERDICT_ABNORMAL = -1;

    /** max(RAM - 8 GiB, RAM x 0.4) in bytes; -1 when meminfo is unreadable. */
    private long probeNeedBytes(long memTotalKb) {
        if (memTotalKb <= 0) return -1;
        long total = memTotalKb * 1024;
        return Math.max(total - PROBE_KEEP_BYTES, (long) (total * PROBE_MIN_RAM_FRACTION));
    }

    private static long pagesFor(long bytes) {
        return (bytes + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    /** Kick off the probe worker; the switch stays under the flow's control. */
    private void startCmaProbe() {
        new Thread(this::runCmaProbe, "hugepage-cma-probe").start();
    }

    /**
     * The consumability probe (worker thread). Steps, per the module docs:
     * precondition {@code avail >= max(RAM-7G, 40% RAM)} (guided acquire, else
     * save-and-reboot); then {@code echo avail > pool_want_with_cma},
     * {@code echo 0 > pool_want} (the freed blocks flip to the reservoir), run
     * {@code balloon 1536} and judge from how much CmaFree the pressure consumed
     * whether this vendor lets user apps allocate from CMA. {@code pool_want} is
     * restored on every path.
     *
     * <p>Only a pass is written to settings.prop ({@code cma_probe_result=1}) -
     * an unreadable result asks the user, and enabling counts as a pass. A
     * failure (or a decline) records nothing and clears any stale verdict, so
     * the next launch can simply probe again.
     */
    private void runCmaProbe() {
        var cancelled = new AtomicBoolean(false);
        ProbeUi ui = null;
        boolean raised = false;    // pool_want_with_cma raised by us
        boolean zeroed = false;    // pool_want emptied by us
        long prevWant = -1;
        try {
            // Balloon pressure would squeeze (or LMK-kill) running VMs.
            if (runningVmMemMib() > 0) {
                probeFail(null, getString(R.string.hugepage_cma_vms_running));
                return;
            }
            var snap = model.state();
            prevWant = snap.targetIdeal;
            long needBytes = probeNeedBytes(model.memTotalKb());
            if (needBytes <= 0) {
                probeFail(null, getString(R.string.hugepage_cma_probe_failed_generic));
                return;
            }
            long needPages = pagesFor(needBytes);
            // 1. How big a reservoir to measure against. It is NOT bounded by
            //    the configured pool: the probe empties the pool into the
            //    reservoir (pool_want=0 -> the module's shrink flips every avail
            //    block to CMA, instantly), so the only ceiling is the module's
            //    RAM-derived pool_size_max. A cap below what we want makes the
            //    verdict shakier, not impossible: warn and let the user go on.
            long cap = model.poolSizeMax();
            long target = Math.max(needPages, Math.max(prevWant, snap.wantWithCma));
            if (cap > 0) target = Math.min(target, cap);
            long goal = Math.min(needPages, target);
            if (goal < needPages && !probeAsk(
                getString(R.string.hugepage_cma_small_reservoir_title),
                getString(R.string.hugepage_cma_small_reservoir_msg,
                    SizeUtils.formatSize(goal * PAGE_SIZE),
                    SizeUtils.formatSize(needPages * PAGE_SIZE)),
                getString(R.string.hugepage_cma_probe_anyway))) {
                probeCancelled(null, false);
                return;
            }
            ui = probeProgressShow(reservoirStage(snap.cmaPool, goal), cancelled);
            if (ui == null) {
                probeCancelled(null, false);
                return;
            }
            // 2. Build the reservoir. Already there (a previous run persisted the
            //    target and the module assembled it at boot) -> measure directly,
            //    touching nothing.
            boolean built = snap.cmaPool >= goal;
            if (!built) {
                // Raise the total first: pool_want=0 would otherwise soft-disable
                // the pool and hand its pages back to the buddy allocator instead
                // of flipping them into the reservoir. Never lower an existing
                // bigger total - that demolishes reservoir the device is lending
                // out right now.
                if (snap.wantWithCma < target) {
                    var w = model.writeWantWithCma(target);
                    if (!w.ok()) {
                        probeDismiss(ui);
                        probeUnavailable(getString(R.string.hugepage_cma_unavailable_write,
                            w.detail != null ? w.detail : "?"));
                        return;
                    }
                }
                raised = true;
                // Empty the pool: its avail blocks flip to CMA at once (the fast
                // path - a from-scratch sweep would hit the fragmentation wall).
                // Only the live knob is written, so a reboot restores pool_want
                // even if this app dies before the restore below.
                var shrink = model.writeWant(0);
                if (!shrink.ok()) {
                    probeRollback();
                    probeDismiss(ui);
                    probeFail(null, getString(R.string.hugepage_cma_probe_failed_generic));
                    return;
                }
                zeroed = true;
                Thread.sleep(3000);          // let the flips land
                model.acquire(3);            // best-effort top-up toward the target
                built = waitReservoir(ui, goal, cancelled);
            }
            if (cancelled.get()) {
                model.stopAcquire();
                probeRestore(prevWant, zeroed);
                probeRollback();
                probeCancelled(ui, true);
                return;
            }
            // Whatever actually assembled is what the balloon is judged against.
            long reservoirPages = model.state().cmaPool;
            if (reservoirPages <= 0) {
                probeRestore(prevWant, zeroed);
                probeRollback();
                probeDismiss(ui);
                probeUnavailable(getString(R.string.hugepage_cma_unavailable_reservoir));
                return;
            }
            if (!built) {
                // The runtime sweep hit the fragmentation wall. This is not a
                // different failure from "it can't be built" - the module builds
                // the reservoir first at init, on the cleanest memory there is,
                // so the very same target simply works after a reboot. Persist
                // it and pick the probe back up then (see the pending prompt) -
                // or measure right now against the smaller reservoir that did
                // get built, accepting a shakier verdict.
                probeDismiss(ui);
                ui = null;
                int choice = probeAskChoice(
                    getString(R.string.hugepage_cma_reservoir_short_title),
                    getString(R.string.hugepage_cma_reservoir_short_msg,
                        SizeUtils.formatSize(reservoirPages * PAGE_SIZE),
                        SizeUtils.formatSize(goal * PAGE_SIZE)),
                    getString(R.string.hugepage_cma_save_reboot),
                    getString(R.string.hugepage_cma_probe_anyway));
                if (choice != CHOICE_NEGATIVE) {
                    // "I'll reboot, then probe", or dismissed. Nothing is saved:
                    // the probe assembles the reservoir out of the pool itself,
                    // so a fresh boot - where memory is unfragmented - simply
                    // lets the same run succeed. Undo our live writes and go.
                    probeRestore(prevWant, zeroed);
                    probeRollback();
                    if (choice == CHOICE_POSITIVE)
                        probeToast(getString(R.string.hugepage_cma_reboot_hint));
                    probeEndUi(false);
                    return;
                }
                // Probe anyway: a fresh progress dialog for the pressure stage.
                ui = probeProgressShow(
                    getString(R.string.hugepage_cma_probe_running_balloon), cancelled);
                if (ui == null) {
                    probeRestore(prevWant, zeroed);
                    probeRollback();
                    probeCancelled(null, false);
                    return;
                }
            }
            // 3. Pressure + judgment, measured against the reservoir that exists.
            probeStage(ui, getString(R.string.hugepage_cma_probe_running_balloon));
            var out = model.runBalloon(BALLOON_FLOOR_MB, BALLOON_TIMEOUT_S);
            if (cancelled.get()) {
                probeRestore(prevWant, zeroed);
                probeRollback();
                probeCancelled(ui, true);
                return;
            }
            int verdict = judgeBalloon(out, reservoirPages);
            probeDismiss(ui);
            ui = null;
            // A pass enables straight away. Anything else - a denial, or numbers
            // that match neither verdict - is put to the user, because a probe
            // can be wrong (a small reservoir, a vendor that only lets some
            // allocation classes in). Enabling either way counts as a pass and
            // is recorded, so the switch stops probing from now on; declining
            // records nothing and clears any stale verdict, leaving the probe
            // available next launch.
            if (verdict == VERDICT_ALLOWED) {
                model.setCmaProbeAllowed();
                probeEnableWithSmallPool(target);
                probeToast(getString(R.string.hugepage_cma_probe_ok,
                    SizeUtils.formatSize(PROBE_POOL_BYTES)));
                probeEndUi(true);
                return;
            }
            boolean enable = verdict == VERDICT_DENIED
                ? probeAsk(getString(R.string.hugepage_cma_probe_denied_title),
                    getString(R.string.hugepage_cma_probe_denied_result),
                    getString(R.string.hugepage_cma_probe_enable))
                : probeAskAbnormal(out, reservoirPages);
            if (enable) {
                model.setCmaProbeAllowed();
                probeEnableWithSmallPool(target);   // also restores pool_want
                probeToast(getString(R.string.hugepage_cma_enabled_pool,
                    SizeUtils.formatSize(PROBE_POOL_BYTES)));
                probeEndUi(true);
            } else {
                probeRestore(prevWant, zeroed);
                model.clearCmaProbeResult();
                model.saveCmaTarget(0);   // demolishes the reservoir
                if (zeroed) model.acquire(1);   // refill the restored pool
                probeEndUi(false);
            }
        } catch (InterruptedException e) {
            probeRestore(prevWant, zeroed);
            if (raised) probeRollback();
            probeCancelled(ui, ui != null);
        } catch (Exception e) {
            // Never leave the flow lock stuck: undo our writes and surface the
            // error instead of a wedged switch.
            Log.w(TAG, "CMA probe failed", e);
            probeRestore(prevWant, zeroed);
            if (raised) probeRollback();
            probeDismiss(ui);
            probeFail(null, getString(R.string.hugepage_cma_probe_failed_generic));
        }
    }

    /** Put {@code pool_want} back if the probe emptied it (live knob only). */
    private void probeRestore(long prevWant, boolean zeroed) {
        if (zeroed && prevWant >= 0) model.writeWant(prevWant);
    }

    /**
     * The reboot half of the probe: the reservoir target survived a reboot with
     * no verdict recorded, so the module has now built it on clean memory and
     * the measurement can finally run. Asked once per visit; declining leaves
     * the reservoir in place (it is still lent to apps) and the switch on.
     */
    private void promptPendingProbe() {
        if (probePromptShown || cmaBusy || isFinishing() || isDestroyed()) return;
        probePromptShown = true;
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hugepage_cma_probe_pending_title)
            .setMessage(R.string.hugepage_cma_probe_pending_msg)
            .setPositiveButton(R.string.hugepage_cma_probe_continue, (d, w) -> {
                cmaBusy = true;
                startCmaProbe();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Undo the probe's only write: the raised total demolishes its reservoir. */
    private void probeRollback() {
        model.writeWantWithCma(0);
    }

    @NonNull
    private String reservoirStage(long got, long goal) {
        return getString(R.string.hugepage_cma_building,
            SizeUtils.formatSize(got * PAGE_SIZE), SizeUtils.formatSize(goal * PAGE_SIZE));
    }

    /**
     * The end state of an enabled probe: a {@value #PROBE_POOL_BYTES}-byte pool
     * and the with-CMA total the probe assembled, persisted in one settings.prop
     * rewrite so the next boot comes up the same way. This is the probe's first
     * and only {@code pool_want} write: shrinking the pool hands its pages to
     * the reservoir (the module's shrink path), and the v3 acquire then stages
     * 512 MB back in and tops the reservoir up toward the total.
     */
    private void probeEnableWithSmallPool(long total) {
        model.saveTargets(PROBE_POOL_PAGES, total);
        // v3: only the mode-2/3 sweep runs the reservoir-building Phase R.
        model.acquire(3);
    }

    /**
     * Poll the reservoir toward {@code goal} pages, narrating progress. Returns
     * true once {@code pool_cma} reaches it; false on stop/timeout/cancel.
     */
    private boolean waitReservoir(@NonNull ProbeUi ui, long goal,
                                  @NonNull AtomicBoolean cancelled)
        throws InterruptedException {
        for (int i = 0; i < 1800; i++) {   // 30 min hard bound
            if (cancelled.get()) return false;
            var s = model.state();
            probeStage(ui, reservoirStage(s.cmaPool, goal));
            if (s.cmaPool >= goal) return true;
            // Give the worker a few seconds to raise acquire_active before
            // treating "not acquiring" as done-short.
            if (!s.acquiring && i > 5) return false;
            Thread.sleep(1000);
        }
        return false;
    }

    /**
     * Judge the balloon output against the reservoir that was built: pressure
     * that consumed at least half of it means apps allocate from CMA; a tenth
     * or less means they can't; anything between (or unparsable output) is
     * unreadable and goes to the user.
     */
    private int judgeBalloon(@Nullable Map<String, String> out, long reservoirPages) {
        if (out == null) return VERDICT_ABNORMAL;
        long diffKb;
        long heldMb;
        try {
            diffKb = Long.parseLong(out.getOrDefault("cma_diff_kb", "").trim());
            heldMb = Long.parseLong(out.getOrDefault("held_mb", "").trim());
        } catch (NumberFormatException e) {
            return VERDICT_ABNORMAL;
        }
        long reservoirKb = reservoirPages * (PAGE_SIZE / 1024);
        if (reservoirKb <= 0 || heldMb <= 0) return VERDICT_ABNORMAL;
        if (diffKb >= reservoirKb / 2) return VERDICT_ALLOWED;
        if (diffKb <= reservoirKb / 10) return VERDICT_DENIED;
        return VERDICT_ABNORMAL;
    }

    /* ---- probe worker <-> UI plumbing (all blocking helpers) ---- */

    /** Blocking two-choice dialog; false on cancel/back/finish. */
    private boolean probeAsk(@NonNull String title, @NonNull String message,
                             @NonNull String positive) throws InterruptedException {
        return probeAskChoice(title, message, positive,
            getString(android.R.string.cancel)) == CHOICE_POSITIVE;
    }

    /** {@link #probeAskChoice} outcomes; {@code CHOICE_NONE} = back/dismiss/gone. */
    private static final int CHOICE_NONE = 0;
    private static final int CHOICE_POSITIVE = 1;
    private static final int CHOICE_NEGATIVE = 2;

    /**
     * Blocking dialog offering two named actions, with back/outside-tap as a
     * third "neither" outcome. Both buttons are real choices - which is why
     * neither is labelled Cancel by callers that need three ways out.
     */
    private int probeAskChoice(@NonNull String title, @NonNull String message,
                               @NonNull String positive, @NonNull String negative)
        throws InterruptedException {
        var choice = new AtomicInteger(CHOICE_NONE);
        var latch = new CountDownLatch(1);
        runOnUiThread(() -> {
            // isDestroyed covers rotation teardown (isFinishing stays false);
            // the catch covers a window torn down mid-post - either way the
            // latch MUST be counted or the worker blocks forever.
            if (isFinishing() || isDestroyed()) {
                latch.countDown();
                return;
            }
            try {
                new MaterialAlertDialogBuilder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(positive, (d, w) -> choice.set(CHOICE_POSITIVE))
                    .setNegativeButton(negative, (d, w) -> choice.set(CHOICE_NEGATIVE))
                    .setOnDismissListener(d -> latch.countDown())
                    .show();
            } catch (Exception e) {
                latch.countDown();
            }
        });
        latch.await();
        return choice.get();
    }

    /** The "result unreadable - enable anyway?" dialog, with the raw numbers. */
    private boolean probeAskAbnormal(@Nullable Map<String, String> out, long reservoirPages)
        throws InterruptedException {
        long diffKb = 0;
        long heldMb = 0;
        String stop = "?";
        if (out != null) {
            try {
                diffKb = Long.parseLong(out.getOrDefault("cma_diff_kb", "0").trim());
            } catch (NumberFormatException ignored) {
            }
            try {
                heldMb = Long.parseLong(out.getOrDefault("held_mb", "0").trim());
            } catch (NumberFormatException ignored) {
            }
            stop = out.getOrDefault("stop_reason", "?");
        }
        var choice = new AtomicInteger(0);
        var latch = new CountDownLatch(1);
        long fDiffKb = diffKb;
        long fHeldMb = heldMb;
        String fStop = stop;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                latch.countDown();
                return;
            }
            try {
                new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.hugepage_cma_probe_abnormal_title)
                    .setMessage(getString(R.string.hugepage_cma_probe_abnormal_msg,
                        SizeUtils.formatSize(fDiffKb * 1024),
                        SizeUtils.formatSize(reservoirPages * PAGE_SIZE),
                        SizeUtils.formatSize(fHeldMb * 1024 * 1024),
                        fStop))
                    .setPositiveButton(R.string.hugepage_cma_probe_enable,
                        (d, w) -> choice.set(1))
                    .setNegativeButton(R.string.hugepage_cma_probe_keep_off, null)
                    .setOnDismissListener(d -> latch.countDown())
                    .show();
            } catch (Exception e) {
                latch.countDown();
            }
        });
        latch.await();
        return choice.get() == 1;
    }

    /** Show the cancellable progress dialog; null when the activity is gone. */
    @Nullable
    private ProbeUi probeProgressShow(@NonNull String initial,
                                      @NonNull AtomicBoolean cancelled)
        throws InterruptedException {
        var holder = new java.util.concurrent.atomic.AtomicReference<ProbeUi>();
        var latch = new CountDownLatch(1);
        runOnUiThread(() -> {
            try {
                if (isFinishing() || isDestroyed()) return;
                float density = getResources().getDisplayMetrics().density;
                var text = new TextView(this);
                text.setText(initial);
                var box = new LinearLayout(this);
                box.setOrientation(LinearLayout.HORIZONTAL);
                box.setGravity(android.view.Gravity.CENTER_VERTICAL);
                int pad = Math.round(24 * density);
                box.setPaddingRelative(pad, Math.round(16 * density), pad, 0);
                var spinner = new ProgressBar(this);
                box.addView(spinner, Math.round(32 * density), Math.round(32 * density));
                var lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginStart(Math.round(16 * density));
                box.addView(text, lp);
                var dialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.hugepage_enable_cma)
                    .setView(box)
                    .setCancelable(false)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
                dialog.show();
                // Cancel requests cooperative interruption; the worker decides
                // when it is safe to stop, so the button must not dismiss the
                // dialog. A running balloon ignores flags, so also kill it -
                // its run() then returns quickly and the worker sees the flag.
                var btn = dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
                if (btn != null) btn.setOnClickListener(v -> {
                    cancelled.set(true);
                    v.setEnabled(false);
                    runOnPool(() -> runList("pkill", "-f",
                        "gh-hugepage-reserve/balloon"));
                });
                holder.set(new ProbeUi(dialog, text));
            } catch (Exception ignored) {
                // window torn down mid-post: holder stays null = "activity gone"
            } finally {
                latch.countDown();
            }
        });
        latch.await();
        return holder.get();
    }

    private void probeStage(@NonNull ProbeUi ui, @NonNull String msg) {
        runOnUiThread(() -> ui.text.setText(msg));
    }

    private void probeDismiss(@Nullable ProbeUi ui) {
        if (ui != null) runOnUiThread(ui.dialog::dismiss);
    }

    private void probeToast(@NonNull String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    /** Wind the flow down: dismiss, switch off. Callers own the rollback. */
    private void probeCancelled(@Nullable ProbeUi ui, boolean toast) {
        probeDismiss(ui);
        if (toast) probeToast(getString(R.string.hugepage_cma_probe_cancelled));
        probeEndUi(false);
    }

    /** Failure with a message dialog (or toast when {@code title} is null). */
    private void probeFail(@Nullable String title, @NonNull String message) {
        runOnUiThread(() -> {
            cmaBusy = false;
            setCmaSwitch(false);
            if (isFinishing()) return;
            if (title == null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            } else {
                new MaterialAlertDialogBuilder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            }
            refreshStatus();
        });
    }

    private void probeUnavailable(@NonNull String detail) {
        probeFail(getString(R.string.hugepage_cma_unavailable_title), detail);
    }

    /** Release the flow lock and settle the switch to the final state. */
    private void probeEndUi(boolean enabled) {
        runOnUiThread(() -> {
            cmaBusy = false;
            setCmaSwitch(enabled);
            cmaInputLoaded = false;   // reseed the with-CMA total field
            loadPoolSize();           // a passing probe pins pool_want to 512 MB
            refreshStatus();
        });
    }

    private void confirmUnload() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hugepage_stop)
            .setMessage(R.string.hugepage_stop_confirm)
            .setPositiveButton(R.string.hugepage_stop, (d, w) -> doDisable())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private boolean onMenuItemClicked(@NonNull android.view.MenuItem item) {
        if (item.getItemId() == R.id.action_unload_module) {
            confirmUnloadModule();
            return true;
        }
        return false;
    }

    private void confirmUnloadModule() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hugepage_unload_module_title)
            .setMessage(R.string.hugepage_unload_module_confirm)
            .setPositiveButton(R.string.hugepage_unload_module, (d, w) -> doUnloadModule())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void doUnloadModule() {
        btnModuleToggle.setEnabled(false);
        runOnPool(() -> {
            stopAcquireAndWait();
            var res = model.unload();
            runOnUiThread(() -> {
                Toast.makeText(this, res.ok()
                    ? R.string.hugepage_unload_module_done
                    : R.string.hugepage_unload_failed, LENGTH_SHORT).show();
                refreshStatus();
            });
        });
    }

    private void doDismissCrash() {
        runOnPool(() -> {
            runList("rm", "-f", CRASH_FILE);
            runOnUiThread(() -> {
                cardCrashWarning.setVisibility(GONE);
                Toast.makeText(this, R.string.hugepage_crash_dismissed, LENGTH_SHORT).show();
            });
        });
    }
}
