// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * One generation of native-display input sockets: release asks the accept worker to stop, but the
 * worker alone closes the listener and wake descriptors after its last syscall. Closing them from
 * the releasing thread instead lets an old worker (or a second release) close numbers the OS has
 * already reused for the next generation.
 */
public final class NativeInputSocketOwnerTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void await(CountDownLatch latch) {
        try {
            check(latch.await(5, TimeUnit.SECONDS), "latch timed out");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void stopBeforeWorkerStarts() {
        var closed = new ArrayList<Integer>();
        var owner = new NativeInputSocketOwner(10, 11, 12);
        owner.stop(closed::add);
        owner.stop(closed::add);
        check(owner.isStopped(), "stop must be permanent");
        check(closed.equals(List.of(12)), "only wake writer may close before worker exits");
        check(owner.serverFd() == 10 && owner.wakeReadFd() == 11, "worker fds still owned");
        owner.finish(closed::add);
        owner.finish(closed::add);
        check(closed.equals(List.of(12, 10, 11)), "each fd closes exactly once");
        check(owner.serverFd() == -1 && owner.wakeReadFd() == -1, "ownership invalidated");
    }

    @Test
    public void concurrentStopsDoNotCloseActiveListener() throws Exception {
        var closed = new ArrayList<Integer>();
        var owner = new NativeInputSocketOwner(20, 21, 22);
        var enteredClose = new CountDownLatch(1);
        var allowClose = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        IntConsumer closer = fd -> {
            closed.add(fd);
            if (fd == 22) {
                enteredClose.countDown();
                await(allowClose);
            }
        };
        var first = new Thread(() -> owner.stop(closer));
        var second = new Thread(() -> {
            secondEntered.countDown();
            owner.stop(closer);
        });
        first.start();
        await(enteredClose);
        second.start();
        await(secondEntered);
        check(owner.isStopped(), "closed must latch before wake close returns");
        allowClose.countDown();
        first.join(5000);
        second.join(5000);
        check(!first.isAlive() && !second.isAlive(), "release threads must complete");
        check(closed.equals(List.of(22)), "concurrent releases must not close worker fds");
        owner.finish(closer);
        check(closed.equals(List.of(22, 20, 21)), "worker closes listener/read once");
    }

    @Test
    public void finishBeforeReleaseAndFdReuse() {
        var closed = new ArrayList<Integer>();
        var old = new NativeInputSocketOwner(30, 31, 32);
        old.finish(closed::add);
        var next = new NativeInputSocketOwner(30, 31, 32); // OS reuses all descriptor numbers.
        old.stop(closed::add);
        old.finish(closed::add);
        check(closed.size() == 3, "old generation must not close reused fd numbers");
        check(!next.isStopped() && old.isStopped(), "new generation cannot revive old one");
        next.finish(closed::add);
        check(closed.equals(List.of(32, 30, 31, 32, 30, 31)), "new owner closes its own fds");
    }
}
