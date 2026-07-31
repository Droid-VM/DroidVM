// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import static android.view.HapticFeedbackConstants.KEYBOARD_TAP;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.R;

/**
 * On-screen laptop-layout keyboard (ANSI main block + Esc/F row). Non-modifier keys send real key
 * down/up through the shared {@link KeyListener} - press = down, release or slide-off = up - so
 * holds (WASD movement, held arrows) reach the guest, and several keys can be held at once.
 * Shift/Ctrl/Alt/Win are sticky like the extra-keys strip: the adapter owns that state; call
 * {@link #refreshModifiers} to repaint it here (both side's keys mirror the same left-modifier
 * state).
 */
public final class DisplayPhysicalKeyboardView extends LinearLayout {
    private static final int ROW_HEIGHT_DP = 40;
    private static final float TEXT_SIZE_SP = 10f;

    private static final int KIND_NORMAL = 0;
    private static final int KIND_MODIFIER = 1;
    private static final int KIND_FN = 2;

    private static final class Key {
        final String label;
        final int code;
        final float weight;
        final int kind;

        Key(String label, int code, float weight, int kind) {
            this.label = label;
            this.code = code;
            this.weight = weight;
            this.kind = kind;
        }
    }

    private static Key k(String label, int code) {
        return new Key(label, code, 1f, KIND_NORMAL);
    }

    private static Key k(String label, int code, float weight) {
        return new Key(label, code, weight, KIND_NORMAL);
    }

    private static Key m(String label, int code, float weight) {
        return new Key(label, code, weight, KIND_MODIFIER);
    }

    // The FN toggle at the bottom-right corner: shows/hides FN_ROWS, same as the strip's FNx.
    // Esc/PrtSc/Pause are not repeated here - the extra-keys panel already carries them.
    private static final Key[][] FN_ROWS = {
        {
            k("F1", KeyEvent.KEYCODE_F1), k("F2", KeyEvent.KEYCODE_F2),
            k("F3", KeyEvent.KEYCODE_F3), k("F4", KeyEvent.KEYCODE_F4),
            k("F5", KeyEvent.KEYCODE_F5), k("F6", KeyEvent.KEYCODE_F6),
            k("PGUP", KeyEvent.KEYCODE_PAGE_UP),
        },
        {
            k("F7", KeyEvent.KEYCODE_F7), k("F8", KeyEvent.KEYCODE_F8),
            k("F9", KeyEvent.KEYCODE_F9), k("F10", KeyEvent.KEYCODE_F10),
            k("F11", KeyEvent.KEYCODE_F11), k("F12", KeyEvent.KEYCODE_F12),
            k("PGDN", KeyEvent.KEYCODE_PAGE_DOWN),
        },
    };

