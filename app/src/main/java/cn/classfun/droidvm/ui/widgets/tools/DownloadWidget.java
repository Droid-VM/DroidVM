package cn.classfun.droidvm.ui.widgets.tools;

import static cn.classfun.droidvm.lib.utils.StringUtils.formatDuration;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import cn.classfun.droidvm.R;

/**
 * A progress view for a download owned elsewhere (e.g. {@link android.app.DownloadManager}).
 * It renders only — it never runs a download itself. Drive it from the main thread:
 * call {@link #startExternal} once, then feed it {@link #updateExternal} and the
 * {@code markExternal*} state transitions as the download progresses. The cancel
 * button invokes the handler passed to {@link #startExternal}.
 */
public final class DownloadWidget extends FrameLayout {
    private static final int UI_UPDATE_INTERVAL_MS = 300;
    private final Context context;
    private TextView tvFilename, tvPercent, tvSpeed, tvSize, tvEta;
    private LinearProgressIndicator progressBar;
    private MaterialButton btnCancel;
    private String fileName;
    private int state = STATE_IDLE;
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
    private Runnable cancelHandler;

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

    /**
     * Begins showing progress for a download owned elsewhere. The cancel button
     * invokes {@code onCancel}.
     */
    public void startExternal(@NonNull String fileName, @NonNull Runnable onCancel) {
        this.cancelHandler = onCancel;
        this.fileName = fileName;
        this.downloadedBytes = 0;
        this.totalBytes = -1;
        this.speedBytesPerSec = 0;
        this.lastUpdateTime = 0;
        this.lastUpdateBytes = 0;
        tvFilename.setText(fileName);
        setState(STATE_CONNECTING);
    }

    /** Feeds the latest byte counts from the external download into the UI. */
    public void updateExternal(long downloaded, long total) {
        if (state == STATE_FINISHED || state == STATE_FAILED || state == STATE_CANCELLED) return;
        if (state != STATE_DOWNLOADING) setState(STATE_DOWNLOADING);
        totalBytes = total;
        long now = System.currentTimeMillis();
        if (lastUpdateTime == 0) {
            lastUpdateTime = now;
            lastUpdateBytes = downloaded;
        }
        long elapsed = now - lastUpdateTime;
        if (elapsed >= UI_UPDATE_INTERVAL_MS) {
            long deltaBytes = downloaded - lastUpdateBytes;
            speedBytesPerSec = elapsed > 0 ? (deltaBytes * 1000 / elapsed) : 0;
            lastUpdateTime = now;
            lastUpdateBytes = downloaded;
        }
        downloadedBytes = downloaded;
        updateUI(downloaded, total, speedBytesPerSec);
    }

    /** Renders the download as paused, showing why (waiting for network, retrying, …). */
    public void markExternalPaused(@NonNull String reasonText) {
        if (state == STATE_FINISHED) return;
        progressBar.setIndeterminate(false);
        tvSpeed.setText("");
        tvEta.setText(reasonText);
    }

    public void markExternalFinished() {
        setState(STATE_FINISHED);
    }

    public void markExternalFailed(@Nullable String msg) {
        setFailed(msg != null ? msg : "Unknown error");
    }

    public void markExternalCancelled() {
        setState(STATE_CANCELLED);
    }

    private void showCancelConfirmation() {
        var ctx = getContext();
        if (state != STATE_CONNECTING && state != STATE_DOWNLOADING) return;
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.download_cancel_title)
            .setMessage(ctx.getString(R.string.download_cancel_message, fileName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                if (cancelHandler != null) cancelHandler.run();
            })
            .show();
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
    }

    private void setFailed(String msg) {
        state = STATE_FAILED;
        progressBar.setIndeterminate(false);
        tvPercent.setText("");
        tvSpeed.setText("");
        tvSize.setText("");
        tvEta.setText(getContext().getString(R.string.download_failed, msg));
        btnCancel.setVisibility(GONE);
    }

    private void updateUI(long downloaded, long total, long speed) {
        var ctx = getContext();
        if (total > 0) {
            // Material's setProgress() is ignored while indeterminate, so leave that
            // mode explicitly (e.g. after a retry where the size was briefly unknown).
            progressBar.setIndeterminate(false);
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
    }
}
