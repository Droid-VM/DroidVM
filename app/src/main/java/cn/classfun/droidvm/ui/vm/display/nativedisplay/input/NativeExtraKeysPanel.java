package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import static android.view.KeyEvent.KEYCODE_ALT_LEFT;
import static android.view.KeyEvent.KEYCODE_CAPS_LOCK;
import static android.view.KeyEvent.KEYCODE_CTRL_LEFT;
import static android.view.KeyEvent.KEYCODE_META_LEFT;
import static android.view.KeyEvent.KEYCODE_MINUS;
import static android.view.KeyEvent.KEYCODE_SHIFT_LEFT;
import static android.view.KeyEvent.KEYCODE_SLASH;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.ui.vm.display.base.DisplayExtraKeysPanel;
import cn.classfun.droidvm.ui.vm.display.base.KeyListener;

/**
 * Bridges the shared {@link DisplayExtraKeysPanel} (Esc/Tab/arrows/Fn + Ctrl/Alt/Shift/Win
 * modifiers) to the native backend by emitting evdev events through {@link InputForwarder}.
 * Mirrors the VNC adapter's sticky-modifier semantics.
 */
public final class NativeExtraKeysPanel implements KeyListener {
    @NonNull
    private final DisplayExtraKeysPanel panel;
    @Nullable
    private InputForwarder forwarder;

    private boolean ctrlSticky, altSticky, shiftSticky, winSticky;

    public NativeExtraKeysPanel(@NonNull DisplayExtraKeysPanel panel) {
        this.panel = panel;
        panel.setKeyListener(this);
    }

    public void setForwarder(@Nullable InputForwarder forwarder) {
        this.forwarder = forwarder;
    }

    /** True if any active modifier is non-sticky and so must be wrapped around a real keystroke. */
    public boolean hasNonStickyModifiers() {
        return (panel.isCtrlDown() && !ctrlSticky)
            || (panel.isAltDown() && !altSticky)
            || (panel.isShiftDown() && !shiftSticky)
            || (panel.isWinDown() && !winSticky);
    }

    /** Press (or release) every active, non-sticky modifier around a keystroke. */
    public void applyModifiers(boolean down) {
        if (forwarder == null) return;
        if (panel.isCtrlDown() && !ctrlSticky) forwarder.sendKeyEvent(KEYCODE_CTRL_LEFT, down);
        if (panel.isAltDown() && !altSticky) forwarder.sendKeyEvent(KEYCODE_ALT_LEFT, down);
        if (panel.isShiftDown() && !shiftSticky) forwarder.sendKeyEvent(KEYCODE_SHIFT_LEFT, down);
        if (panel.isWinDown() && !winSticky) forwarder.sendKeyEvent(KEYCODE_META_LEFT, down);
        if (!down) {
            if (!ctrlSticky) panel.setCtrlDown(false);
            if (!altSticky) panel.setAltDown(false);
            if (!shiftSticky) panel.setShiftDown(false);
            if (!winSticky) panel.setWinDown(false);
            panel.updateToggleButtons();
        }
    }

    private void tapKey(int androidKeyCode) {
        if (forwarder == null) return;
        applyModifiers(true);
        forwarder.sendKeyEvent(androidKeyCode, true);
        forwarder.sendKeyEvent(androidKeyCode, false);
        applyModifiers(false);
    }

    @Override
    public void onKeyRepeat(int androidKeyCode) {
        tapKey(androidKeyCode);
    }

    @Override
    public void onCharRepeat(char ch) {
        if (ch == '/') tapKey(KEYCODE_SLASH);
        else if (ch == '-') tapKey(KEYCODE_MINUS);
    }

    @Override
    public void onCapsToggle(boolean active) {
        if (forwarder != null) {
            forwarder.sendKeyEvent(KEYCODE_CAPS_LOCK, true);
            forwarder.sendKeyEvent(KEYCODE_CAPS_LOCK, false);
        }
    }

    @Override
    public void onModifierClick(int androidKeyCode) {
        if (getSticky(androidKeyCode)) {
            setSticky(androidKeyCode, false);
            setDown(androidKeyCode, false);
            if (forwarder != null) forwarder.sendKeyEvent(androidKeyCode, false);
        } else {
            setDown(androidKeyCode, !getDown(androidKeyCode));
        }
        panel.updateToggleButtons();
    }

    @Override
    public void onModifierLongClick(int androidKeyCode) {
        setDown(androidKeyCode, true);
        setSticky(androidKeyCode, true);
        if (forwarder != null) forwarder.sendKeyEvent(androidKeyCode, true);
        panel.updateToggleButtons();
    }

    private boolean getDown(int keyCode) {
        switch (keyCode) {
            case KEYCODE_CTRL_LEFT: return panel.isCtrlDown();
            case KEYCODE_ALT_LEFT: return panel.isAltDown();
            case KEYCODE_SHIFT_LEFT: return panel.isShiftDown();
            case KEYCODE_META_LEFT: return panel.isWinDown();
            default: return false;
        }
    }

    private void setDown(int keyCode, boolean value) {
        switch (keyCode) {
            case KEYCODE_CTRL_LEFT: panel.setCtrlDown(value); break;
            case KEYCODE_ALT_LEFT: panel.setAltDown(value); break;
            case KEYCODE_SHIFT_LEFT: panel.setShiftDown(value); break;
            case KEYCODE_META_LEFT: panel.setWinDown(value); break;
        }
    }

    private boolean getSticky(int keyCode) {
        switch (keyCode) {
            case KEYCODE_CTRL_LEFT: return ctrlSticky;
            case KEYCODE_ALT_LEFT: return altSticky;
            case KEYCODE_SHIFT_LEFT: return shiftSticky;
            case KEYCODE_META_LEFT: return winSticky;
            default: return false;
        }
    }

    private void setSticky(int keyCode, boolean value) {
        switch (keyCode) {
            case KEYCODE_CTRL_LEFT: ctrlSticky = value; break;
            case KEYCODE_ALT_LEFT: altSticky = value; break;
            case KEYCODE_SHIFT_LEFT: shiftSticky = value; break;
            case KEYCODE_META_LEFT: winSticky = value; break;
        }
    }
}
