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
