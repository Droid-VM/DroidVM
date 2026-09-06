// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.input;

import static cn.classfun.droidvm.ui.vm.display.base.X11Keymap.androidKeyToXKeysym;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.ui.vm.display.base.BaseExtraKeysAdapter;
import cn.classfun.droidvm.ui.vm.display.base.DisplayExtraKeysPanel;
import cn.classfun.droidvm.ui.vm.display.vnc.base.VncClient;

/**
 * Adapts the shared {@link DisplayExtraKeysPanel} to a VNC backend, emitting X keysyms through
 * {@link VncClient}. The sticky-modifier and key down/up handling live in
 * {@link BaseExtraKeysAdapter}; only the emit/ready hooks are backend-specific.
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
        int keysym = androidKeyToXKeysym(androidKeyCode);
        if (keysym != 0 && vncClient != null) vncClient.sendKey(keysym, down);
    }

    @Override
    protected boolean isReady() {
        return vncClient != null && vncClient.isConnected();
    }
}
