package cn.classfun.droidvm.ui.main.settings;

import static android.content.Intent.ACTION_VIEW;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.Constants.GITHUB_ISSUE_URL;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.api.ApiManager;
import cn.classfun.droidvm.lib.api.Privacy;
import cn.classfun.droidvm.lib.daemon.DaemonHelper;
import cn.classfun.droidvm.lib.data.Language;
import cn.classfun.droidvm.lib.ui.UIContext;
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
    private TextRowWidget itemLicense;
    private SwitchRowWidget itemAutoCheckUpdate;
    private TextRowWidget itemCheckUpdate;
    private TextRowWidget itemPrivacy;
    private TextRowWidget itemApiManager;
    private DaemonHelper daemon;

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
        itemLicense = view.findViewById(R.id.item_license);
        itemAutoCheckUpdate = view.findViewById(R.id.item_auto_check_update);
        itemCheckUpdate = view.findViewById(R.id.item_check_update);
        itemPrivacy = view.findViewById(R.id.item_privacy);
        itemApiManager = view.findViewById(R.id.item_api_manager);
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
        bindOnChecked(itemAutoCheckUpdate, KEY_AUTO_CHECK_UPDATE, true);
        bindOnClick(itemCheckUpdate, this::checkUpdate);
        bindOnClick(itemPrivacy, this::showPrivacyPolicy);
        bindOnClick(itemApiManager, this::showApiManager);
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

    public static boolean isAutoCheckUpdateEnabled(@NonNull Context context) {
        var prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_AUTO_CHECK_UPDATE, true);
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
}
