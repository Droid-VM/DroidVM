package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import static android.view.KeyEvent.KEYCODE_CAPS_LOCK;
import static android.view.KeyEvent.KEYCODE_MINUS;
import static android.view.KeyEvent.KEYCODE_SLASH;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.ui.vm.display.base.BaseExtraKeysAdapter;
import cn.classfun.droidvm.ui.vm.display.base.DisplayExtraKeysPanel;

/**
 * Adapts the shared {@link DisplayExtraKeysPanel} to the native backend, emitting evdev key events
 * through {@link InputForwarder}. The sticky-modifier handling lives in {@link BaseExtraKeysAdapter};
 * only the emit/ready hooks and the repeat/caps key mapping are backend-specific.
 */
public final class NativeExtraKeysPanel extends BaseExtraKeysAdapter {
    @Nullable
    private InputForwarder forwarder;

    public NativeExtraKeysPanel(@NonNull DisplayExtraKeysPanel panel) {
        super(panel);
    }

    public void setForwarder(@Nullable InputForwarder forwarder) {
        this.forwarder = forwarder;
    }

    @Override
    protected void emitKey(int androidKeyCode, boolean down) {
        if (forwarder != null) forwarder.sendKeyEvent(androidKeyCode, down);
    }

    @Override
    protected boolean isReady() {
        return forwarder != null;
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
        if (!isReady()) return;
        emitKey(KEYCODE_CAPS_LOCK, true);
        emitKey(KEYCODE_CAPS_LOCK, false);
    }
}
