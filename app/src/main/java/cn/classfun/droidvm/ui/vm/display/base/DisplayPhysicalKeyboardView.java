// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import static android.view.HapticFeedbackConstants.KEYBOARD_TAP;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cn.classfun.droidvm.R;

/**
 * The laptop keyboard: the Main zone of {@link KeyboardMode#LAPTOP}. Only the ANSI main block
 * lives here - the function keys are the panel's shared FNx zone and the navigation cluster its
 * Extra zone, so both modes show the same rows above whichever Main is up. The bottom row ends
 * with the zone toggles and a button that puts the whole keyboard away.
 *
 * Non-modifier keys send real key down/up ({@link HoldKeyGroup}: press = down, sliding onto
 * another key presses that one, release = up), so holds (WASD movement, held arrows) reach the
 * guest and several keys can be held at once. Shift/Ctrl/Alt/Win are sticky, sharing their state
 * with the panel through the adapter; {@link #refreshModifiers} repaints it here.
 */
public final class DisplayPhysicalKeyboardView extends LinearLayout {
    /** The keys that act on the keyboard itself rather than sending anything to the guest. */
    public interface ZoneListener {
        void onToggleExtraZone();

        void onToggleFnxZone();

        void onCloseKeyboard();
    }

    private static final int ROW_HEIGHT_DP = 40;
    private static final float TEXT_SIZE_SP = 10f;

    private static final int KIND_NORMAL = 0;
    private static final int KIND_MODIFIER = 1;
    private static final int KIND_ZONE_EXTRA = 2;
    private static final int KIND_ZONE_FNX = 3;
    private static final int KIND_CLOSE = 4;

    private static final class Key {
        final String label;
        /** What the key reads while Shift is held; same as {@link #label} when it doesn't shift. */
        final String shiftLabel;
        final int code;
        final float weight;
        final int kind;
        final @DrawableRes int icon;

        Key(String label, String shiftLabel, int code, float weight, int kind,
            @DrawableRes int icon) {
            this.label = label;
            this.shiftLabel = shiftLabel;
            this.code = code;
            this.weight = weight;
            this.kind = kind;
            this.icon = icon;
        }
    }

    /** Letter: lower case unshifted, upper case shifted, like the key it stands for. */
    private static Key a(String lower, int code) {
        return new Key(lower, lower.toUpperCase(Locale.ROOT), code, 1f, KIND_NORMAL, 0);
    }

    /** Key whose face changes under Shift (number row, punctuation). */
    private static Key k(String label, String shiftLabel, int code) {
        return k(label, shiftLabel, code, 1f);
    }

    private static Key k(String label, String shiftLabel, int code, float weight) {
        return new Key(label, shiftLabel, code, weight, KIND_NORMAL, 0);
    }

    private static Key k(String label, int code, float weight) {
        return new Key(label, label, code, weight, KIND_NORMAL, 0);
    }

    /** Key drawn as an icon; glyphs like tab and return come out thin and tiny in the font. */
    private static Key kIcon(@DrawableRes int icon, int code, float weight) {
        return new Key("", "", code, weight, KIND_NORMAL, icon);
    }

    private static Key m(String label, int code, float weight) {
        return new Key(label, label, code, weight, KIND_MODIFIER, 0);
    }

    private static Key mIcon(@DrawableRes int icon, int code, float weight) {
        return new Key("", "", code, weight, KIND_MODIFIER, icon);
    }

    private static Key special(String label, int kind, float weight, @DrawableRes int icon) {
        return new Key(label, label, 0, weight, kind, icon);
    }

