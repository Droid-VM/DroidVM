package cn.classfun.droidvm.ui.disk.download;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;
import static com.google.android.material.R.attr.colorOnSurfaceVariant;
import static cn.classfun.droidvm.lib.utils.FileUtils.externalPath;
import static cn.classfun.droidvm.lib.utils.NetUtils.USER_AGENT;
import static cn.classfun.droidvm.lib.utils.NetUtils.extractFileName;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.download.DiskDownloadManager;
import cn.classfun.droidvm.lib.download.DiskDownloadService;
import cn.classfun.droidvm.lib.ui.NotificationPermission;
import cn.classfun.droidvm.lib.ui.SimpleTextWatcher;
import cn.classfun.droidvm.lib.utils.NetUtils.HttpException;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;
import cn.classfun.droidvm.ui.widgets.tools.DownloadWidget;
import cn.classfun.droidvm.ui.widgets.tools.KernelAnalysisWidget;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class ImportURLActivity extends AppCompatActivity {
    private static final String TAG = "ImportURL";
    private static final long POLL_INTERVAL_MS = 500;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextInputRowWidget inputUrl, inputFilename, inputFolder;
    private TextView tvStatus;
    private CircularProgressIndicator progressLoad;
    private MaterialButton btnLoad;
    private MaterialCardView cardInfo;
    private TextView tvInfoSize, tvInfoType;
    private DownloadWidget downloadWidget;
    private KernelAnalysisWidget kernelAnalysis;
    private NestedScrollView scrollView;
    private ExtendedFloatingActionButton fabImport;
    private NotificationPermission notifPermission;
    private CollapsingToolbarLayout collapsingToolbar;
    private MaterialToolbar toolbar;
    private boolean isLoading = false;
    private boolean infoLoaded = false;
    private boolean isDownloading = false;
    private String downloadName = null;
    private String downloadFolder = null;
    private long currentDownloadId = -1;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Uri> folderPickerLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_url);
        notifPermission = new NotificationPermission(this);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        toolbar = findViewById(R.id.toolbar);
        inputUrl = findViewById(R.id.input_url);
        inputFilename = findViewById(R.id.input_filename);
        inputFolder = findViewById(R.id.input_folder);
        tvStatus = findViewById(R.id.tv_status);
        progressLoad = findViewById(R.id.progress_load);
        btnLoad = findViewById(R.id.btn_load);
        cardInfo = findViewById(R.id.card_info);
        tvInfoSize = findViewById(R.id.tv_info_size);
        tvInfoType = findViewById(R.id.tv_info_type);
        downloadWidget = findViewById(R.id.download_widget);
        kernelAnalysis = findViewById(R.id.kernel_analysis);
        scrollView = findViewById(R.id.scroll_view);
        fabImport = findViewById(R.id.fab_import);
        initialize();
    }

    private void initialize() {
        collapsingToolbar.setTitle(getString(R.string.import_url_title));
        toolbar.setNavigationOnClickListener(v -> confirmExit());
        var cb = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, cb);
        var folderTree = new ActivityResultContracts.OpenDocumentTree();
        folderPickerLauncher = registerForActivityResult(folderTree, this::onFolderPickerResult);
        var path = pathJoin(externalPath(), "DroidVM");
        inputFolder.setText(path);
        inputFolder.setIconButtonOnClickListener(() -> folderPickerLauncher.launch(null));
        inputUrl.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                // The URL changed, so any loaded info / kernel analysis is stale.
                kernelAnalysis.reset();
                if (infoLoaded) {
                    infoLoaded = false;
                    cardInfo.setVisibility(GONE);
                    setStatusIdle();
                }
            }
        });
        kernelAnalysis.setUrlProvider(() -> inputUrl.getText());
        btnLoad.setOnClickListener(v -> doLoad());
        fabImport.setOnClickListener(v -> doImport());
        setStatusIdle();
        // Restore the whole form if re-created mid-download; else just the progress bar.
        if (!restoreSession()) reattachActiveDownload();
    }

    /** Snapshot of the form, kept across activity re-creation while a download runs. */
    private static final class Session {
        String url, filename, folder;
        boolean infoLoaded;
        String infoSize, infoType;
    }

    /** The in-progress import (only one download runs at a time), or {@code null}. */
    private static Session session;

    private void captureSession() {
        var s = new Session();
        s.url = inputUrl.getText();
        s.filename = inputFilename.getText();
        s.folder = inputFolder.getText();
        s.infoLoaded = infoLoaded;
        s.infoSize = tvInfoSize.getText().toString();
        s.infoType = tvInfoType.getText().toString();
        session = s;
    }

    private boolean restoreSession() {
        var s = session;
        if (s == null) return false;
        inputUrl.setText(s.url);
        inputFilename.setText(s.filename);
        inputFolder.setText(s.folder);
        if (s.infoLoaded) {
            infoLoaded = true;
            tvInfoSize.setText(s.infoSize);
            tvInfoType.setText(s.infoType);
            cardInfo.setVisibility(VISIBLE);
        }
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Stop driving the widget; the download keeps running in the foreground
        // service (notification shade) and registers the disk itself.
        pollHandler.removeCallbacks(pollRunnable);
        executor.shutdownNow();
    }

    private void confirmExit() {
        // Leave normally (so you can navigate to other screens while downloading).
        // The download keeps running in the foreground service; the screen's state
        // is preserved and restored when re-opened.
        if (isDownloading && currentDownloadId >= 0)
            Toast.makeText(this, R.string.download_background_toast, LENGTH_SHORT).show();
        finish();
    }

    private void onFolderPickerResult(Uri uri) {
        if (uri == null) return;
        var path = resolveUriPath(this, uri);
        if (path != null) inputFolder.setText(path);
    }

    private void setStatusIdle() {
        progressLoad.setVisibility(GONE);
        tvStatus.setText(R.string.import_url_status_idle);
        tvStatus.setTextColor(resolveThemeColor(colorOnSurfaceVariant));
        btnLoad.setEnabled(true);
    }

    private void setStatusLoading() {
        progressLoad.setVisibility(VISIBLE);
        tvStatus.setText(R.string.import_url_status_loading);
        tvStatus.setTextColor(resolveThemeColor(colorOnSurfaceVariant));
        btnLoad.setEnabled(false);
    }

    private void setStatusLoaded(long contentLength) {
        progressLoad.setVisibility(GONE);
        var size = contentLength >= 0 ?
            formatSize(contentLength) :
            getString(R.string.import_url_unknown);
        tvStatus.setText(getString(R.string.import_url_status_size, size));
        tvStatus.setTextColor(resolveThemeColor(colorOnSurfaceVariant));
        btnLoad.setEnabled(true);
    }

    private void setStatusError(String msg) {
        progressLoad.setVisibility(GONE);
        tvStatus.setText(getString(R.string.import_url_status_error, msg));
        tvStatus.setTextColor(resolveThemeColor(android.R.attr.colorError));
        btnLoad.setEnabled(true);
    }

    private void doLoad() {
        if (isLoading || isDownloading) return;
        var urlStr = inputUrl.getText();
        if (urlStr.isEmpty()) {
            inputUrl.setError(getString(R.string.import_url_error_url_empty));
            return;
        }
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            inputUrl.setError(getString(R.string.import_url_error_url_invalid));
            return;
        }
        inputUrl.setError(null);
        isLoading = true;
        setStatusLoading();
        cardInfo.setVisibility(GONE);
        executor.submit(() -> {
            try {
                var conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 400)
                    throw new HttpException(code);
                long contentLength = conn.getContentLengthLong();
                var contentType = conn.getContentType();
                var disposition = conn.getHeaderField("Content-Disposition");
                var finalUrl = conn.getURL().toString();
                conn.disconnect();
                var fileName = extractFileName(disposition, finalUrl);
                runOnUiThread(() -> {
                    isLoading = false;
                    infoLoaded = true;
                    onHeadLoaded(contentLength, contentType, fileName);
                });
            } catch (Exception e) {
                Log.e(TAG, "HEAD failed", e);
                var msg = e.getMessage();
                runOnUiThread(() -> {
                    isLoading = false;
                    setStatusError(msg != null ? msg : "Unknown error");
                });
            }
        });
    }

    private void onHeadLoaded(long contentLength, String contentType, String fileName) {
        setStatusLoaded(contentLength);
        cardInfo.setVisibility(VISIBLE);
        var size = contentLength >= 0 ?
            formatSize(contentLength) :
            getString(R.string.import_url_unknown);
        tvInfoSize.setText(getString(R.string.import_url_status_size, size));
        tvInfoType.setText(getString(R.string.import_url_info_type,
            contentType != null ? contentType : "unknown"));
        var current = inputFilename.getText();
        if (fileName != null && !fileName.isEmpty() &&
            current.trim().isEmpty()) {
            inputFilename.setText(fileName);
        }
    }

    private void doImport() {
        if (isDownloading) return;
        if (DiskDownloadManager.hasActiveDownload()) {
            Toast.makeText(this, R.string.download_one_at_a_time, LENGTH_SHORT).show();
            return;
        }
        var urlStr = inputUrl.getText();
        if (urlStr.isEmpty()) {
            inputUrl.setError(getString(R.string.import_url_error_url_empty));
            return;
        }
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            inputUrl.setError(getString(R.string.import_url_error_url_invalid));
            return;
        }
        inputUrl.setError(null);
        var name = inputFilename.getText();
        if (name.isEmpty()) {
            name = extractFileName(null, urlStr);
            if (name.isEmpty()) {
                inputFilename.setError(getString(R.string.import_url_error_name_empty));
                return;
            }
            inputFilename.setText(name);
        }
        inputFilename.setError(null);
        var folder = inputFolder.getText();
        if (folder.isEmpty()) {
            inputFolder.setError(getString(R.string.import_url_error_folder_empty));
            return;
        }
        inputFolder.setError(null);
        var destPath = pathJoin(folder, name);
        if (new File(destPath).exists()) {
            inputFilename.setError(getString(R.string.import_url_error_file_exists));
            return;
        }
        downloadName = name;
        downloadFolder = folder;
        notifPermission.ensureThen(() -> startDownload(urlStr));
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
                this, url, USER_AGENT, folder, name, ImportURLActivity.class);
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
            getString(R.string.import_url_success, result.name),
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
        isDownloading = false;
        setInputsEnabled(true);
        fabImport.setVisibility(VISIBLE);
        downloadWidget.setVisibility(GONE);
    }

    private void onDownloadFailed(@Nullable String reason) {
        long id = currentDownloadId;
        currentDownloadId = -1;
        pollHandler.removeCallbacks(pollRunnable);
        if (id >= 0) DiskDownloadManager.cancel(id);
        downloadWidget.markExternalFailed(reason);
        isDownloading = false;
        setInputsEnabled(true);
        fabImport.setVisibility(VISIBLE);
    }

    private void setInputsEnabled(boolean enabled) {
        inputUrl.setEnabled(enabled);
        inputFilename.setEnabled(enabled);
        inputFolder.setEnabled(enabled);
        btnLoad.setEnabled(enabled);
    }

    private int resolveThemeColor(int attr) {
        try (var a = obtainStyledAttributes(new int[]{attr})) {
            return a.getColor(0, 0);
        }
    }
}
