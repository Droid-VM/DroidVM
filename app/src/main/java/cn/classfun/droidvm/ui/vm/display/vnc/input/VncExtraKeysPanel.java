package cn.classfun.droidvm.ui.vm.display.vnc.input;

import static android.view.KeyEvent.KEYCODE_CAPS_LOCK;
import static cn.classfun.droidvm.ui.vm.display.base.X11Keymap.androidKeyToXKeysym;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.ui.vm.display.base.BaseExtraKeysAdapter;
import cn.classfun.droidvm.ui.vm.display.base.DisplayExtraKeysPanel;
import cn.classfun.droidvm.ui.vm.display.vnc.base.VncClient;

/**
 * Adapts the shared {@link DisplayExtraKeysPanel} to a VNC backend, emitting X keysyms through
 * {@link VncClient}. The sticky-modifier handling lives in {@link BaseExtraKeysAdapter}; only the
 * emit/ready hooks and the keysym-level send helpers are backend-specific.
 */
public final class VncExtraKeysPanel extends BaseExtraKeysAdapter {
    @Nullable
    private VncClient vncClient;

    public VncExtraKeysPanel(@NonNull DisplayExtraKeysPanel panel) {
        super(panel);
    }

    public void setVncClient(@Nullable VncClient client) {
        this.vncClient = client;
    }

    @NonNull
    @SuppressWarnings("unused")
    public DisplayExtraKeysPanel getPanel() {
        return panel;
    }

    @Override
    protected void emitKey(int androidKeyCode, boolean down) {
        if (vncClient != null) vncClient.sendKey(androidKeyToXKeysym(androidKeyCode), down);
    }

    @Override
    protected boolean isReady() {
        return vncClient != null && vncClient.isConnected();
    }

    public void sendKeysym(int keysym) {
        if (!isReady() || keysym == 0) return;
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
}
