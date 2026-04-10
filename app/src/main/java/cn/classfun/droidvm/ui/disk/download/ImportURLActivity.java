package cn.classfun.droidvm.ui.disk.download;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;
import static com.google.android.material.R.attr.colorOnSurfaceVariant;
import static cn.classfun.droidvm.lib.utils.FileUtils.externalPath;
import static cn.classfun.droidvm.lib.utils.NetUtils.extractFileName;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;
import static cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity.startOptimize;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.SimpleTextWatcher;
import cn.classfun.droidvm.lib.utils.NetUtils.HttpException;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;
import cn.classfun.droidvm.ui.widgets.tools.DownloadWidget;
import cn.classfun.droidvm.ui.widgets.tools.DownloadWidget.OnDownloadListener;
import cn.classfun.droidvm.ui.widgets.row.TextInputRowWidget;

public final class ImportURLActivity extends AppCompatActivity implements OnDownloadListener {
    private static final String TAG = "ImportURL";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextInputRowWidget inputUrl, inputFilename, inputFolder;
    private TextView tvStatus;
    private CircularProgressIndicator progressLoad;
    private MaterialButton btnLoad;
    private MaterialCardView cardInfo;
    private TextView tvInfoSize, tvInfoType;
    private DownloadWidget downloadWidget;
    private NestedScrollView scrollView;
    private ExtendedFloatingActionButton fabImport;
    private CollapsingToolbarLayout collapsingToolbar;
    private MaterialToolbar toolbar;
    private boolean isLoading = false;
    private boolean infoLoaded = false;
    private boolean isDownloading = false;
    private String downloadName = null;
    private String downloadFolder = null;
    private ActivityResultLauncher<Uri> folderPickerLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_url);
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
                if (infoLoaded) {
                    infoLoaded = false;
                    cardInfo.setVisibility(GONE);
                    setStatusIdle();
                }
            }
        });
        btnLoad.setOnClickListener(v -> doLoad());
        fabImport.setOnClickListener(v -> doImport());
        setStatusIdle();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (downloadWidget != null)
            downloadWidget.stopAndCleanup();
        executor.shutdownNow();
    }

    private void confirmExit() {
        if (isDownloading && downloadWidget != null && downloadWidget.isRunning()) {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.download_exit_title)
                .setMessage(R.string.download_exit_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                .show();
        } else {
            finish();
        }
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
        startDownload(urlStr, destPath);
    }

    private void startDownload(String url, String destPath) {
        isDownloading = true;
        setInputsEnabled(false);
        fabImport.setVisibility(GONE);
        downloadWidget.setVisibility(VISIBLE);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        downloadWidget.setOnDownloadListener(this);
        downloadWidget.start(url, destPath);
    }

    @Override
    public void onFinished(DownloadWidget widget, File file) {
        var config = new DiskConfig();
        config.setName(downloadName);
        config.item.set("folder", downloadFolder);
        Runnable done = () -> {
            var resultData = new Intent();
            resultData.putExtra("result_disk_path", config.getFullPath());
            setResult(RESULT_OK, resultData);
            if (config.getFormat() == DiskFormat.QCOW2)
                startOptimize(this, config.getId());
            finish();
        };
        runOnPool(() -> {
            var store = new DiskStore();
            store.load(this);
            store.add(config);
            store.save(this);
            runOnUiThread(done);
        });
        Toast.makeText(
            this,
            getString(R.string.import_url_success, downloadName),
            LENGTH_SHORT
        ).show();
    }

    @Override
    public void onFailed(DownloadWidget widget, String error) {
        isDownloading = false;
        setInputsEnabled(true);
        fabImport.setVisibility(VISIBLE);
    }

    @Override
    public void onCancelled(DownloadWidget widget) {
        isDownloading = false;
        setInputsEnabled(true);
        fabImport.setVisibility(VISIBLE);
        downloadWidget.setVisibility(GONE);
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
