package cn.classfun.droidvm.ui.widgets.tools;

import static cn.classfun.droidvm.lib.utils.NetUtils.USER_AGENT;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.formatDuration;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.utils.NetUtils.HttpException;

public final class DownloadWidget extends FrameLayout {
    private static final String TAG = "DownloadWidget";
    private static final int UI_UPDATE_INTERVAL_MS = 300;
    private final Context context;
    private TextView tvFilename, tvPercent, tvSpeed, tvSize, tvEta;
    private LinearProgressIndicator progressBar;
    private MaterialButton btnCancel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private String url;
    private String destPath;
    private String fileName;
    private String userAgent = USER_AGENT;
    private volatile int state = STATE_IDLE;
    public static final int STATE_IDLE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_DOWNLOADING = 2;
    public static final int STATE_FINISHED = 3;
    public static final int STATE_FAILED = 4;
    public static final int STATE_CANCELLED = 5;
    private long totalBytes = -1;
    private long downloadedBytes = 0;
    private long speedBytesPerSec = 0;
    private long lastUpdateTime = 0;
    private long lastUpdateBytes = 0;
    private OnDownloadListener listener;

    @SuppressWarnings({"EmptyMethod", "unused"})
    public interface OnDownloadListener {
        default void onStateChanged(DownloadWidget widget, int state) {
        }

        default void onProgress(DownloadWidget widget, long downloaded, long total) {
        }

        default void onFinished(DownloadWidget widget, File file) {
        }

        default void onFailed(DownloadWidget widget, String error) {
        }

        default void onCancelled(DownloadWidget widget) {
        }
    }

    public DownloadWidget(@NonNull Context context) {
        super(context);
        this.context = context;
        init();
    }

    public DownloadWidget(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        this.context = context;
        init();
    }

    public DownloadWidget(@NonNull Context context, @Nullable AttributeSet attrs, int attr) {
        super(context, attrs, attr);
        this.context = context;
        init();
    }

    private void init() {
        var inf = LayoutInflater.from(context);
        inf.inflate(R.layout.widget_download, this, true);
        tvFilename = findViewById(R.id.tv_filename);
        tvPercent = findViewById(R.id.tv_percent);
        tvSpeed = findViewById(R.id.tv_speed);
        tvSize = findViewById(R.id.tv_size);
        tvEta = findViewById(R.id.tv_eta);
        progressBar = findViewById(R.id.progress_bar);
        btnCancel = findViewById(R.id.btn_cancel);
        btnCancel.setOnClickListener(v -> showCancelConfirmation());
        setIdle();
    }

    public void setOnDownloadListener(OnDownloadListener listener) {
        this.listener = listener;
    }

