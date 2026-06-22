package cn.classfun.droidvm.ui.disk.lxc;

import static android.R.attr.colorError;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;
import static com.google.android.material.R.attr.colorOnSurfaceVariant;
import static java.util.Objects.requireNonNullElse;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;
import static cn.classfun.droidvm.lib.utils.FileUtils.externalPath;
import static cn.classfun.droidvm.lib.utils.NetUtils.BROWSER_USER_AGENT;
import static cn.classfun.droidvm.lib.utils.NetUtils.LXC_USER_AGENT;
import static cn.classfun.droidvm.lib.utils.NetUtils.fetchJSON;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity.startOptimize;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.api.ApiManager;
import cn.classfun.droidvm.lib.api.Privacy;
import cn.classfun.droidvm.lib.data.Repos;
import cn.classfun.droidvm.lib.download.DiskDownloadManager;
import cn.classfun.droidvm.lib.download.DiskDownloadService;
import cn.classfun.droidvm.lib.ui.IconItemAdapter;
import cn.classfun.droidvm.lib.ui.NotificationPermission;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;
import cn.classfun.droidvm.ui.widgets.row.DropdownRowWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;
import cn.classfun.droidvm.ui.widgets.tools.DownloadWidget;
import cn.classfun.droidvm.ui.widgets.tools.KernelAnalysisWidget;

