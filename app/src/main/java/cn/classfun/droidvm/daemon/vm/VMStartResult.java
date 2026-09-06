// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm;

import cn.classfun.droidvm.lib.natives.NativeProcess;

public final class VMStartResult {
    private NativeProcess process;

    public NativeProcess getProcess() {
        return process;
    }

    public void setProcess(NativeProcess process) {
        this.process = process;
    }

    public boolean isSuccess() {
        return process != null && process.isAlive();
    }
}
