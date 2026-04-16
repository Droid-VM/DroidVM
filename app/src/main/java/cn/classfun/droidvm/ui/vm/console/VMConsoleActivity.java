package cn.classfun.droidvm.ui.vm.console;

import static android.view.HapticFeedbackConstants.KEYBOARD_TAP;
import static android.view.KeyEvent.*;
import static android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT;
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

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalSessionClient;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalViewClient;

public final class VMConsoleActivity extends AppCompatActivity {
    private static final String TAG = "VMConsoleActivity";
    public static final String EXTRA_VM_ID = "vm_id";
    public static final String EXTRA_VM_NAME = "vm_name";
    public static final String EXTRA_STREAM = "stream";
    public static final String EXTRA_LOGS = "logs";
    private static final String DEFAULT_STREAM = "uart";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> saveLogLauncher;
    private TerminalView terminalView;
    private TerminalSession terminalSession;
    private boolean ctrlDown = false;
    private boolean altDown = false;
    public String vmId;
    public String vmName;
    public String streamName;

    private final TerminalSessionClient sessionClient = new SimpleTerminalSessionClient() {
        @Override
        public void onTextChanged(@NonNull TerminalSession s) {
            mainHandler.post(() -> {
                if (terminalView != null)
                    terminalView.onScreenUpdated();
            });
        }
    };

