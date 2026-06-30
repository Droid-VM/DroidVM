package cn.classfun.droidvm.ui.hugepage;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellReadFile;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
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
    private static final String SYSFS_BASE = "/sys/module/gh_hugepage_reserve";
    private static final String SYSFS_PARAMS = pathJoin(SYSFS_BASE, "parameters");
    private static final String MAGISK_BASE = "/data/adb/modules/gh-hugepage-reserve";
    private static final String MODULE_PROP = pathJoin(MAGISK_BASE, "module.prop");
    private static final String SETTINGS_PROP = pathJoin(MAGISK_BASE, "settings.prop");
    private static final String DISABLE_FILE = pathJoin(MAGISK_BASE, "disable");
    private static final String CRASH_FILE = pathJoin(MAGISK_BASE, "crash");
    private static final long PAGE_SIZE = 2L * 1024 * 1024; // 2MiB per page
    private final Handler handler = new Handler(Looper.getMainLooper());
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
    private MaterialButton btnViewProcesses;
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
            else if (!moduleLoaded) doLoad();
            else if (moduleSoftDisabled) doSoftEnable();
            else if (moduleHasPoolWant) doSoftDisable();
            else confirmUnload();
        });
        btnViewProcesses.setOnClickListener(v -> startActivity(
            new Intent(this, HugePageProcessActivity.class)));
        cardCrashWarning.setOnClickListener(v -> doDismissCrash());
        loadPoolSize();
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
            var moduleInst = shellCheckExists(MODULE_PROP);
            var moduleLoaded = shellCheckExists(SYSFS_BASE);
            var moduleDisabled = shellCheckExists(DISABLE_FILE);
            var crashStamp = shellCheckExists(CRASH_FILE);
            var refillStat = "";
            var servedSummary = "";
            if (moduleLoaded) try {
                refillStat = shellReadFile(pathJoin(SYSFS_PARAMS, "refill_stat"));
                // Reconcile + read per-VM served pages so this screen's bar shows
                // the exact same per-VM breakdown as the usage screen's bar.
                if (shellCheckExists(pathJoin(SYSFS_PARAMS, "served_summary"))) {
                    run("echo 1 > %s/reconcile", SYSFS_PARAMS);
                    servedSummary = shellReadFile(pathJoin(SYSFS_PARAMS, "served_summary"));
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read parameters", e);
            }
            var stats = parseProp(refillStat);
            var owners = parseServedOwners(servedSummary);
            runOnUiThread(() -> updateUI(
                moduleInst, moduleLoaded, moduleDisabled, crashStamp, stats, owners
            ));
        });
    }

    /** Parse served_summary "owner ... pid=N pages=M" lines into {pid, pages}. */
    @NonNull
    private List<long[]> parseServedOwners(@NonNull String raw) {
        var owners = new ArrayList<long[]>();
        for (var line : raw.split("\n")) {
            line = line.trim();
            if (!line.startsWith("owner ")) continue;
            long pid = -1, pages = 0;
            for (var tok : line.split("\\s+")) {
                try {
                    if (tok.startsWith("pid=")) pid = Long.parseLong(tok.substring(4));
                    else if (tok.startsWith("pages=")) pages = Long.parseLong(tok.substring(6));
                } catch (NumberFormatException ignored) {
                }
            }
            if (pid > 0 && pages > 0) owners.add(new long[]{pid, pages});
        }
        return owners;
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

    private long getPages(@NonNull Map<String, String> stats, @NonNull String key) {
        var value = stats.get(key);
        if (value == null) return 0;
        return Long.parseLong(value);
    }

    private void updateUI(
        boolean installed, boolean loaded,
        boolean disabled, boolean crashed,
        @NonNull Map<String, String> stats,
        @NonNull List<long[]> owners
    ) {
        if (isFinishing()) return;
        cardCrashWarning.setVisibility(crashed ? VISIBLE : GONE);
        cardNotLoaded.setVisibility(loaded ? GONE : VISIBLE);
        if (loaded && !stats.isEmpty()) {
            rowStatState.setValue(stats.getOrDefault("state", "-"));
            rowStatTotalServed.setValue(stats.getOrDefault("total_served", "-"));
            rowStatTotalRefilled.setValue(stats.getOrDefault("total_refilled", "-"));
            rowStatActiveVms.setValue(stats.getOrDefault("active_vms", "-"));
            try {
                var poolAvail = getPages(stats, "pool_avail");
                var poolTotal = getPages(stats, "pool_total");
                // "total" shows the desired target (pool_want); grow raises it
                // while capacity (pool_total) only rises via acquire. Old
                // modules without pool_want fall back to capacity.
                var poolWant = getPages(stats, "pool_want");
                if (poolWant <= 0) poolWant = poolTotal;
                // Used = pages currently served to VMs (traced). Prefer the live
                // served counter; pool_total - avail under-counts after a shrink
                // that left served pages out. Old modules: fall back to that.
                var used = stats.containsKey("served")
                    ? getPages(stats, "served")
                    : Math.clamp(poolTotal - poolAvail, 0, poolTotal);
                // 2x2 caption (identical to the usage screen): used / available
                // on top, total / pool-size below. Total = real held reserve
                // (used + avail = owned + traced), shown raw - no clamp to the
                // pool size, so a kernel that fails to release on shrink shows up
                // as total > the size you set.
                var held = used + poolAvail;
                setPagesString(tvPoolUsed, R.string.hugepage_stat_pool_used, used);
                setPagesString(tvPoolAvail, R.string.hugepage_stat_pool_available, poolAvail);
                setPagesString(tvPoolTotal, R.string.hugepage_stat_pool_total, held);
                setPagesString(tvPoolSize, R.string.hugepage_stat_pool_size, poolWant);
                // Segmented bar: one colored segment per VM (same per-VM colours
                // and deficit block as the usage screen's bar) + a track for the
                // available portion; total = want.
                boolean dark = HugePageColor.isDark(this);
                int n = owners.size();
                int[] colors = new int[n + 1];
                float[] values = new float[n + 1];
                long seg = 0;
                for (int i = 0; i < n; i++) {
                    int pid = (int) owners.get(i)[0];
                    long ownerPages = owners.get(i)[1];
                    colors[i] = HugePageColor.forPid(pid, dark);
                    values[i] = ownerPages;
                    seg += ownerPages;
                }
                colors[n] = HugePageColor.pending(this);
                values[n] = Math.max(0, poolWant - seg - poolAvail);
                float total = Math.max(poolWant, seg + poolAvail);
                segPoolBar.setData(colors, values, total);
            } catch (NumberFormatException e) {
                tvPoolUsed.setText("-");
                tvPoolAvail.setText("-");
                tvPoolTotal.setText("-");
                tvPoolSize.setText("-");
                segPoolBar.setData(new int[0], new float[0], 0f);
            }
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
        boolean acquiring = loaded && "1".equals(stats.get("acquire_active"));
        moduleAcquiring = acquiring;
        progressSavePoolSize.setVisibility(acquiring ? VISIBLE : GONE);
        if (acquiring) btnSavePoolSize.setTextColor(Color.TRANSPARENT);
        else btnSavePoolSize.setTextColor(saveTextColors);
        btnSavePoolSize.setEnabled(installed);
        // Install / Enable / Disable. "Disable" on v7 shrinks the pool to one
        // page (frees memory) but keeps the module loaded so it never loses
        // per-VM tracking; soft-disabled shows "Enable" again. v6 has no
        // pool_want knob, so Disable falls back to rmmod.
        moduleInstalled = installed;
        moduleLoaded = loaded;
        moduleHasPoolWant = stats.containsKey("pool_want");
        moduleSoftDisabled = loaded && moduleHasPoolWant
            && getPages(stats, "pool_want") <= 1;
        btnModuleToggle.setEnabled(true);
        if (!installed) {
            btnModuleToggle.setText(R.string.hugepage_btn_install);
            btnModuleToggle.setIconResource(R.drawable.ic_download);
        } else if (!loaded || moduleSoftDisabled) {
            btnModuleToggle.setText(R.string.hugepage_btn_enable);
            btnModuleToggle.setIconResource(R.drawable.ic_start);
        } else {
            btnModuleToggle.setText(R.string.hugepage_btn_disable);
            btnModuleToggle.setIconResource(R.drawable.ic_stop);
        }
        rowModuleEnable.setEnabled(installed);
        rowModuleEnable.setChecked(!disabled);
    }

    private void doLoad() {
        btnModuleToggle.setEnabled(false);
        runOnPool(() -> {
            if (shellCheckExists(SYSFS_BASE)) {
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.hugepage_already_loaded,
                        LENGTH_SHORT).show();
                    refreshStatus();
                });
                return;
            }
            // Read the configured size from settings.prop in Java (rather than
            // sourcing it in shell). v7 reads pool_want, older builds pool_target.
            var size = "1024";
            if (shellCheckExists(SETTINGS_PROP)) {
                var settings = parseProp(shellReadFile(SETTINGS_PROP));
                size = settings.getOrDefault("pool_want",
                    settings.getOrDefault("pool_target", "1024"));
            }
            // Insmod compatibly across versions: v7 takes pool_want, older builds
            // take pool_target, anything else loads bare. First success wins.
            var ko = pathJoin(MAGISK_BASE, "gh_hugepage_reserve.ko");
            var result =
                run("insmod \"%s\" pool_want=\"%s\"", ko, size).isSuccess()
                    || run("insmod \"%s\" pool_target=\"%s\"", ko, size).isSuccess()
                    || run("insmod \"%s\"", ko).isSuccess();
            runOnUiThread(() -> {
                Toast.makeText(this, result
                        ? R.string.hugepage_loaded
                        : R.string.hugepage_load_failed,
                    LENGTH_SHORT).show();
                refreshStatus();
            });
        });
    }

    /**
     * Soft-disable (v7): shrink the pool to a single page so the reserved memory
     * is freed back to the system, but keep the module loaded so it never loses
     * per-VM tracking (unlike rmmod). settings.prop is left untouched, so the
     * next boot still allocates the configured capacity.
     */
    private void doSoftDisable() {
        btnModuleToggle.setEnabled(false);
        runOnPool(() -> {
            // The module refuses a resize mid-acquire, so stop the worker first.
            stopAcquireAndWait();
            run("echo 1 > %s/pool_want", SYSFS_PARAMS);
            runOnUiThread(this::refreshStatus);
        });
    }

    /**
     * Interrupt a running acquire: write 0 to the acquire knob. pool_want is left
     * intact, so the worker stops at the size reached so far and the remaining
     * deficit keeps showing as "waiting for acquire".
     */
    private void interruptAcquire() {
        runOnPool(() -> {
            run("echo 0 > %s/acquire", SYSFS_PARAMS);
            runOnUiThread(this::refreshStatus);
        });
    }

    /**
     * Interrupt any running acquire and block (on the pool thread) until the
     * worker is quiescent. pool_want can't be set while an acquire runs, so call
     * this before writing pool_want. The worker can be mid-migration, so this may
     * take a moment; bounded so a wedged worker can't hang us forever.
     */
    private void stopAcquireAndWait() {
        if (!shellCheckExists(pathJoin(SYSFS_PARAMS, "acquire"))) return;
        run("echo 0 > %s/acquire", SYSFS_PARAMS);
        for (int i = 0; i < 60; i++) {
            var st = parseProp(shellReadFile(pathJoin(SYSFS_PARAMS, "refill_stat")));
            if (!"1".equals(st.get("acquire_active"))) return;
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                return;
            }
        }
    }

    /** Re-enable a soft-disabled pool: restore the configured size and refill. */
    private void doSoftEnable() {
        btnModuleToggle.setEnabled(false);
        runOnPool(() -> {
            var size = "1024";
            if (shellCheckExists(SETTINGS_PROP)) {
                var s = parseProp(shellReadFile(SETTINGS_PROP));
                size = s.getOrDefault("pool_want",
                    s.getOrDefault("pool_target", "1024"));
            }
            run("echo %s > %s/pool_want", size, SYSFS_PARAMS);
            if (shellCheckExists(pathJoin(SYSFS_PARAMS, "acquire"))) {
                run("echo 1 > %s/acquire", SYSFS_PARAMS);
            }
            runOnUiThread(this::refreshStatus);
        });
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
            // Persist for the next load. Write BOTH keys so the size survives
            // whichever module/loader is installed at boot: v7's loader reads
            // pool_want, an older (v6) package's loader reads pool_target.
            var wroteWant = run("echo 'pool_want=%s' > %s",
                pages.toString(), SETTINGS_PROP).isSuccess();
            var wroteTarget = run("echo 'pool_target=%s' >> %s",
                pages.toString(), SETTINGS_PROP).isSuccess();
            var result = wroteWant && wroteTarget;
            // Apply at runtime via the single pool_want knob: shrink frees excess
            // now, grow only raises the target (Acquire fills it). Old modules
            // without pool_want just keep the saved value (effective next reboot).
            var applied = false;
            if (shellCheckExists(pathJoin(SYSFS_PARAMS, "pool_want"))) {
                applied = run("echo %s > %s/pool_want",
                    pages.toString(), SYSFS_PARAMS).isSuccess();
                // Saving also kicks off one acquire so a grown target starts
                // filling immediately (a shrink just makes the worker exit at
                // once). The 1 s status poll then animates the bar + Save spinner.
                if (applied && shellCheckExists(pathJoin(SYSFS_PARAMS, "acquire"))) {
                    run("echo 1 > %s/acquire", SYSFS_PARAMS);
                }
            }
            var finalApplied = applied;
            runOnUiThread(() -> {
                int msg = !result ? R.string.hugepage_pool_size_failed
                    : finalApplied ? R.string.hugepage_pool_size_applied
                    : R.string.hugepage_pool_size_saved;
                Toast.makeText(this, msg, LENGTH_SHORT).show();
                refreshStatus();
            });
        });
    }

    private void doToggleModule() {
        var enabled = rowModuleEnable.isChecked();
        if (shellCheckExists(DISABLE_FILE) != enabled) return;
        runOnPool(() -> {
            var result = enabled ?
                runList("rm", "-f", DISABLE_FILE) :
                runList("touch", DISABLE_FILE);
            runOnUiThread(() -> {
                if (result.isSuccess()) {
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
            .setPositiveButton(R.string.hugepage_stop, (d, w) -> doUnload())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void doUnload() {
        btnModuleToggle.setEnabled(false);
        runOnPool(() -> {
            var result = runList("rmmod", "gh_hugepage_reserve").isSuccess();
            runOnUiThread(() -> {
                var msg = result ?
                    R.string.hugepage_unloaded :
                    R.string.hugepage_unload_failed;
                Toast.makeText(this, msg, LENGTH_SHORT).show();
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