    // Every row totals 15 weight units, so the columns line up down the block.
    private static final Key[][] ROWS = {
        {
            k("`", "~", KeyEvent.KEYCODE_GRAVE),
            k("1", "!", KeyEvent.KEYCODE_1), k("2", "@", KeyEvent.KEYCODE_2),
            k("3", "#", KeyEvent.KEYCODE_3), k("4", "$", KeyEvent.KEYCODE_4),
            k("5", "%", KeyEvent.KEYCODE_5), k("6", "^", KeyEvent.KEYCODE_6),
            k("7", "&", KeyEvent.KEYCODE_7), k("8", "*", KeyEvent.KEYCODE_8),
            k("9", "(", KeyEvent.KEYCODE_9), k("0", ")", KeyEvent.KEYCODE_0),
            k("-", "_", KeyEvent.KEYCODE_MINUS), k("=", "+", KeyEvent.KEYCODE_EQUALS),
            kIcon(R.drawable.ic_key_backspace, KeyEvent.KEYCODE_DEL, 2f),
        },
        {
            kIcon(R.drawable.ic_key_tab, KeyEvent.KEYCODE_TAB, 1.5f),
            a("q", KeyEvent.KEYCODE_Q), a("w", KeyEvent.KEYCODE_W),
            a("e", KeyEvent.KEYCODE_E), a("r", KeyEvent.KEYCODE_R),
            a("t", KeyEvent.KEYCODE_T), a("y", KeyEvent.KEYCODE_Y),
            a("u", KeyEvent.KEYCODE_U), a("i", KeyEvent.KEYCODE_I),
            a("o", KeyEvent.KEYCODE_O), a("p", KeyEvent.KEYCODE_P),
            k("[", "{", KeyEvent.KEYCODE_LEFT_BRACKET),
            k("]", "}", KeyEvent.KEYCODE_RIGHT_BRACKET),
            k("\\", "|", KeyEvent.KEYCODE_BACKSLASH, 1.5f),
        },
        {
            k("CAPS", KeyEvent.KEYCODE_CAPS_LOCK, 1.75f),
            a("a", KeyEvent.KEYCODE_A), a("s", KeyEvent.KEYCODE_S),
            a("d", KeyEvent.KEYCODE_D), a("f", KeyEvent.KEYCODE_F),
            a("g", KeyEvent.KEYCODE_G), a("h", KeyEvent.KEYCODE_H),
            a("j", KeyEvent.KEYCODE_J), a("k", KeyEvent.KEYCODE_K),
            a("l", KeyEvent.KEYCODE_L),
            k(";", ":", KeyEvent.KEYCODE_SEMICOLON),
            k("'", "\"", KeyEvent.KEYCODE_APOSTROPHE),
            kIcon(R.drawable.ic_key_enter, KeyEvent.KEYCODE_ENTER, 2.25f),
        },
        {
            mIcon(R.drawable.ic_key_shift, KeyEvent.KEYCODE_SHIFT_LEFT, 2.25f),
            a("z", KeyEvent.KEYCODE_Z), a("x", KeyEvent.KEYCODE_X),
            a("c", KeyEvent.KEYCODE_C), a("v", KeyEvent.KEYCODE_V),
            a("b", KeyEvent.KEYCODE_B), a("n", KeyEvent.KEYCODE_N),
            a("m", KeyEvent.KEYCODE_M),
            k(",", "<", KeyEvent.KEYCODE_COMMA), k(".", ">", KeyEvent.KEYCODE_PERIOD),
            k("/", "?", KeyEvent.KEYCODE_SLASH),
            mIcon(R.drawable.ic_key_shift, KeyEvent.KEYCODE_SHIFT_LEFT, 2.75f),
        },
        {
            m("CTRL", KeyEvent.KEYCODE_CTRL_LEFT, 1.5f),
            mIcon(R.drawable.ic_key_windows, KeyEvent.KEYCODE_META_LEFT, 1.5f),
            m("ALT", KeyEvent.KEYCODE_ALT_LEFT, 1.5f),
            kIcon(R.drawable.ic_key_space, KeyEvent.KEYCODE_SPACE, 4.5f),
            m("ALT", KeyEvent.KEYCODE_ALT_LEFT, 1.5f),
            m("CTRL", KeyEvent.KEYCODE_CTRL_LEFT, 1.5f),
            special("Ex", KIND_ZONE_EXTRA, 1f, 0),
            special("Fn", KIND_ZONE_FNX, 1f, 0),
            special("", KIND_CLOSE, 1f, R.drawable.ic_keyboard_close),
        },
    };

