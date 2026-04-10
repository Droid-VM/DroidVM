package cn.classfun.droidvm.ui.vm.display.vnc.input;

import static java.lang.Math.max;
import static java.lang.Math.min;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.ui.vm.display.base.DisplayTouchPadPanel;
import cn.classfun.droidvm.ui.vm.display.base.DisplayTouchPadPanel.Buttons;
import cn.classfun.droidvm.ui.vm.display.vnc.base.VncClient;

public final class VncTouchPadPanel implements DisplayTouchPadPanel.TouchPadListener {
    @Nullable
    private VncClient vncClient;
    @NonNull
    private final DisplayTouchPadPanel panel;

    private float cursorX, cursorY;
    private int fbWidth, fbHeight;

    public VncTouchPadPanel(@NonNull DisplayTouchPadPanel panel) {
        this.panel = panel;
        panel.setTouchPadListener(this);
    }

    public void setVncClient(@Nullable VncClient client) {
        this.vncClient = client;
    }

    @NonNull
    @SuppressWarnings("unused")
    public DisplayTouchPadPanel getPanel() {
        return panel;
    }

    public void setFramebufferSize(int width, int height) {
        fbWidth = width;
        fbHeight = height;
        cursorX = width / 2f;
        cursorY = height / 2f;
    }

    @Override
    public void onCursorMove(float dx, float dy) {
        if (vncClient == null || !vncClient.isConnected()) return;
        if (fbWidth <= 0 || fbHeight <= 0) return;
        cursorX = max(0, min(cursorX + dx, fbWidth - 1));
        cursorY = max(0, min(cursorY + dy, fbHeight - 1));
        vncClient.sendPointer((int) cursorX, (int) cursorY, 0);
    }

    @Override
    public void onTap() {
        if (vncClient == null || !vncClient.isConnected()) return;
        vncClient.sendPointer((int) cursorX, (int) cursorY, Buttons.LEFT.toMask());
        vncClient.sendPointer((int) cursorX, (int) cursorY, 0);
    }

    @Override
    public void onScroll(boolean up) {
        if (vncClient == null || !vncClient.isConnected()) return;
        int mask = up ? 8 : 16;
        vncClient.sendPointer((int) cursorX, (int) cursorY, mask);
        vncClient.sendPointer((int) cursorX, (int) cursorY, 0);
    }

    @Override
    public void onMouseButton(Buttons button, boolean pressed) {
        if (vncClient == null || !vncClient.isConnected()) return;
        int mask = button.toMask();
        vncClient.sendPointer((int) cursorX, (int) cursorY, pressed ? mask : 0);
    }
}
