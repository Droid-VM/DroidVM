// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
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
import android.widget.TextView;
import android.widget.ScrollView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import android.widget.RadioGroup;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.radiobutton.MaterialRadioButton;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import cn.classfun.droidvm.BuildConfig;
import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.api.ApiManager;
import cn.classfun.droidvm.lib.api.Privacy;
import cn.classfun.droidvm.daemon.vm.UsbAcmPool;
import cn.classfun.droidvm.lib.daemon.DaemonHelper;
import cn.classfun.droidvm.lib.daemon.ConfigurationClient;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.HostPreferencesClient;
import cn.classfun.droidvm.lib.daemon.DiskDefaultsClient;
import cn.classfun.droidvm.lib.daemon.UsbAcmClient;
import cn.classfun.droidvm.lib.daemon.VMEventHandler;
import cn.classfun.droidvm.lib.data.Language;
import cn.classfun.droidvm.lib.store.base.DataConfig;
import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.UIContext;
import cn.classfun.droidvm.lib.utils.CpuUtils;
import cn.classfun.droidvm.ui.disk.create.DiskCompress;
import cn.classfun.droidvm.ui.disk.operation.OptimizeCompression;
import cn.classfun.droidvm.ui.hugepage.HugePageActivity;
import cn.classfun.droidvm.ui.main.base.MainBaseFragment;
import cn.classfun.droidvm.ui.setup.SetupActivity;
import cn.classfun.droidvm.ui.setup.step.PrivacyStepFragment;
import cn.classfun.droidvm.ui.update.UpdateDialog;
import cn.classfun.droidvm.ui.update.UpdateInfo;
import cn.classfun.droidvm.ui.update.VersionCheck;
import cn.classfun.droidvm.ui.usb.UsbDevicesActivity;
import cn.classfun.droidvm.ui.usb.UsbRulesActivity;
import cn.classfun.droidvm.ui.widgets.row.SwitchRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextRowWidget;
import cn.classfun.droidvm.ui.widgets.tools.CpuCorePickerDialog;

