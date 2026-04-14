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
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.LinkedHashMap;
import java.util.Map;

import cn.classfun.droidvm.R;
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
    private MaterialButton btnManualRefill;
    private MaterialButton btnStop;
    private LinearProgressIndicator progressPoolUsage;
    private TextView tvPoolAvail;
    private TextView tvPoolTotal;
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
        btnManualRefill = findViewById(R.id.btn_manual_refill);
        btnStop = findViewById(R.id.btn_stop);
        progressPoolUsage = findViewById(R.id.progress_pool_usage);
        tvPoolAvail = findViewById(R.id.tv_pool_avail);
        tvPoolTotal = findViewById(R.id.tv_pool_total);
        rowModuleEnable = findViewById(R.id.row_module_enable);
        rowStatState = findViewById(R.id.row_stat_state);
        rowStatTotalServed = findViewById(R.id.row_stat_total_served);
        rowStatTotalRefilled = findViewById(R.id.row_stat_total_refilled);
        rowStatActiveVms = findViewById(R.id.row_stat_active_vms);
        initialize();
    }

    private void initialize() {
        if (!shellCheckExists(MODULE_PROP)) {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hugepage_not_installed)
                .setMessage(R.string.hugepage_not_installed_desc)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.hugepage_download, (d, v) -> openModulePage())
                .setOnDismissListener(d -> finish())
                .show();
            return;
        }
        toolbar.setTitle(R.string.hugepage_title);
        toolbar.setNavigationOnClickListener(v -> finish());
        btnManualRefill.setOnClickListener(v -> doManualRefill());
        rowModuleEnable.setOnCheckedChangeListener(this::doToggleModule);
        btnStop.setOnClickListener(v -> confirmUnload());
        cardCrashWarning.setOnClickListener(v -> doDismissCrash());
        inputPoolSize.setOnFocusLostListener(this::savePoolSize);
        inputPoolSize.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                savePoolSize();
                return true;
            }
            return false;
        });
        loadPoolSize();
    }

    private void openModulePage() {
        var url = "https://github.com/Droid-VM/gh-hugepage-reserve";
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
        savePoolSize();
    }

    private void scheduleRefresh() {
        if (resumed) handler.postDelayed(refreshRunnable, 1000);
    }

    private void refreshStatus() {
        runOnPool(() -> {
            var moduleLoaded = shellCheckExists(SYSFS_BASE);
            var moduleDisabled = shellCheckExists(DISABLE_FILE);
            var crashStamp = shellCheckExists(CRASH_FILE);
            var refillStat = "";
            if (moduleLoaded) try {
                refillStat = shellReadFile(pathJoin(SYSFS_PARAMS, "refill_stat"));
            } catch (Exception e) {
                Log.w(TAG, "Failed to read parameters", e);
            }
            var stats = parseProp(refillStat);
            runOnUiThread(() -> updateUI(
                moduleLoaded, moduleDisabled, crashStamp, stats
            ));
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

    private long getPages(@NonNull Map<String, String> stats, @NonNull String key) {
        var value = stats.get(key);
        if (value == null) return 0;
        return Long.parseLong(value);
    }

    private void updateUI(
        boolean loaded,
        boolean disabled, boolean crashed,
        @NonNull Map<String, String> stats
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
                setPagesString(tvPoolAvail, R.string.hugepage_stat_pool_available, poolAvail);
                setPagesString(tvPoolTotal, R.string.hugepage_stat_pool_total, poolTotal);
                if (poolTotal > 0) {
                    var used = Math.clamp(poolTotal - poolAvail, 0, poolTotal);
                    var progress = (int) (used * 1000L / poolTotal);
                    progressPoolUsage.setProgress(progress);
                } else {
                    progressPoolUsage.setProgress(0);
                }
            } catch (NumberFormatException e) {
                tvPoolAvail.setText("-");
                tvPoolTotal.setText("-");
                progressPoolUsage.setProgress(0);
            }
        } else {
            rowStatState.setValue(getString(R.string.hugepage_stats_unavailable));
            rowStatTotalServed.setValue(null);
            rowStatTotalRefilled.setValue(null);
            rowStatActiveVms.setValue(null);
            tvPoolAvail.setText("-");
            tvPoolTotal.setText("-");
            progressPoolUsage.setProgress(0);
        }
        btnManualRefill.setEnabled(loaded);
        btnStop.setEnabled(loaded);
        rowModuleEnable.setChecked(!disabled);
    }

    private void doManualRefill() {
        btnManualRefill.setEnabled(false);
        runOnPool(() -> {
            var result = run(
                "echo 1 > %s/manual_refill",
                SYSFS_PARAMS
            ).isSuccess();
            runOnUiThread(() -> {
                btnManualRefill.setEnabled(true);
                var msg = result ?
                    R.string.hugepage_refill_triggered :
                    R.string.hugepage_refill_failed;
                Toast.makeText(this, msg, LENGTH_SHORT).show();
            });
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
            var cur = settings.getOrDefault("pool_target", "2048");
            if (cur == null || cur.isEmpty()) cur = "2048";
            try {
                var pages = Long.parseLong(cur);
                var bytes = BigInteger.valueOf(pages * PAGE_SIZE);
                runOnUiThread(() -> inputPoolSize.setBigValue(bytes));
            } catch (NumberFormatException e) {
                Log.w(TAG, "Failed to parse pool_target", e);
            }
        });
    }

    private void savePoolSize() {
        if (!inputPoolSize.isInputValid()) return;
        var bytes = inputPoolSize.getBigValue();
        var pages = bytes.divide(BigInteger.valueOf(PAGE_SIZE));
        runOnPool(() -> {
            var result = run(
                "echo 'pool_target=%s' > %s",
                pages.toString(), SETTINGS_PROP
            ).isSuccess();
            runOnUiThread(() -> {
                var msg = result ?
                    R.string.hugepage_pool_size_saved :
                    R.string.hugepage_pool_size_failed;
                Toast.makeText(this, msg, LENGTH_SHORT).show();
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
        btnStop.setEnabled(false);
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
