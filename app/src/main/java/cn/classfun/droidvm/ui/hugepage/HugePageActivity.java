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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.size.SizeUtils;
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
        rowStatState = findViewById(R.id.row_stat_state);
        rowStatTotalServed = findViewById(R.id.row_stat_total_served);
        rowStatTotalRefilled = findViewById(R.id.row_stat_total_refilled);
        rowStatActiveVms = findViewById(R.id.row_stat_active_vms);
        initialize();
    }

    private void initialize() {
        toolbar.setTitle(R.string.hugepage_title);
        toolbar.setNavigationOnClickListener(v -> finish());
        btnSavePoolSize.setOnClickListener(v -> {
            if (moduleAcquiring) interruptAcquire();
            else savePoolSize();
        });
        rowModuleEnable.setOnCheckedChangeListener(this::doToggleModule);
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
            List<long[]> owners = new ArrayList<>();
            if (snap.loaded) {
                for (var e : model.usage(null).entries) {
                    if (e.pages > 0) owners.add(new long[]{e.pid, e.pages});
                }
            }
            // Only fetch VM names when there are rows to label.
            Map<Integer, String> vmMap = owners.isEmpty()
                ? new LinkedHashMap<>() : model.vmNames(false);
            runOnUiThread(() -> updateUI(snap, crashStamp, owners, vmMap));
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
        @NonNull Map<Integer, String> vmMap
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
            // Apple-storage-bar style: one labelled colored block per VM
            // (used), then the available portion as a track-coloured gap,
            // then the waiting-to-acquire (deficit) block pinned flush right.
            // Each block draws its label inside if wide enough.
            boolean dark = HugePageColor.isDark(this);
            int n = owners.size();
            int[] usedColors = new int[n];
            float[] usedValues = new float[n];
            String[] usedLabels = new String[n];
            long seg = 0;
            for (int i = 0; i < n; i++) {
                int pid = (int) owners.get(i)[0];
                long ownerPages = owners.get(i)[1];
                usedColors[i] = HugePageColor.forPid(pid, dark);
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
            long deficit = Math.max(0, poolWant - seg - poolAvail);
            // 2x2 caption: used / available on top, total / pool-size below.
            // Total = real held reserve (used + avail), shown raw - no clamp
            // to the pool size, so a kernel that fails to release on shrink
            // shows up as total > the size you set.
            var held = used + poolAvail;
            setPagesString(tvPoolUsed, R.string.hugepage_stat_pool_used, used);
            setPagesString(tvPoolAvail, R.string.hugepage_stat_pool_available, poolAvail);
            setPagesString(tvPoolTotal, R.string.hugepage_stat_pool_total, held);
            setPagesString(tvPoolSize, R.string.hugepage_stat_pool_size, poolWant);
            segPoolBar.setStorage(usedColors, usedValues, usedLabels,
                poolAvail, fmt("%s\n%s", getString(R.string.hugepage_bar_available), SizeUtils.formatSize(poolAvail * PAGE_SIZE)),
                HugePageColor.pending(this), deficit,
                fmt("%s\n%s", getString(R.string.hugepage_proc_deficit), SizeUtils.formatSize(deficit * PAGE_SIZE)),
                poolWant);
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
            long got = snap.free + snap.lent;
            long want = snap.targetIdeal;
            Toast.makeText(this, got >= want
                    ? getString(R.string.hugepage_proc_acquire_full,
                        SizeUtils.formatSize(want * PAGE_SIZE))
                    : getString(R.string.hugepage_proc_acquire_partial,
                        SizeUtils.formatSize(got * PAGE_SIZE),
                        SizeUtils.formatSize(want * PAGE_SIZE)),
                Toast.LENGTH_LONG).show();
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
                runOnUiThread(() -> inputPoolSize.setBigValue(bytes));
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

    private void savePoolSize() {
        if (!inputPoolSize.isInputValid()) return;
        var bytes = inputPoolSize.getBigValue();
        var pages = bytes.divide(BigInteger.valueOf(PAGE_SIZE));
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
            var res = model.saveSize(pages.longValue());
            // A grow leaves a deficit -> kick one v1 fill so the raised target
            // starts filling at once (a shrink is applied by the write itself).
            // The GUI drives acquire; the model's saveSize deliberately doesn't.
            var snap = model.state();
            if (res.ok() && snap.loaded && snap.deficit > 0) model.acquire(1);
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

    private void confirmUnload() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hugepage_stop)
            .setMessage(R.string.hugepage_stop_confirm)
            .setPositiveButton(R.string.hugepage_stop, (d, w) -> doDisable())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
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
