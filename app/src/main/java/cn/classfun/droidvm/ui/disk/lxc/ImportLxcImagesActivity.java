// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.lxc;

import static android.R.attr.colorError;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;
import static com.google.android.material.R.attr.colorOnSurfaceVariant;
import static java.util.Objects.requireNonNullElse;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;
import static cn.classfun.droidvm.lib.utils.FileUtils.checkFileName;
import static cn.classfun.droidvm.lib.utils.FileUtils.externalPath;
import static cn.classfun.droidvm.lib.utils.NetUtils.BROWSER_USER_AGENT;
import static cn.classfun.droidvm.lib.utils.NetUtils.LXC_USER_AGENT;
import static cn.classfun.droidvm.lib.utils.NetUtils.fetchJSON;
import static cn.classfun.droidvm.lib.utils.NetUtils.generateRandomMac;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity.startOptimizeAfterImport;
import static cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity.startOptimizeAfterImportForResult;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.api.ApiManager;
import cn.classfun.droidvm.lib.api.Privacy;
import cn.classfun.droidvm.lib.data.Repos;
import cn.classfun.droidvm.lib.download.DiskDownloadManager;
import cn.classfun.droidvm.lib.download.DiskDownloadService;
import cn.classfun.droidvm.lib.size.SizeUnit;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.IconItemAdapter;
import cn.classfun.droidvm.lib.ui.NotificationPermission;
import cn.classfun.droidvm.lib.ui.SimpleTextWatcher;
import cn.classfun.droidvm.ui.agent.AgentOperationActivity;
import cn.classfun.droidvm.ui.agent.autogrow.AutoGrowAction;
import cn.classfun.droidvm.ui.agent.base.AgentVM;
import cn.classfun.droidvm.ui.agent.password.ChangePasswordActivity;
import cn.classfun.droidvm.ui.agent.password.PasswordAction;
import cn.classfun.droidvm.ui.disk.action.BackingChainLinker;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;
import cn.classfun.droidvm.ui.widgets.row.DropdownRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;
import cn.classfun.droidvm.ui.widgets.tools.DownloadWidget;
import cn.classfun.droidvm.ui.widgets.tools.KernelAnalysisWidget;