public final class ImportLxcImagesActivity extends AppCompatActivity {
    private static final String TAG = "ImportLxcImages";
    private static final String IMAGES_META_PATH = "/streams/v1/images.json";
    private static final long POLL_INTERVAL_MS = 500;
    private final Map<String, String> displayVersionToRelease = new LinkedHashMap<>();
    private Repos.Repo lxcRepo;
    private String[] metaSourceKeys;
    private String[] metaSourceLabels;
    private DropdownRowWidget dropdownMetaSource;
    private TextInputRowWidget inputCustomMetaUrl;
    private String[] dlSourceKeys;
    private String[] dlSourceLabels;
    private DropdownRowWidget dropdownDlSource;
    private TextInputRowWidget inputCustomDlUrl;
    private TextView tvMetaStatus;
    private CircularProgressIndicator progressMeta;
    private MaterialButton btnLoad;
    private View dividerImage, tvImageHeader;
    private DropdownRowWidget dropdownDistro, dropdownVersion, dropdownVariant, dropdownBuild;
    private View dividerOutput, tvOutputHeader;
    private TextInputRowWidget inputFilename, inputFolder;
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
    private boolean isClassFunApiAvailable = false;
    /** True once the user explicitly picks a source, so a background refresh
     * won't override their choice (but may still upgrade an untouched default). */
    private boolean userTouchedSource = false;
    private String downloadName = null;
    private String downloadFolder = null;
    private ApiManager apiManager = null;
    private ActivityResultLauncher<Uri> folderPickerLauncher;
    private long currentDownloadId = -1;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        cardInfo = findViewById(R.id.card_info);
        tvInfoSize = findViewById(R.id.tv_info_size);
        tvInfoPath = findViewById(R.id.tv_info_path);
        fabImport = findViewById(R.id.fab_import);
        downloadWidget = findViewById(R.id.download_widget);
        kernelAnalysis = findViewById(R.id.kernel_analysis);
        kernelAnalysis.setUrlProvider(() -> selectedImage == null
            ? null : pathJoin(getDownloadBaseUrl(), selectedImage.getDownloadPath()));
        scrollView = findViewById(R.id.scroll_view);
        initialize();
    }

    private void initialize() {
        collapsingToolbar.setTitle(getString(R.string.lxc_title));
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
        // show the built-in source list immediately (screen usable at once),
        // then refresh from the API in the background and swap it in when ready
        loadBuiltinSources();
        runOnPool(this::asyncRefreshSources);
        // If this screen was re-created while its download is still running,
        // restore the whole form; otherwise just re-attach the progress bar.
        if (!restoreSession()) reattachActiveDownload();
    }

    /** Snapshot of the form, kept across activity re-creation while a download runs. */
    private static final class Session {
        String metaSource, customMetaUrl, dlSource, customDlUrl;
        List<LxcImage> allImages;
        String distro, version, variant, build;
        String filename, folder;
    }

    /** The in-progress import (only one download runs at a time), or {@code null}. */
    private static Session session;

    private void captureSession() {
        var s = new Session();
        s.metaSource = dropdownMetaSource.getText();
        s.customMetaUrl = inputCustomMetaUrl.getText();
        s.dlSource = dropdownDlSource.getText();
        s.customDlUrl = inputCustomDlUrl.getText();
        s.allImages = new ArrayList<>(allImages);
        s.distro = dropdownDistro.getText();
        s.version = dropdownVersion.getText();
        s.variant = dropdownVariant.getText();
        s.build = dropdownBuild.getText();
        s.filename = inputFilename.getText();
        s.folder = inputFolder.getText();
        session = s;
    }

    /**
     * Rebuilds the form from the last saved session (replays the
     * distro->version->variant->build cascade) and re-attaches the progress bar if a
     * download is still running. The session is kept after the download ends too,
     * so reopening restores the last selections (hit Load to refresh). Returns
     * false if there's nothing to restore.
     */
    private boolean restoreSession() {
        var s = session;
        if (s == null) return false;
        if (lxcRepo == null) return false; // sources unavailable; can't rebuild
        dropdownMetaSource.setText(s.metaSource);
        inputCustomMetaUrl.setText(s.customMetaUrl);
        inputCustomMetaUrl.setVisibility(
            getSelectedMetaSourceKey().equals("custom") ? VISIBLE : GONE);
        dropdownDlSource.setText(s.dlSource);
        inputCustomDlUrl.setText(s.customDlUrl);
        inputCustomDlUrl.setVisibility(
            getSelectedDlSourceKey().equals("custom") ? VISIBLE : GONE);
        onImagesLoaded(s.allImages, s.allImages.size());
        dropdownDistro.setText(s.distro);
        onDistroSelected(s.distro);
        dropdownVersion.setText(s.version);
        onVersionSelected(s.version);
        dropdownVariant.setText(s.variant);
        onVariantSelected(s.variant);
        dropdownBuild.setText(s.build);
        onBuildSelected(s.build);
        inputFilename.setText(s.filename);
        inputFolder.setText(s.folder);
        reattachActiveDownload();
        return true;
    }

    /** Re-attaches just the progress bar to the running download (no form state). */
    private void reattachActiveDownload() {
        long id = DiskDownloadManager.activeDownloadId(getClass().getName());
        if (id < 0) return;
        currentDownloadId = id;
        isDownloading = true;
        setInputsEnabled(false);
        fabImport.setVisibility(GONE);
        downloadWidget.setVisibility(VISIBLE);
        var name = DiskDownloadManager.downloadName(id);
        downloadWidget.startExternal(name != null ? name : "", this::cancelDownload);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        pollHandler.post(pollRunnable);
    }

    /**
     * Populates the source dropdowns synchronously from the bundled repo list
     * so the screen is usable immediately, before the API refresh returns.
     */
    private void loadBuiltinSources() {
        var repos = Repos.loadYAML(this);
        if (repos != null) lxcRepo = repos.getRepo().get("lxc-images");
        if (lxcRepo == null) {
            // bundled data missing/corrupt: fall back to a blocking load
            setMetaSourcesLoading();
            return;
        }
        setupSourceDropdown();
        setupImageDropdowns();
        setMetaRefreshing();
    }

    /**
     * Background refresh: fetches the freshest repo/mirror list (and ClassFun
     * availability) from the API, then swaps it into the dropdowns without
     * disturbing a user who has already moved on to loading images.
     */
    private void asyncRefreshSources() {
        boolean classfun = false;
        Repos.Repo freshRepo = null;
        try {
            if (Privacy.isPrivacyAgreed(this)) {
                apiManager = ApiManager.create(this);
                classfun = apiManager.isServiceEnabled("lxc_images_metadata");
            }
            var repos = Repos.load(this);
            if (repos != null) freshRepo = repos.getRepo().get("lxc-images");
        } catch (Exception e) {
            Log.w(TAG, "Background source refresh failed; keeping built-in list", e);
        }
        final boolean classfunAvailable = classfun;
        final Repos.Repo repo = freshRepo;
        runOnUiThread(() -> applyRefreshedSources(repo, classfunAvailable));
    }

    private void applyRefreshedSources(@Nullable Repos.Repo freshRepo, boolean classfunAvailable) {
        if (isFinishing()) return;
        isClassFunApiAvailable = classfunAvailable;
        if (freshRepo != null) {
            // capture any explicit choice before the list swap
            var metaSel = getSelectedMetaSourceKey();
            var dlSel = getSelectedDlSourceKey();
            lxcRepo = freshRepo;
            setupSourceDropdown();
            // keep an explicit pick; otherwise take the fresh default
            // (which now prefers ClassFun when it just became available)
            if (userTouchedSource) restoreSourceSelection(metaSel, dlSel);
        } else if (lxcRepo == null) {
            // both the bundled list and the refresh failed
            setMetaError("source list unavailable");
            btnLoad.setEnabled(false);
            return;
        }
        // clear the "refreshing" spinner only if the user hasn't moved on
        if (!isLoading && !isDownloading && allImages.isEmpty())
            setMetaIdle();
    }

    /** Re-applies a previously selected source key after the list is rebuilt. */
    private void restoreSourceSelection(@NonNull String metaKey, @NonNull String dlKey) {
        int mi = findSourceIndex(metaSourceKeys, metaKey);
        dropdownMetaSource.setText(metaSourceLabels[mi]);
        inputCustomMetaUrl.setVisibility(
            metaSourceKeys[mi].equals("custom") ? VISIBLE : GONE);
        int di = findSourceIndex(dlSourceKeys, dlKey);
        dropdownDlSource.setText(dlSourceLabels[di]);
        inputCustomDlUrl.setVisibility(
            dlSourceKeys[di].equals("custom") ? VISIBLE : GONE);
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
            && dlSourceKeys != null && dlSourceLabels != null;
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
        var keys = new ArrayList<String>();
        var labels = new ArrayList<String>();
        if (isClassFunApiAvailable) {
            keys.add("classfun");
            labels.add(getString(R.string.lxc_source_classfun));
        }
        keys.add("official");
        labels.add(getString(R.string.lxc_source_official));
        for (var mirror : lxcRepo.getMirrors()) {
            keys.add(mirror.getId());
            labels.add(mirror.getName());
        }
        keys.add("custom");
        labels.add(getString(R.string.lxc_source_custom));
        metaSourceKeys = keys.toArray(new String[0]);
        metaSourceLabels = labels.toArray(new String[0]);
        dlSourceKeys = keys.toArray(new String[0]);
        dlSourceLabels = labels.toArray(new String[0]);
        int metaDefaultIndex = findSourceIndex(metaSourceKeys,
            isClassFunApiAvailable ? "classfun" : "official");
        var aMeta = IconItemAdapter.create(this, metaSourceLabels, R.drawable.ic_nav_network);
        dropdownMetaSource.setAdapter(aMeta);
        dropdownMetaSource.setText(metaSourceLabels[metaDefaultIndex]);
        dropdownMetaSource.setOnItemClickListener((p, v, pos, id) -> {
            userTouchedSource = true;
            boolean isCustom = metaSourceKeys[pos].equals("custom");
            inputCustomMetaUrl.setVisibility(isCustom ? VISIBLE : GONE);
            setMetaIdle();
        });
        inputCustomMetaUrl.setVisibility(
            metaSourceKeys[metaDefaultIndex].equals("custom") ? VISIBLE : GONE);
        int downDefaultIndex = findSourceIndex(dlSourceKeys,
            getString(R.string.lxc_default_download_source));
        var aDown = IconItemAdapter.create(this, dlSourceLabels, R.drawable.ic_download);
        dropdownDlSource.setAdapter(aDown);
        dropdownDlSource.setText(dlSourceLabels[downDefaultIndex]);
        dropdownDlSource.setOnItemClickListener((p, v, pos, id) -> {
            userTouchedSource = true;
            boolean isCustom = dlSourceKeys[pos].equals("custom");
            inputCustomDlUrl.setVisibility(isCustom ? VISIBLE : GONE);
        });
        inputCustomDlUrl.setVisibility(
            dlSourceKeys[downDefaultIndex].equals("custom") ? VISIBLE : GONE);
    }

    private int findSourceIndex(@NonNull String[] keys, @NonNull String target) {
        for (int i = 0; i < keys.length; i++)
            if (keys[i].equals(target)) return i;
        return 0;
    }

    @Nullable
    private String resolveSourceUrl(@NonNull String key) {
        if (lxcRepo == null) return null;
        if (key.equals("official")) return lxcRepo.getUrl();
        if (key.equals("custom")) return null;
        var mirror = lxcRepo.getMirror(key);
        if (mirror == null) return null;
        return mirror.getRepoUrl(lxcRepo);
    }

    @NonNull
    private String getMetaBaseUrl() {
        var key = getSelectedMetaSourceKey();
        if (key.equals("custom")) {
            var url = inputCustomMetaUrl.getText().trim();
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            return url;
        }
        if (key.equals("classfun")) {
            if (isClassFunApiAvailable)
                return apiManager.getApiUrl("lxc_images_metadata");
            key = "official";
        }
        var base = resolveSourceUrl(key);
        if (base == null) return "";
        return pathJoin(base, IMAGES_META_PATH);
    }

    @NonNull
    private String getDownloadBaseUrl() {
        var key = getSelectedDlSourceKey();
        if (key.equals("custom")) {
            var url = inputCustomDlUrl.getText().trim();
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            return url;
        }
        return requireNonNullElse(resolveSourceUrl(key), "");
    }

    private void loadImages() {
        if (isLoading || isDownloading || !sourcesReady()) return;
        var baseUrl = getMetaBaseUrl();
        if (baseUrl.isEmpty()) {
            if (getSelectedMetaSourceKey().equals("custom"))
                inputCustomMetaUrl.setError(getString(R.string.lxc_error_custom_url));
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
        inputFilename.setText(img.getDefaultFileName());
        tvInfoSize.setText(getString(R.string.lxc_info_size, formatSize(img.getSize())));
        var downloadUrl = pathJoin(getDownloadBaseUrl(), img.getDownloadPath());
        tvInfoPath.setText(getString(R.string.lxc_info_path, downloadUrl));
        cardInfo.setVisibility(VISIBLE);
        // A new image is selected: offer a fresh kernel analysis for it.
        kernelAnalysis.setVisibility(VISIBLE);
        kernelAnalysis.reset();
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
            if (getSelectedDlSourceKey().equals("custom"))
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
                this, url, LXC_USER_AGENT, folder, name, ImportLxcImagesActivity.class);
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
            return;
        }
        currentDownloadId = id;
        DiskDownloadService.start(this, id);
        if (!isDestroyed()) pollHandler.post(pollRunnable);
    }

    /** Mirrors the download's live state into the on-screen widget. */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentDownloadId < 0) return;
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
        var result = DiskDownloadManager.getResult(id);
        if (result == null) {
            finish();
            return;
        }
        Toast.makeText(
            this,
            getString(R.string.lxc_import_success, result.name),
            LENGTH_SHORT
        ).show();
        var resultData = new Intent();
        resultData.putExtra("result_disk_path", pathJoin(result.folder, result.name));
        setResult(RESULT_OK, resultData);
        if (result.diskId != null && DiskFormat.fromFilename(result.name) == DiskFormat.QCOW2)
            startOptimize(this, result.diskId);
        finish();
    }

    private void cancelDownload() {
        long id = currentDownloadId;
        currentDownloadId = -1;
        pollHandler.removeCallbacks(pollRunnable);
        if (id >= 0) DiskDownloadManager.cancel(id);
        downloadWidget.markExternalCancelled();
        resetAfterDownloadStop();
    }

    private void onDownloadFailed(@Nullable String reason) {
        long id = currentDownloadId;
        currentDownloadId = -1;
        pollHandler.removeCallbacks(pollRunnable);
        if (id >= 0) DiskDownloadManager.cancel(id);
        downloadWidget.markExternalFailed(reason);
        resetAfterDownloadStop();
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
        float alpha = enabled ? 1.0f : 0.38f;
        dividerOutput.setAlpha(alpha);
        tvOutputHeader.setAlpha(alpha);
        inputFilename.setAlpha(enabled ? 1.0f : alpha);
        inputFolder.setAlpha(enabled ? 1.0f : alpha);
        cardInfo.setAlpha(alpha);
        inputFilename.setEnabled(enabled);
        inputFolder.setEnabled(enabled);
        fabImport.setVisibility(enabled ? VISIBLE : GONE);
        if (!enabled) {
            inputFilename.setText("");
            tvInfoSize.setText("");
            tvInfoPath.setText("");
            cardInfo.setVisibility(GONE);
            kernelAnalysis.setVisibility(GONE);
            selectedImage = null;
        }
    }

    private String getSelectedMetaSourceKey() {
        if (metaSourceLabels == null || metaSourceKeys == null)
            return isClassFunApiAvailable ? "classfun" : "official";
        var label = dropdownMetaSource.getText();
        for (int i = 0; i < metaSourceLabels.length; i++)
            if (metaSourceLabels[i].equals(label))
                return metaSourceKeys[i];
        return isClassFunApiAvailable ? "classfun" : "official";
    }

    private String getSelectedDlSourceKey() {
        if (dlSourceLabels == null || dlSourceKeys == null)
            return "official";
        var label = dropdownDlSource.getText();
        for (int i = 0; i < dlSourceLabels.length; i++)
            if (dlSourceLabels[i].equals(label))
                return dlSourceKeys[i];
        return "official";
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
