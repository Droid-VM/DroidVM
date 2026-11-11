// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.input.EvdevEncoder;

/**
 * The console's input side apart from the picture: where the keyboard sits, above or below the
 * pointer area, and the mouse buttons under the pointer area in the relative modes -- with the
 * picture on an external display and the keyboard on top, a tablet is a laptop's lower half.
 *
 * <p>The pointer area is the display container: the picture (or the block standing in for it)
 * maps the absolute modes, and the whole of it is the pad in the relative ones. Both settings are
 * the app's, as the input mode is, and both consoles share this.</p>
 */
public final class ConsoleWorkspace {
    private static final String PREFS = "droidvm_prefs";
    private static final String KEY_KEYBOARD_ON_TOP = "display_keyboard_on_top";
    private static final String KEY_POINTER_BUTTONS = "display_pointer_buttons";

    /** A mouse button pressed or let go: EvdevEncoder.BTN_LEFT or BTN_RIGHT. */
    public interface ButtonSink {
        void onPointerButton(short button, boolean down);
    }

    private final SharedPreferences prefs;
    private final LinearLayout root;
    private final View container;
    private final View buttons;
    private final List<View> keyboard;
    private InputMode inputMode = InputMode.TOUCH;

    /**
     * @param root     the console's vertical root, holding the container, the buttons and the
     *                 keyboard views one after another
     * @param keyboard the keyboard's views (extra keys, laptop keyboard), in their order
     */
    public ConsoleWorkspace(@NonNull Context context, @NonNull LinearLayout root,
                            @NonNull View container, @NonNull View buttons,
                            @NonNull List<View> keyboard, @NonNull ButtonSink sink) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.root = root;
        this.container = container;
        this.buttons = buttons;
        this.keyboard = keyboard;
        wire(buttons.findViewById(R.id.btn_pointer_left), EvdevEncoder.BTN_LEFT, sink);
        wire(buttons.findViewById(R.id.btn_pointer_right), EvdevEncoder.BTN_RIGHT, sink);
        applyOrder();
        applyButtons();
    }

    public boolean isKeyboardOnTop() {
        return prefs.getBoolean(KEY_KEYBOARD_ON_TOP, false);
    }

    /** Moves the keyboard to the other side of the pointer area; true when it is on top now. */
    public boolean toggleKeyboardOnTop() {
        boolean top = !isKeyboardOnTop();
        prefs.edit().putBoolean(KEY_KEYBOARD_ON_TOP, top).apply();
        applyOrder();
        return top;
    }

    public boolean arePointerButtonsOn() {
        return prefs.getBoolean(KEY_POINTER_BUTTONS, false);
    }

    /** Shows or hides the mouse buttons; true when they are on now. */
    public boolean togglePointerButtons() {
        boolean on = !arePointerButtonsOn();
        prefs.edit().putBoolean(KEY_POINTER_BUTTONS, on).apply();
        applyButtons();
        return on;
    }

    /** The buttons belong to the relative modes: the absolute ones click where the finger is. */
    public void setInputMode(@NonNull InputMode mode) {
        inputMode = mode;
        applyButtons();
    }

    private void applyButtons() {
        buttons.setVisibility(arePointerButtonsOn() && inputMode.isVmPointer()
            ? View.VISIBLE : View.GONE);
    }

    /** Container, buttons and keyboard in their order: the keyboard after them, or before. */
    private void applyOrder() {
        int at = root.indexOfChild(container);
        for (var view : keyboard) at = Math.min(at, root.indexOfChild(view));
        root.removeView(container);
        root.removeView(buttons);
        for (var view : keyboard) root.removeView(view);
        if (isKeyboardOnTop()) {
            for (var view : keyboard) root.addView(view, at++);
            root.addView(container, at++);
            root.addView(buttons, at);
        } else {
            root.addView(container, at++);
            root.addView(buttons, at++);
            for (var view : keyboard) root.addView(view, at++);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void wire(@NonNull View button, short code, @NonNull ButtonSink sink) {
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    // The click a laptop's button makes, felt here: the phone's own feedback.
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    sink.onPointerButton(code, true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP)
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
                    sink.onPointerButton(code, false);
                    return true;
                default:
                    return true;
            }
        });
    }
}
