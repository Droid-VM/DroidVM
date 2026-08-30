// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.ui.termux;

import static android.content.Context.MODE_PRIVATE;
import static android.view.HapticFeedbackConstants.KEYBOARD_TAP;
import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static android.view.KeyEvent.KEYCODE_ESCAPE;
import static android.view.KeyEvent.KEYCODE_MOVE_END;
import static android.view.KeyEvent.KEYCODE_MOVE_HOME;
import static android.view.KeyEvent.KEYCODE_PAGE_DOWN;
import static android.view.KeyEvent.KEYCODE_PAGE_UP;
import static android.view.KeyEvent.KEYCODE_TAB;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import cn.classfun.droidvm.R;

/** Shared terminal presentation: font, zoom, soft keyboard and extra keys. */
public final class TerminalPanelView extends LinearLayout {
    private static final String PREFS_NAME = "droidvm_prefs";
    private static final String KEY_FONT_SIZE = "console_font_size";
    private static final float MIN_FONT_SIZE = 2;
    private static final float MAX_FONT_SIZE = 48;
    private static final float DEFAULT_FONT_SIZE = 5;

    private final TerminalView terminalView;
    private final View extraKeysRow1;
    private final View extraKeysRow2;
    private TerminalSession terminalSession;
    private float currentFontSize;
    private boolean interactive = false;
    private boolean ctrlDown = false;
    private boolean altDown = false;

    private final TerminalViewClient viewClient = new SimpleTerminalViewClient() {
        @Override
        public float onScale(float scale) {
            var dampened = 1.0f + (scale - 1.0f) * 0.1f;
            currentFontSize = clampFontSize(currentFontSize * dampened);
            applyFontSize();
            return dampened;
        }

        @Override
        public void onSingleTapUp(MotionEvent e) {
            if (!interactive) return;
            var imm = getContext().getSystemService(InputMethodManager.class);
            if (imm == null) return;
            terminalView.requestFocus();
            imm.showSoftInput(terminalView, 0);
        }

        @Override
        public boolean readControlKey() {
            if (!ctrlDown) return false;
            ctrlDown = false;
            updateToggleButtons();
            return true;
        }

        @Override
        public boolean readAltKey() {
            if (!altDown) return false;
            altDown = false;
            updateToggleButtons();
            return true;
        }
    };

    public TerminalPanelView(@NonNull Context context) {
        this(context, null);
    }

    public TerminalPanelView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TerminalPanelView(
        @NonNull Context context,
        @Nullable AttributeSet attrs,
        int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setBackgroundColor(context.getColor(android.R.color.black));
        LayoutInflater.from(context).inflate(R.layout.view_terminal_panel, this, true);
        terminalView = findViewById(R.id.terminal_view);
        extraKeysRow1 = findViewById(R.id.extra_keys_row1);
        extraKeysRow2 = findViewById(R.id.extra_keys_row2);
        terminalView.setTerminalViewClient(viewClient);
        currentFontSize = loadFontSize();
        applyFontSize();
        TerminalFonts.apply(terminalView);
        setupExtraKeys();
        applyInteractionState();
    }

    /** Attaches a session owned and stopped by the host activity. */
    public void attachSession(@NonNull TerminalSession session) {
        terminalSession = session;
        terminalView.attachSession(session);
    }

    /** Drops the input reference when the host stops the attached session. */
    public void clearSession(@Nullable TerminalSession session) {
        if (terminalSession == session) terminalSession = null;
    }

    /** Allows input and reveals the terminal extra-key rows. Zoom always remains available. */
    public void setInteractive(boolean value) {
        if (interactive == value) return;
        interactive = value;
        if (!interactive) {
            ctrlDown = false;
            altDown = false;
        }
        applyInteractionState();
    }

    public void refresh() {
        terminalView.onScreenUpdated();
    }

    private void applyInteractionState() {
        int visibility = interactive ? VISIBLE : GONE;
        extraKeysRow1.setVisibility(visibility);
        extraKeysRow2.setVisibility(visibility);
        terminalView.setFocusable(interactive);
        terminalView.setFocusableInTouchMode(interactive);
        if (interactive) terminalView.requestFocus();
        updateToggleButtons();
    }

    private float loadFontSize() {
        var saved = getContext().getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE);
        return clampFontSize(saved);
    }

    private static float clampFontSize(float value) {
        return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, value));
    }

    private void applyFontSize() {
        var density = getResources().getDisplayMetrics().density;
        terminalView.setTextSize((int) (currentFontSize * density));
    }

    private void saveFontSize() {
        getContext().getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putFloat(KEY_FONT_SIZE, currentFontSize)
            .apply();
    }

    @Override
    protected void onDetachedFromWindow() {
        saveFontSize();
        super.onDetachedFromWindow();
    }

    private void sendKey(int keyCode) {
        if (!interactive || terminalSession == null) return;
        var down = new KeyEvent(ACTION_DOWN, keyCode);
        var up = new KeyEvent(ACTION_UP, keyCode);
        terminalView.onKeyDown(keyCode, down);
        terminalView.onKeyUp(keyCode, up);
    }

    private void sendChar(char ch) {
        if (interactive && terminalSession != null)
            terminalSession.write(String.valueOf(ch));
    }

    private void updateToggleButtons() {
        setToggleStyle(findViewById(R.id.btn_ctrl), ctrlDown);
        setToggleStyle(findViewById(R.id.btn_alt), altDown);
    }

    private void setToggleStyle(Button button, boolean active) {
        if (active) {
            button.setBackgroundColor(getContext().getColor(R.color.extra_key_bg_active));
            button.setTextColor(getContext().getColor(R.color.extra_key_text_active));
        } else {
            button.setBackground(null);
            button.setTextColor(getContext().getColor(R.color.extra_key_text));
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

    private void setExtraKeyClick(int id, OnClickListener listener) {
        findViewById(id).setOnClickListener(v -> {
            if (!interactive) return;
            v.performHapticFeedback(KEYBOARD_TAP);
            listener.onClick(v);
        });
    }
}
