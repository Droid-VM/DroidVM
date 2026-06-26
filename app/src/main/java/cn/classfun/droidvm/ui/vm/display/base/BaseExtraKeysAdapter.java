package cn.classfun.droidvm.ui.vm.display.base;

import static android.view.KeyEvent.KEYCODE_ALT_LEFT;
import static android.view.KeyEvent.KEYCODE_CTRL_LEFT;
import static android.view.KeyEvent.KEYCODE_META_LEFT;
import static android.view.KeyEvent.KEYCODE_SHIFT_LEFT;

import androidx.annotation.NonNull;

/**
 * Shared sticky-modifier state machine for the {@link DisplayExtraKeysPanel}. It bridges the
 * panel's Ctrl/Alt/Shift/Win toggles (sticky vs. one-shot) to a backend; the VNC and native-display
 * adapters differ only in {@link #emitKey} (how a key reaches the backend) and {@link #isReady}
 * (whether the backend can accept events), so everything else lives here once.
 */
public abstract class BaseExtraKeysAdapter implements KeyListener {
    @NonNull
    protected final DisplayExtraKeysPanel panel;

    private boolean ctrlSticky, altSticky, shiftSticky, winSticky;

    protected BaseExtraKeysAdapter(@NonNull DisplayExtraKeysPanel panel) {
        this.panel = panel;
        panel.setKeyListener(this);
    }

    /** Sends one key down/up to the backend. Only invoked when {@link #isReady()} is true. */
    protected abstract void emitKey(int androidKeyCode, boolean down);

    /** True when the backend can currently accept events. */
    protected abstract boolean isReady();

    /** True if any active modifier is non-sticky and so must be wrapped around a real keystroke. */
    public boolean hasNonStickyModifiers() {
        return (panel.isCtrlDown() && !ctrlSticky)
            || (panel.isAltDown() && !altSticky)
            || (panel.isShiftDown() && !shiftSticky)
            || (panel.isWinDown() && !winSticky);
    }

    /** Press (or release) every active, non-sticky modifier around a keystroke. */
    public void applyModifiers(boolean down) {
        if (!isReady()) return;
        if (panel.isCtrlDown() && !ctrlSticky) emitKey(KEYCODE_CTRL_LEFT, down);
        if (panel.isAltDown() && !altSticky) emitKey(KEYCODE_ALT_LEFT, down);
        if (panel.isShiftDown() && !shiftSticky) emitKey(KEYCODE_SHIFT_LEFT, down);
        if (panel.isWinDown() && !winSticky) emitKey(KEYCODE_META_LEFT, down);
        if (!down) {
            if (!ctrlSticky) panel.setCtrlDown(false);
            if (!altSticky) panel.setAltDown(false);
            if (!shiftSticky) panel.setShiftDown(false);
            if (!winSticky) panel.setWinDown(false);
            panel.updateToggleButtons();
        }
    }

    /** Taps a key with the active modifiers wrapped around it. */
    protected void tapKey(int androidKeyCode) {
        if (!isReady()) return;
        applyModifiers(true);
        emitKey(androidKeyCode, true);
        emitKey(androidKeyCode, false);
        applyModifiers(false);
    }

    @Override
    public void onModifierClick(int androidKeyCode) {
        if (getSticky(androidKeyCode)) {
            setSticky(androidKeyCode, false);
            setDown(androidKeyCode, false);
            if (isReady()) emitKey(androidKeyCode, false);
        } else {
            setDown(androidKeyCode, !getDown(androidKeyCode));
        }
        panel.updateToggleButtons();
    }

    @Override
    public void onModifierLongClick(int androidKeyCode) {
        setDown(androidKeyCode, true);
        setSticky(androidKeyCode, true);
        if (isReady()) emitKey(androidKeyCode, true);
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