public final class MainSettingsFragment extends MainBaseFragment {
    private static final long DAEMON_REFRESH_INTERVAL_MS = 1000L;
    private static final String PREFS_NAME = "droidvm_prefs";
    public static final String KEY_VM_AUTO_CONSOLE = "vm_auto_console";
    public static final String KEY_VM_CLEAR_LOGS_BEFORE_START = HostPreferencesClient.CLEAR_LOGS;
    /** Raise the huge-page reserve target for each starting Gunyah VM (read by the daemon too). */
    public static final String KEY_VM_HUGEPAGE_AUTO_RESERVE = HostPreferencesClient.AUTO_RESERVE;
    public static final String KEY_VM_OPTIMIZE_COMPRESSION = DiskDefaultsClient.LEGACY_COMPRESSION;
    /** Sentinel for {@link #KEY_VM_OPTIMIZE_COMPRESSION}: prompt on every optimize. */
    public static final String OPTIMIZE_COMPRESSION_ASK = "ask";
    public static final String KEY_QEMU_IMG_CPU_AFFINITY = DiskDefaultsClient.LEGACY_CPUS;
    public static final String KEY_OPTIMIZE_SDCARD = HostPreferencesClient.OPTIMIZE_SDCARD;
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
    private SwitchRowWidget itemVMHugepageAutoReserve;
    private TextRowWidget itemVMOptimizeCompression;
    private TextRowWidget itemDiskDefaults;
    private DiskDefaultsClient.Snapshot diskDefaults;
    private boolean diskDefaultsBusy, diskDefaultsLegacy;
    private int diskDefaultsGeneration;
    private SwitchRowWidget itemOptimizeSdcard;
    private TextRowWidget itemHostPreferences;
    private HostPreferencesClient.Snapshot hostPreferences;
    private boolean hostPreferencesBusy, bindingHostPreferences;
    private int hostPreferencesGeneration;
    private TextRowWidget itemUsbAcmPorts;
    private UsbAcmClient.Snapshot usbAcm;
    private boolean usbAcmBusy;
    private TextRowWidget itemUsbPassthrough;
    private TextRowWidget itemUsbDevices;
    private TextRowWidget itemCpuAffinity;
    private TextRowWidget itemLicense;
    private SwitchRowWidget itemAutoCheckUpdate;
    private TextRowWidget itemCheckUpdate;
    private TextRowWidget itemPrivacy;
    private TextRowWidget itemApiManager;
    private TextRowWidget itemHugepageReserve;
    private TextRowWidget itemKernelModules;
    private TextRowWidget itemExportConfig;
    private TextRowWidget itemImportConfig;
    private DaemonHelper daemon;
    private ActivityResultLauncher<String> exportConfigLauncher;
    private ActivityResultLauncher<String[]> importConfigLauncher;
    // Survives Fragment recreation while a worker still commits configuration.
    private static final java.util.concurrent.atomic.AtomicBoolean importingConfig =
        new java.util.concurrent.atomic.AtomicBoolean();

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
        item.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            VMEventHandler.sendAppConfig(requireActivity());
        });
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        var ui = UIContext.fromFragment(this);
        daemon = new DaemonHelper(ui);
        daemon.setOnRefreshDaemonStatus(this::onRefreshDaemonStatus);
        itemLanguage = view.findViewById(R.id.item_language);
        view.findViewById(R.id.item_webui).setOnClickListener(v -> startActivity(
            new Intent(requireContext(), cn.classfun.droidvm.ui.webui.WebUiActivity.class)));
        itemFeedback = view.findViewById(R.id.item_feedback);
        itemDaemonStatus = view.findViewById(R.id.item_daemon_status);
        itemDaemonStart = view.findViewById(R.id.item_daemon_start);
        itemDaemonStop = view.findViewById(R.id.item_daemon_stop);
        itemDaemonRestart = view.findViewById(R.id.item_daemon_restart);
        itemVMAutoConsole = view.findViewById(R.id.item_vm_auto_console);
        itemVMClearLogsBeforeStart = view.findViewById(R.id.item_vm_clear_logs_before_start);
        itemVMHugepageAutoReserve = view.findViewById(R.id.item_vm_hugepage_auto_reserve);
        itemVMOptimizeCompression = view.findViewById(R.id.item_vm_optimize_compression);
        itemDiskDefaults = view.findViewById(R.id.item_disk_defaults);
        itemOptimizeSdcard = view.findViewById(R.id.item_optimize_sdcard);
        itemHostPreferences = view.findViewById(R.id.item_host_preferences);
        itemUsbAcmPorts = view.findViewById(R.id.item_usb_acm_ports);
        itemUsbPassthrough = view.findViewById(R.id.item_usb_passthrough);
        itemUsbDevices = view.findViewById(R.id.item_usb_devices);
        itemCpuAffinity = view.findViewById(R.id.item_cpu_affinity);
        itemLicense = view.findViewById(R.id.item_license);
        itemAutoCheckUpdate = view.findViewById(R.id.item_auto_check_update);
        itemCheckUpdate = view.findViewById(R.id.item_check_update);
        itemPrivacy = view.findViewById(R.id.item_privacy);
        itemApiManager = view.findViewById(R.id.item_api_manager);
        itemHugepageReserve = view.findViewById(R.id.item_hugepage_reserve);
        itemKernelModules = view.findViewById(R.id.item_kernel_modules);
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
        bindSharedPreference(itemVMClearLogsBeforeStart, KEY_VM_CLEAR_LOGS_BEFORE_START);
        bindSharedPreference(itemVMHugepageAutoReserve, KEY_VM_HUGEPAGE_AUTO_RESERVE);
        bindOnClick(itemHostPreferences, this::refreshHostPreferences);
        bindOnClick(itemVMOptimizeCompression, this::showOptimizeCompressionDialog);
        bindOnClick(itemDiskDefaults, () -> workDiskDefaults(null, null));
        itemVMOptimizeCompression.setEnabled(false);
        itemCpuAffinity.setEnabled(false);
        refreshOptimizeCompressionSummary();
        bindOnClick(itemCpuAffinity, this::showCpuAffinityDialog);
        refreshCpuAffinitySummary();
        bindSharedPreference(itemOptimizeSdcard, KEY_OPTIMIZE_SDCARD);
        bindOnClick(itemUsbAcmPorts, this::showUsbAcmPortsDialog);
        refreshUsbAcmPortsSummary();
        bindOnClick(itemUsbPassthrough, this::showUsbPassthrough);
        bindOnClick(itemUsbDevices, this::showUsbDevices);
        bindOnChecked(itemAutoCheckUpdate, KEY_AUTO_CHECK_UPDATE, true);
        bindOnClick(itemCheckUpdate, this::checkUpdate);
        bindOnClick(itemPrivacy, this::showPrivacyPolicy);
        bindOnClick(itemApiManager, this::showApiManager);
        bindOnClick(itemHugepageReserve, this::showHugePageReserve);
        bindOnClick(itemKernelModules, this::showKernelModules);
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
        refreshHostPreferences();
        refreshUsbAcmPreferences(false);
        workDiskDefaults(null, null);
    }

    @Override
    public void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(daemonStatusRefreshRunnable);
    }

    @Override
    public void onDestroyView() {
        ++hostPreferencesGeneration;
        hostPreferencesBusy = false;
        hostPreferences = null;
        usbAcm = null;
        usbAcmBusy = false;
        ++diskDefaultsGeneration;
        diskDefaults = null;
        diskDefaultsBusy = diskDefaultsLegacy = false;
        super.onDestroyView();
    }

    private void bindSharedPreference(SwitchRowWidget row, String key) {
        row.setEnabled(false);
        row.setOnCheckedChangeListener((button, checked) -> {
            if (bindingHostPreferences) return;
            final var baseline = hostPreferences;
            renderHostPreferences(); // A tap is a request, never a successful local save.
            if (baseline != null && !hostPreferencesBusy) updateHostPreference(baseline, key, checked);
        });
    }

    private void renderHostPreferences() {
        bindingHostPreferences = true;
        try {
            if (hostPreferences != null) {
                itemVMClearLogsBeforeStart.setChecked(hostPreferences.value(KEY_VM_CLEAR_LOGS_BEFORE_START));
                itemVMHugepageAutoReserve.setChecked(hostPreferences.value(KEY_VM_HUGEPAGE_AUTO_RESERVE));
                itemOptimizeSdcard.setChecked(hostPreferences.value(KEY_OPTIMIZE_SDCARD));
            }
            boolean editable = hostPreferences != null && hostPreferences.writable && !hostPreferencesBusy
                && !HostPreferencesClient.getInstance().hasPending();
            itemVMClearLogsBeforeStart.setEnabled(editable);
            itemVMHugepageAutoReserve.setEnabled(editable);
            itemOptimizeSdcard.setEnabled(editable);
        } finally { bindingHostPreferences = false; }
    }

    /** Also serves as explicit retry/query: no failed or unknown patch is replayed. */
    private void refreshHostPreferences() {
        if (!isAdded() || getView() == null || hostPreferencesBusy) return;
        workHostPreferences(null, null, false);
    }

    private void updateHostPreference(HostPreferencesClient.Snapshot baseline, String key, boolean value) {
        workHostPreferences(baseline, key, value);
    }

    private void workHostPreferences(HostPreferencesClient.Snapshot baseline, String key, boolean value) {
        hostPreferencesBusy = true;
        final int generation = ++hostPreferencesGeneration;
        final View owner = getView();
        itemHostPreferences.setVisibility(GONE);
        renderHostPreferences();
        runOnPool(() -> {
            HostPreferencesClient.Snapshot snapshot = null;
            int message = R.string.settings_host_preferences_ready;
            try {
                var client = HostPreferencesClient.getInstance();
                var receipt = baseline == null ? client.queryPending() : client.patch(baseline, key, value);
                snapshot = client.synchronizeLegacy();
                if (receipt != null && !receipt.succeeded)
                    message = "CONFLICT".equals(receipt.errorCode)
                        ? R.string.settings_host_preferences_conflict : R.string.settings_host_preferences_failed;
                else if (baseline != null) message = R.string.settings_host_preferences_saved;
                if (!snapshot.writable) message = R.string.settings_host_preferences_unavailable;
            } catch (HostPreferencesClient.Blocked error) {
                message = error.uncertain ? R.string.settings_host_preferences_pending
                    : error.unsupported ? R.string.settings_host_preferences_unsupported
                    : R.string.settings_host_preferences_failed;
                if (!error.uncertain && !error.unsupported) {
                    try { snapshot = HostPreferencesClient.getInstance().refresh(); }
                    catch (Exception ignored) { /* Read failure must not re-enable cached switches. */ }
                    if ("CONFLICT".equals(error.code)) message = snapshot == null
                        ? R.string.settings_host_preferences_unavailable : R.string.settings_host_preferences_conflict;
                }
            } catch (Exception error) { message = R.string.settings_host_preferences_unavailable; }
            final var result = snapshot;
            final int status = message;
            mainHandler.post(() -> {
                if (!isAdded() || getView() != owner || generation != hostPreferencesGeneration) return;
                hostPreferencesBusy = false;
                hostPreferences = result;
                itemHostPreferences.setSubtitle(status);
                boolean pending = HostPreferencesClient.getInstance().hasPending();
                itemHostPreferences.setText(getString(pending ? R.string.hugepage_operation_query : R.string.menu_refresh));
                itemHostPreferences.setVisibility(pending || result == null || !result.writable ? VISIBLE : GONE);
                renderHostPreferences();
                if (baseline != null) Toast.makeText(requireContext(), status, LENGTH_LONG).show();
            });
        });
    }

    public static boolean isAutoConsoleEnabled(@NonNull Context context) {
        var prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_VM_AUTO_CONSOLE, false);
    }

    public static boolean isHugepageAutoReserveEnabled(@NonNull Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VM_HUGEPAGE_AUTO_RESERVE, true);
    }

    public static boolean isClearLogsBeforeStartEnabled(@NonNull Context context) {
        var prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_VM_CLEAR_LOGS_BEFORE_START, false);
    }

    /**
     * The compression a disk optimize should target: the wire value of a member of
     * {@link DiskCompress#CROSVM_SUPPORTED}, or {@link #OPTIMIZE_COMPRESSION_ASK} to prompt
     * each time (the prompt's "remember" writes this back). A stored value that isn't (or is
     * no longer) crosvm-supported reads as ask.
     */
    @NonNull
    public static String getOptimizeCompression(@NonNull Context context) {
        var prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        var value = prefs.getString(KEY_VM_OPTIMIZE_COMPRESSION, OPTIMIZE_COMPRESSION_ASK);
        var compress = DiskCompress.fromValue(value);
        if (compress != null && compress.isCrosvmSupported()) return value;
        return OPTIMIZE_COMPRESSION_ASK;
    }

    @StringRes
    private static int optimizeCompressionLabel(@NonNull String value) {
        var compress = DiskCompress.fromValue(value);
        if (compress != null) return OptimizeCompression.labelOf(compress);
        return R.string.settings_optimize_compression_ask;
    }

    private void refreshOptimizeCompressionSummary() {
        itemVMOptimizeCompression.setSubtitle(
            optimizeCompressionLabel(diskDefaults == null ? getOptimizeCompression(requireContext()) : diskDefaults.optimizeCompression));
    }

    private void renderDiskDefaults() {
        boolean writable = !diskDefaultsBusy && !DiskDefaultsClient.getInstance().hasPending()
            && (diskDefaultsLegacy || (diskDefaults != null && diskDefaults.writable));
        itemVMOptimizeCompression.setEnabled(writable);
        itemCpuAffinity.setEnabled(writable);
        refreshOptimizeCompressionSummary();
        refreshCpuAffinitySummary();
    }

    /** The status row only queries an existing operation; it never retries a previous save. */
    private void workDiskDefaults(DiskDefaultsClient.Snapshot baseline, JSONObject changes) {
        if (!isAdded() || getView() == null || diskDefaultsBusy) return;
        diskDefaultsBusy = true;
        final int generation = ++diskDefaultsGeneration;
        final View owner = getView();
        final var context = requireContext().getApplicationContext();
        itemDiskDefaults.setVisibility(GONE);
        renderDiskDefaults();
        runOnPool(() -> {
            DiskDefaultsClient.Snapshot snapshot = null;
            boolean legacy = false;
            int message = R.string.settings_host_preferences_ready;
            try {
                var client = DiskDefaultsClient.getInstance();
                if (changes == null) {
                    var receipt = client.queryPending();
                    if (receipt != null) message = receipt.succeeded ? R.string.settings_host_preferences_saved
                        : "CONFLICT".equals(receipt.errorCode) ? R.string.settings_host_preferences_conflict : R.string.settings_host_preferences_failed;
                }
                if (changes != null && baseline == null) {
                    // The daemon may have upgraded while a legacy dialog was open.
                    if (client.hasPending() || client.supported()) throw new java.io.IOException("Legacy disk defaults are no longer writable");
                    var edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                    if (changes.has(DiskDefaultsClient.COMPRESSION)) edit.putString(KEY_VM_OPTIMIZE_COMPRESSION, changes.getString(DiskDefaultsClient.COMPRESSION));
                    if (changes.has(DiskDefaultsClient.CPUS)) {
                        var cpus = changes.getJSONArray(DiskDefaultsClient.CPUS);
                        var csv = new StringBuilder();
                        for (int i = 0; i < cpus.length(); i++) { if (i > 0) csv.append(','); csv.append(cpus.getInt(i)); }
                        edit.putString(KEY_QEMU_IMG_CPU_AFFINITY, csv.toString());
                    }
                    if (!edit.commit()) throw new java.io.IOException("Could not save local disk defaults");
                } else if (changes != null) {
                    var receipt = client.patch(baseline, changes);
                    message = receipt.succeeded ? R.string.settings_host_preferences_saved
                        : "CONFLICT".equals(receipt.errorCode) ? R.string.settings_host_preferences_conflict : R.string.settings_host_preferences_failed;
                }
                snapshot = client.synchronizeLegacy();
                if (!snapshot.writable) message = R.string.settings_host_preferences_unavailable;
            } catch (DiskDefaultsClient.Blocked error) {
                legacy = error.unsupported && !DiskDefaultsClient.getInstance().hasPending() && baseline == null;
                message = legacy ? R.string.settings_disk_defaults_legacy : error.uncertain ? R.string.settings_host_preferences_pending
                    : "CONFLICT".equals(error.code) ? R.string.settings_host_preferences_conflict : R.string.settings_host_preferences_unavailable;
                if (!error.uncertain && !error.unsupported) {
                    try { snapshot = DiskDefaultsClient.getInstance().refresh(); } catch (Exception ignored) { }
                }
            } catch (Exception error) { message = R.string.settings_host_preferences_unavailable; }
            final var result = snapshot;
            final boolean oldDaemon = legacy;
            final int status = message;
            mainHandler.post(() -> {
                if (!isAdded() || getView() != owner || generation != diskDefaultsGeneration) return;
                diskDefaultsBusy = false; diskDefaults = result; diskDefaultsLegacy = oldDaemon;
                itemDiskDefaults.setSubtitle(status);
                boolean pending = DiskDefaultsClient.getInstance().hasPending();
                itemDiskDefaults.setText(getString(pending ? R.string.hugepage_operation_query : R.string.menu_refresh));
                itemDiskDefaults.setVisibility(pending || (!oldDaemon && (result == null || !result.writable)) ? VISIBLE : GONE);
                renderDiskDefaults();
                if (changes != null) Toast.makeText(requireContext(), status, LENGTH_LONG).show();
            });
        });
    }

    /** Whether the USB ACM feature is switched on; VMs with an ACM port refuse to boot
     *  without it, so the serial editor uses this to warn at configuration time. */
    public static boolean isUsbAcmEnabled(@NonNull Context context) {
        var prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(UsbAcmPool.KEY_USB_ACM_ENABLE, UsbAcmPool.DEFAULT_ENABLE);
    }

    /**
     * USB ACM pool size the daemon pre-binds; the same clamp the daemon applies, so the
     * serial-port slot picker never offers a slot the pool will refuse.
     */
    public static int getUsbAcmPorts(@NonNull Context context) {
        var prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        var n = prefs.getInt(UsbAcmPool.KEY_USB_ACM_PORTS, UsbAcmPool.DEFAULT_PORTS);
        return Math.max(1, Math.min(UsbAcmPool.MAX_PORTS, n));
    }

    private void refreshUsbAcmPortsSummary() {
        if (UsbAcmClient.getInstance().hasPending()) itemUsbAcmPorts.setSubtitle(R.string.settings_host_preferences_pending);
        else if (usbAcm == null) itemUsbAcmPorts.setSubtitle(R.string.settings_usb_acm_loading);
        else if (!usbAcm.writable) itemUsbAcmPorts.setSubtitle(R.string.settings_usb_acm_unavailable);
        else itemUsbAcmPorts.setSubtitle(getString(usbAcm.live.applied
            ? R.string.settings_usb_acm_saved_applied : R.string.settings_usb_acm_saved_pending,
            usbAcm.enabled ? getString(R.string.settings_usb_acm_ports_summary, usbAcm.ports) : getString(R.string.settings_usb_acm_disabled)));
    }

    private void showUsbAcmPortsDialog() {
        refreshUsbAcmPreferences(true);
    }

    private void refreshUsbAcmPreferences(boolean openDialog) {
        if (!isAdded() || getView() == null || usbAcmBusy) return;
        usbAcmBusy = true;
        final View owner = getView();
        itemUsbAcmPorts.setEnabled(false);
        itemUsbAcmPorts.setSubtitle(R.string.settings_usb_acm_loading);
        runOnPool(() -> {
            UsbAcmClient.Snapshot snapshot = null;
            int errorMessage = 0;
            try {
                var client = UsbAcmClient.getInstance();
                if (openDialog) client.queryPending();
                snapshot = client.synchronizeLegacy();
            } catch (UsbAcmClient.Blocked error) {
                errorMessage = error.uncertain ? R.string.settings_host_preferences_pending
                    : error.unsupported ? R.string.settings_usb_acm_unsupported : R.string.settings_usb_acm_unavailable;
            } catch (Exception error) { errorMessage = R.string.settings_usb_acm_unavailable; }
            final var result = snapshot;
            final int error = errorMessage;
            mainHandler.post(() -> {
                if (!isAdded() || getView() != owner) return;
                usbAcmBusy = false; usbAcm = result; itemUsbAcmPorts.setEnabled(true);
                if (error != 0) {
                    itemUsbAcmPorts.setSubtitle(error);
                    if (openDialog) Toast.makeText(requireContext(), error, LENGTH_LONG).show();
                } else {
                    refreshUsbAcmPortsSummary();
                    if (openDialog) showUsbAcmDraft(result);
                }
            });
        });
    }

    private void showUsbAcmDraft(UsbAcmClient.Snapshot baseline) {
        var context = requireContext();
        var view = getLayoutInflater().inflate(R.layout.dialog_usb_acm_ports, null);
        TextView observation = view.findViewById(R.id.text_acm_observation);
        String owners = baseline.live.owners.isEmpty() ? getString(R.string.settings_usb_acm_no_owners)
            : baseline.live.owners.stream().map(owner -> getString(R.string.settings_usb_acm_owner, owner.slot, owner.vmId))
                .collect(java.util.stream.Collectors.joining("\n"));
        String unknown = getString(R.string.settings_usb_acm_unknown);
        observation.setText(getString(R.string.settings_usb_acm_observation,
            baseline.enabled ? getString(R.string.settings_usb_acm_ports_summary, baseline.ports) : getString(R.string.settings_usb_acm_disabled),
            baseline.live.applied ? getString(R.string.settings_usb_acm_applied) : getString(R.string.settings_usb_acm_not_applied),
            baseline.live.udcBound == null ? unknown : getString(baseline.live.udcBound ? R.string.settings_usb_acm_bound : R.string.settings_usb_acm_unbound),
            baseline.live.ports == null ? unknown : Integer.toString(baseline.live.ports), owners));
        MaterialSwitch enable = view.findViewById(R.id.switch_acm_enable);
        RadioGroup group = view.findViewById(R.id.group_acm_ports);
        for (int i = 1; i <= UsbAcmPool.MAX_PORTS; i++) {
            var radio = new MaterialRadioButton(context);
            radio.setId(i);
            radio.setText(getString(R.string.settings_usb_acm_ports_summary, i));
            radio.setMinHeight(48);
            group.addView(radio);
        }
        enable.setChecked(baseline.enabled);
        group.check(baseline.ports);
        boolean writable = baseline.writable && baseline.live.owners.isEmpty() && !UsbAcmClient.getInstance().hasPending();
        enable.setEnabled(writable);
        if (!baseline.live.owners.isEmpty()) observation.append("\n" + getString(R.string.settings_usb_acm_owners_block));
        if (!baseline.writable) observation.append("\n" + getString(R.string.settings_usb_acm_unavailable));
        var scroll = new ScrollView(context);
        scroll.addView(view);
        var dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_usb_acm_ports_title)
            .setView(scroll)
            .setPositiveButton(R.string.settings_usb_acm_save_apply, null)
            .setNeutralButton(R.string.settings_usb_acm_apply_saved, null)
            .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> {
            Runnable update = () -> {
                boolean dirty = enable.isChecked() != baseline.enabled || group.getCheckedRadioButtonId() != baseline.ports;
                for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setEnabled(writable && enable.isChecked());
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setEnabled(writable && dirty);
                dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setEnabled(writable && !dirty && !baseline.live.applied);
            };
            enable.setOnCheckedChangeListener((button, checked) -> update.run());
            group.setOnCheckedChangeListener((buttons, checked) -> update.run());
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(button -> {
                dialog.dismiss(); submitUsbAcm(baseline, false, enable.isChecked(), group.getCheckedRadioButtonId());
            });
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener(button -> {
                dialog.dismiss(); submitUsbAcm(baseline, true, baseline.enabled, baseline.ports);
            });
            update.run();
        });
        dialog.show();
    }

    private void submitUsbAcm(UsbAcmClient.Snapshot baseline, boolean apply, boolean enabled, int ports) {
        if (usbAcmBusy) return;
        usbAcmBusy = true;
        final View owner = getView();
        itemUsbAcmPorts.setEnabled(false); itemUsbAcmPorts.setSubtitle(R.string.settings_usb_acm_loading);
        runOnPool(() -> {
            int message;
            UsbAcmClient.Snapshot snapshot = null;
            try {
                var client = UsbAcmClient.getInstance();
                var receipt = apply ? client.apply(baseline) : client.configure(baseline, enabled, ports);
                message = receipt.applied ? R.string.settings_usb_acm_success : receipt.saved
                    ? R.string.settings_usb_acm_apply_failed : "CONFLICT".equals(receipt.errorCode)
                        ? R.string.settings_host_preferences_conflict : R.string.settings_host_preferences_failed;
                snapshot = client.refresh();
            } catch (UsbAcmClient.Blocked error) {
                message = error.uncertain ? R.string.settings_host_preferences_pending
                    : error.unsupported ? R.string.settings_usb_acm_unsupported : R.string.settings_usb_acm_unavailable;
                if (!error.uncertain && !error.unsupported) {
                    try { snapshot = UsbAcmClient.getInstance().refresh(); }
                    catch (Exception ignored) {}
                    if (snapshot != null && "CONFLICT".equals(error.code)) message = R.string.settings_host_preferences_conflict;
                }
            } catch (Exception error) { message = R.string.settings_usb_acm_unavailable; }
            final int status = message; final var result = snapshot;
            mainHandler.post(() -> {
                if (!isAdded() || getView() != owner) return;
                usbAcmBusy = false; usbAcm = result; itemUsbAcmPorts.setEnabled(true);
                itemUsbAcmPorts.setSubtitle(status); Toast.makeText(requireContext(), status, LENGTH_LONG).show();
            });
        });
    }

    // Ask + every crosvm-supported compression; the list grows as CROSVM_SUPPORTED does.
    private void showOptimizeCompressionDialog() {
        if (diskDefaultsBusy || DiskDefaultsClient.getInstance().hasPending()
            || (!diskDefaultsLegacy && (diskDefaults == null || !diskDefaults.writable))) return;
        final var baseline = diskDefaults;
        var values = new ArrayList<String>();
        values.add(OPTIMIZE_COMPRESSION_ASK);
        for (var compress : DiskCompress.CROSVM_SUPPORTED) values.add(compress.value());
        var labels = new String[values.size()];
        for (int i = 0; i < values.size(); i++)
            labels[i] = getString(optimizeCompressionLabel(values.get(i)));
        var current = baseline == null ? getOptimizeCompression(requireContext()) : baseline.optimizeCompression;
        int checked = Math.max(0, values.indexOf(current));
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_optimize_compression_title)
            .setSingleChoiceItems(labels, checked, (d, which) -> {
                try { workDiskDefaults(baseline, new JSONObject().put(DiskDefaultsClient.COMPRESSION, values.get(which))); }
                catch (JSONException error) { throw new IllegalStateException(error); }
                d.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
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
        var csv = diskDefaults == null ? getQemuImgCpuAffinity(requireContext()) : diskDefaults.cpuCsv();
        if (csv.isEmpty()) {
            itemCpuAffinity.setSubtitle(R.string.settings_cpu_affinity_all_cores);
        } else {
            itemCpuAffinity.setSubtitle(getString(
                R.string.settings_cpu_affinity_summary_fmt, CpuUtils.compactRanges(csv)));
        }
    }

    private void showCpuAffinityDialog() {
        if (diskDefaultsBusy || DiskDefaultsClient.getInstance().hasPending()
            || (!diskDefaultsLegacy && (diskDefaults == null || !diskDefaults.writable))) return;
        final var baseline = diskDefaults;
        var ctx = requireContext();
        // Saved selection, or the "filter out little cores" default when unset.
        var savedCsv = baseline == null ? getQemuImgCpuAffinity(ctx) : baseline.cpuCsv();
        CpuCorePickerDialog.show(
            ctx, R.string.settings_cpu_affinity_title,
            savedCsv.isEmpty() ? CpuUtils.defaultBigCoresCsv() : savedCsv,
            picked -> {
                try { workDiskDefaults(baseline, new JSONObject().put(DiskDefaultsClient.CPUS, new org.json.JSONArray(CpuUtils.parseCpuSet(picked)))); }
                catch (JSONException error) { throw new IllegalStateException(error); }
            });
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

    private void showUsbPassthrough() {
        startActivity(new Intent(requireContext(), UsbRulesActivity.class));
    }

    private void showUsbDevices() {
        startActivity(new Intent(requireContext(), UsbDevicesActivity.class));
    }

    private void showKernelModules() {
        KernelModuleDialog.show(getChildFragmentManager());
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

    private static <T extends DataConfig> void loadStore(
        @NonNull Context ctx,
        @NonNull Class<? extends DataStore<T>> store,
        @NonNull JSONObject json
    ) throws Exception {
        if (VMStore.class.equals(store)) VMStore.importPersisted(ctx, json);
        else if (NetworkStore.class.equals(store)) NetworkStore.importPersisted(ctx, json);
        else if (DiskStore.class.equals(store)) DiskStore.importPersisted(ctx, json);
        else throw new IllegalArgumentException("No importer for " + store.getSimpleName());
    }

    @SuppressWarnings("unused")
    private void onImportFileSelected(Uri uri) {
        if (uri == null) return;
        var ctx = requireContext().getApplicationContext();
        if (!importingConfig.compareAndSet(false, true)) return;
        runOnPool(() -> {
            var completed = new java.util.ArrayList<String>();
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
                // Check all category shapes before the first persistent change.
                for (String key : new String[]{"networks", "disks", "vms"})
                    if (importJson.has(key)) importJson.getJSONArray(key);
                int count = 0;
                ConfigurationClient.EditSession joint = null;
                org.json.JSONArray importedVms = null, importedDisks = null;
                if (importJson.has("vms") || importJson.has("disks")) {
                    var client = new ConfigurationClient(request -> DaemonConnection.getInstance().request(request));
                    if (client.supported()) {
                        // Parse/migrate before the first write; capture both revisions once.
                        if (importJson.has("vms")) importedVms = new VMStore(importJson).toJson().getJSONArray("vms");
                        if (importJson.has("disks")) importedDisks = new DiskStore(importJson).toJson().getJSONArray("disks");
                        joint = client.edit();
                    }
                }
                // Dependencies must exist before the VM service validates NICs.
                // Network and VM/disk remain separate commits; report completed
                // categories if the later transaction conflicts or is uncertain.
                if (importJson.has("networks")) {
                    loadStore(ctx, NetworkStore.class, importJson);
                    completed.add("networks");
                    count++;
                }
                if (joint != null) {
                    joint.merge(importedVms, importedDisks);
                    if (importedDisks != null) { completed.add("disks"); count++; }
                    if (importedVms != null) { completed.add("VM"); count++; }
                } else {
                    if (importJson.has("disks")) {
                        loadStore(ctx, DiskStore.class, importJson);
                        completed.add("disks");
                        count++;
                    }
                    if (importJson.has("vms")) {
                        loadStore(ctx, VMStore.class, importJson);
                        completed.add("VM");
                        count++;
                    }
                }
                final int imported = count;
                mainHandler.post(() -> Toast.makeText(ctx,
                    ctx.getString(R.string.settings_import_success, imported), LENGTH_SHORT).show());
            } catch (JSONException e) {
                String detail = ctx.getString(R.string.settings_import_invalid_json);
                if (!completed.isEmpty()) detail = ctx.getString(R.string.settings_import_partial, detail, completed.size());
                final String message = detail;
                mainHandler.post(() -> Toast.makeText(ctx, message, LENGTH_LONG).show());
            } catch (Exception e) {
                String detail = ctx.getString(R.string.settings_import_failed, e.getMessage());
                if (!completed.isEmpty()) detail = ctx.getString(R.string.settings_import_partial, detail, completed.size());
                final String message = detail;
                mainHandler.post(() -> Toast.makeText(ctx, message, LENGTH_LONG).show());
            } finally {
                importingConfig.set(false);
            }
        });
    }
}
