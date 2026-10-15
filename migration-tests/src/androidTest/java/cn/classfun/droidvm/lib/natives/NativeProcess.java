// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.natives;

// Signature-only fixture for the production JNI exports. The implementation is
// built directly from app's native_process.c and daemon's common/process.c.
public final class NativeProcess {
    static { System.loadLibrary("migration_process"); }
    public static native int[] nativeForkExec(String[] argv, String[] envp, String dir, int[] preserveFds, long[] rlimits);
    public static native int nativeWaitPid(int pid);
    public static native int nativeTryWaitPid(int pid);
    public static native void nativeKill(int pid, int signal);
}