    private float currentFontSize = 5;
    private final TerminalViewClient viewClient = new SimpleTerminalViewClient() {
        @Override
        public float onScale(float scale) {
            var dampened = 1.0f + (scale - 1.0f) * 0.1f;
            currentFontSize = Math.max(2, Math.min(48, currentFontSize * dampened));
            if (terminalView != null) {
                var density = getResources().getDisplayMetrics().density;
                terminalView.setTextSize((int) (currentFontSize * density));
            }
            return dampened;
        }

        @Override
        public void onSingleTapUp(MotionEvent e) {
            var imm = getSystemService(InputMethodManager.class);
            if (imm != null && terminalView != null) {
                terminalView.requestFocus();
                imm.showSoftInput(terminalView, SHOW_IMPLICIT);
            }
        }

        @Override
        public boolean readControlKey() {
            if (ctrlDown) {
                ctrlDown = false;
                updateToggleButtons();
                return true;
            }
            return false;
        }

        @Override
        public boolean readAltKey() {
            if (altDown) {
                altDown = false;
                updateToggleButtons();
                return true;
            }
            return false;
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
        setupToolbarMenu(toolbar, R.menu.menu_vm_console, this::onMenuItemClicked);
        terminalView = findViewById(R.id.terminal_view);
        terminalView.setTerminalViewClient(viewClient);
        var consoleBin = getAssetBinaryPath("droidvm");
        var shell = findExecute("su", "/system/bin/su");
        var cwd = getFilesDir().getAbsolutePath();
        var cmd = fmt(
            logs ? "%s logs %s %s; sleep 2" : "exec %s console --raw %s %s",
            escapedString(consoleBin),
            escapedString(vmId),
            escapedString(streamName)
        );
        var args = new String[]{"su", "-c", cmd};
        var env = new String[]{
            "TERM=xterm-256color",
            "PATH=/system/bin",
            fmt("HOME=%s", cwd),
        };
        var density = getResources().getDisplayMetrics().density;
        var session = new TerminalSession(shell, cwd, args, env, null, sessionClient);
        terminalSession = session;
        terminalView.attachSession(session);
        terminalView.setTextSize((int) (currentFontSize * density));
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        terminalView.requestFocus();
        setupExtraKeys();
    }

    private boolean onMenuItemClicked(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save_log) {
            saveLogToFile();
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

    private void sendKey(int keyCode) {
        if (terminalSession != null) {
            var down = new KeyEvent(ACTION_DOWN, keyCode);
            var up = new KeyEvent(ACTION_UP, keyCode);
            terminalView.onKeyDown(keyCode, down);
            terminalView.onKeyUp(keyCode, up);
        }
    }

    private void sendChar(char ch) {
        if (terminalSession != null)
            terminalSession.write(String.valueOf(ch));
    }

    private void updateToggleButtons() {
        setToggleStyle(findViewById(R.id.btn_ctrl), ctrlDown);
        setToggleStyle(findViewById(R.id.btn_alt), altDown);
    }

    private void setToggleStyle(Button btn, boolean active) {
        if (btn == null) return;
        if (active) {
            btn.setBackgroundColor(getColor(R.color.extra_key_bg_active));
            btn.setTextColor(getColor(R.color.extra_key_text_active));
        } else {
            btn.setBackground(null);
            btn.setTextColor(getColor(R.color.extra_key_text));
        }
    }

    private void setupExtraKeys() {
        setExtraKeyClick(R.id.btn_esc, v -> sendKey(KEYCODE_ESCAPE));
        setExtraKeyClick(R.id.btn_slash, v -> sendChar('/'));
        setExtraKeyClick(R.id.btn_dash, v -> sendChar('-'));
        setExtraKeyClick(R.id.btn_home, v -> sendKey(KEYCODE_MOVE_HOME));
        setExtraKeyClick(R.id.btn_up, v -> sendKey(KEYCODE_DPAD_UP));
        setExtraKeyClick(R.id.btn_end, v -> sendKey(KEYCODE_MOVE_END));
        setExtraKeyClick(R.id.btn_pgup, v -> sendKey(KEYCODE_PAGE_UP));
        setExtraKeyClick(R.id.btn_tab, v -> sendKey(KEYCODE_TAB));
        setExtraKeyClick(R.id.btn_ctrl, v -> {
            ctrlDown = !ctrlDown;
            updateToggleButtons();
        });
        setExtraKeyClick(R.id.btn_alt, v -> {
            altDown = !altDown;
            updateToggleButtons();
        });
        setExtraKeyClick(R.id.btn_left, v -> sendKey(KEYCODE_DPAD_LEFT));
        setExtraKeyClick(R.id.btn_down, v -> sendKey(KEYCODE_DPAD_DOWN));
        setExtraKeyClick(R.id.btn_right, v -> sendKey(KEYCODE_DPAD_RIGHT));
        setExtraKeyClick(R.id.btn_pgdn, v -> sendKey(KEYCODE_PAGE_DOWN));
    }

    private void setExtraKeyClick(int id, View.OnClickListener listener) {
        findViewById(id).setOnClickListener(v -> {
            v.performHapticFeedback(KEYBOARD_TAP);
            listener.onClick(v);
        });
    }

    private void saveLogToFile() {
        var sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        saveLogLauncher.launch(fmt(
            "droidvm_console_%s_%s_%s.txt",
            vmName, streamName, sdf.format(new Date())
        ));
    }

    private void onSaveLogResult(@Nullable Uri uri) {
        if (uri == null) return;
        Consumer<Integer> showToast = resId -> runOnUiThread(() ->
            Toast.makeText(this, resId, LENGTH_SHORT).show());
        DaemonConnection.OnError err = e -> {
            Log.w(TAG, fmt("Failed to fetch log for %s stream %s", vmName, streamName), e);
            showToast.accept(R.string.vm_info_logs_no_logs);
        };
        DaemonConnection.OnUnsuccessful failed = resp ->
            err.onError(new Exception(resp.optString("message", "Unknown error")));
        DaemonConnection.OnResponse success = resp -> {
            var data = resp.optString(streamName, "");
            var text = URLDecoder.decode(data, StandardCharsets.UTF_8);
            try (var os = requireNonNull(getContentResolver().openOutputStream(uri))) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
                os.flush();
                showToast.accept(R.string.logs_save_success);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save log file", e);
                showToast.accept(R.string.vm_info_logs_save_failed);
            }
        };
        runOnPool(() -> DaemonConnection.getInstance().buildRequest("vm_console_history")
            .put("vm_id", vmId)
            .put("stream", streamName)
            .onResponse(success)
            .onUnsuccessful(failed)
            .onError(err)
            .invoke());
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
