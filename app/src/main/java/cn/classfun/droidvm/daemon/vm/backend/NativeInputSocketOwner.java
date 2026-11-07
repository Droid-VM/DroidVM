// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.daemon.vm.backend;

import java.util.function.IntConsumer;

/** One generation's descriptors. Only the accept worker calls finish after its last syscall. */
final class NativeInputSocketOwner {
    private int serverFd;
    private int wakeReadFd;
    private int wakeWriteFd;
    private volatile boolean stopped;

    NativeInputSocketOwner(int serverFd, int wakeReadFd, int wakeWriteFd) {
        this.serverFd = serverFd;
        this.wakeReadFd = wakeReadFd;
        this.wakeWriteFd = wakeWriteFd;
    }

    boolean isStopped() {
        return stopped;
    }

    synchronized int serverFd() {
        return serverFd;
    }

    synchronized int wakeReadFd() {
        return wakeReadFd;
    }

    /** Closing the pipe writer signals HUP, without a blocking write or SIGPIPE risk. */
    synchronized void stop(IntConsumer close) {
        stopped = true;
        int fd = wakeWriteFd;
        wakeWriteFd = -1;
        if (fd >= 0) close.accept(fd);
    }

    /** Worker-only, or startup rollback before a worker has been started. Never retries close. */
    synchronized void finish(IntConsumer close) {
        stop(close);
        int server = serverFd;
        int wake = wakeReadFd;
        serverFd = -1;
        wakeReadFd = -1;
        if (server >= 0) close.accept(server);
        if (wake >= 0) close.accept(wake);
    }
}
