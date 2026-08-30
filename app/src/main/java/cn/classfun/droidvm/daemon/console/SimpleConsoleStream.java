// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.console;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.store.vm.VMConfig;

public final class SimpleConsoleStream extends ConsoleStream {
    public SimpleConsoleStream(@NonNull VMConfig config, @NonNull String name) {
        super(config, name);
    }

    @Override
    public boolean isReadable() {
        return true;
    }

    @Override
    public boolean isWritable() {
        return false;
    }
}

