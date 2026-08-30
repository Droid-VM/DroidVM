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
    private static final String DEFAULT_STREAM = "uart";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> saveLogLauncher;
    private TerminalPanelView terminalPanel;
    private TerminalSession terminalSession;
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
        var logs = intent.getBooleanExtra(EXTRA_LOGS, false);
        if (vmId == null) vmId = "";
        if (vmName == null) vmName = "";
        if (streamName == null || streamName.isEmpty()) streamName = DEFAULT_STREAM;
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(fmt("%s - %s", vmName, streamName));
        toolbar.setNavigationOnClickListener(v -> finish());
        var item = setupToolbarMenu(toolbar, R.menu.menu_vm_console, this::onMenuItemClicked);
        item.setIconTintList(ColorStateList.valueOf(Color.WHITE));
        item.setIconTintMode(PorterDuff.Mode.SRC_IN);
        var consoleBin = getAssetBinaryPath("droidvm");
        var shell = findExecute("su", "/system/bin/su");
        var cwd = getFilesDir().getAbsolutePath();
        var cmd = fmt(
            logs ? "%s logs %s %s; sleep 2" : "exec %s console --raw %s %s",
            escapedString(consoleBin),
        terminalPanel = findViewById(R.id.terminal_panel);
        terminalPanel.setInteractive(true);
            escapedString(vmId),
            escapedString(streamName)
        );
        var args = new String[]{"su", "-c", cmd};
        var env = new String[]{
            "TERM=xterm-256color",
            "PATH=/system/bin",
            fmt("HOME=%s", cwd),
        };
        currentFontSize = loadFontSize();
        var density = getResources().getDisplayMetrics().density;
        var session = new TerminalSession(shell, cwd, args, env, null, sessionClient);
        terminalSession = session;
        terminalView.setTextSize((int) (currentFontSize * density));
        TerminalFonts.apply(terminalView);
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        terminalView.requestFocus();
        setupExtraKeys();
        terminalPanel.attachSession(session);
        terminalPanel.clearSession(terminalSession);
    }

    private boolean onMenuItemClicked(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save_log) {
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
        if (terminalSession != null) try {
            if (terminalSession.isRunning())
                shellKillProcess(terminalSession.getPid(), SIGHUP);
        } catch (Exception ignored) {
        }
        terminalSession = null;
    }

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
