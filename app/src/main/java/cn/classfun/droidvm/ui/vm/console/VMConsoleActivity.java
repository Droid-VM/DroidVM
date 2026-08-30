// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.console;

import static android.widget.Toast.LENGTH_SHORT;
import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.ui.MaterialMenu.setupToolbarMenu;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getAssetBinaryPath;
import static cn.classfun.droidvm.lib.utils.FileUtils.findExecute;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.SIGHUP;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.shellKillProcess;
import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.getEditText;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalSessionClient;
import cn.classfun.droidvm.lib.ui.termux.TerminalPanelView;
import cn.classfun.droidvm.lib.utils.ShareUtils;

public final class VMConsoleActivity extends AppCompatActivity {
    private static final String TAG = "VMConsoleActivity";
    public static final String EXTRA_VM_ID = "vm_id";
    public static final String EXTRA_VM_NAME = "vm_name";
    public static final String EXTRA_STREAM = "stream";
    public static final String EXTRA_LOGS = "logs";
    /** Initial value of the filter; empty or absent opens the page unfiltered. */
    public static final String EXTRA_FILTER = "filter";
    // Every backend registers stdio; "uart" only exists on the QEMU backend these days, and
    // the crosvm serial streams are named per port (serialN/sbsaN/vconN) so none is a safe
    // universal fallback.
    private static final String DEFAULT_STREAM = "stdio";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> saveLogLauncher;
    private MaterialToolbar toolbar;
    private TerminalPanelView terminalPanel;
    private TerminalSession terminalSession;
    /** True for the history dump, false for the live console. Decides the command either way. */
    private boolean logsMode = false;
    /** The text every shown line must contain; empty is no filter. Never null. */
    private String filter = "";
    public String vmId;
    public String vmName;
    public String streamName;

    private final TerminalSessionClient sessionClient = new SimpleTerminalSessionClient(this) {
        @Override
        public void onTextChanged(@NonNull TerminalSession s) {
            mainHandler.post(() -> {
                if (terminalPanel != null) terminalPanel.refresh();
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vm_console);
        var contract = new ActivityResultContracts.CreateDocument("text/plain");
        saveLogLauncher = registerForActivityResult(contract, this::onSaveLogResult);
        var intent = getIntent();
        vmId = intent.getStringExtra(EXTRA_VM_ID);
        vmName = intent.getStringExtra(EXTRA_VM_NAME);
        streamName = intent.getStringExtra(EXTRA_STREAM);
        logsMode = intent.getBooleanExtra(EXTRA_LOGS, false);
        filter = intent.getStringExtra(EXTRA_FILTER);
        if (vmId == null) vmId = "";
        if (vmName == null) vmName = "";
        if (filter == null) filter = "";
        if (streamName == null || streamName.isEmpty()) streamName = DEFAULT_STREAM;
        toolbar = findViewById(R.id.toolbar);
        updateTitle();
        toolbar.setNavigationOnClickListener(v -> finish());
        var item = setupToolbarMenu(toolbar, R.menu.menu_vm_console, this::onMenuItemClicked);
        item.setIconTintList(ColorStateList.valueOf(Color.WHITE));
        item.setIconTintMode(PorterDuff.Mode.SRC_IN);
        terminalPanel = findViewById(R.id.terminal_panel);
        terminalPanel.setInteractive(true);
        startSession();
    }

    /**
     * The shell line the terminal runs, for the current mode and filter.
     *
     * <p>Filtering is a pipe because the page has nothing else to filter: what is on screen is a
     * pty a subprocess writes to, not a buffer this activity holds. {@code grep -F} is toybox's,
     * and {@code --} keeps a filter that starts with a dash from being read as an option.</p>
     *
     * <p>No {@code --line-buffered} on the live path. toybox 0.8.12-android does accept the
     * option, but its grep already flushes each matching line as it produces it -- measured on
     * device, a matching line was in a redirected file two seconds into a producer that had not
     * exited, with and without the option -- so it would buy nothing here while breaking the page
     * outright on any toybox whose grep lacks it, since an unknown long option is exit 2 rather
     * than a warning.</p>
     */
    @NonNull
    private String buildCommand() {
        var base = fmt(
            logsMode ? "%s logs %s %s" : "%s console --raw %s %s",
            escapedString(getAssetBinaryPath("droidvm")),
            escapedString(vmId),
            escapedString(streamName)
        );
        if (!filter.isEmpty())
            base = fmt("%s | grep -F -- %s", base, escapedString(filter));
        // The dump's `sleep 2` keeps the last lines on screen after the command ends. The live
        // path replaces the shell instead, which it can only do while there is no pipeline for
        // the shell to wait on.
        if (logsMode) return fmt("%s; sleep 2", base);
        return filter.isEmpty() ? fmt("exec %s", base) : base;
    }

    private void startSession() {
        stopSession();
        var shell = findExecute("su", "/system/bin/su");
        var cwd = getFilesDir().getAbsolutePath();
        var args = new String[]{"su", "-c", buildCommand()};
        var env = new String[]{
            "TERM=xterm-256color",
            "PATH=/system/bin",
            fmt("HOME=%s", cwd),
        };
        var session = new TerminalSession(shell, cwd, args, env, null, sessionClient);
        terminalSession = session;
        terminalPanel.attachSession(session);
    }

    private void stopSession() {
        if (terminalSession == null) return;
        try {
            if (terminalSession.isRunning())
                shellKillProcess(terminalSession.getPid(), SIGHUP);
        } catch (Exception ignored) {
        }
        terminalSession.finishIfRunning();
        terminalPanel.clearSession(terminalSession);
        terminalSession = null;
    }

    private void updateTitle() {
        toolbar.setTitle(filter.isEmpty()
            ? fmt("%s - %s", vmName, streamName)
            : getString(R.string.logs_title_filtered, vmName, streamName, filter));
    }

    /**
     * Applies a new filter, which means running the command again -- for the dump that re-reads
     * the same history, for the live console it is a reconnect and the backlog it had on screen
     * is not read again.
     */
    private void applyFilter(@NonNull String value) {
        if (value.equals(filter)) return;
        filter = value;
        updateTitle();
        startSession();
    }

    private void showFilterDialog() {
        var view = getLayoutInflater().inflate(R.layout.dialog_console_filter, null);
        TextInputEditText etFilter = view.findViewById(R.id.et_console_filter);
        etFilter.setText(filter);
        etFilter.setSelection(filter.length());
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs_filter_title)
            .setMessage(R.string.logs_filter_message)
            .setView(view)
            .setPositiveButton(android.R.string.ok, (d, w) -> applyFilter(getEditText(etFilter)))
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.logs_filter_show_all, (d, w) -> applyFilter(""))
            .show();
    }

