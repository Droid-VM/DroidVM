package cn.classfun.droidvm.ui.vm.console;

import static android.view.HapticFeedbackConstants.KEYBOARD_TAP;
import static android.view.KeyEvent.*;
import static android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getAssetBinaryPath;
import static cn.classfun.droidvm.lib.utils.FileUtils.findExecute;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.shellKillProcess;
import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalSessionClient;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalViewClient;

public final class VMConsoleActivity extends AppCompatActivity {
    public static final String EXTRA_VM_ID = "vm_id";
    public static final String EXTRA_VM_NAME = "vm_name";
    public static final String EXTRA_STREAM = "stream";
    private static final String DEFAULT_STREAM = "uart";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TerminalView terminalView;
    private TerminalSession terminalSession;
    private boolean ctrlDown = false;
    private boolean altDown = false;

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
        var intent = getIntent();
        var vmId = intent.getStringExtra(EXTRA_VM_ID);
        var vmName = intent.getStringExtra(EXTRA_VM_NAME);
        var streamName = intent.getStringExtra(EXTRA_STREAM);
        if (vmId == null) vmId = "";
        if (vmName == null) vmName = "";
        if (streamName == null || streamName.isEmpty()) streamName = DEFAULT_STREAM;
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(fmt("%s - %s", vmName, streamName));
        toolbar.setNavigationOnClickListener(v -> finish());
        terminalView = findViewById(R.id.terminal_view);
        terminalView.setTerminalViewClient(viewClient);
        var consoleBin = getAssetBinaryPath("droidvm");
        var shell = findExecute("su", "/system/bin/su");
        var cwd = getFilesDir().getAbsolutePath();
        var cmd = fmt(
            "%s console %s %s",
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (terminalSession != null) try {
            if (terminalSession.isRunning())
                shellKillProcess(terminalSession.getPid());
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
}