    private static final Key[][] ROWS = {
        {
            k("`", KeyEvent.KEYCODE_GRAVE),
            k("1", KeyEvent.KEYCODE_1), k("2", KeyEvent.KEYCODE_2),
            k("3", KeyEvent.KEYCODE_3), k("4", KeyEvent.KEYCODE_4),
            k("5", KeyEvent.KEYCODE_5), k("6", KeyEvent.KEYCODE_6),
            k("7", KeyEvent.KEYCODE_7), k("8", KeyEvent.KEYCODE_8),
            k("9", KeyEvent.KEYCODE_9), k("0", KeyEvent.KEYCODE_0),
            k("-", KeyEvent.KEYCODE_MINUS), k("=", KeyEvent.KEYCODE_EQUALS),
            k("\u232B" /* backspace glyph */, KeyEvent.KEYCODE_DEL, 2f),
        },
        {
            k("TAB", KeyEvent.KEYCODE_TAB, 1.5f),
            k("Q", KeyEvent.KEYCODE_Q), k("W", KeyEvent.KEYCODE_W),
            k("E", KeyEvent.KEYCODE_E), k("R", KeyEvent.KEYCODE_R),
            k("T", KeyEvent.KEYCODE_T), k("Y", KeyEvent.KEYCODE_Y),
            k("U", KeyEvent.KEYCODE_U), k("I", KeyEvent.KEYCODE_I),
            k("O", KeyEvent.KEYCODE_O), k("P", KeyEvent.KEYCODE_P),
            k("[", KeyEvent.KEYCODE_LEFT_BRACKET), k("]", KeyEvent.KEYCODE_RIGHT_BRACKET),
            k("\\", KeyEvent.KEYCODE_BACKSLASH, 1.5f),
        },
        {
            k("CAPS", KeyEvent.KEYCODE_CAPS_LOCK, 1.75f),
            k("A", KeyEvent.KEYCODE_A), k("S", KeyEvent.KEYCODE_S),
            k("D", KeyEvent.KEYCODE_D), k("F", KeyEvent.KEYCODE_F),
            k("G", KeyEvent.KEYCODE_G), k("H", KeyEvent.KEYCODE_H),
            k("J", KeyEvent.KEYCODE_J), k("K", KeyEvent.KEYCODE_K),
            k("L", KeyEvent.KEYCODE_L),
            k(";", KeyEvent.KEYCODE_SEMICOLON), k("'", KeyEvent.KEYCODE_APOSTROPHE),
            k("ENTER", KeyEvent.KEYCODE_ENTER, 2.25f),
        },
        {
            m("SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT, 2.25f),
            k("Z", KeyEvent.KEYCODE_Z), k("X", KeyEvent.KEYCODE_X),
            k("C", KeyEvent.KEYCODE_C), k("V", KeyEvent.KEYCODE_V),
            k("B", KeyEvent.KEYCODE_B), k("N", KeyEvent.KEYCODE_N),
            k("M", KeyEvent.KEYCODE_M),
            k(",", KeyEvent.KEYCODE_COMMA), k(".", KeyEvent.KEYCODE_PERIOD),
            k("/", KeyEvent.KEYCODE_SLASH),
            m("SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT, 2.75f),
        },
        {
            m("CTRL", KeyEvent.KEYCODE_CTRL_LEFT, 1.5f),
            m("WIN", KeyEvent.KEYCODE_META_LEFT, 1.5f),
            m("ALT", KeyEvent.KEYCODE_ALT_LEFT, 1.5f),
            k("", KeyEvent.KEYCODE_SPACE, 6f),
            m("ALT", KeyEvent.KEYCODE_ALT_LEFT, 1.5f),
            m("CTRL", KeyEvent.KEYCODE_CTRL_LEFT, 1.5f),
            new Key("FN", 0, 1.5f, KIND_FN),
        },
    };

    @Nullable
    private KeyListener keyListener;
    private final Map<Integer, List<Button>> modifierButtons = new HashMap<>();
    private LinearLayout fnContainer;
    private Button fnButton;
    private boolean fnActive;
    // Down/up + glide handling for every non-modifier key (press = down, slide-in = that key's
    // down, release/slide-off = up) - a W-to-A roll works without lifting the finger.
    private final HoldKeyGroup holdKeys = new HoldKeyGroup((code, down) -> {
        if (keyListener != null) keyListener.onKey(code, down);
    });

    public DisplayPhysicalKeyboardView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public DisplayPhysicalKeyboardView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public DisplayPhysicalKeyboardView(
        @NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(@NonNull Context context) {
        setOrientation(VERTICAL);
        // First child, so the FN toggle expands upward and the keys under the fingers never move.
        fnContainer = new LinearLayout(context);
        fnContainer.setOrientation(VERTICAL);
        fnContainer.setVisibility(GONE);
        for (Key[] rowSpec : FN_ROWS) fnContainer.addView(buildRow(context, rowSpec));
        addView(fnContainer,
            new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        for (Key[] rowSpec : ROWS) addView(buildRow(context, rowSpec));
    }

    @NonNull
    private LinearLayout buildRow(@NonNull Context context, @NonNull Key[] rowSpec) {
        int rowHeight = Math.round(
            ROW_HEIGHT_DP * context.getResources().getDisplayMetrics().density);
        var row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setBackgroundColor(context.getColor(R.color.extra_keys_background));
        for (Key key : rowSpec) row.addView(buildKey(context, key));
        row.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, rowHeight));
        return row;
    }

    @NonNull
    private Button buildKey(@NonNull Context context, @NonNull Key key) {
        // Same visual as the extra-keys strip; denser text since these rows fit up to 14 keys.
        var btn = new Button(context, null, 0, R.style.ExtraKey);
        btn.setText(key.label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP);
        btn.setLayoutParams(new LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight));
        if (key.kind == KIND_MODIFIER) {
            modifierButtons.computeIfAbsent(key.code, c -> new ArrayList<>()).add(btn);
            btn.setOnClickListener(v -> {
                v.performHapticFeedback(KEYBOARD_TAP);
                if (keyListener != null) keyListener.onModifierClick(key.code);
            });
            btn.setOnLongClickListener(v -> {
                v.performHapticFeedback(KEYBOARD_TAP);
                if (keyListener != null) keyListener.onModifierLongClick(key.code);
                return true;
            });
        } else if (key.kind == KIND_FN) {
            fnButton = btn;
            btn.setOnClickListener(v -> {
                v.performHapticFeedback(KEYBOARD_TAP);
                fnActive = !fnActive;
                ViewHeightAnimator.setVisible(fnContainer, fnActive);
                paintToggle(fnButton, fnActive);
            });
        } else {
            holdKeys.register(btn, key.code);
        }
        return btn;
    }

    public void setKeyListener(@Nullable KeyListener listener) {
        this.keyListener = listener;
    }

    /** Repaint the sticky-modifier keys from the shared adapter state. */
    public void refreshModifiers(boolean ctrl, boolean alt, boolean shift, boolean win) {
        paintModifier(KeyEvent.KEYCODE_CTRL_LEFT, ctrl);
        paintModifier(KeyEvent.KEYCODE_ALT_LEFT, alt);
        paintModifier(KeyEvent.KEYCODE_SHIFT_LEFT, shift);
        paintModifier(KeyEvent.KEYCODE_META_LEFT, win);
    }

    private void paintModifier(int keyCode, boolean active) {
        var buttons = modifierButtons.get(keyCode);
        if (buttons == null) return;
        for (Button btn : buttons) paintToggle(btn, active);
    }

    private void paintToggle(@NonNull Button btn, boolean active) {
        if (active) {
            btn.setBackgroundColor(getContext().getColor(R.color.extra_key_bg_active));
            btn.setTextColor(getContext().getColor(R.color.extra_key_text_active));
        } else {
            btn.setBackgroundResource(R.drawable.extra_key_bg);
            btn.setTextColor(getContext().getColor(R.color.extra_key_text));
        }
    }

    public void setVisibleAnimated(boolean visible) {
        ViewHeightAnimator.setVisible(this, visible);
    }
}
