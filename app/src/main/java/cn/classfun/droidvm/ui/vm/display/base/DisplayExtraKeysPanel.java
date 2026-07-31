// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import static android.view.HapticFeedbackConstants.KEYBOARD_TAP;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.R;

/**
 * The extra-keys panel. Two zones: the top rows hold keys a laptop layout doesn't have (nav
 * cluster, Esc, symbols); the bottom {@code phy_common_row} holds keys a laptop layout does have
 * (Tab/modifiers/Enter/FNx) and yields - {@link #setPhyCommonRowVisible} - while the physical
 * keyboard is up. Non-modifier keys send real key down/up ({@link HoldKeyTouchListener}), so the
 * guest sees holds; modifiers are sticky (tap = one-shot, long-press = locked), owned by
 * {@link BaseExtraKeysAdapter} with this panel as the display of that state.
 */
public final class DisplayExtraKeysPanel extends LinearLayout {
    private LinearLayout fnKeysContainer;
    private LinearLayout phyCommonRow;
    private boolean fnxActive = false;
    private boolean phyCommonVisible = true;
    @Nullable
    private KeyListener keyListener;
    // Notified whenever the modifier toggle state repaints, so a second view of the same state
    // (the physical keyboard's modifier keys) can repaint too.
    @Nullable
    private Runnable modifierStateObserver;
    // Down/up + glide handling for every non-modifier key (press = down, slide-in = that key's
    // down, release/slide-off = up).
    private final HoldKeyGroup holdKeys = new HoldKeyGroup((code, down) -> {
        if (keyListener != null) keyListener.onKey(code, down);
    });

    private boolean ctrlDown, altDown, shiftDown, winDown;

    public DisplayExtraKeysPanel(@NonNull Context context) {
        super(context);
        init(context);
    }

