// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm;

import java.io.Closeable;
import java.util.Objects;

import cn.classfun.droidvm.daemon.console.FDPipeConsoleStream;
import cn.classfun.droidvm.lib.natives.UnixHelper;

public final class SerialPipe implements Closeable {
    private final String name;
    private FDPipeConsoleStream stream;
    private final SerialPipeFds fds = new SerialPipeFds();

    public SerialPipe(
        FDPipeConsoleStream stream,
        String name
    ) {
        this.name = name;
        Objects.requireNonNull(stream, "stream");
        boolean ready = false;
        try {
            fds.setOutputPipe(UnixHelper.nativePipe());
            fds.setInputPipe(UnixHelper.nativePipe());
            fds.requireReady();
            fds.transferOutputLocal(stream::setReadFd, stream::getPosixReadFd);
            fds.transferInputLocal(stream::setWriteFd, stream::getPosixWriteFd);
            this.stream = stream;
            ready = true;
        } finally {
            // Do not close the caller's stream or its adopted ends on partial failure.
            if (!ready) fds.close(() -> {}, UnixHelper::nativeCloseFd);
        }
    }

    @SuppressWarnings("unused")
    public synchronized FDPipeConsoleStream getStream() {
        return stream;
    }

    public synchronized boolean isReady() {
        return stream != null && stream.isReady();
    }

    /** Borrowed local fd; the stream's PFD is its sole owner after construction. */
    public synchronized int getOutputLocalFd() {
        return fds.outputLocalFd();
    }

    public synchronized int getOutputRemoteFd() {
        return fds.outputRemoteFd();
    }

    /** Borrowed local fd; the stream's PFD is its sole owner after construction. */
    public synchronized int getInputLocalFd() {
        return fds.inputLocalFd();
    }

    public synchronized int getInputRemoteFd() {
        return fds.inputRemoteFd();
    }

    public synchronized void closeRemoteFd() {
        fds.closeRemoteFd(UnixHelper::nativeCloseFd);
    }

    @Override
    public synchronized void close() {
        FDPipeConsoleStream ownedStream = stream;
        stream = null;
        fds.close(() -> {
            if (ownedStream != null) ownedStream.close();
        }, UnixHelper::nativeCloseFd);
    }

    @SuppressWarnings("unused")
    public String getName() {
        return name;
    }
}
