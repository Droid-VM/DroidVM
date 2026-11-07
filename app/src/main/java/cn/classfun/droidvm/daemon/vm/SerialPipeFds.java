// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.daemon.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** SerialPipe's raw-fd ownership; transferred local ends remain borrowable, never closeable here. */
final class SerialPipeFds {
    private int[] pipeIn;
    private int[] pipeOut;
    private boolean inputLocalTransferred;
    private boolean outputLocalTransferred;

    synchronized void setInputPipe(int[] pipe) {
        pipeIn = pipe;
    }

    synchronized void setOutputPipe(int[] pipe) {
        pipeOut = pipe;
    }

    synchronized void requireReady() {
        if (!valid(pipeIn) || !valid(pipeOut))
            throw new IllegalStateException("Failed to create pipes");
    }

    private static boolean valid(int[] pipe) {
        return pipe != null && pipe.length == 2 && pipe[0] >= 0 && pipe[1] >= 0;
    }

    synchronized int outputLocalFd() {
        return borrow(pipeOut, 0, "output local");
    }

    synchronized int outputRemoteFd() {
        return borrow(pipeOut, 1, "output remote");
    }

    synchronized int inputLocalFd() {
        return borrow(pipeIn, 1, "input local");
    }

    synchronized int inputRemoteFd() {
        return borrow(pipeIn, 0, "input remote");
    }

    private static int borrow(int[] pipe, int index, String name) {
        if (pipe == null || pipe.length <= index || pipe[index] < 0)
            throw new IllegalStateException(fmt("%s fd invalid", name));
        return pipe[index];
    }

    synchronized void transferOutputLocal(IntConsumer adopter, IntSupplier adoptedFd) {
        transfer(outputLocalFd(), adopter, adoptedFd, () -> outputLocalTransferred = true);
    }

    synchronized void transferInputLocal(IntConsumer adopter, IntSupplier adoptedFd) {
        transfer(inputLocalFd(), adopter, adoptedFd, () -> inputLocalTransferred = true);
    }

    private static void transfer(int fd, IntConsumer adopter, IntSupplier adoptedFd,
                                 Runnable markTransferred) {
        try {
            adopter.accept(fd);
            markTransferred.run();
        } catch (RuntimeException | Error failure) {
            // A setter may fail after adoptFd, while constructing its Java stream wrapper.
            try {
                if (adoptedFd.getAsInt() == fd) markTransferred.run();
            } catch (RuntimeException | Error probeFailure) {
                // Ownership is uncertain: never risk raw-closing a possibly adopted fd.
                markTransferred.run();
                if (probeFailure != failure) failure.addSuppressed(probeFailure);
            }
            throw failure;
        }
    }

    synchronized void closeRemoteFd(IntConsumer closer) {
        // Detach both ends before invoking any closer, including a reentrant one.
        int input = detach(pipeIn, 0);
        int output = detach(pipeOut, 1);
        try {
            closeFd(input, closer);
        } finally {
            closeFd(output, closer);
        }
    }

    synchronized void close(Runnable closeStream, IntConsumer closer) {
        int[] input = pipeIn;
        int[] output = pipeOut;
        pipeIn = null;
        pipeOut = null;
        // Invalidate borrowed local numbers before stream.close() can release/reuse them.
        try {
            closeStream.run();
        } finally {
            try {
                closeOwned(input, inputLocalTransferred ? 1 : -1, closer);
            } finally {
                closeOwned(output, outputLocalTransferred ? 0 : -1, closer);
            }
        }
    }

    private static int detach(int[] pipe, int index) {
        if (pipe == null || pipe.length <= index) return -1;
        int fd = pipe[index];
        pipe[index] = -1;
        return fd;
    }

    private static void closeOwned(int[] pipe, int transferredIndex, IntConsumer closer) {
        if (pipe == null) return;
        for (int i = 0; i < pipe.length; i++) {
            if (i != transferredIndex) closeFd(pipe[i], closer);
        }
    }

    private static void closeFd(int fd, IntConsumer closer) {
        if (fd >= 0) closer.accept(fd);
    }
}