public class ImportLxcImagesActivity extends AppCompatActivity {
    private static final String TAG = "ImportLxcImages";
    private static final String IMAGES_META_PATH = "/streams/v1/images.json";
    private static final long POLL_INTERVAL_MS = 500;
    private static final String PREFS_NAME = "droidvm_prefs";
    private static final String PREF_META_SOURCE = "lxc_meta_source";
    private static final String PREF_DL_SOURCE = "lxc_download_source";
    private static final String PREF_DL_SOURCE_NAME = "lxc_download_source_name";
    private static final String PREF_DL_SOURCE_URL = "lxc_download_source_url";
    private static final String PREF_CUSTOM_META_URL = "lxc_custom_meta_url";
    private static final String PREF_CUSTOM_DL_URL = "lxc_custom_download_url";
    private static final String SOURCE_OFFICIAL = "official";
    private static final String SOURCE_CLASSFUN = "classfun";
    private static final String SOURCE_CERNET = "cernet";
    private static final String SOURCE_CUSTOM = "custom";
    private static final String STATE_PENDING_PASSWORD_DISK_ID = "pending_password_disk_id";
    private static final String STATE_PENDING_RESET_PASSWORD = "pending_reset_password";
    private static final String STATE_PENDING_IMPORT_NAME = "pending_import_name";
    private static final String STATE_PENDING_LINUX_DISK_ID = "pending_linux_disk_id";
    private static final String STATE_PENDING_LINUX_VM_NAME = "pending_linux_vm_name";
    private static final String STATE_PENDING_LINUX_ROOT_PASSWORD = "pending_linux_root_password";
    private static final String STATE_PENDING_LINUX_NETWORK_ID = "pending_linux_network_id";
    private static final String STATE_PENDING_LINUX_CPU = "pending_linux_cpu";
    private static final String STATE_PENDING_LINUX_MEMORY_MB = "pending_linux_memory_mb";
    private static final String STATE_PENDING_LINUX_DISK_BYTES = "pending_linux_disk_bytes";
    private final Map<String, String> displayVersionToRelease = new LinkedHashMap<>();
    private Repos.Repo builtinLxcRepo;
    private Repos.Repo lxcRepo;
    private String[] metaSourceKeys;
    private String[] metaSourceLabels;
    private DropdownRowWidget dropdownMetaSource;
    private TextInputRowWidget inputCustomMetaUrl;
    private String[] dlSourceKeys;
    private String[] dlSourceLabels;
    private String[] dlSourceUrls;
    private DropdownRowWidget dropdownDlSource;
    private TextInputRowWidget inputCustomDlUrl;
    private TextView tvMetaStatus;
    private CircularProgressIndicator progressMeta;
    private MaterialButton btnLoad;
    private View dividerImage, tvImageHeader;
    private DropdownRowWidget dropdownDistro, dropdownVersion, dropdownVariant, dropdownBuild;
    private View dividerOutput, tvOutputHeader;
    private TextInputRowWidget inputFilename, inputFolder, inputResetPassword;
    private View dividerSettings, tvSettingsHeader;
    private TextInputRowWidget inputVmName, inputVmCpu, inputVmMemory;
    private TextInputRowWidget inputVmDiskSize, inputVmRootPassword;
    private DropdownRowWidget dropdownVmNetwork;
    private String[] vmNetworkIds = new String[0];
    private String[] vmNetworkLabels = new String[0];
    private String selectedVmNetworkId = "";
    private MaterialCardView cardInfo;
    private TextView tvInfoSize, tvInfoPath;
    private ExtendedFloatingActionButton fabImport;
    private NotificationPermission notifPermission;
    private DownloadWidget downloadWidget;
    private KernelAnalysisWidget kernelAnalysis;
    private NestedScrollView scrollView;
    private CollapsingToolbarLayout collapsingToolbar;
    private MaterialToolbar toolbar;
    private List<LxcImage> allImages = new ArrayList<>();
    private LxcImage selectedImage;
    private boolean isLoading = false;
    private boolean isDownloading = false;
    private boolean isProbingSources = false;
    private volatile boolean isClassFunApiAvailable = false;
    private String selectedMetaSourceKey = SOURCE_OFFICIAL;
    private String selectedDlSourceKey = SOURCE_OFFICIAL;
    private String rememberedDlSourceKey = "";
    private String rememberedDlSourceName = "";
    private String rememberedDlSourceUrl = "";
    private boolean automaticMetadataRequest = false;
    private SharedPreferences sourcePrefs;
    /** Only the first valid response may settle the one-time source probe. */
    private final AtomicBoolean sourceProbeSettled = new AtomicBoolean(false);
    /** A manual selection always wins, including against an already queued probe callback. */
    private final AtomicBoolean userOverrodeSourceProbe = new AtomicBoolean(false);
    private final AtomicInteger sourceProbeFailures = new AtomicInteger(0);
    private volatile int sourceProbeRequestCount = 0;
    private String downloadName = null;
    private String downloadFolder = null;
    private volatile ApiManager apiManager = null;
    private ActivityResultLauncher<Uri> folderPickerLauncher;
    private ActivityResultLauncher<Intent> optimizeLauncher;
    private ActivityResultLauncher<Intent> passwordLauncher;
    private ActivityResultLauncher<Intent> linuxResizeLauncher;
    private ActivityResultLauncher<Intent> linuxMaintenanceLauncher;
    private boolean linuxVmMode = false;
    @Nullable
    private UUID pendingPasswordDiskId;
    @NonNull
    private String pendingResetPassword = "";
    @NonNull
    private String pendingImportName = "";
    @Nullable
    private UUID pendingLinuxDiskId;
    @NonNull
    private String pendingLinuxVmName = "";
    @NonNull
    private String pendingLinuxRootPassword = "";
    @NonNull
    private String pendingLinuxNetworkId = "";
    private long pendingLinuxCpu = 1;
    private long pendingLinuxMemoryMb = 512;
    private long pendingLinuxDiskBytes = 16L * 1024 * 1024 * 1024;
    private long currentDownloadId = -1;
    private boolean activityStarted = false;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        linuxVmMode = isLinuxVmMode();
        setContentView(R.layout.activity_import_lxc_images);
        notifPermission = new NotificationPermission(this);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        toolbar = findViewById(R.id.toolbar);
        dropdownMetaSource = findViewById(R.id.dropdown_meta_source);
        inputCustomMetaUrl = findViewById(R.id.input_custom_meta_url);
        dropdownDlSource = findViewById(R.id.dropdown_download_source);
        inputCustomDlUrl = findViewById(R.id.input_custom_download_url);
        tvMetaStatus = findViewById(R.id.tv_meta_status);
        progressMeta = findViewById(R.id.progress_meta);
        btnLoad = findViewById(R.id.btn_load);
        dividerImage = findViewById(R.id.divider_image);
        tvImageHeader = findViewById(R.id.tv_image_header);
        dropdownDistro = findViewById(R.id.dropdown_distro);
        dropdownVersion = findViewById(R.id.dropdown_version);
        dropdownVariant = findViewById(R.id.dropdown_variant);
        dropdownBuild = findViewById(R.id.dropdown_build);
        dividerOutput = findViewById(R.id.divider_output);
        tvOutputHeader = findViewById(R.id.tv_output_header);
        inputFilename = findViewById(R.id.input_filename);
        inputFolder = findViewById(R.id.input_folder);
        inputResetPassword = findViewById(R.id.input_reset_password);
        dividerSettings = findViewById(R.id.divider_settings);
        tvSettingsHeader = findViewById(R.id.tv_settings_header);
        inputVmName = findViewById(R.id.input_vm_name);
        inputVmCpu = findViewById(R.id.input_vm_cpu);
        inputVmMemory = findViewById(R.id.input_vm_memory);
        inputVmDiskSize = findViewById(R.id.input_vm_disk_size);
        inputVmRootPassword = findViewById(R.id.input_vm_root_password);
        dropdownVmNetwork = findViewById(R.id.dropdown_vm_network);
        cardInfo = findViewById(R.id.card_info);
        tvInfoSize = findViewById(R.id.tv_info_size);
        tvInfoPath = findViewById(R.id.tv_info_path);
        fabImport = findViewById(R.id.fab_import);
        downloadWidget = findViewById(R.id.download_widget);
        kernelAnalysis = findViewById(R.id.kernel_analysis);
        kernelAnalysis.setUrlProvider(() -> selectedImage == null
            ? null : pathJoin(getDownloadBaseUrl(), selectedImage.getDownloadPath()));
        scrollView = findViewById(R.id.scroll_view);
        optimizeLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) launchPasswordAfterImport();
                else completePendingImport();
            });
        passwordLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result ->
                completePendingImport());
        linuxResizeLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) launchLinuxMaintenance();
                else finishLinuxVmFlow(false);
            });
        linuxMaintenanceLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) createPendingLinuxVm();
                else finishLinuxVmFlow(false);
            });
        if (savedInstanceState != null) {
            var diskId = savedInstanceState.getString(STATE_PENDING_PASSWORD_DISK_ID);
            if (diskId != null) {
                try {
                    pendingPasswordDiskId = UUID.fromString(diskId);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid pending password disk ID", e);
                }
            }
            pendingResetPassword = requireNonNullElse(
                savedInstanceState.getString(STATE_PENDING_RESET_PASSWORD), "");
            pendingImportName = requireNonNullElse(
                savedInstanceState.getString(STATE_PENDING_IMPORT_NAME), "");
            pendingLinuxDiskId = parseUuid(savedInstanceState.getString(
                STATE_PENDING_LINUX_DISK_ID), "pending Linux disk");
            pendingLinuxVmName = requireNonNullElse(
                savedInstanceState.getString(STATE_PENDING_LINUX_VM_NAME), "");
            pendingLinuxRootPassword = requireNonNullElse(
                savedInstanceState.getString(STATE_PENDING_LINUX_ROOT_PASSWORD), "");
            pendingLinuxNetworkId = requireNonNullElse(
                savedInstanceState.getString(STATE_PENDING_LINUX_NETWORK_ID), "");
            pendingLinuxCpu = savedInstanceState.getLong(STATE_PENDING_LINUX_CPU, 1);
            pendingLinuxMemoryMb = savedInstanceState.getLong(
                STATE_PENDING_LINUX_MEMORY_MB, 512);
            pendingLinuxDiskBytes = savedInstanceState.getLong(
                STATE_PENDING_LINUX_DISK_BYTES, 16L * 1024 * 1024 * 1024);
        }
        initialize();
    }

    /** Subclass entry point keeps download notifications mode-safe without duplicating logic. */
    protected boolean isLinuxVmMode() {
        return false;
    }

    @Nullable
    private UUID parseUuid(@Nullable String value, @NonNull String label) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, fmt("Invalid %s ID", label), e);
            return null;
        }
    }

    private void initialize() {
        collapsingToolbar.setTitle(getString(
            linuxVmMode ? R.string.linux_vm_create_title : R.string.lxc_title));
        toolbar.setNavigationOnClickListener(v -> confirmExit());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });
        btnLoad.setOnClickListener(v -> loadImages());
        var folderTree = new ActivityResultContracts.OpenDocumentTree();
        folderPickerLauncher = registerForActivityResult(folderTree, this::onFolderPickerResult);
        var path = pathJoin(externalPath(), "DroidVM");
        inputFolder.setText(path);
        inputFolder.setIconButtonOnClickListener(() -> folderPickerLauncher.launch(null));
        fabImport.setOnClickListener(v -> doImport());
        configureModeUi();
        setupVmNetworkDropdown();
        sourcePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean hasRememberedSources = loadRememberedSources();
        loadRememberedCustomUrls();
        setupCustomUrlPersistence();
        // Show bundled download mirrors immediately. Metadata deliberately has
        // only Official, ClassFun and Custom; mirrors remain download-only.
        loadBuiltinSources();
        // If this screen was re-created while its download is still running,
        // restore the whole form; otherwise just re-attach the progress bar.
        boolean restoredSession = restoreSession();
        if (!restoredSession) reattachActiveDownload();
        boolean shouldProbe = !hasRememberedSources && !restoredSession && !isDownloading;
        boolean shouldAutoLoad = hasRememberedSources && !restoredSession && !isDownloading;
        automaticMetadataRequest = shouldProbe || shouldAutoLoad;
        if (shouldProbe) {
            isProbingSources = true;
            isLoading = true;
            setMetaLoading();
        } else if (shouldAutoLoad) {
            // loadImages() is posted after ClassFun initialization. Do not mark
            // isLoading yet or that call would reject itself.
            setMetaLoading();
        } else if (!restoredSession && !isDownloading) {
            setMetaRefreshing();
        }
        runOnPool(() -> asyncInitializeSources(shouldProbe, shouldAutoLoad));
    }

    private void configureModeUi() {
        dividerOutput.setVisibility(linuxVmMode ? GONE : VISIBLE);
        tvOutputHeader.setVisibility(linuxVmMode ? GONE : VISIBLE);
        dividerSettings.setVisibility(linuxVmMode ? VISIBLE : GONE);
        tvSettingsHeader.setVisibility(linuxVmMode ? VISIBLE : GONE);
        if (!linuxVmMode) return;
        inputVmCpu.setValue(1);
        inputVmMemory.setValue(512, SizeUnit.MB);
        inputVmDiskSize.setValue(16, SizeUnit.GB);
        fabImport.setText(R.string.linux_vm_create_action);
        setOutputEnabled(false);
    }

    private void setupVmNetworkDropdown() {
        var ids = new ArrayList<String>();
        var labels = new ArrayList<String>();
        ids.add("");
        labels.add(getString(R.string.linux_vm_network_none));
        var store = new NetworkStore();
        store.load(this);
        store.forEach((id, config) -> {
            ids.add(id.toString());
            labels.add(config.getName());
        });
        vmNetworkIds = ids.toArray(new String[0]);
        vmNetworkLabels = labels.toArray(new String[0]);
        dropdownVmNetwork.setAdapter(IconItemAdapter.create(
            this, vmNetworkLabels, R.drawable.ic_nav_network));
        dropdownVmNetwork.setOnItemClickListener((p, v, pos, id) ->
            selectedVmNetworkId = vmNetworkIds[pos]);
        // A fresh quick-create VM joins the first configured network. Keep the explicit
        // no-network row as the fallback when the store is empty and as a manual choice.
        if (selectedVmNetworkId.isEmpty() && vmNetworkIds.length > 1)
            selectedVmNetworkId = vmNetworkIds[1];
        applyVmNetworkSelection(selectedVmNetworkId);
    }

    private void applyVmNetworkSelection(@Nullable String networkId) {
        var id = requireNonNullElse(networkId, "");
        int index = indexOfSource(vmNetworkIds, id);
        if (index < 0) index = 0;
        selectedVmNetworkId = vmNetworkIds[index];
        dropdownVmNetwork.setText(vmNetworkLabels[index]);
    }

    /** Snapshot of the form, kept across activity re-creation while a download runs. */
    private static final class Session {
        boolean linuxVmMode;
        long downloadId = -1;
        String metaSource, customMetaUrl, dlSource, customDlUrl;
        List<LxcImage> allImages;
        String distro, version, variant, build;
        String filename, folder, resetPassword;
        String vmName, vmRootPassword, vmNetworkId;
        long vmCpu, vmMemoryMb, vmDiskBytes;
    }

    /** Stable snapshot used to merge a remembered source with either repo list. */
    private static final class DownloadSource {
        final String key;
        final String name;
        final String baseUrl;

        DownloadSource(@NonNull String key, @NonNull String name, @NonNull String baseUrl) {
            this.key = key;
            this.name = name;
            this.baseUrl = baseUrl;
        }
    }

    /**
     * Form/download state is isolated by concrete entry Activity. Linux VM creation
     * and plain LXC import share this implementation, but must never overwrite one
     * another while either screen is in the background.
     */
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @NonNull
    private String sessionKey() {
        return getClass().getName();
    }

    @Nullable
    private Session getSession() {
        return sessions.get(sessionKey());
    }

    private void captureSession() {
        var s = new Session();
        s.linuxVmMode = linuxVmMode;
        s.metaSource = getSelectedMetaSourceKey();
        s.customMetaUrl = inputCustomMetaUrl.getText();
        s.dlSource = getSelectedDlSourceKey();
        s.customDlUrl = inputCustomDlUrl.getText();
        s.allImages = new ArrayList<>(allImages);
        s.distro = dropdownDistro.getText();
        s.version = dropdownVersion.getText();
        s.variant = dropdownVariant.getText();
        s.build = dropdownBuild.getText();
        if (linuxVmMode) {
            s.vmName = inputVmName.getText();
            s.vmCpu = inputVmCpu.getValue();
            s.vmMemoryMb = inputVmMemory.getValue(SizeUnit.MB);
            s.vmDiskBytes = inputVmDiskSize.getValue();
            s.vmRootPassword = inputVmRootPassword.getText();
            s.vmNetworkId = selectedVmNetworkId;
        } else {
            s.filename = inputFilename.getText();
            s.folder = inputFolder.getText();
            s.resetPassword = inputResetPassword.getText();
        }
        sessions.put(sessionKey(), s);
    }

    /**
     * Rebuilds the form from the last saved session (replays the
     * distro->version->variant->build cascade) and re-attaches the progress bar if a
     * download is still running or its terminal result has not yet been consumed.
     * The session is removed when the download succeeds, fails or is cancelled.
     * Returns false if there's nothing to restore.
     */
    private boolean restoreSession() {
        var s = getSession();
        if (s == null || s.linuxVmMode != linuxVmMode) return false;
        if (lxcRepo == null) return false; // sources unavailable; can't rebuild
        applySourceSelection(s.metaSource, s.dlSource);
        inputCustomMetaUrl.setText(s.customMetaUrl);
        inputCustomMetaUrl.setVisibility(
            getSelectedMetaSourceKey().equals(SOURCE_CUSTOM) ? VISIBLE : GONE);
        inputCustomDlUrl.setText(s.customDlUrl);
        inputCustomDlUrl.setVisibility(
            getSelectedDlSourceKey().equals(SOURCE_CUSTOM) ? VISIBLE : GONE);
        onImagesLoaded(s.allImages, s.allImages.size());
        dropdownDistro.setText(s.distro);
        onDistroSelected(s.distro);
        dropdownVersion.setText(s.version);
        onVersionSelected(s.version);
        dropdownVariant.setText(s.variant);
        onVariantSelected(s.variant);
        dropdownBuild.setText(s.build);
        onBuildSelected(s.build);
        if (linuxVmMode) {
            inputVmName.setText(s.vmName);
            inputVmCpu.setValue(s.vmCpu);
            inputVmMemory.setValue(s.vmMemoryMb, SizeUnit.MB);
            inputVmDiskSize.setValue(s.vmDiskBytes);
            inputVmRootPassword.setText(s.vmRootPassword);
            applyVmNetworkSelection(s.vmNetworkId);
            pendingLinuxVmName = requireNonNullElse(s.vmName, "");
            pendingLinuxRootPassword = requireNonNullElse(s.vmRootPassword, "");
            pendingLinuxNetworkId = requireNonNullElse(s.vmNetworkId, "");
            pendingLinuxCpu = s.vmCpu;
            pendingLinuxMemoryMb = s.vmMemoryMb;
            pendingLinuxDiskBytes = s.vmDiskBytes;
        } else {
            inputFilename.setText(s.filename);
            inputFolder.setText(s.folder);
            inputResetPassword.setText(s.resetPassword);
        }
        reattachSessionDownload(s);
        return true;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingPasswordDiskId != null)
            outState.putString(
                STATE_PENDING_PASSWORD_DISK_ID, pendingPasswordDiskId.toString());
        if (!pendingResetPassword.isEmpty())
            outState.putString(STATE_PENDING_RESET_PASSWORD, pendingResetPassword);
        if (!pendingImportName.isEmpty())
            outState.putString(STATE_PENDING_IMPORT_NAME, pendingImportName);
        if (pendingLinuxDiskId != null)
            outState.putString(STATE_PENDING_LINUX_DISK_ID, pendingLinuxDiskId.toString());
        if (!pendingLinuxVmName.isEmpty())
            outState.putString(STATE_PENDING_LINUX_VM_NAME, pendingLinuxVmName);
        if (!pendingLinuxRootPassword.isEmpty())
            outState.putString(
                STATE_PENDING_LINUX_ROOT_PASSWORD, pendingLinuxRootPassword);
        if (!pendingLinuxNetworkId.isEmpty())
            outState.putString(STATE_PENDING_LINUX_NETWORK_ID, pendingLinuxNetworkId);
        outState.putLong(STATE_PENDING_LINUX_CPU, pendingLinuxCpu);
        outState.putLong(STATE_PENDING_LINUX_MEMORY_MB, pendingLinuxMemoryMb);
        outState.putLong(STATE_PENDING_LINUX_DISK_BYTES, pendingLinuxDiskBytes);
    }

    /** Re-attaches just the progress bar to the running download (no form state). */
    private void reattachActiveDownload() {
        long id = DiskDownloadManager.activeDownloadId(getClass().getName());
        if (id < 0) return;
        var s = getSession();
        if (s != null) s.downloadId = id;
        attachDownload(id);
    }

    /** Re-attaches to this form's exact job, including an unconsumed success. */
    private void reattachSessionDownload(@NonNull Session s) {
        if (s.downloadId < 0 || DiskDownloadManager.query(s.downloadId) == null) {
            reattachActiveDownload();
            return;
        }
        attachDownload(s.downloadId);
    }

    private void attachDownload(long id) {
        currentDownloadId = id;
        isDownloading = true;
        setInputsEnabled(false);
        fabImport.setVisibility(GONE);
        downloadWidget.setVisibility(VISIBLE);
        var name = DiskDownloadManager.downloadName(id);
        downloadWidget.startExternal(name != null ? name : "", this::cancelDownload);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        if (activityStarted) pollHandler.post(pollRunnable);
    }

    /** Returns true only when the complete, usable pair has already been remembered. */
    private boolean loadRememberedSources() {
        if (!sourcePrefs.contains(PREF_META_SOURCE) || !sourcePrefs.contains(PREF_DL_SOURCE))
            return false;
        var meta = sourcePrefs.getString(PREF_META_SOURCE, null);
        var download = sourcePrefs.getString(PREF_DL_SOURCE, null);
        if (!isAllowedMetadataSource(meta) || download == null || download.isEmpty()) {
            sourcePrefs.edit()
                .remove(PREF_META_SOURCE)
                .remove(PREF_DL_SOURCE)
                .remove(PREF_DL_SOURCE_NAME)
                .remove(PREF_DL_SOURCE_URL)
                .apply();
            return false;
        }
        selectedMetaSourceKey = meta;
        selectedDlSourceKey = download;
        rememberedDlSourceKey = download;
        rememberedDlSourceName = requireNonNullElse(
            sourcePrefs.getString(PREF_DL_SOURCE_NAME, ""), "");
        rememberedDlSourceUrl = requireNonNullElse(
            sourcePrefs.getString(PREF_DL_SOURCE_URL, ""), "");
        // No probe will be started, but marking it settled also protects the
        // remembered pair from any stale callback left in the process.
        sourceProbeSettled.set(true);
        return true;
    }

    private boolean isAllowedMetadataSource(@Nullable String key) {
        return SOURCE_OFFICIAL.equals(key) || SOURCE_CLASSFUN.equals(key)
            || SOURCE_CUSTOM.equals(key);
    }

    private void loadRememberedCustomUrls() {
        inputCustomMetaUrl.setText(sourcePrefs.getString(PREF_CUSTOM_META_URL, ""));
        inputCustomDlUrl.setText(sourcePrefs.getString(PREF_CUSTOM_DL_URL, ""));
    }

    private void setupCustomUrlPersistence() {
        inputCustomMetaUrl.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                sourcePrefs.edit().putString(PREF_CUSTOM_META_URL, s.toString()).apply();
            }
        });
        inputCustomDlUrl.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                sourcePrefs.edit().putString(PREF_CUSTOM_DL_URL, s.toString()).apply();
                if (SOURCE_CUSTOM.equals(selectedDlSourceKey))
                    rememberSourcePair(selectedMetaSourceKey, selectedDlSourceKey);
            }
        });
    }

    /**
     * Populates the source dropdowns synchronously from the bundled repo list
     * so the screen is usable immediately, before the API refresh returns.
     */
    private void loadBuiltinSources() {
        var repos = Repos.loadYAML(this);
        if (repos != null) builtinLxcRepo = repos.getRepo().get("lxc-images");
        lxcRepo = builtinLxcRepo;
        if (lxcRepo == null) {
            // bundled data missing/corrupt: fall back to a blocking load
            setMetaSourcesLoading();
            return;
        }
        hydrateRememberedDownloadSource(lxcRepo);
        setupSourceDropdown();
        setupImageDropdowns();
    }

    /**
     * Resolves ClassFun first, starts the one-time race when needed, then refreshes
     * the download mirror list. The two metadata GETs share a start gate so neither
     * source gets an artificial scheduling head start.
     */
    private void asyncInitializeSources(boolean shouldProbe, boolean shouldAutoLoad) {
        boolean classfun = false;
        try {
            if (Privacy.isPrivacyAgreed(this)) {
                apiManager = ApiManager.create(this);
                classfun = apiManager.isServiceEnabled("lxc_images_metadata");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize ClassFun metadata service", e);
        }
        final boolean classfunAvailable = classfun;
        runOnUiThread(() -> {
            isClassFunApiAvailable = classfunAvailable;
            if (shouldAutoLoad && !isFinishing() && !isDestroyed()) loadImages();
        });

        if (shouldProbe && !sourceProbeSettled.get())
            startSourceProbe(classfunAvailable);

        Repos.Repo freshRepo = null;
        try {
            var repos = Repos.load(this);
            if (repos != null) freshRepo = repos.getRepo().get("lxc-images");
        } catch (Exception e) {
            Log.w(TAG, "Background mirror refresh failed; keeping built-in list", e);
        }
        final Repos.Repo repo = freshRepo;
        runOnUiThread(() -> applyRefreshedSources(repo, classfunAvailable));
    }

    private void applyRefreshedSources(@Nullable Repos.Repo freshRepo, boolean classfunAvailable) {
        if (isFinishing() || isDestroyed()) return;
        isClassFunApiAvailable = classfunAvailable;
        if (freshRepo != null) {
            boolean sourcesWereMissing = lxcRepo == null;
            // A second import screen may update the globally remembered preference while this
            // screen's download is in the background. Keep the source labels from this exact
            // session until its job is consumed; the new preference applies next time.
            var metaSel = isDownloading ? getSelectedMetaSourceKey() : sourcePrefs.getString(
                PREF_META_SOURCE, getSelectedMetaSourceKey());
            var dlSel = isDownloading ? getSelectedDlSourceKey() : sourcePrefs.getString(
                PREF_DL_SOURCE, getSelectedDlSourceKey());
            lxcRepo = freshRepo;
            selectedMetaSourceKey = requireNonNullElse(metaSel, SOURCE_OFFICIAL);
            selectedDlSourceKey = requireNonNullElse(dlSel, SOURCE_OFFICIAL);
            hydrateRememberedDownloadSource(freshRepo);
            setupSourceDropdown();
            if (sourcesWereMissing) setupImageDropdowns();
        } else if (lxcRepo == null) {
            // both the bundled list and the refresh failed
            setMetaError("source list unavailable");
            btnLoad.setEnabled(false);
            return;
        }
        // Never replace an automatic load's progress/error with "load metadata".
        if (!automaticMetadataRequest && !isLoading && !isDownloading && allImages.isEmpty())
            setMetaIdle();
        automaticMetadataRequest = false;
    }

    private void startSourceProbe(boolean classfunAvailable) {
        var repo = lxcRepo;
        if (repo == null) {
            if (sourceProbeSettled.compareAndSet(false, true))
                finishSourceProbeFailure("Official LXC source unavailable");
            return;
        }
        var officialUrl = pathJoin(repo.getUrl(), IMAGES_META_PATH);
        String classfunUrl = null;
        if (classfunAvailable && apiManager != null) {
            try {
                classfunUrl = apiManager.getApiUrl("lxc_images_metadata");
            } catch (Exception e) {
                Log.w(TAG, "ClassFun metadata probe unavailable", e);
            }
        }

        sourceProbeFailures.set(0);
        sourceProbeRequestCount = classfunUrl == null ? 1 : 2;
        var startGate = new CountDownLatch(1);
        runOnPool(() -> probeMetadataSource(
            SOURCE_OFFICIAL, SOURCE_OFFICIAL, officialUrl, startGate));
        if (classfunUrl != null) {
            final var url = classfunUrl;
            runOnPool(() -> probeMetadataSource(
                SOURCE_CLASSFUN, SOURCE_CERNET, url, startGate));
        }
        startGate.countDown();
    }

    private void probeMetadataSource(
        @NonNull String metadataSource,
        @NonNull String downloadSource,
        @NonNull String url,
        @NonNull CountDownLatch startGate
    ) {
        try {
            startGate.await();
            if (sourceProbeSettled.get()) return;
            var json = fetchJSON(url, BROWSER_USER_AGENT);
            if (!sourceProbeSettled.compareAndSet(false, true)) return;
            // fetchJSON has already validated the response. Settle the race at
            // response completion; parsing time must not change which source won.
            var images = LxcImageParser.parse(json);
            Log.i(TAG, fmt("Initial metadata probe selected %s", metadataSource));
            runOnUiThread(() -> applySourceProbeWinner(
                metadataSource, downloadSource, images));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordSourceProbeFailure(metadataSource, e);
        } catch (Exception e) {
            recordSourceProbeFailure(metadataSource, e);
        }
    }

    private void recordSourceProbeFailure(@NonNull String source, @NonNull Exception error) {
        Log.w(TAG, fmt("Metadata probe failed for %s", source), error);
        if (sourceProbeSettled.get()) return;
        if (sourceProbeFailures.incrementAndGet() < sourceProbeRequestCount) return;
        if (!sourceProbeSettled.compareAndSet(false, true)) return;
        var message = error.getMessage();
        finishSourceProbeFailure(message != null ? message : "Unknown error");
    }

    private void finishSourceProbeFailure(@NonNull String message) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || userOverrodeSourceProbe.get()) return;
            isProbingSources = false;
            isLoading = false;
            setMetaError(message);
        });
    }

    private void applySourceProbeWinner(
        @NonNull String metadataSource,
        @NonNull String downloadSource,
        @NonNull List<LxcImage> images
    ) {
        if (isFinishing() || isDestroyed() || userOverrodeSourceProbe.get()) return;
        rememberSourcePair(metadataSource, downloadSource);
        // The refreshed repo may not contain the probe's CERNET entry. Rebuild
        // from the remembered snapshot so the winning source is always selectable.
        setupSourceDropdown();
        applySourceSelection(metadataSource, downloadSource);
        isProbingSources = false;
        isLoading = false;
        onImagesLoaded(images, images.size());
    }

    private void setMetaRefreshing() {
        progressMeta.setVisibility(VISIBLE);
        tvMetaStatus.setText(R.string.lxc_meta_refreshing);
        tvMetaStatus.setTextColor(resolveThemeColor(colorOnSurfaceVariant));
        btnLoad.setEnabled(true);
        setImageSectionEnabled(false);
        setOutputEnabled(false);
    }

    private void setMetaSourcesLoading() {
        progressMeta.setVisibility(VISIBLE);
        tvMetaStatus.setText(R.string.lxc_meta_loading);
        tvMetaStatus.setTextColor(resolveThemeColor(colorOnSurfaceVariant));
        btnLoad.setEnabled(false);
        setImageSectionEnabled(false);
        setOutputEnabled(false);
    }

    private boolean sourcesReady() {
        return metaSourceKeys != null && metaSourceLabels != null
            && dlSourceKeys != null && dlSourceLabels != null && dlSourceUrls != null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        if (currentDownloadId >= 0) {
            pollHandler.removeCallbacks(pollRunnable);
            pollHandler.post(pollRunnable);
        }
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        pollHandler.removeCallbacks(pollRunnable);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Stop driving the on-screen widget; the download keeps running in the
        // foreground service (notification shade) and registers the disk itself.
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void confirmExit() {
        // Leave normally (so you can navigate to other screens while downloading).
        // The download keeps running in the foreground service; its state is
        // restored when this screen is re-opened.
        if (isDownloading && currentDownloadId >= 0)
            Toast.makeText(this, R.string.download_background_toast, LENGTH_SHORT).show();
        finish();
    }

    private void onFolderPickerResult(Uri uri) {
        if (uri == null) return;
        var path = resolveUriPath(this, uri);
        if (path != null) inputFolder.setText(path);
    }

    private void setMetaIdle() {
        progressMeta.setVisibility(GONE);
        tvMetaStatus.setText(R.string.lxc_meta_idle);
        tvMetaStatus.setTextColor(resolveThemeColor(colorOnSurfaceVariant));
        btnLoad.setEnabled(true);
        setImageSectionEnabled(false);
        setOutputEnabled(false);
    }

    private void setMetaLoading() {
        progressMeta.setVisibility(VISIBLE);
        tvMetaStatus.setText(R.string.lxc_meta_loading);
        tvMetaStatus.setTextColor(resolveThemeColor(colorOnSurfaceVariant));
        btnLoad.setEnabled(false);
        setImageSectionEnabled(false);
        setOutputEnabled(false);
    }

    private void setMetaLoaded(int count) {
        progressMeta.setVisibility(GONE);
        tvMetaStatus.setText(getResources().getQuantityString(
            R.plurals.lxc_meta_count, count, count
        ));
        tvMetaStatus.setTextColor(resolveThemeColor(colorOnSurfaceVariant));
        btnLoad.setEnabled(true);
    }

    private void setMetaError(String msg) {
        progressMeta.setVisibility(GONE);
        tvMetaStatus.setText(getString(R.string.lxc_meta_error, msg));
        tvMetaStatus.setTextColor(resolveThemeColor(colorError));
        btnLoad.setEnabled(true);
        setImageSectionEnabled(false);
        setOutputEnabled(false);
    }

    private int resolveThemeColor(int attr) {
        try (var a = obtainStyledAttributes(new int[]{attr})) {
            return a.getColor(0, 0);
        }
    }

    private void setupSourceDropdown() {
        var metaKeys = new ArrayList<String>();
        var metaLabels = new ArrayList<String>();
        metaKeys.add(SOURCE_OFFICIAL);
        metaLabels.add(getString(R.string.lxc_source_official));
        metaKeys.add(SOURCE_CLASSFUN);
        metaLabels.add(getString(R.string.lxc_source_classfun));
        metaKeys.add(SOURCE_CUSTOM);
        metaLabels.add(getString(R.string.lxc_source_custom));
        metaSourceKeys = metaKeys.toArray(new String[0]);
        metaSourceLabels = metaLabels.toArray(new String[0]);

        var downloadSources = buildDownloadSources(lxcRepo);
        dlSourceKeys = new String[downloadSources.size()];
        dlSourceLabels = new String[downloadSources.size()];
        dlSourceUrls = new String[downloadSources.size()];
        int sourceIndex = 0;
        for (var source : downloadSources.values()) {
            dlSourceKeys[sourceIndex] = source.key;
            dlSourceLabels[sourceIndex] = source.name;
            dlSourceUrls[sourceIndex] = source.baseUrl;
            sourceIndex++;
        }

        int metaDefaultIndex = findSourceIndex(metaSourceKeys, selectedMetaSourceKey);
        var aMeta = IconItemAdapter.create(this, metaSourceLabels, R.drawable.ic_nav_network);
        dropdownMetaSource.setAdapter(aMeta);
        dropdownMetaSource.setText(metaSourceLabels[metaDefaultIndex]);
        selectedMetaSourceKey = metaSourceKeys[metaDefaultIndex];
        dropdownMetaSource.setOnItemClickListener((p, v, pos, id) -> {
            selectedMetaSourceKey = metaSourceKeys[pos];
            boolean isCustom = selectedMetaSourceKey.equals(SOURCE_CUSTOM);
            inputCustomMetaUrl.setVisibility(isCustom ? VISIBLE : GONE);
            onUserChangedSource();
            setMetaIdle();
        });
        inputCustomMetaUrl.setVisibility(
            selectedMetaSourceKey.equals(SOURCE_CUSTOM) ? VISIBLE : GONE);

        int downDefaultIndex = findSourceIndex(dlSourceKeys, selectedDlSourceKey);
        var aDown = IconItemAdapter.create(this, dlSourceLabels, R.drawable.ic_download);
        dropdownDlSource.setAdapter(aDown);
        dropdownDlSource.setText(dlSourceLabels[downDefaultIndex]);
        selectedDlSourceKey = dlSourceKeys[downDefaultIndex];
        dropdownDlSource.setOnItemClickListener((p, v, pos, id) -> {
            selectedDlSourceKey = dlSourceKeys[pos];
            boolean isCustom = selectedDlSourceKey.equals(SOURCE_CUSTOM);
            inputCustomDlUrl.setVisibility(isCustom ? VISIBLE : GONE);
            onUserChangedSource();
        });
        inputCustomDlUrl.setVisibility(
            selectedDlSourceKey.equals(SOURCE_CUSTOM) ? VISIBLE : GONE);
    }

    private int findSourceIndex(@NonNull String[] keys, @NonNull String target) {
        int index = indexOfSource(keys, target);
        return index >= 0 ? index : 0;
    }

    private int indexOfSource(@Nullable String[] keys, @NonNull String target) {
        if (keys == null) return -1;
        for (int i = 0; i < keys.length; i++)
            if (keys[i].equals(target)) return i;
        return -1;
    }

    /**
     * Before Repos.load() completes this is the union of remembered and
     * repo.yaml. Afterwards lxcRepo points at Repos.load(), so rebuilding
     * produces the union of remembered and remote.
     * putIfAbsent makes the exact remembered name/base URL win on key collisions.
     */
    @NonNull
    private LinkedHashMap<String, DownloadSource> buildDownloadSources(
        @Nullable Repos.Repo repo
    ) {
        var result = new LinkedHashMap<String, DownloadSource>();
        if (!rememberedDlSourceKey.isEmpty()) {
            var rememberedName = rememberedDlSourceName.isEmpty()
                ? rememberedDlSourceKey : rememberedDlSourceName;
            if (!rememberedDlSourceUrl.isEmpty() || SOURCE_CUSTOM.equals(rememberedDlSourceKey)) {
                result.put(rememberedDlSourceKey, new DownloadSource(
                    rememberedDlSourceKey, rememberedName, rememberedDlSourceUrl));
            }
        }
        addRepoDownloadSources(result, repo);
        result.putIfAbsent(SOURCE_CUSTOM, new DownloadSource(
            SOURCE_CUSTOM,
            getString(R.string.lxc_source_custom),
            normalizeBaseUrl(inputCustomDlUrl.getText())
        ));
        return result;
    }

    private void addRepoDownloadSources(
        @NonNull LinkedHashMap<String, DownloadSource> result,
        @Nullable Repos.Repo repo
    ) {
        if (repo == null) return;
        result.putIfAbsent(SOURCE_OFFICIAL, new DownloadSource(
            SOURCE_OFFICIAL,
            getString(R.string.lxc_source_official),
            normalizeBaseUrl(repo.getUrl())
        ));
        for (var mirror : repo.getMirrors()) {
            var url = normalizeBaseUrl(mirror.getRepoUrl(repo));
            if (url.isEmpty()) continue;
            result.putIfAbsent(mirror.getId(), new DownloadSource(
                mirror.getId(), mirror.getName(), url));
        }
    }

    @Nullable
    private DownloadSource findDownloadSourceInRepo(
        @NonNull String key,
        @Nullable Repos.Repo repo
    ) {
        if (repo == null || SOURCE_CUSTOM.equals(key)) return null;
        if (SOURCE_OFFICIAL.equals(key)) {
            return new DownloadSource(
                key,
                getString(R.string.lxc_source_official),
                normalizeBaseUrl(repo.getUrl())
            );
        }
        var mirror = repo.getMirror(key);
        if (mirror == null) return null;
        var url = normalizeBaseUrl(mirror.getRepoUrl(repo));
        if (url.isEmpty()) return null;
        return new DownloadSource(key, mirror.getName(), url);
    }

    @Nullable
    private DownloadSource findCurrentDownloadSource(@NonNull String key) {
        int index = indexOfSource(dlSourceKeys, key);
        if (index >= 0 && dlSourceLabels != null && dlSourceUrls != null) {
            return new DownloadSource(
                key, dlSourceLabels[index], dlSourceUrls[index]);
        }
        var source = findDownloadSourceInRepo(key, lxcRepo);
        if (source == null && builtinLxcRepo != lxcRepo)
            source = findDownloadSourceInRepo(key, builtinLxcRepo);
        return source;
    }

    /** Migrates old key-only preferences, while never replacing a saved URL. */
    private void hydrateRememberedDownloadSource(@Nullable Repos.Repo repo) {
        if (rememberedDlSourceKey.isEmpty()) return;
        String name = rememberedDlSourceName;
        String url = rememberedDlSourceUrl;
        DownloadSource fallback = findDownloadSourceInRepo(rememberedDlSourceKey, repo);
        if (fallback == null && builtinLxcRepo != repo)
            fallback = findDownloadSourceInRepo(rememberedDlSourceKey, builtinLxcRepo);
        if (SOURCE_CUSTOM.equals(rememberedDlSourceKey)) {
            if (name.isEmpty()) name = getString(R.string.lxc_source_custom);
            var customUrl = normalizeBaseUrl(inputCustomDlUrl.getText());
            if (!customUrl.isEmpty()) url = customUrl;
        } else if (fallback != null) {
            if (name.isEmpty()) name = fallback.name;
            if (url.isEmpty()) url = fallback.baseUrl;
        }
        if (name.isEmpty()) name = rememberedDlSourceKey;
        rememberedDlSourceName = name;
        rememberedDlSourceUrl = url;
        sourcePrefs.edit()
            .putString(PREF_DL_SOURCE_NAME, name)
            .putString(PREF_DL_SOURCE_URL, url)
            .apply();
    }

    private void applySourceSelection(@NonNull String metaKey, @NonNull String dlKey) {
        int mi = findSourceIndex(metaSourceKeys, metaKey);
        selectedMetaSourceKey = metaSourceKeys[mi];
        dropdownMetaSource.setText(metaSourceLabels[mi]);
        inputCustomMetaUrl.setVisibility(
            selectedMetaSourceKey.equals(SOURCE_CUSTOM) ? VISIBLE : GONE);
        int di = findSourceIndex(dlSourceKeys, dlKey);
        selectedDlSourceKey = dlSourceKeys[di];
        dropdownDlSource.setText(dlSourceLabels[di]);
        inputCustomDlUrl.setVisibility(
            selectedDlSourceKey.equals(SOURCE_CUSTOM) ? VISIBLE : GONE);
    }

    private void onUserChangedSource() {
        boolean wasProbing = isProbingSources;
        automaticMetadataRequest = false;
        userOverrodeSourceProbe.set(true);
        sourceProbeSettled.set(true);
        rememberSourcePair(getSelectedMetaSourceKey(), getSelectedDlSourceKey());
        if (!wasProbing) return;
        isProbingSources = false;
        isLoading = false;
        setMetaIdle();
    }

    private void rememberSourcePair(@NonNull String metadataSource, @NonNull String downloadSource) {
        DownloadSource source;
        if (SOURCE_CUSTOM.equals(downloadSource)) {
            source = new DownloadSource(
                SOURCE_CUSTOM,
                getString(R.string.lxc_source_custom),
                normalizeBaseUrl(inputCustomDlUrl.getText())
            );
        } else {
            source = findCurrentDownloadSource(downloadSource);
        }
        if (source == null) {
            String previousName = downloadSource.equals(rememberedDlSourceKey)
                ? rememberedDlSourceName : downloadSource;
            String previousUrl = downloadSource.equals(rememberedDlSourceKey)
                ? rememberedDlSourceUrl : "";
            source = new DownloadSource(downloadSource, previousName, previousUrl);
        }
        rememberedDlSourceKey = source.key;
        rememberedDlSourceName = source.name;
        rememberedDlSourceUrl = source.baseUrl;
        sourcePrefs.edit()
            .putString(PREF_META_SOURCE, metadataSource)
            .putString(PREF_DL_SOURCE, downloadSource)
            .putString(PREF_DL_SOURCE_NAME, source.name)
            .putString(PREF_DL_SOURCE_URL, source.baseUrl)
            .apply();
    }

    @NonNull
    private String normalizeBaseUrl(@Nullable String url) {
        if (url == null) return "";
        var result = url.trim();
        while (result.endsWith("/") && !result.endsWith("://"))
            result = result.substring(0, result.length() - 1);
        return result;
    }

    @Nullable
    private String resolveSourceUrl(@NonNull String key) {
        if (lxcRepo == null) return null;
        if (key.equals(SOURCE_OFFICIAL)) return lxcRepo.getUrl();
        if (key.equals(SOURCE_CUSTOM)) return null;
        var mirror = lxcRepo.getMirror(key);
        if (mirror == null) return null;
        return mirror.getRepoUrl(lxcRepo);
    }

    @NonNull
    private String getMetaBaseUrl() {
        var key = getSelectedMetaSourceKey();
        if (key.equals(SOURCE_CUSTOM)) {
            return normalizeBaseUrl(inputCustomMetaUrl.getText());
        }
        if (key.equals(SOURCE_CLASSFUN)) {
            if (isClassFunApiAvailable && apiManager != null)
                return apiManager.getApiUrl("lxc_images_metadata");
            return "";
        }
        var base = resolveSourceUrl(key);
        if (base == null) return "";
        return pathJoin(base, IMAGES_META_PATH);
    }

    @NonNull
    private String getDownloadBaseUrl() {
        var key = getSelectedDlSourceKey();
        if (key.equals(SOURCE_CUSTOM)) {
            return normalizeBaseUrl(inputCustomDlUrl.getText());
        }
        int index = indexOfSource(dlSourceKeys, key);
        if (index >= 0 && dlSourceUrls != null) return dlSourceUrls[index];
        if (key.equals(rememberedDlSourceKey)) return rememberedDlSourceUrl;
        return "";
    }

    private void loadImages() {
        if (isLoading || isDownloading || !sourcesReady()) return;
        var baseUrl = getMetaBaseUrl();
        if (baseUrl.isEmpty()) {
            if (getSelectedMetaSourceKey().equals(SOURCE_CUSTOM))
                inputCustomMetaUrl.setError(getString(R.string.lxc_error_custom_url));
            else
                setMetaError(getString(R.string.lxc_error_source_unavailable));
            return;
        }
        inputCustomMetaUrl.setError(null);
        isLoading = true;
        setMetaLoading();
        runOnPool(() -> {
            try {
                var json = fetchJSON(baseUrl, BROWSER_USER_AGENT);
                var images = LxcImageParser.parse(json);
                var count = images.size();
                Log.d(TAG, fmt("Parsed %d disk-kvm images", count));
                runOnUiThread(() -> {
                    isLoading = false;
                    onImagesLoaded(images, count);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load images", e);
                final var msg = e.getMessage();
                runOnUiThread(() -> {
                    isLoading = false;
                    setMetaError(msg != null ? msg : "Unknown error");
                });
            }
        });
    }

    private void onImagesLoaded(@NonNull List<LxcImage> images, int count) {
        allImages = images;
        selectedImage = null;
        if (images.isEmpty()) {
            setMetaError(getString(R.string.lxc_no_images));
            return;
        }
        setMetaLoaded(count);
        setImageSectionEnabled(true);
        populateDistros();
    }

    private void setupImageDropdowns() {
        dropdownDistro.setOnItemClickListener((p, v, pos, id) ->
            onDistroSelected(dropdownDistro.getText()));
        dropdownVersion.setOnItemClickListener((p, v, pos, id) ->
            onVersionSelected(dropdownVersion.getText()));
        dropdownVariant.setOnItemClickListener((p, v, pos, id) ->
            onVariantSelected(dropdownVariant.getText()));
        dropdownBuild.setOnItemClickListener((p, v, pos, id) ->
            onBuildSelected(dropdownBuild.getText()));
    }

    private void populateDistros() {
        var distros = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (var img : allImages) distros.add(img.getDistro());
        setDropdownItems(dropdownDistro, distros.toArray(new String[0]), R.drawable.ic_linux);
        clearDropdown(dropdownVersion, R.drawable.ic_source_branch);
        clearDropdown(dropdownVariant, R.drawable.ic_package);
        clearDropdown(dropdownBuild, R.drawable.ic_wrench);
        dropdownVersion.setEnabled(false);
        dropdownVariant.setEnabled(false);
        dropdownBuild.setEnabled(false);
        setOutputEnabled(false);
    }

    private void onDistroSelected(String distro) {
        displayVersionToRelease.clear();
        var sorted = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (var img : allImages)
            if (img.getDistro().equals(distro))
                sorted.put(img.getDisplayVersion(), img.getDistroVersion());
        displayVersionToRelease.putAll(sorted);
        setDropdownItems(dropdownVersion, displayVersionToRelease.keySet().toArray(new String[0]), R.drawable.ic_source_branch);
        dropdownVersion.setEnabled(true);
        clearDropdown(dropdownVariant, R.drawable.ic_package);
        clearDropdown(dropdownBuild, R.drawable.ic_wrench);
        dropdownVariant.setEnabled(false);
        dropdownBuild.setEnabled(false);
        setOutputEnabled(false);
    }

    private void onVersionSelected(String displayVersion) {
        var distro = dropdownDistro.getText();
        var release = displayVersionToRelease.get(displayVersion);
        if (release == null) release = displayVersion;
        var variants = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (var img : allImages)
            if (img.getDistro().equals(distro) && img.getDistroVersion().equals(release))
                variants.add(img.getVariant());
        setDropdownItems(dropdownVariant, variants.toArray(new String[0]), R.drawable.ic_package);
        dropdownVariant.setEnabled(true);
        clearDropdown(dropdownBuild, R.drawable.ic_wrench);
        dropdownBuild.setEnabled(false);
        setOutputEnabled(false);
    }

    private void onVariantSelected(String variant) {
        var distro = dropdownDistro.getText();
        var displayVersion = dropdownVersion.getText();
        var release = displayVersionToRelease.get(displayVersion);
        if (release == null) release = displayVersion;
        var builds = new TreeSet<String>(Comparator.reverseOrder());
        for (LxcImage img : allImages) {
            if (img.getDistro().equals(distro) &&
                img.getDistroVersion().equals(release) &&
                img.getVariant().equals(variant)
            ) builds.add(img.getBuildSerial());
        }
        setDropdownItems(dropdownBuild, builds.toArray(new String[0]), R.drawable.ic_wrench);
        dropdownBuild.setEnabled(true);
        setOutputEnabled(false);
    }

    private void onBuildSelected(String build) {
        var distro = dropdownDistro.getText();
        var displayVersion = dropdownVersion.getText();
        var release = displayVersionToRelease.get(displayVersion);
        if (release == null) release = displayVersion;
        var variant = dropdownVariant.getText();
        for (var img : allImages) {
            if (!img.getDistro().equals(distro)) continue;
            if (!img.getDistroVersion().equals(release)) continue;
            if (!img.getVariant().equals(variant)) continue;
            if (!img.getBuildSerial().equals(build)) continue;
            selectedImage = img;
            showOutput(img);
            return;
        }
        selectedImage = null;
        hideOutput();
    }

    private void showOutput(@NonNull LxcImage img) {
        setOutputEnabled(true);
        if (linuxVmMode) {
            if (inputVmName.getText().isEmpty())
                inputVmName.setText(defaultLinuxVmName(img));
            kernelAnalysis.setVisibility(VISIBLE);
            kernelAnalysis.reset();
            return;
        }
        inputFilename.setText(img.getDefaultFileName());
        tvInfoSize.setText(getString(R.string.lxc_info_size, formatSize(img.getSize())));
        var downloadUrl = pathJoin(getDownloadBaseUrl(), img.getDownloadPath());
        tvInfoPath.setText(getString(R.string.lxc_info_path, downloadUrl));
        cardInfo.setVisibility(VISIBLE);
        // A new image is selected: offer a fresh kernel analysis for it.
        kernelAnalysis.setVisibility(VISIBLE);
        kernelAnalysis.reset();
    }

    @NonNull
    private String defaultLinuxVmName(@NonNull LxcImage image) {
        var base = fmt("%s-%s", image.getDistro(), image.getDistroVersion())
            .replace(":", "-");
        if (!checkFileName(base)) base = "Linux-VM";
        var store = new VMStore();
        store.load(this);
        if (store.isNameUnique(base)) return base;
        int suffix = 2;
        while (!store.isNameUnique(fmt("%s-%d", base, suffix))) suffix++;
        return fmt("%s-%d", base, suffix);
    }

    private void hideOutput() {
        selectedImage = null;
        setOutputEnabled(false);
    }

    private void doImport() {
        if (isDownloading) return;
        if (DiskDownloadManager.hasActiveDownload()) {
            Toast.makeText(this, R.string.download_one_at_a_time, LENGTH_SHORT).show();
            return;
        }
        if (selectedImage == null) {
            Toast.makeText(
                this, R.string.lxc_error_no_image,
                LENGTH_SHORT
            ).show();
            return;
        }
        if (linuxVmMode) {
            doLinuxVmDownload();
            return;
        }
        var name = inputFilename.getText();
        if (name.isEmpty()) {
            inputFilename.setError(getString(R.string.lxc_error_name_empty));
            return;
        }
        inputFilename.setError(null);
        var folder = inputFolder.getText();
        if (folder.isEmpty()) {
            inputFolder.setError(getString(R.string.lxc_error_folder_empty));
            return;
        }
        inputFolder.setError(null);
        var downloadBaseUrl = getDownloadBaseUrl();
        if (downloadBaseUrl.isEmpty()) {
            if (getSelectedDlSourceKey().equals(SOURCE_CUSTOM))
                inputCustomDlUrl.setError(getString(R.string.lxc_error_custom_url));
            return;
        }
        inputCustomDlUrl.setError(null);
        var downloadUrl = pathJoin(downloadBaseUrl, selectedImage.getDownloadPath());
        var destPath = pathJoin(folder, name);
        if (new File(destPath).exists()) {
            inputFilename.setError(getString(R.string.import_url_error_file_exists));
            return;
        }
        downloadName = name;
        downloadFolder = folder;
        notifPermission.ensureThen(() -> startDownload(downloadUrl));
    }

    private void doLinuxVmDownload() {
        var vmName = inputVmName.getText();
        inputVmName.setError(null);
        if (vmName.isEmpty()) {
            inputVmName.setError(getString(R.string.create_vm_error_name_empty));
            return;
        }
        if (!checkFileName(vmName)) {
            inputVmName.setError(getString(R.string.create_vm_error_name_invalid));
            return;
        }
        var vmStore = new VMStore();
        vmStore.load(this);
        if (!vmStore.isNameUnique(vmName)) {
            inputVmName.setError(getString(R.string.create_vm_error_name_duplicate));
            return;
        }
        inputVmCpu.setError(null);
        inputVmMemory.setError(null);
        inputVmDiskSize.setError(null);
        final long cpu;
        final long memoryMb;
        final long diskBytes;
        try {
            if (!inputVmCpu.isInputValid() || !inputVmMemory.isInputValid()
                || !inputVmDiskSize.isInputValid()) throw new NumberFormatException();
            cpu = inputVmCpu.getValue();
            memoryMb = inputVmMemory.getValue(SizeUnit.MB);
            diskBytes = inputVmDiskSize.getValue();
        } catch (Exception e) {
            if (!inputVmCpu.isInputValid())
                inputVmCpu.setError(getString(R.string.create_vm_error_invalid_number));
            if (!inputVmMemory.isInputValid())
                inputVmMemory.setError(getString(R.string.create_vm_error_invalid_number));
            if (!inputVmDiskSize.isInputValid())
                inputVmDiskSize.setError(getString(R.string.create_vm_error_invalid_number));
            return;
        }
        var password = inputVmRootPassword.getText();
        inputVmRootPassword.setError(null);
        if (password.isEmpty()) {
            inputVmRootPassword.setError(getString(R.string.change_password_error_empty));
            return;
        }
        if (!selectedVmNetworkId.isEmpty()) {
            try {
                var networks = new NetworkStore();
                networks.load(this);
                if (networks.findById(UUID.fromString(selectedVmNetworkId)) == null)
                    throw new IllegalArgumentException("network missing");
            } catch (Exception e) {
                Toast.makeText(
                    this, R.string.linux_vm_network_unavailable, LENGTH_SHORT).show();
                setupVmNetworkDropdown();
                return;
            }
        }
        var downloadBaseUrl = getDownloadBaseUrl();
        if (downloadBaseUrl.isEmpty()) {
            if (getSelectedDlSourceKey().equals(SOURCE_CUSTOM))
                inputCustomDlUrl.setError(getString(R.string.lxc_error_custom_url));
            return;
        }
        inputCustomDlUrl.setError(null);
        var image = selectedImage;
        if (image == null) return;
        var name = image.getDefaultFileName();
        var folder = pathJoin(externalPath(), "DroidVM", vmName);
        var destPath = pathJoin(folder, name);
        if (new File(destPath).exists()) {
            inputVmName.setError(getString(R.string.import_url_error_file_exists));
            return;
        }
        pendingLinuxVmName = vmName;
        pendingLinuxRootPassword = password;
        pendingLinuxNetworkId = selectedVmNetworkId;
        pendingLinuxCpu = cpu;
        pendingLinuxMemoryMb = memoryMb;
        pendingLinuxDiskBytes = diskBytes;
        downloadName = name;
        downloadFolder = folder;
        var downloadUrl = pathJoin(downloadBaseUrl, image.getDownloadPath());
        notifPermission.ensureThen(() -> startDownload(downloadUrl));
    }

    private void startDownload(String url) {
        isDownloading = true;
        captureSession();
        setInputsEnabled(false);
        fabImport.setVisibility(GONE);
        downloadWidget.setVisibility(VISIBLE);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        downloadWidget.startExternal(downloadName, this::cancelDownload);
        final var folder = downloadFolder;
        final var name = downloadName;
        // enqueue() resolves redirects (network I/O), so run it off the main thread.
        runOnPool(() -> {
            long id = DiskDownloadManager.enqueue(
                this, url, LXC_USER_AGENT, folder, name, getClass());
            runOnUiThread(() -> onDownloadEnqueued(id));
        });
    }

    private void onDownloadEnqueued(long id) {
        if (id < 0) {
            if (isDownloading) onDownloadFailed(getString(R.string.download_error_start));
            return;
        }
        if (!isDownloading) {
            // Cancelled while still enqueueing.
            DiskDownloadManager.cancel(id);
            DiskDownloadManager.release(id);
            return;
        }
        currentDownloadId = id;
        var s = getSession();
        if (s != null) s.downloadId = id;
        DiskDownloadManager.retainUntilReleased(id);
        DiskDownloadService.start(this, id);
        if (activityStarted && !isDestroyed()) pollHandler.post(pollRunnable);
    }

    /** Mirrors the download's live state into the on-screen widget. */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!activityStarted || currentDownloadId < 0) return;
            var p = DiskDownloadManager.query(currentDownloadId);
            if (p == null) {
                cancelDownload(); // job gone (cancelled elsewhere)
                return;
            }
            switch (p.state) {
                case DiskDownloadManager.STATE_SUCCESS:
                    onDownloadSucceeded();
                    break;
                case DiskDownloadManager.STATE_FAILED:
                    onDownloadFailed(p.reason);
                    break;
                case DiskDownloadManager.STATE_CANCELLED:
                    cancelDownload();
                    break;
                case DiskDownloadManager.STATE_PAUSED:
                    downloadWidget.updateExternal(p.downloaded, p.total);
                    downloadWidget.markExternalPaused(
                        p.reason != null ? p.reason : getString(R.string.download_paused));
                    pollHandler.postDelayed(this, POLL_INTERVAL_MS);
                    break;
                default: // CONNECTING, RUNNING
                    downloadWidget.updateExternal(p.downloaded, p.total);
                    pollHandler.postDelayed(this, POLL_INTERVAL_MS);
                    break;
            }
        }
    };

    private void onDownloadSucceeded() {
        long id = currentDownloadId;
        currentDownloadId = -1;
        pollHandler.removeCallbacks(pollRunnable);
        downloadWidget.markExternalFinished();
        var result = DiskDownloadManager.consumeResult(id);
        if (result == null) {
            clearSessionDownloadId(id);
            DiskDownloadManager.release(id);
            finish();
            return;
        }
        clearSessionDownloadId(id);
        if (linuxVmMode) {
            onLinuxDownloadSucceeded(result);
            return;
        }
        var resetPassword = inputResetPassword.getText();
        inputResetPassword.setText("");
        var s = getSession();
        if (s != null) s.resetPassword = "";
        var resultData = new Intent();
        resultData.putExtra("result_disk_path", pathJoin(result.folder, result.name));
        setResult(RESULT_OK, resultData);
        boolean hasPasswordReset = result.diskId != null && !resetPassword.isEmpty();
        if (hasPasswordReset) {
            pendingPasswordDiskId = result.diskId;
            pendingResetPassword = resetPassword;
            pendingImportName = result.name;
        } else {
            showImportSuccess(result.name);
        }
        if (result.diskId != null && DiskFormat.fromFilename(result.name) == DiskFormat.QCOW2) {
            // Resolve the backing chain first (may prompt once), then rewrite only when the
            // compression can't boot on crosvm. A requested password reset waits for a successful
            // rewrite before starting its visual operation.
            var diskId = result.diskId;
            var diskPath = pathJoin(result.folder, result.name);
            if (!hasPasswordReset) {
                BackingChainLinker.link(this, diskId, () ->
                    startOptimizeAfterImport(this, diskId, diskPath, this::finish));
            } else {
                BackingChainLinker.link(this, diskId, () ->
                    startOptimizeAfterImportForResult(
                        this,
                        diskId,
                        diskPath,
                        optimizeLauncher,
                        this::launchPasswordAfterImport,
                        this::completePendingImport));
            }
            return;
        }
        if (hasPasswordReset) {
            launchPasswordAfterImport();
            return;
        }
        finish();
    }

    private void onLinuxDownloadSucceeded(@NonNull DiskDownloadManager.Result result) {
        if (result.diskId == null) {
            finishLinuxVmFlow(false);
            return;
        }
        pendingLinuxDiskId = result.diskId;
        inputVmRootPassword.setText("");
        var s = getSession();
        if (s != null) s.vmRootPassword = "";
        try {
            var task = new JSONObject();
            task.put("action", "resize");
            task.put("size", String.valueOf(pendingLinuxDiskBytes));
            var intent = cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity.createIntent(
                this, result.diskId, task);
            intent.putExtra(
                cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity.EXTRA_AUTOFINISH,
                true);
            linuxResizeLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Linux VM disk resize", e);
            finishLinuxVmFlow(false);
        }
    }

    private void launchLinuxMaintenance() {
        var diskId = pendingLinuxDiskId;
        if (diskId == null || pendingLinuxRootPassword.isEmpty()) {
            finishLinuxVmFlow(false);
            return;
        }
        try {
            var diskStore = new DiskStore();
            diskStore.load(this);
            var disk = diskStore.findById(diskId);
            if (disk == null) throw new IllegalStateException("Downloaded disk is not registered");
            var agentVM = new AgentVM(VMBackend.QEMU, VMHypervisor.SOFT);
            var password = new PasswordAction(agentVM);
            password.setPassword(pendingLinuxRootPassword);
            password.setChangeNormalUsers(false);
            new AutoGrowAction(agentVM);
            agentVM.addDisk(disk);
            var intent = AgentOperationActivity.createIntent(this, agentVM);
            intent.putExtra(AgentOperationActivity.EXTRA_AUTOFINISH_ON_SUCCESS, true);
            pendingLinuxRootPassword = "";
            linuxMaintenanceLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Linux VM maintenance", e);
            finishLinuxVmFlow(false);
        }
    }

    private void createPendingLinuxVm() {
        var diskId = pendingLinuxDiskId;
        if (diskId == null || pendingLinuxVmName.isEmpty()) {
            finishLinuxVmFlow(false);
            return;
        }
        try {
            var diskStore = new DiskStore();
            diskStore.load(this);
            var disk = diskStore.findById(diskId);
            if (disk == null) throw new IllegalStateException("Downloaded disk is not registered");
            var config = VMConfig.createWithCustomizeDefaults(this);
            config.setName(pendingLinuxVmName);
            config.item.set("cpu_count", pendingLinuxCpu);
            config.item.set("memory_mb", pendingLinuxMemoryMb);
            var disks = DataItem.newArray();
            var diskItem = DataItem.newObject();
            diskItem.set("path", disk.getFullPath());
            diskItem.set("bus", DiskBus.VIRTIO);
            diskItem.set("readonly", false);
            disks.append(diskItem);
            config.item.set("disks", disks);
            var networks = DataItem.newArray();
            if (!pendingLinuxNetworkId.isEmpty()) {
                var network = DataItem.newObject();
                network.set("network_id", pendingLinuxNetworkId);
                network.set("mac_address", generateRandomMac());
                networks.append(network);
            }
            config.item.set("networks", networks);
            var vmStore = new VMStore();
            vmStore.load(this);
            vmStore.add(config);
            if (!vmStore.save(this))
                throw new IllegalStateException("Failed to save VM configuration");
            var result = new Intent();
            result.putExtra("result_vm_id", config.getId().toString());
            result.putExtra("result_disk_path", disk.getFullPath());
            setResult(RESULT_OK, result);
            Toast.makeText(
                this,
                getString(R.string.linux_vm_create_success, config.getName()),
                LENGTH_SHORT
            ).show();
            finishLinuxVmFlow(true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create Linux VM", e);
            finishLinuxVmFlow(false);
        }
    }

    private void finishLinuxVmFlow(boolean success) {
        pendingLinuxDiskId = null;
        pendingLinuxVmName = "";
        pendingLinuxRootPassword = "";
        pendingLinuxNetworkId = "";
        if (!success && !isFinishing())
            Toast.makeText(this, R.string.linux_vm_create_failed, LENGTH_SHORT).show();
        finish();
    }

    private void launchPasswordAfterImport() {
        var diskId = pendingPasswordDiskId;
        var password = pendingResetPassword;
        pendingPasswordDiskId = null;
        pendingResetPassword = "";
        if (diskId == null || password.isEmpty() || isFinishing()) {
            completePendingImport();
            return;
        }
        try {
            passwordLauncher.launch(
                ChangePasswordActivity.createQuickIntent(this, diskId, password));
        } catch (Exception e) {
            Log.e(TAG, "Failed to start quick password change", e);
            completePendingImport();
        }
    }

    private void completePendingImport() {
        var name = pendingImportName;
        pendingPasswordDiskId = null;
        pendingResetPassword = "";
        pendingImportName = "";
        if (!name.isEmpty()) showImportSuccess(name);
        finish();
    }

    private void showImportSuccess(@NonNull String name) {
        Toast.makeText(
            this,
            getString(R.string.lxc_import_success, name),
            LENGTH_SHORT
        ).show();
    }

    private void cancelDownload() {
        long id = currentDownloadId;
        currentDownloadId = -1;
        pollHandler.removeCallbacks(pollRunnable);
        if (id >= 0) {
            DiskDownloadManager.cancel(id);
            DiskDownloadManager.release(id);
        }
        clearSessionDownloadId(id);
        downloadWidget.markExternalCancelled();
        resetAfterDownloadStop();
    }

    private void onDownloadFailed(@Nullable String reason) {
        long id = currentDownloadId;
        currentDownloadId = -1;
        pollHandler.removeCallbacks(pollRunnable);
        if (id >= 0) {
            DiskDownloadManager.cancel(id);
            DiskDownloadManager.release(id);
        }
        clearSessionDownloadId(id);
        downloadWidget.markExternalFailed(reason);
        resetAfterDownloadStop();
    }

    private void clearSessionDownloadId(long id) {
        var s = getSession();
        if (s != null && (id < 0 || s.downloadId == id))
            sessions.remove(sessionKey(), s);
    }

    private void resetAfterDownloadStop() {
        isDownloading = false;
        setInputsEnabled(true);
        progressMeta.setVisibility(GONE);
        btnLoad.setEnabled(true);
        fabImport.setVisibility(VISIBLE);
    }

    private void setInputsEnabled(boolean enabled) {
        dropdownMetaSource.setEnabled(enabled);
        inputCustomMetaUrl.setEnabled(enabled);
        dropdownDlSource.setEnabled(enabled);
        inputCustomDlUrl.setEnabled(enabled);
        dropdownDistro.setEnabled(enabled);
        dropdownVersion.setEnabled(enabled);
        dropdownVariant.setEnabled(enabled);
        dropdownBuild.setEnabled(enabled);
        inputFilename.setEnabled(enabled);
        inputFolder.setEnabled(enabled);
        inputResetPassword.setEnabled(enabled);
        inputVmName.setEnabled(enabled);
        inputVmCpu.setEnabled(enabled);
        inputVmMemory.setEnabled(enabled);
        inputVmDiskSize.setEnabled(enabled);
        inputVmRootPassword.setEnabled(enabled);
        dropdownVmNetwork.setEnabled(enabled);
        btnLoad.setEnabled(enabled);
    }

    private void setImageSectionEnabled(boolean enabled) {
        float alpha = enabled ? 1.0f : 0.38f;
        dividerImage.setAlpha(alpha);
        tvImageHeader.setAlpha(alpha);
        dropdownDistro.setAlpha(enabled ? 1.0f : alpha);
        dropdownVersion.setAlpha(enabled ? 1.0f : alpha);
        dropdownVariant.setAlpha(enabled ? 1.0f : alpha);
        dropdownBuild.setAlpha(enabled ? 1.0f : alpha);
        dropdownDistro.setEnabled(enabled);
        dropdownVersion.setEnabled(enabled);
        dropdownVariant.setEnabled(enabled);
        dropdownBuild.setEnabled(enabled);
        if (!enabled) {
            clearDropdown(dropdownDistro, R.drawable.ic_linux);
            clearDropdown(dropdownVersion, R.drawable.ic_source_branch);
            clearDropdown(dropdownVariant, R.drawable.ic_package);
            clearDropdown(dropdownBuild, R.drawable.ic_wrench);
            displayVersionToRelease.clear();
            allImages.clear();
            selectedImage = null;
        }
    }

    private void setOutputEnabled(boolean enabled) {
        if (linuxVmMode) {
            setLinuxSettingsEnabled(enabled);
            return;
        }
        float alpha = enabled ? 1.0f : 0.38f;
        dividerOutput.setAlpha(alpha);
        tvOutputHeader.setAlpha(alpha);
        inputFilename.setAlpha(enabled ? 1.0f : alpha);
        inputFolder.setAlpha(enabled ? 1.0f : alpha);
        inputResetPassword.setAlpha(enabled ? 1.0f : alpha);
        cardInfo.setAlpha(alpha);
        inputFilename.setEnabled(enabled);
        inputFolder.setEnabled(enabled);
        inputResetPassword.setEnabled(enabled);
        fabImport.setVisibility(enabled ? VISIBLE : GONE);
        if (!enabled) {
            inputFilename.setText("");
            inputResetPassword.setText("");
            tvInfoSize.setText("");
            tvInfoPath.setText("");
            cardInfo.setVisibility(GONE);
            kernelAnalysis.setVisibility(GONE);
            selectedImage = null;
        }
    }

    private void setLinuxSettingsEnabled(boolean enabled) {
        float alpha = enabled ? 1.0f : 0.38f;
        dividerSettings.setAlpha(alpha);
        tvSettingsHeader.setAlpha(alpha);
        inputVmName.setAlpha(enabled ? 1.0f : alpha);
        inputVmCpu.setAlpha(enabled ? 1.0f : alpha);
        inputVmMemory.setAlpha(enabled ? 1.0f : alpha);
        inputVmDiskSize.setAlpha(enabled ? 1.0f : alpha);
        inputVmRootPassword.setAlpha(enabled ? 1.0f : alpha);
        dropdownVmNetwork.setAlpha(enabled ? 1.0f : alpha);
        inputVmName.setEnabled(enabled);
        inputVmCpu.setEnabled(enabled);
        inputVmMemory.setEnabled(enabled);
        inputVmDiskSize.setEnabled(enabled);
        inputVmRootPassword.setEnabled(enabled);
        dropdownVmNetwork.setEnabled(enabled);
        fabImport.setVisibility(enabled ? VISIBLE : GONE);
        if (!enabled) {
            inputVmName.setText("");
            inputVmRootPassword.setText("");
            kernelAnalysis.setVisibility(GONE);
            selectedImage = null;
        }
    }

    private String getSelectedMetaSourceKey() {
        return selectedMetaSourceKey;
    }

    private String getSelectedDlSourceKey() {
        return selectedDlSourceKey;
    }

    private void setDropdownItems(@NonNull DropdownRowWidget dropdown, String[] items, int icon) {
        dropdown.setAdapter(IconItemAdapter.create(this, items, icon));
        dropdown.setText("");
    }

    private void clearDropdown(@NonNull DropdownRowWidget dropdown, int icon) {
        dropdown.setAdapter(IconItemAdapter.create(this, new String[0], icon));
        dropdown.setText("");
    }
}
