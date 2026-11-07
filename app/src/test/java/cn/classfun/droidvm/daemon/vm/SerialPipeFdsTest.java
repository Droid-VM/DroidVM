// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The serial pipe hands its two local ends to a {@link cn.classfun.droidvm.daemon.console.FDPipeConsoleStream}
 * through {@code adoptFd}, so the stream owns them afterwards. Closing the stream first and then
 * closing the saved integers again is a double close: the kernel can already have given the number
 * to an unrelated FileInputStream, and the second close then takes that one down. Every close in
 * these tests immediately reuses the number, so any raw close of an owned end is visible.
 */
public final class SerialPipeFdsTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void invalid(Runnable getter) {
        try {
            getter.run();
            throw new AssertionError("closed fd must not remain borrowable");
        } catch (IllegalStateException expected) {
        }
    }

    /** Every close immediately reuses the integer for an unrelated resource. */
    private static final class FakeCloser {
        final HashMap<Integer, String> live = new HashMap<>();
        final List<Integer> closed = new ArrayList<>();

        FakeCloser(int... fds) {
            for (int fd : fds) live.put(fd, "pipe");
        }

        void close(int fd) {
            check("pipe".equals(live.get(fd)), String.format("closed reused/unowned fd %d", fd));
            live.put(fd, "unrelated");
            closed.add(fd);
        }
    }

    private static SerialPipeFds pipes() {
        var fds = new SerialPipeFds();
        fds.setInputPipe(new int[]{0, 1});
        fds.setOutputPipe(new int[]{2, 3});
        fds.requireReady();
        return fds;
    }

    private static void transferBoth(SerialPipeFds fds) {
        fds.transferOutputLocal(fd -> {}, () -> 2);
        fds.transferInputLocal(fd -> {}, () -> 1);
    }

    @Test
    public void transferredLocalsAreNeverRawClosed() {
        var fds = pipes();
        var fake = new FakeCloser(0, 1, 2, 3);
        transferBoth(fds);
        check(fds.inputLocalFd() == 1 && fds.outputLocalFd() == 2,
            "transfer must preserve borrowed getter values");
        fds.close(() -> {
            fake.close(2); // PFD close immediately reuses the local integer.
            fake.close(1);
        }, fake::close);
        fds.closeRemoteFd(fake::close);
        fds.close(() -> {}, fake::close);
        check(fake.closed.equals(List.of(2, 1, 0, 3)), "each end closes once");
        invalid(fds::inputLocalFd);
        invalid(fds::outputLocalFd);
        invalid(fds::inputRemoteFd);
        invalid(fds::outputRemoteFd);
    }

    @Test
    public void partialTransferFailure() {
        for (int transfers = 0; transfers <= 2; transfers++) {
            var fds = pipes();
            var fake = new FakeCloser(0, 1, 2, 3);
            var failure = new IllegalStateException("fake setter failure");
            try {
                if (transfers == 0) {
                    fds.transferOutputLocal(fd -> { throw failure; }, () -> -1);
                } else if (transfers == 1) {
                    fds.transferOutputLocal(fd -> { throw failure; }, () -> 2);
                } else {
                    fds.transferOutputLocal(fd -> {}, () -> 2);
                    fds.transferInputLocal(fd -> { throw failure; }, () -> 1);
                }
                throw new AssertionError("setter failure must propagate");
            } catch (IllegalStateException expected) {
                check(expected == failure, "preserve original setter failure");
            }
            // Constructor failure does not close the caller-owned stream, even if
            // a setter threw after adoptFd but before returning successfully.
            fds.close(() -> {}, fake::close);
            check(fake.closed.size() == 4 - transfers, "close only untransferred ends");
            if (transfers >= 1) {
                check("pipe".equals(fake.live.get(2)), "read PFD must retain ownership");
                fake.close(2);
            }
            if (transfers == 2) {
                check("pipe".equals(fake.live.get(1)), "write PFD must retain ownership");
                fake.close(1);
            }
            fds.closeRemoteFd(fake::close);
            fds.close(() -> {}, fake::close);
            check(fake.closed.size() == 4, "cleanup must not touch reused descriptors");
        }
    }

    @Test
    public void creationFailureClosesOnlyAllocatedFds() {
        for (boolean inputOnly : new boolean[]{false, true}) {
            var fds = new SerialPipeFds();
            var fake = new FakeCloser(10, 11);
            if (inputOnly) fds.setInputPipe(new int[]{10, 11});
            else fds.setOutputPipe(new int[]{10, 11});
            invalid(fds::requireReady);
            fds.closeRemoteFd(fake::close); // Also covers the former pipe_out/pipe_in typo.
            fds.close(() -> {}, fake::close);
            check(fake.closed.size() == 2, "single allocated pipe must close once");
        }
    }

    @Test
    public void remoteCloseDetachesBeforeReentry() {
        var fds = pipes();
        var fake = new FakeCloser(0, 1, 2, 3);
        transferBoth(fds);
        fds.closeRemoteFd(fd -> {
            invalid(fds::inputRemoteFd);
            invalid(fds::outputRemoteFd);
            fake.close(fd);
            fds.closeRemoteFd(fake::close);
        });
        check(fake.closed.equals(List.of(0, 3)), "remote release closes neither local end");
        check(fds.inputLocalFd() == 1 && fds.outputLocalFd() == 2,
            "local borrows survive remote release");
        fds.close(() -> { fake.close(2); fake.close(1); }, fake::close);
        check(fake.closed.equals(List.of(0, 3, 2, 1)), "no remote reclose after reuse");
    }

    @Test
    public void fullCloseDetachesBeforeStreamClose() {
        var fds = pipes();
        var fake = new FakeCloser(0, 1, 2, 3);
        transferBoth(fds);
        fds.close(() -> {
            invalid(fds::inputLocalFd);
            invalid(fds::outputLocalFd);
            invalid(fds::inputRemoteFd);
            invalid(fds::outputRemoteFd);
            fds.closeRemoteFd(fake::close);
            fds.close(() -> {}, fake::close);
            fake.close(2);
            fake.close(1);
        }, fake::close);
        check(fake.closed.equals(List.of(2, 1, 0, 3)), "reentry cannot reacquire closing fds");
    }

    @Test
    public void streamFailureStillClosesRemoteEnds() {
        var fds = pipes();
        var fake = new FakeCloser(0, 1, 2, 3);
        transferBoth(fds);
        var failure = new IllegalStateException("fake stream close failure");
        try {
            fds.close(() -> { throw failure; }, fake::close);
            throw new AssertionError("stream failure must propagate");
        } catch (IllegalStateException expected) {
            check(expected == failure, "preserve stream failure");
        }
        check(fake.closed.equals(List.of(0, 3)), "remote cleanup runs despite stream failure");
        fds.close(() -> {}, fake::close);
        check(fake.closed.size() == 2, "failed close is still detached");
    }

    private static void await(CountDownLatch latch) {
        try {
            check(latch.await(5, TimeUnit.SECONDS), "latch timed out");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void concurrentCloseAndRemoteClose() throws Exception {
        var fds = pipes();
        var fake = new FakeCloser(0, 1, 2, 3);
        transferBoth(fds);
        var inStreamClose = new CountDownLatch(1);
        var remoteStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var full = new Thread(() -> {
            try {
                fds.close(() -> {
                    inStreamClose.countDown();
                    await(release);
                    fake.close(2);
                    fake.close(1);
                }, fake::close);
            } catch (Throwable e) { failure.compareAndSet(null, e); }
        });
        var remote = new Thread(() -> {
            remoteStarted.countDown();
            try { fds.closeRemoteFd(fake::close); }
            catch (Throwable e) { failure.compareAndSet(null, e); }
        });
        full.start();
        try {
            await(inStreamClose);
            remote.start();
            await(remoteStarted);
        } finally {
            release.countDown();
        }
        full.join(5000);
        remote.join(5000);
        check(!full.isAlive() && !remote.isAlive(), "close threads must complete");
        if (failure.get() != null) throw new AssertionError(failure.get());
        check(fake.closed.equals(List.of(2, 1, 0, 3)), "concurrent release closes once");
    }
}