    public DisplayExtraKeysPanel(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public DisplayExtraKeysPanel(
        @NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(@NonNull Context context) {
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.widget_display_extra_keys, this, true);
        fnKeysContainer = findViewById(R.id.fn_keys_container);
        phyCommonRow = findViewById(R.id.phy_common_row);
        setupKeys();
    }

    public void setKeyListener(@Nullable KeyListener listener) {
        this.keyListener = listener;
    }

    public void setModifierStateObserver(@Nullable Runnable observer) {
        this.modifierStateObserver = observer;
    }

    public boolean isCtrlDown() {
        return ctrlDown;
    }

    public boolean isAltDown() {
        return altDown;
    }

    public boolean isShiftDown() {
        return shiftDown;
    }

    public boolean isWinDown() {
        return winDown;
    }

    public void setCtrlDown(boolean v) {
        ctrlDown = v;
        updateToggleButtons();
    }

    public void setAltDown(boolean v) {
        altDown = v;
        updateToggleButtons();
    }

    public void setShiftDown(boolean v) {
        shiftDown = v;
        updateToggleButtons();
    }

    public void setWinDown(boolean v) {
        winDown = v;
        updateToggleButtons();
    }

    public void updateToggleButtons() {
        setToggleStyle(findViewById(R.id.btn_ctrl), ctrlDown);
        setToggleStyle(findViewById(R.id.btn_alt), altDown);
        setToggleStyle(findViewById(R.id.btn_shift), shiftDown);
        setToggleStyle(findViewById(R.id.btn_win), winDown);
        setToggleStyle(findViewById(R.id.btn_fnx), fnxActive);
        if (modifierStateObserver != null) modifierStateObserver.run();
    }

    private void setToggleStyle(@Nullable Button btn, boolean active) {
        if (btn == null) return;
        if (active) {
            btn.setBackgroundColor(getContext().getColor(R.color.extra_key_bg_active));
            btn.setTextColor(getContext().getColor(R.color.extra_key_text_active));
        } else {
            btn.setBackgroundResource(R.drawable.extra_key_bg);
            btn.setTextColor(getContext().getColor(R.color.extra_key_text));
        }
    }

    private void setupKeys() {
        setupHoldKey(R.id.btn_esc, KeyEvent.KEYCODE_ESCAPE);
        setupHoldKey(R.id.btn_slash, KeyEvent.KEYCODE_SLASH);
        setupHoldKey(R.id.btn_dash, KeyEvent.KEYCODE_MINUS);
        setupHoldKey(R.id.btn_home, KeyEvent.KEYCODE_MOVE_HOME);
        setupHoldKey(R.id.btn_up, KeyEvent.KEYCODE_DPAD_UP);
        setupHoldKey(R.id.btn_end, KeyEvent.KEYCODE_MOVE_END);
        setupHoldKey(R.id.btn_pgup, KeyEvent.KEYCODE_PAGE_UP);
        setupHoldKey(R.id.btn_bksp, KeyEvent.KEYCODE_DEL);
        setupHoldKey(R.id.btn_del, KeyEvent.KEYCODE_FORWARD_DEL);
        setupHoldKey(R.id.btn_ins, KeyEvent.KEYCODE_INSERT);
        setupHoldKey(R.id.btn_left, KeyEvent.KEYCODE_DPAD_LEFT);
        setupHoldKey(R.id.btn_down, KeyEvent.KEYCODE_DPAD_DOWN);
        setupHoldKey(R.id.btn_right, KeyEvent.KEYCODE_DPAD_RIGHT);
        setupHoldKey(R.id.btn_pgdn, KeyEvent.KEYCODE_PAGE_DOWN);
        setupHoldKey(R.id.btn_tab, KeyEvent.KEYCODE_TAB);
        setupModifierKey(R.id.btn_ctrl, KeyEvent.KEYCODE_CTRL_LEFT);
        setupModifierKey(R.id.btn_shift, KeyEvent.KEYCODE_SHIFT_LEFT);
        setupModifierKey(R.id.btn_win, KeyEvent.KEYCODE_META_LEFT);
        setupModifierKey(R.id.btn_alt, KeyEvent.KEYCODE_ALT_LEFT);
        setupHoldKey(R.id.btn_enter, KeyEvent.KEYCODE_ENTER);
        findViewById(R.id.btn_fnx).setOnClickListener(v -> {
            v.performHapticFeedback(KEYBOARD_TAP);
            fnxActive = !fnxActive;
            ViewHeightAnimator.setVisible(fnKeysContainer, fnxActive);
            updateToggleButtons();
        });
        setupHoldKey(R.id.btn_f1, KeyEvent.KEYCODE_F1);
        setupHoldKey(R.id.btn_f2, KeyEvent.KEYCODE_F2);
        setupHoldKey(R.id.btn_f3, KeyEvent.KEYCODE_F3);
        setupHoldKey(R.id.btn_f4, KeyEvent.KEYCODE_F4);
        setupHoldKey(R.id.btn_f5, KeyEvent.KEYCODE_F5);
        setupHoldKey(R.id.btn_f6, KeyEvent.KEYCODE_F6);
        setupHoldKey(R.id.btn_prtsc, KeyEvent.KEYCODE_SYSRQ);
        setupHoldKey(R.id.btn_f7, KeyEvent.KEYCODE_F7);
        setupHoldKey(R.id.btn_f8, KeyEvent.KEYCODE_F8);
        setupHoldKey(R.id.btn_f9, KeyEvent.KEYCODE_F9);
        setupHoldKey(R.id.btn_f10, KeyEvent.KEYCODE_F10);
        setupHoldKey(R.id.btn_f11, KeyEvent.KEYCODE_F11);
        setupHoldKey(R.id.btn_f12, KeyEvent.KEYCODE_F12);
        setupHoldKey(R.id.btn_pause, KeyEvent.KEYCODE_BREAK);
    }

    private void setupHoldKey(int id, int keyCode) {
        holdKeys.register(findViewById(id), keyCode);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupModifierKey(int btnId, int keyCode) {
        var btn = findViewById(btnId);
        btn.setOnClickListener(v -> {
            v.performHapticFeedback(KEYBOARD_TAP);
            if (keyListener != null) keyListener.onModifierClick(keyCode);
        });
        btn.setOnLongClickListener(v -> {
            v.performHapticFeedback(KEYBOARD_TAP);
            if (keyListener != null) keyListener.onModifierLongClick(keyCode);
            return true;
        });
    }

    /**
     * Show/hide the bottom row of laptop-common keys. Hidden while the physical keyboard is up
     * (it has those keys itself); the FNx expansion collapses with it since FNx lives there.
     */
    public void setPhyCommonRowVisible(boolean visible) {
        if (phyCommonVisible == visible) return;
        phyCommonVisible = visible;
        if (!visible && fnxActive) {
            fnxActive = false;
            fnKeysContainer.setVisibility(GONE);
            updateToggleButtons();
        }
        if (getVisibility() == VISIBLE) {
            ViewHeightAnimator.setVisible(phyCommonRow, visible);
        } else {
            phyCommonRow.setVisibility(visible ? VISIBLE : GONE);
        }
    }

    public void setVisibleAnimated(boolean visible) {
        ViewHeightAnimator.setVisible(this, visible);
    }
}
