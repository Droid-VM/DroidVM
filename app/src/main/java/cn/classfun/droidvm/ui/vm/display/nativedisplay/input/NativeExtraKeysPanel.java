// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.ui.vm.display.base.BaseExtraKeysAdapter;
import cn.classfun.droidvm.ui.vm.display.base.DisplayExtraKeysPanel;

/**
 * Adapts the shared {@link DisplayExtraKeysPanel} to the native backend, emitting evdev key events
 * through {@link InputForwarder}. The sticky-modifier and key down/up handling live in
 * {@link BaseExtraKeysAdapter}; only the emit/ready hooks are backend-specific.
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
}