    public void start(@NonNull String url, @NonNull String destPath) {
        if (state == STATE_CONNECTING || state == STATE_DOWNLOADING) return;
        this.url = url;
        this.destPath = destPath;
        this.fileName = basename(destPath);
        this.cancelled.set(false);
        this.downloadedBytes = 0;
        this.totalBytes = -1;
        this.speedBytesPerSec = 0;
        this.lastUpdateTime = 0;
        this.lastUpdateBytes = 0;
        tvFilename.setText(fileName);
        setState(STATE_CONNECTING);
        executor.submit(this::doDownload);
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isRunning() {
        return state == STATE_CONNECTING || state == STATE_DOWNLOADING;
    }

    public void stopAndCleanup() {
        if (!isRunning()) return;
        cancelled.set(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (isRunning()) {
            cancelled.set(true);
        }
        executor.shutdownNow();
    }

    private void showCancelConfirmation() {
        var ctx = getContext();
        if (state != STATE_CONNECTING && state != STATE_DOWNLOADING) return;
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.download_cancel_title)
            .setMessage(ctx.getString(R.string.download_cancel_message, fileName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (d, w) -> doCancel())
            .show();
    }

    private void doCancel() {
        cancelled.set(true);
    }

    private void doDownload() {
        InputStream in = null;
        FileOutputStream out = null;
        HttpURLConnection conn = null;
        var destFile = new File(destPath);
        try {
            var parent = destFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs())
                Log.w(TAG, fmt("Failed to create parent directories: %s", parent.getAbsolutePath()));
            Log.i(TAG, fmt("Starting download: %s", url));
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", userAgent);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400)
                throw new HttpException(code);
            totalBytes = conn.getContentLengthLong();
            handler.post(() -> setState(STATE_DOWNLOADING));
            var contType = conn.getContentType();
            if (contType != null && contType.contains("text/html"))
                throw new RuntimeException("Download URL points to an HTML page, not a file");
            in = conn.getInputStream();
            out = new FileOutputStream(destFile);
            var buffer = new byte[65536];
            int bytesRead;
            lastUpdateTime = System.currentTimeMillis();
            lastUpdateBytes = 0;
            while ((bytesRead = in.read(buffer)) != -1) {
                if (cancelled.get()) {
                    handler.post(() -> {
                        setState(STATE_CANCELLED);
                        if (listener != null) listener.onCancelled(this);
                    });
                    closeQuietly(out);
                    out = null;
                    if (destFile.exists() && !destFile.delete()) Log.w(TAG, fmt(
                        "Failed to delete incomplete file: %s",
                        destFile.getAbsolutePath()
                    ));
                    return;
                }
                out.write(buffer, 0, bytesRead);
                downloadedBytes += bytesRead;
                long now = System.currentTimeMillis();
                if (now - lastUpdateTime >= UI_UPDATE_INTERVAL_MS) {
                    long elapsed = now - lastUpdateTime;
                    long deltaBytes = downloadedBytes - lastUpdateBytes;
                    speedBytesPerSec = elapsed > 0 ? (deltaBytes * 1000 / elapsed) : 0;
                    lastUpdateTime = now;
                    lastUpdateBytes = downloadedBytes;
                    final long dl = downloadedBytes;
                    final long total = totalBytes;
                    final long speed = speedBytesPerSec;
                    handler.post(() -> updateUI(dl, total, speed));
                }
            }
            out.flush();
            closeQuietly(out);
            out = null;
            handler.post(() -> {
                setState(STATE_FINISHED);
                if (listener != null) listener.onFinished(this, destFile);
            });
        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            String msg = e.getMessage();
            closeQuietly(out);
            out = null;
            if (destFile.exists() && !destFile.delete()) Log.w(TAG, fmt(
                "Failed to delete incomplete file: %s",
                destFile.getAbsolutePath()
            ));
            handler.post(() -> {
                setFailed(msg != null ? msg : "Unknown error");
                if (listener != null) listener.onFailed(this, msg);
            });
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (conn != null) conn.disconnect();
        }
    }

    private void setIdle() {
        tvFilename.setText("");
        tvPercent.setText("");
        tvSpeed.setText("");
        tvSize.setText("");
        tvEta.setText("");
        progressBar.setProgress(0);
        progressBar.setIndeterminate(false);
        btnCancel.setVisibility(GONE);
    }

    private void setState(int newState) {
        state = newState;
        var ctx = getContext();
        switch (newState) {
            case STATE_CONNECTING:
                progressBar.setIndeterminate(true);
                tvPercent.setText(R.string.download_connecting);
                tvSpeed.setText("");
                tvSize.setText("");
                tvEta.setText("");
                btnCancel.setVisibility(VISIBLE);
                btnCancel.setEnabled(true);
                break;

            case STATE_DOWNLOADING:
                progressBar.setIndeterminate(false);
                updateUI(downloadedBytes, totalBytes, speedBytesPerSec);
                btnCancel.setVisibility(VISIBLE);
                btnCancel.setEnabled(true);
                break;

            case STATE_FINISHED:
                progressBar.setIndeterminate(false);
                progressBar.setProgress(1000);
                tvPercent.setText(ctx.getString(
                    R.string.download_percent, 100.0f));
                tvSpeed.setText("");
                tvEta.setText(R.string.download_finished);
                btnCancel.setVisibility(GONE);
                break;

            case STATE_CANCELLED:
                progressBar.setIndeterminate(false);
                tvSpeed.setText("");
                tvEta.setText(R.string.download_cancelled);
                btnCancel.setVisibility(GONE);
                break;

            case STATE_FAILED:
                break;
        }
        if (listener != null) listener.onStateChanged(this, newState);
    }

    private void setFailed(String msg) {
        state = STATE_FAILED;
        progressBar.setIndeterminate(false);
        tvPercent.setText("");
        tvSpeed.setText("");
        tvSize.setText("");
        tvEta.setText(getContext().getString(R.string.download_failed, msg));
        btnCancel.setVisibility(GONE);
        if (listener != null) listener.onStateChanged(this, STATE_FAILED);
    }

    private void updateUI(long downloaded, long total, long speed) {
        var ctx = getContext();
        if (total > 0) {
            int progress = (int) (downloaded * 1000 / total);
            progressBar.setProgress(progress);
            float percent = downloaded * 100f / total;
            tvPercent.setText(ctx.getString(R.string.download_percent, percent));
        } else {
            progressBar.setIndeterminate(true);
            tvPercent.setText("");
        }
        if (speed > 0) {
            tvSpeed.setText(ctx.getString(
                R.string.download_speed,
                formatSize(speed)
            ));
        } else {
            tvSpeed.setText("");
        }
        if (total > 0) {
            tvSize.setText(ctx.getString(
                R.string.download_size_progress,
                formatSize(downloaded),
                formatSize(total)
            ));
        } else {
            tvSize.setText(ctx.getString(
                R.string.download_size_unknown,
                formatSize(downloaded)
            ));
        }
        if (total > 0 && speed > 0) {
            long remaining = total - downloaded;
            long etaSec = remaining / speed;
            tvEta.setText(ctx.getString(
                R.string.download_eta,
                formatDuration(etaSec)
            ));
        } else {
            tvEta.setText(ctx.getString(
                R.string.download_eta,
                ctx.getString(R.string.download_eta_unknown)
            ));
        }
        if (listener != null) listener.onProgress(this, downloaded, total);
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
