package cn.classfun.droidvm.ui.vm.display.vnc.input;

import static android.view.KeyEvent.*;
import static cn.classfun.droidvm.ui.vm.display.base.X11Keymap.androidKeyToXKeysym;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.ui.vm.display.base.DisplayExtraKeysPanel;
import cn.classfun.droidvm.ui.vm.display.base.KeyListener;
import cn.classfun.droidvm.ui.vm.display.vnc.base.VncClient;

public final class VncExtraKeysPanel implements KeyListener {
    @Nullable
    private VncClient vncClient;
    @NonNull
    private final DisplayExtraKeysPanel panel;

    private boolean ctrlSticky, altSticky, shiftSticky, winSticky;

    public VncExtraKeysPanel(@NonNull DisplayExtraKeysPanel panel) {
        this.panel = panel;
        panel.setKeyListener(this);
    }

    public void setVncClient(@Nullable VncClient client) {
        this.vncClient = client;
    }

    @NonNull
    @SuppressWarnings("unused")
    public DisplayExtraKeysPanel getPanel() {
        return panel;
    }

    public boolean hasNonStickyModifiers() {
        return (panel.isCtrlDown() && !ctrlSticky)
            || (panel.isAltDown() && !altSticky)
            || (panel.isShiftDown() && !shiftSticky)
            || (panel.isWinDown() && !winSticky);
    }

    public void applyModifiers(boolean down) {
        if (vncClient == null || !vncClient.isConnected()) return;
        if (panel.isCtrlDown() && !ctrlSticky)
            vncClient.sendKey(androidKeyToXKeysym(KEYCODE_CTRL_LEFT), down);
        if (panel.isAltDown() && !altSticky)
            vncClient.sendKey(androidKeyToXKeysym(KEYCODE_ALT_LEFT), down);
        if (panel.isShiftDown() && !shiftSticky)
            vncClient.sendKey(androidKeyToXKeysym(KEYCODE_SHIFT_LEFT), down);
        if (panel.isWinDown() && !winSticky)
            vncClient.sendKey(androidKeyToXKeysym(KEYCODE_META_LEFT), down);
        if (!down) {
            if (!ctrlSticky) panel.setCtrlDown(false);
            if (!altSticky) panel.setAltDown(false);
            if (!shiftSticky) panel.setShiftDown(false);
            if (!winSticky) panel.setWinDown(false);
            panel.updateToggleButtons();
        }
    }

    public void sendKeysym(int keysym) {
        if (vncClient == null || !vncClient.isConnected() || keysym == 0) return;
        applyModifiers(true);
        vncClient.sendKey(keysym, true);
        vncClient.sendKey(keysym, false);
        applyModifiers(false);
    }

    public void sendKey(int androidKeyCode) {
        sendKeysym(androidKeyToXKeysym(androidKeyCode));
    }

    public void sendChar(char ch) {
        sendKeysym(ch);
    }

    @Override
    public void onKeyRepeat(int androidKeyCode) {
        sendKey(androidKeyCode);
    }

    @Override
    public void onCharRepeat(char ch) {
        sendChar(ch);
    }


    @Override
    public void onCapsToggle(boolean active) {
        sendKey(KEYCODE_CAPS_LOCK);
    }

    @Override
    public void onModifierClick(int androidKeyCode) {
        if (getSticky(androidKeyCode)) {
            setSticky(androidKeyCode, false);
            setDown(androidKeyCode, false);
            if (vncClient != null && vncClient.isConnected())
                vncClient.sendKey(androidKeyToXKeysym(androidKeyCode), false);
        } else {
            setDown(androidKeyCode, !getDown(androidKeyCode));
        }
        panel.updateToggleButtons();
    }

    @Override
    public void onModifierLongClick(int androidKeyCode) {
        setDown(androidKeyCode, true);
        setSticky(androidKeyCode, true);
        if (vncClient != null && vncClient.isConnected())
            vncClient.sendKey(androidKeyToXKeysym(androidKeyCode), true);
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