    private boolean onMenuItemClicked(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_filter) {
            showFilterDialog();
            return true;
        } else if (id == R.id.action_save_log) {
            saveLogToFile();
            return true;
        } else if (id == R.id.action_share_log) {
            shareLog();
            return true;
        } else if (id == R.id.action_clear_log) {
            clearLog();
            return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSession();
    }

    private void saveLogToFile() {
        var sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        saveLogLauncher.launch(fmt(
            "droidvm_console_%s_%s_%s.txt",
            vmName, streamName, sdf.format(new Date())
        ));
    }

    /** Fetch the full console history via IPC; {@code onText} receives the decoded text. */
    private void fetchConsoleText(Consumer<String> onText) {
        DaemonConnection.OnError err = e -> {
            Log.w(TAG, fmt("Failed to fetch log for %s stream %s", vmName, streamName), e);
            runOnUiThread(() ->
                Toast.makeText(this, R.string.vm_info_logs_no_logs, LENGTH_SHORT).show());
        };
        DaemonConnection.OnUnsuccessful failed = resp ->
            err.onError(new Exception(resp.optString("message", "Unknown error")));
        DaemonConnection.OnResponse success = resp -> {
            var data = resp.optString(streamName, "");
            onText.accept(URLDecoder.decode(data, StandardCharsets.UTF_8));
        };
        runOnPool(() -> DaemonConnection.getInstance().buildRequest("vm_console_history")
            .put("vm_id", vmId)
            .put("stream", streamName)
            .onResponse(success)
            .onUnsuccessful(failed)
            .onError(err)
            .invoke());
    }

    private void onSaveLogResult(@Nullable Uri uri) {
        if (uri == null) return;
        Consumer<Integer> showToast = resId -> runOnUiThread(() ->
            Toast.makeText(this, resId, LENGTH_SHORT).show());
        fetchConsoleText(text -> {
            try (var os = requireNonNull(getContentResolver().openOutputStream(uri))) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
                os.flush();
                showToast.accept(R.string.logs_save_success);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save log file", e);
                showToast.accept(R.string.vm_info_logs_save_failed);
            }
        });
    }

    private void shareLog() {
        var sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        var filename = fmt(
            "droidvm_console_%s_%s_%s.txt",
            vmName, streamName, sdf.format(new Date())
        );
        fetchConsoleText(text -> ShareUtils.shareTextAsFile(
            this,
            filename,
            text,
            getString(R.string.logs_share_title),
            msg -> runOnUiThread(() -> Toast.makeText(
                this,
                fmt(getString(R.string.logs_share_failed), msg),
                Toast.LENGTH_LONG
            ).show())
        ));
    }

    private void clearLog() {
        runOnPool(() -> DaemonConnection.getInstance().buildRequest("vm_console_clear")
            .put("vm_id", vmId)
            .put("stream", streamName)
            .invoke());
        if (terminalSession != null) {
            var emulator = terminalSession.getEmulator();
            var reset = "\033c\033]104\07\033[!p\033[?3;4l\033[4l\033>\033[?69l\r";
            var resetBytes = reset.getBytes(StandardCharsets.UTF_8);
            emulator.append(resetBytes, resetBytes.length);
        }
    }
}