    @Nullable
    private KeyListener keyListener;
    @Nullable
    private ZoneListener zoneListener;
    private final Map<Integer, List<View>> modifierButtons = new HashMap<>();
    private final List<View> extraZoneButtons = new ArrayList<>();
    private final List<View> fnxZoneButtons = new ArrayList<>();
    // Keys whose face changes under Shift, so the keyboard reads like what it will type.
    private final Map<Button, Key> shiftableKeys = new HashMap<>();
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
    private View buildKey(@NonNull Context context, @NonNull Key key) {
        View view;
        if (key.icon != 0) {
            // An ImageButton centres its drawable; a Button's compound drawable does not, and at
            // these key sizes the offset is obvious.
            var img = new ImageButton(context);
            img.setImageResource(key.icon);
            img.setScaleType(ImageView.ScaleType.CENTER);
            img.setBackgroundResource(R.drawable.extra_key_bg);
            img.setImageTintList(ColorStateList.valueOf(
                context.getColor(R.color.extra_key_text)));
            img.setPadding(0, 0, 0, 0);
            view = img;
        } else {
            // Same visual as the extra-keys strip; denser text since these rows fit up to 14 keys.
            var btn = new Button(context, null, 0, R.style.ExtraKey);
            btn.setText(key.label);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP);
            if (!key.label.equals(key.shiftLabel)) shiftableKeys.put(btn, key);
            view = btn;
        }
        view.setLayoutParams(new LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight));
        switch (key.kind) {
            case KIND_MODIFIER:
                modifierButtons.computeIfAbsent(key.code, c -> new ArrayList<>()).add(view);
                view.setOnClickListener(v -> {
                    v.performHapticFeedback(KEYBOARD_TAP);
                    if (keyListener != null) keyListener.onModifierClick(key.code);
                });
                view.setOnLongClickListener(v -> {
                    v.performHapticFeedback(KEYBOARD_TAP);
                    if (keyListener != null) keyListener.onModifierLongClick(key.code);
                    return true;
                });
                break;
            case KIND_ZONE_EXTRA:
                extraZoneButtons.add(view);
                setTapAction(view, () -> {
                    if (zoneListener != null) zoneListener.onToggleExtraZone();
                });
                break;
            case KIND_ZONE_FNX:
                fnxZoneButtons.add(view);
                setTapAction(view, () -> {
                    if (zoneListener != null) zoneListener.onToggleFnxZone();
                });
                break;
            case KIND_CLOSE:
                setTapAction(view, () -> {
                    if (zoneListener != null) zoneListener.onCloseKeyboard();
                });
                break;
            default:
                holdKeys.register(view, key.code);
                break;
        }
        return view;
    }

    private void setTapAction(@NonNull View btn, @NonNull Runnable action) {
        btn.setOnClickListener(v -> {
            v.performHapticFeedback(KEYBOARD_TAP);
            action.run();
        });
    }

    public void setKeyListener(@Nullable KeyListener listener) {
        this.keyListener = listener;
    }

    public void setZoneListener(@Nullable ZoneListener listener) {
        this.zoneListener = listener;
    }

    /** Repaint the sticky-modifier keys, and the key faces Shift changes, from adapter state. */
    public void refreshModifiers(boolean ctrl, boolean alt, boolean shift, boolean win) {
        paintModifier(KeyEvent.KEYCODE_CTRL_LEFT, ctrl);
        paintModifier(KeyEvent.KEYCODE_ALT_LEFT, alt);
        paintModifier(KeyEvent.KEYCODE_SHIFT_LEFT, shift);
        paintModifier(KeyEvent.KEYCODE_META_LEFT, win);
        for (var entry : shiftableKeys.entrySet())
            entry.getKey().setText(shift ? entry.getValue().shiftLabel : entry.getValue().label);
    }

    /** Repaint the Extra/FNx toggles to match the zones actually on screen. */
    public void setZoneToggleState(boolean extraOn, boolean fnxOn) {
        for (var btn : extraZoneButtons) paintToggle(btn, extraOn);
        for (var btn : fnxZoneButtons) paintToggle(btn, fnxOn);
    }

    private void paintModifier(int keyCode, boolean active) {
        var buttons = modifierButtons.get(keyCode);
        if (buttons == null) return;
        for (var btn : buttons) paintToggle(btn, active);
    }

    /** Works for both kinds of key: a text {@link Button} and an icon-only {@link ImageButton}. */
    private void paintToggle(@NonNull View key, boolean active) {
        int color = getContext().getColor(
            active ? R.color.extra_key_text_active : R.color.extra_key_text);
        if (active) key.setBackgroundColor(getContext().getColor(R.color.extra_key_bg_active));
        else key.setBackgroundResource(R.drawable.extra_key_bg);
        if (key instanceof Button) ((Button) key).setTextColor(color);
        else if (key instanceof ImageButton)
            ((ImageButton) key).setImageTintList(ColorStateList.valueOf(color));
    }

    public void setVisibleAnimated(boolean visible) {
        ViewHeightAnimator.setVisible(this, visible);
    }
}
