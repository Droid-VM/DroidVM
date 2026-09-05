// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import static android.view.HapticFeedbackConstants.KEYBOARD_TAP;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.R;

/**
 * The keyboard panel's shared zones: Extra (nav cluster and the keys no soft keyboard sends),
 * FNx (function keys, shared by both keyboard modes), and the Main row used in system-IME mode.
 * The laptop keyboard is {@link DisplayPhysicalKeyboardView}, docked below this and supplying
 * its own Main - so whichever mode is up, Extra and FNx look and behave identically.
 *
 * Non-modifier keys send real key down/up ({@link HoldKeyGroup}: press = down, sliding onto
 * another key presses that one, release = up), so the guest sees holds and does its own
 * auto-repeat. Shift/Ctrl/Alt/Win are sticky - tap for one-shot, long-press to lock - with
 * {@link BaseExtraKeysAdapter} owning that state and this panel rendering it.
 */
public final class DisplayExtraKeysPanel extends LinearLayout {
    /** Zone toggle and IME summon live on the keys themselves, not in a menu. */
    public interface ZoneListener {
        void onToggleFnxZone();

        void onShowSystemKeyboard();
    }

    private View extraZone;
    private View fnxZone;
    private View systemMainRow;
    @Nullable
    private KeyListener keyListener;
    @Nullable
    private ZoneListener zoneListener;
    // Notified whenever the modifier toggle state repaints, so a second view of the same state
    // (the laptop keyboard's modifier keys) can repaint too.
    @Nullable
    private Runnable modifierStateObserver;

    private boolean ctrlDown, altDown, shiftDown, winDown;

    private final HoldKeyGroup holdKeys = new HoldKeyGroup((code, down) -> {
        if (keyListener != null) keyListener.onKey(code, down);
    });

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
        extraZone = findViewById(R.id.extra_zone);
        fnxZone = findViewById(R.id.fnx_zone);
        systemMainRow = findViewById(R.id.system_main_row);
        setupKeys();
    }

    public void setKeyListener(@Nullable KeyListener listener) {
        this.keyListener = listener;
    }

    public void setZoneListener(@Nullable ZoneListener listener) {
        this.zoneListener = listener;
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
        if (modifierStateObserver != null) modifierStateObserver.run();
    }

    /** Repaint the Fn toggle to match the zone actually on screen. */
    public void setZoneToggleState(boolean fnxOn) {
        setToggleStyle(findViewById(R.id.btn_zone_fnx), fnxOn);
    }

    /**
     * Which key owns the Main row's last slot. The IME's own visibility decides: while it is up
     * the slot toggles the Fn zone, and while it is dismissed it summons the IME back - the
     * other key would do nothing in each case. The Fn zone itself keeps its state throughout;
     * only the way to toggle it goes away with the IME.
     */
    public void setImeVisible(boolean imeVisible) {
        findViewById(R.id.btn_zone_fnx).setVisibility(imeVisible ? VISIBLE : GONE);
        findViewById(R.id.btn_show_ime).setVisibility(imeVisible ? GONE : VISIBLE);
    }

    /** Works for both kinds of key: a text {@link Button} and an icon-only {@link ImageButton}. */
    private void setToggleStyle(@Nullable View key, boolean active) {
        if (key == null) return;
        int color = getContext().getColor(
            active ? R.color.extra_key_text_active : R.color.extra_key_text);
        if (active) key.setBackgroundColor(getContext().getColor(R.color.extra_key_bg_active));
        else key.setBackgroundResource(R.drawable.extra_key_bg);
        if (key instanceof Button) ((Button) key).setTextColor(color);
        else if (key instanceof ImageButton)
            ((ImageButton) key).setImageTintList(ColorStateList.valueOf(color));
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
        setupHoldKey(R.id.btn_tab, KeyEvent.KEYCODE_TAB);
        setupHoldKey(R.id.btn_enter, KeyEvent.KEYCODE_ENTER);
        setupModifierKey(R.id.btn_ctrl, KeyEvent.KEYCODE_CTRL_LEFT);
        setupModifierKey(R.id.btn_shift, KeyEvent.KEYCODE_SHIFT_LEFT);
        setupModifierKey(R.id.btn_win, KeyEvent.KEYCODE_META_LEFT);
        setupModifierKey(R.id.btn_alt, KeyEvent.KEYCODE_ALT_LEFT);
        setupTapKey(R.id.btn_zone_fnx, () -> {
            if (zoneListener != null) zoneListener.onToggleFnxZone();
        });
        setupTapKey(R.id.btn_show_ime, () -> {
            if (zoneListener != null) zoneListener.onShowSystemKeyboard();
        });
    }

    private void setupHoldKey(int id, int keyCode) {
        holdKeys.register(findViewById(id), keyCode);
    }

    private void setupTapKey(int id, @NonNull Runnable action) {
        findViewById(id).setOnClickListener(v -> {
            v.performHapticFeedback(KEYBOARD_TAP);
            action.run();
        });
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
     * Show the zones the chrome state calls for. The panel itself is visible whenever any zone
     * is - an empty panel would otherwise leave a blank strip over the display.
     */
    public void applyZones(boolean extraOn, boolean fnxOn, boolean systemMainOn) {
        setZoneToggleState(fnxOn);
        boolean any = extraOn || fnxOn || systemMainOn;
        if (!any) {
            // Slide the whole panel away; the zones keep their own states for the way back.
            ViewHeightAnimator.hide(this);
            return;
        }
        if (getVisibility() != VISIBLE) {
            // Panel arriving: put the zones in place first, then animate the panel as a whole,
            // so its slide-in is one movement rather than several nested ones.
            extraZone.setVisibility(extraOn ? VISIBLE : GONE);
            fnxZone.setVisibility(fnxOn ? VISIBLE : GONE);
            systemMainRow.setVisibility(systemMainOn ? VISIBLE : GONE);
            ViewHeightAnimator.show(this);
            return;
        }
        // Panel already up: animate each zone so Ex/FNx slide open and shut.
        ViewHeightAnimator.setVisible(extraZone, extraOn);
        ViewHeightAnimator.setVisible(fnxZone, fnxOn);
        ViewHeightAnimator.setVisible(systemMainRow, systemMainOn);
    }
}
