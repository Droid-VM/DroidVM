package cn.classfun.droidvm.ui.main.settings;

import static android.content.Intent.ACTION_VIEW;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.Constants.GITHUB_ISSUE_URL;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import cn.classfun.droidvm.BuildConfig;
import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.api.ApiManager;
import cn.classfun.droidvm.lib.api.Privacy;
import cn.classfun.droidvm.lib.daemon.DaemonHelper;
import cn.classfun.droidvm.lib.data.Language;
import cn.classfun.droidvm.lib.store.base.DataConfig;
import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.UIContext;
import cn.classfun.droidvm.lib.utils.CpuUtils;
import cn.classfun.droidvm.ui.hugepage.HugePageActivity;
import cn.classfun.droidvm.ui.main.base.MainBaseFragment;
import cn.classfun.droidvm.ui.setup.SetupActivity;
import cn.classfun.droidvm.ui.setup.step.PrivacyStepFragment;
import cn.classfun.droidvm.ui.update.UpdateDialog;
import cn.classfun.droidvm.ui.update.UpdateInfo;
import cn.classfun.droidvm.ui.update.VersionCheck;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextRowWidget;

public final class MainSettingsFragment extends MainBaseFragment {
    private static final long DAEMON_REFRESH_INTERVAL_MS = 1000L;
    private static final String PREFS_NAME = "droidvm_prefs";
    public static final String KEY_VM_AUTO_CONSOLE = "vm_auto_console";
    public static final String KEY_VM_CLEAR_LOGS_BEFORE_START = "vm_clear_logs_before_start";
    public static final String KEY_VM_KEEP_COMPRESS_ON_OPTIMIZE = "vm_keep_compress_on_optimize";
    public static final String KEY_QEMU_IMG_CPU_AFFINITY = "qemu_img_cpu_affinity";
    // Reserved for a future crosvm/qemu VM affinity setting; not read or
    // written yet, and intentionally has no UI in this release.
    // public static final String KEY_CROSVM_CPU_AFFINITY = "crosvm_cpu_affinity";
    public static final String KEY_AUTO_CHECK_UPDATE = "auto_check_update";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable daemonStatusRefreshRunnable = this::periodRefreshDaemonStatus;
    private String[] languageTags;
    private String[] languageNames;
    private TextRowWidget itemLanguage;
    private TextRowWidget itemFeedback;
    private TextRowWidget itemDaemonStatus;
    private TextRowWidget itemDaemonStart;
    private TextRowWidget itemDaemonStop;
    private TextRowWidget itemDaemonRestart;
    private SwitchRowWidget itemVMAutoConsole;
    private SwitchRowWidget itemVMClearLogsBeforeStart;
    private SwitchRowWidget itemVMKeepCompressOnOptimize;
    private TextRowWidget itemCpuAffinity;
    private TextRowWidget itemLicense;
    private SwitchRowWidget itemAutoCheckUpdate;
    private TextRowWidget itemCheckUpdate;
    private TextRowWidget itemPrivacy;
    private TextRowWidget itemApiManager;
    private TextRowWidget itemHugepageReserve;
    private TextRowWidget itemExportConfig;
    private TextRowWidget itemImportConfig;
    private DaemonHelper daemon;
    private ActivityResultLauncher<String> exportConfigLauncher;
    private ActivityResultLauncher<String[]> importConfigLauncher;

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_main_settings;
    }

    @Override
    public int getTitleResId() {
        return R.string.nav_settings;
    }

    @Override
    protected @MenuRes int getCustomMenuResId() {
        return R.menu.menu_main_settings;
    }

    private void bindOnClick(@NonNull View view, Runnable action) {
        view.setOnClickListener(v -> action.run());
    }

    private void bindOnChecked(@NonNull SwitchRowWidget item, String key, boolean def) {
        var prefs = requireContext().getSharedPreferences(PREFS_NAME, 0);
        item.setChecked(prefs.getBoolean(key, def));
        item.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean(key, checked).apply());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        var ui = UIContext.fromFragment(this);
        daemon = new DaemonHelper(ui);
        daemon.setOnRefreshDaemonStatus(this::onRefreshDaemonStatus);
        itemLanguage = view.findViewById(R.id.item_language);
        itemFeedback = view.findViewById(R.id.item_feedback);
        itemDaemonStatus = view.findViewById(R.id.item_daemon_status);
        itemDaemonStart = view.findViewById(R.id.item_daemon_start);
        itemDaemonStop = view.findViewById(R.id.item_daemon_stop);
        itemDaemonRestart = view.findViewById(R.id.item_daemon_restart);
        itemVMAutoConsole = view.findViewById(R.id.item_vm_auto_console);
        itemVMClearLogsBeforeStart = view.findViewById(R.id.item_vm_clear_logs_before_start);
        itemVMKeepCompressOnOptimize = view.findViewById(R.id.item_vm_keep_compress_on_optimize);
        itemCpuAffinity = view.findViewById(R.id.item_cpu_affinity);
        itemLicense = view.findViewById(R.id.item_license);
        itemAutoCheckUpdate = view.findViewById(R.id.item_auto_check_update);
        itemCheckUpdate = view.findViewById(R.id.item_check_update);
        itemPrivacy = view.findViewById(R.id.item_privacy);
        itemApiManager = view.findViewById(R.id.item_api_manager);
        itemHugepageReserve = view.findViewById(R.id.item_hugepage_reserve);
        itemExportConfig = view.findViewById(R.id.item_export_config);
        itemImportConfig = view.findViewById(R.id.item_import_config);
        exportConfigLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            this::onExportFileSelected
        );
        importConfigLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            this::onImportFileSelected
        );
        initSettings();
    }

    private void initSettings() {
        loadLanguages();
        bindOnClick(itemLanguage, this::showLanguageDialog);
        bindOnClick(itemFeedback, this::openFeedback);
        bindOnClick(itemLicense, this::showLicenseDialog);
        bindOnClick(itemDaemonStatus, daemon::asyncRefreshDaemonStatus);
        bindOnClick(itemDaemonStart, daemon::asyncStartDaemon);
        bindOnClick(itemDaemonStop, daemon::asyncStopDaemon);
        bindOnClick(itemDaemonRestart, daemon::asyncRestartDaemon);
        bindOnChecked(itemVMAutoConsole, KEY_VM_AUTO_CONSOLE, false);
        bindOnChecked(itemVMClearLogsBeforeStart, KEY_VM_CLEAR_LOGS_BEFORE_START, false);
        bindOnChecked(itemVMKeepCompressOnOptimize, KEY_VM_KEEP_COMPRESS_ON_OPTIMIZE, false);
        bindOnClick(itemCpuAffinity, this::showCpuAffinityDialog);
        refreshCpuAffinitySummary();
        bindOnChecked(itemAutoCheckUpdate, KEY_AUTO_CHECK_UPDATE, true);
        bindOnClick(itemCheckUpdate, this::checkUpdate);
        bindOnClick(itemPrivacy, this::showPrivacyPolicy);
        bindOnClick(itemApiManager, this::showApiManager);
        bindOnClick(itemHugepageReserve, this::showHugePageReserve);
        bindOnClick(itemExportConfig, this::exportConfig);
        bindOnClick(itemImportConfig, this::importConfig);
        itemDaemonStatus.setSubtitle(R.string.settings_daemon_checking);
        itemDaemonStart.setVisibility(GONE);
        itemDaemonStop.setVisibility(GONE);
        itemDaemonRestart.setVisibility(GONE);
        refreshLanguageSummary();
        daemon.asyncRefreshDaemonStatus();
    }

    private void periodRefreshDaemonStatus() {
        if (isAdded())
            daemon.asyncRefreshDaemonStatus();
        mainHandler.postDelayed(daemonStatusRefreshRunnable, DAEMON_REFRESH_INTERVAL_MS);
    }

    @Override
    public void onResume() {
        super.onResume();
        mainHandler.post(daemonStatusRefreshRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(daemonStatusRefreshRunnable);
    }

    public static boolean isAutoConsoleEnabled(@NonNull Context context) {
        var prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_VM_AUTO_CONSOLE, false);
    }

    public static boolean isClearLogsBeforeStartEnabled(@NonNull Context context) {
        var prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_VM_CLEAR_LOGS_BEFORE_START, false);
    }

    public static boolean isKeepCompressOnOptimizeEnabled(@NonNull Context context) {
        var prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_VM_KEEP_COMPRESS_ON_OPTIMIZE, false);
    }

    public static boolean isAutoCheckUpdateEnabled(@NonNull Context context) {
        var prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_AUTO_CHECK_UPDATE, true);
    }

    /**
     * qemu-img CPU affinity as a taskset -c core list (e.g. "4,5,6,7").
     * Empty string means no binding (qemu-img may use every core).
     */
    @NonNull
    public static String getQemuImgCpuAffinity(@NonNull Context context) {
        var prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_QEMU_IMG_CPU_AFFINITY, "");
    }

    private void onRefreshDaemonStatus(boolean r) {
        itemDaemonStatus.setSubtitle(r ?
            R.string.settings_daemon_running : R.string.settings_daemon_stopped);
        itemDaemonStart.setVisibility(r ? GONE : VISIBLE);
        itemDaemonStop.setVisibility(r ? VISIBLE : GONE);
        itemDaemonRestart.setVisibility(r ? VISIBLE : GONE);
    }

    private void loadLanguages() {
        var languages = new Language.List(requireContext());
        languageTags = languages.getTagsArray();
        languageNames = languages.getNamesArray();
        languageNames[0] = getString(R.string.settings_language_system_default);
    }

    private void refreshLanguageSummary() {
        if (itemLanguage == null || languageTags == null) return;
        var locales = AppCompatDelegate.getApplicationLocales();
        if (locales.isEmpty()) {
            itemLanguage.setSubtitle(R.string.settings_language_system_default);
            return;
        }
        var tag = locales.toLanguageTags();
        for (int i = 1; i < languageTags.length; i++) {
            if (languageTags[i].equals(tag)) {
                itemLanguage.setSubtitle(languageNames[i]);
                return;
            }
        }
        itemLanguage.setSubtitle(tag);
    }

    private void showLanguageDialog() {
        if (languageTags == null) return;
        var ctx = requireContext();
        var currentLocales = AppCompatDelegate.getApplicationLocales();
        var currentTag = currentLocales.isEmpty() ? "" : currentLocales.toLanguageTags();
        int checkedItem = 0;
        for (int i = 0; i < languageTags.length; i++) {
            if (languageTags[i].equals(currentTag)) {
                checkedItem = i;
                break;
            }
        }
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_language_title)
            .setSingleChoiceItems(languageNames, checkedItem, (dialog, which) -> {
                var tag = languageTags[which];
                var localeList = tag.isEmpty() ?
                    LocaleListCompat.getEmptyLocaleList() :
                    LocaleListCompat.forLanguageTags(tag);
                AppCompatDelegate.setApplicationLocales(localeList);
                refreshLanguageSummary();
                dialog.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void refreshCpuAffinitySummary() {
        if (itemCpuAffinity == null) return;
        var csv = getQemuImgCpuAffinity(requireContext());
        if (csv.isEmpty()) {
            itemCpuAffinity.setSubtitle(R.string.settings_cpu_affinity_all_cores);
        } else {
            itemCpuAffinity.setSubtitle(getString(
                R.string.settings_cpu_affinity_summary_fmt, CpuUtils.compactRanges(csv)));
        }
    }

    private void showCpuAffinityDialog() {
        var ctx = requireContext();
        var cores = CpuUtils.getCores();
        int tiers = CpuUtils.tierCount(cores);
        var labels = new String[cores.size()];
        for (int i = 0; i < cores.size(); i++)
            labels[i] = cpuCoreLabel(cores.get(i), tiers);

        // Saved selection, or the "filter out little cores" default when unset.
        var savedCsv = getQemuImgCpuAffinity(ctx);
        var selectedIdx = parseCsvToSet(
            savedCsv.isEmpty() ? CpuUtils.defaultBigCoresCsv() : savedCsv);
        var checked = new boolean[cores.size()];
        for (int i = 0; i < cores.size(); i++)
            checked[i] = selectedIdx.contains(cores.get(i).index);

        var dialog = new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_cpu_affinity_title)
            .setMultiChoiceItems(labels, checked, (d, which, isChecked) ->
                checked[which] = isChecked)
            .setNeutralButton(R.string.settings_cpu_affinity_big_only, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                var sb = new StringBuilder();
                for (int i = 0; i < cores.size(); i++) {
                    if (!checked[i]) continue;
                    if (sb.length() > 0) sb.append(',');
                    sb.append(cores.get(i).index);
                }
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_QEMU_IMG_CPU_AFFINITY, sb.toString()).apply();
                refreshCpuAffinitySummary();
            })
            .create();
        dialog.show();
        // Re-check only the big cores without dismissing the dialog.
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            var bigIdx = parseCsvToSet(CpuUtils.defaultBigCoresCsv());
            var list = dialog.getListView();
            for (int i = 0; i < cores.size(); i++) {
                checked[i] = bigIdx.contains(cores.get(i).index);
                list.setItemChecked(i, checked[i]);
            }
        });
    }

    @NonNull
    private String cpuCoreLabel(@NonNull CpuUtils.CpuCore core, int tiers) {
        var freq = CpuUtils.formatFreq(core.maxFreqKHz);
        int tierRes;
        if (tiers <= 1) tierRes = 0;
        else if (core.tier == 0) tierRes = R.string.settings_cpu_affinity_tier_little;
        else if (tiers >= 3 && core.tier == tiers - 1)
            tierRes = R.string.settings_cpu_affinity_tier_prime;
        else tierRes = R.string.settings_cpu_affinity_tier_big;
        var sb = new StringBuilder(fmt("CPU%d", core.index));
        if (!freq.isEmpty()) sb.append("    ").append(freq);
        if (tierRes != 0) sb.append("    ").append(getString(tierRes));
        return sb.toString();
    }

    @NonNull
    private static java.util.Set<Integer> parseCsvToSet(@NonNull String csv) {
        var set = new java.util.HashSet<Integer>();
        if (csv.isEmpty()) return set;
        for (var part : csv.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                set.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
            }
        }
        return set;
    }

    private void showLicenseDialog() {
        new SoftwareLicenseDialog(requireContext()).show();
    }

    private void openFeedback() {
        startActivity(new Intent(ACTION_VIEW, Uri.parse(GITHUB_ISSUE_URL)));
    }

    private void checkUpdate() {
        itemCheckUpdate.setSubtitle(R.string.settings_check_update_checking);
        new VersionCheck().check(requireContext(), new VersionCheck.Callback() {
            @Override
            public void onUpdateAvailable(@NonNull UpdateInfo info) {
                if (!isAdded()) return;
                itemCheckUpdate.setSubtitle(R.string.settings_check_update_summary);
                new UpdateDialog(requireContext(), info).show();
            }

            @Override
            public void onNoUpdate() {
                if (!isAdded()) return;
                itemCheckUpdate.setSubtitle(R.string.settings_check_update_summary);
                Toast.makeText(requireContext(),
                    R.string.settings_check_update_no_update, LENGTH_SHORT).show();
            }

            @Override
            public void onError(@NonNull Exception e) {
                if (!isAdded()) return;
                itemCheckUpdate.setSubtitle(R.string.settings_check_update_summary);
                Toast.makeText(requireContext(),
                    R.string.settings_check_update_error, LENGTH_LONG).show();
            }
        });
    }

    private void showPrivacyPolicy() {
        var ctx = requireContext();
        Privacy.unsetPrivacyAgreement(ctx);
        var intent = SetupActivity.createSingleStepIntent(ctx, PrivacyStepFragment.class);
        startActivity(intent);
        requireActivity().finish();
    }

    private void showApiManager() {
        runOnPool(() -> {
            ApiManager api;
            try {
                api = ApiManager.create(requireContext());
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(requireContext(),
                    R.string.settings_api_manager_not_loaded, Toast.LENGTH_SHORT).show());
                return;
            }
            mainHandler.post(() -> {
                if (!isAdded()) return;
                new ApiManagerDialog(requireContext(), api).show();
            });
        });
    }

    private void showHugePageReserve() {
        startActivity(new Intent(requireContext(), HugePageActivity.class));
    }

    private void exportConfig() {
        var sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        var timestamp = sdf.format(new Date());
        exportConfigLauncher.launch(fmt("DroidVM_backup_%s.json", timestamp));
    }

    @SuppressWarnings("unused")
    private void onExportFileSelected(Uri uri) {
        if (uri == null) return;
        var ctx = requireContext().getApplicationContext();
        runOnPool(() -> {
            try {
                var vmStore = new VMStore(ctx);
                var diskStore = new DiskStore(ctx);
                var netStore = new NetworkStore(ctx);
                var exportJson = new JSONObject();
                exportJson.put("version", 1);
                exportJson.put("timestamp", System.currentTimeMillis());
                var sdf = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.getDefault());
                exportJson.put("date", sdf.format(new Date()));
                exportJson.put("app_version", BuildConfig.VERSION_NAME);
                exportJson.put("app_version_code", BuildConfig.VERSION_CODE);
                exportJson.put("app_version_name", BuildConfig.VERSION_NAME);
                exportJson.put("app_build_type", BuildConfig.BUILD_TYPE);
                exportJson.put("app_id", BuildConfig.APPLICATION_ID);
                exportJson.put("vms", vmStore.toJson().getJSONArray("vms"));
                exportJson.put("disks", diskStore.toJson().getJSONArray("disks"));
                exportJson.put("networks", netStore.toJson().getJSONArray("networks"));
                try (var os = ctx.getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new Exception("Failed to open output stream");
                    os.write(exportJson.toString(4).getBytes(StandardCharsets.UTF_8));
                }
                mainHandler.post(() -> Toast.makeText(requireContext(),
                    R.string.settings_export_success, LENGTH_SHORT).show());
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(requireContext(),
                    getString(R.string.settings_export_failed, e.getMessage()), LENGTH_LONG).show());
            }
        });
    }

    private void importConfig() {
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_import_confirm_title)
            .setMessage(R.string.settings_import_confirm_message)
            .setPositiveButton(android.R.string.ok, (d, w) ->
                importConfigLauncher.launch(new String[]{"application/json"}))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private <T extends DataConfig> void loadStore(
        @NonNull Class<? extends DataStore<T>> store,
        @NonNull JSONObject json
    ) throws Exception {
        var ctx = requireContext().getApplicationContext();
        var oldStore = store.newInstance();
        var newStore = store.newInstance();
        oldStore.load(ctx);
        newStore.load(json);
        newStore.forEach((k, v) -> {
            if (oldStore.findById(k) == null)
                oldStore.add(v);
            else
                oldStore.update(v);
        });
        oldStore.save(ctx);
    }

    @SuppressWarnings("unused")
    private void onImportFileSelected(Uri uri) {
        if (uri == null) return;
        var ctx = requireContext().getApplicationContext();
        runOnPool(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                try (var is = ctx.getContentResolver().openInputStream(uri)) {
                    if (is == null) throw new Exception("Failed to open input stream");
                    try (var reader = new BufferedReader(new InputStreamReader(is))) {
                        String line;
                        while ((line = reader.readLine()) != null)
                            sb.append(line).append('\n');
                    }
                }
                var importJson = new JSONObject(sb.toString());
                int count = 0;
                if (importJson.has("vms")) {
                    loadStore(VMStore.class, importJson);
                    count++;
                }
                if (importJson.has("disks")) {
                    loadStore(DiskStore.class, importJson);
                    count++;
                }
                if (importJson.has("networks")) {
                    loadStore(NetworkStore.class, importJson);
                    count++;
                }
                final int imported = count;
                mainHandler.post(() -> Toast.makeText(requireContext(),
                    getString(R.string.settings_import_success, imported), LENGTH_SHORT).show());
            } catch (JSONException e) {
                mainHandler.post(() -> Toast.makeText(requireContext(),
                    R.string.settings_import_invalid_json, LENGTH_LONG).show());
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(requireContext(),
                    getString(R.string.settings_import_failed, e.getMessage()), LENGTH_LONG).show());
            }
        });
    }
}
