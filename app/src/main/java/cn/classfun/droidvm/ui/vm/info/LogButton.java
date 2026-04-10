package cn.classfun.droidvm.ui.vm.info;

import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.ui.IconItemAdapter;

public final class LogButton {
    private static final String TAG = "VMInfoActivity";
    private final VMInfoActivity parent;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String pendingLogText;
    private final ActivityResultLauncher<String> saveLauncher;

    public LogButton(@NonNull VMInfoActivity parent) {
        this.parent = parent;
        saveLauncher = parent.registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"),
            this::onSaveResult
        );
    }

    void showLogsChooser() {
        if (parent.config == null) return;
        DaemonConnection.getInstance().buildRequest("vm_console_list")
            .put("vm_id", parent.vmId.toString())
            .onResponse(resp ->
                mainHandler.post(() -> buildLogsChooserDialog(resp.optJSONArray("data"))))
            .onUnsuccessful(r ->
                mainHandler.post(() -> buildLogsChooserDialog(null)))
            .onError(e -> {
                Log.w(TAG, "Failed to list consoles for logs", e);
                mainHandler.post(() -> buildLogsChooserDialog(null));
            })
            .invoke();
    }

    private void buildLogsChooserDialog(@Nullable JSONArray streams) {
        if (parent.isFinishing()) return;
        var names = new ArrayList<String>();
        if (streams != null) for (int i = 0; i < streams.length(); i++) {
            var name = streams.optString(i, "");
            if (!name.isEmpty()) names.add(name);
        }
        if (names.isEmpty()) {
            Toast.makeText(parent, R.string.vm_info_logs_no_logs, LENGTH_SHORT).show();
            return;
        }
        if (names.size() == 1) {
            fetchAndShowLog(names.get(0));
            return;
        }
        var adapter = IconItemAdapter.create(parent, names, R.drawable.ic_serial_port);
        new MaterialAlertDialogBuilder(parent)
            .setTitle(R.string.vm_info_logs_chooser_title)
            .setAdapter(adapter, (dialog, which) -> fetchAndShowLog(names.get(which)))
            .show();
    }

    private void fetchAndShowLog(@NonNull String stream) {
        DaemonConnection.OnError err = e -> {
            Log.w(TAG, fmt("Failed to fetch log for %s", stream), e);
            mainHandler.post(() ->
                Toast.makeText(parent, R.string.vm_info_logs_no_logs, LENGTH_SHORT).show());
        };
        DaemonConnection.getInstance().buildRequest("vm_console_history")
            .put("vm_id", parent.vmId.toString())
            .put("stream", stream)
            .onResponse(resp ->
                mainHandler.post(() -> showLogContentDialog(stream, resp.optString(stream, ""))))
            .onUnsuccessful(r -> err.onError(new Exception(r.optString("message", "Unknown error"))))
            .onError(err)
            .invoke();
    }

    private void showLogContentDialog(@NonNull String stream, @NonNull String logText) {
        if (parent.isFinishing()) return;
        var text = logText.trim();
        if (text.isEmpty()) text = parent.getString(R.string.vm_info_logs_no_logs);
        var scrollView = new ScrollView(parent);
        var tvLog = new TextView(parent);
        int pad = (int) (16 * parent.getResources().getDisplayMetrics().density);
        tvLog.setPadding(pad, pad, pad, pad);
        tvLog.setTextIsSelectable(true);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(12);
        tvLog.setText(text);
        scrollView.addView(tvLog);
        var finalText = text;
        new MaterialAlertDialogBuilder(parent)
            .setTitle(parent.getString(R.string.vm_info_logs_title, stream))
            .setView(scrollView)
            .setPositiveButton(R.string.vm_info_logs_copy, (d, w) -> {
                var cm = (ClipboardManager) parent.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("log", finalText));
                Toast.makeText(parent, R.string.vm_info_logs_copied, LENGTH_SHORT).show();
            })
            .setNeutralButton(R.string.vm_info_logs_save, (d, w) ->
                saveLogToFile(stream, finalText))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void saveLogToFile(@NonNull String stream, @NonNull String logText) {
        pendingLogText = logText;
        var name = parent.config != null ? parent.config.getName() : parent.vmId.toString();
        var sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        var filename = fmt("droidvm_console_%s_%s_%s.txt", name, stream, sdf.format(new Date()));
        saveLauncher.launch(filename);
    }

    private void onSaveResult(Uri uri) {
        if (uri == null || pendingLogText == null) return;
        try (
            var os = parent.getContentResolver().openOutputStream(uri);
            var writer = new BufferedWriter(new OutputStreamWriter(os))
        ) {
            writer.write(pendingLogText);
            writer.flush();
            Toast.makeText(parent, R.string.logs_save_success, LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save log file", e);
            Toast.makeText(parent, R.string.vm_info_logs_save_failed, LENGTH_SHORT).show();
        } finally {
            pendingLogText = null;
        }
    }
}
