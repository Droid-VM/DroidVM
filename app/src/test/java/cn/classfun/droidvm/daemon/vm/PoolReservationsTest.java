// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PoolReservationsTest {
    @Test
    public void rejectedTargetRollsBackWithoutDroppingAnotherVm() {
        var writes = new ArrayList<Long>();
        var pool = new PoolReservations(pages -> {
            writes.add(pages);
            return pages <= 100;
        });
        var running = UUID.randomUUID();
        var starting = UUID.randomUUID();
        assertTrue(pool.reserve(running, 100));
        assertFalse(pool.reserve(starting, 256));
        assertFalse(pool.contains(starting));
        assertTrue(pool.contains(running));
        pool.release(starting);
        pool.release(running);
        assertEquals(List.of(100L, 356L, 100L, 0L), writes);
    }

    @Test
    public void rejectedTargetDoesNotMakeNextStartLookReserved() {
        var writes = new ArrayList<Long>();
        var allowWrite = new java.util.concurrent.atomic.AtomicBoolean(false);
        var pool = new PoolReservations(pages -> {
            writes.add(pages);
            return pages == 0 || allowWrite.get();
        });
        var vm = UUID.randomUUID();
        assertFalse(pool.reserve(vm, 256));
        allowWrite.set(true);
        assertTrue(pool.reserve(vm, 256));
        assertEquals(List.of(256L, 0L, 256L), writes);
    }

    @Test
    public void lastExitReturnsTargetToZero() {
        var writes = new ArrayList<Long>();
        var pool = new PoolReservations(writes::add);
        var vm = UUID.randomUUID();
        pool.reserve(vm, 2560);
        pool.release(vm);
        assertEquals(List.of(2560L, 0L), writes);
    }

    @Test
    public void exitingOneVmPreservesTheOthersBudget() {
        var writes = new ArrayList<Long>();
        var pool = new PoolReservations(writes::add);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        pool.reserve(first, 2560);
        pool.reserve(second, 512);
        pool.release(first);
        pool.release(second);
        assertEquals(List.of(2560L, 3072L, 512L, 0L), writes);
    }

    @Test
    public void relaunchRetainsBudgetWithoutWriting() {
        var writes = new ArrayList<Long>();
        var pool = new PoolReservations(writes::add);
        var vm = UUID.randomUUID();
        pool.reserve(vm, 2560);
        // A relaunch keeps the registered VM even if configuration has since been changed.
        pool.reserve(vm, 3072);
        pool.reserve(vm, 2560);
        assertEquals(List.of(2560L), writes);
        pool.release(vm);
        assertEquals(List.of(2560L, 0L), writes);
    }

    @Test
    public void failedStartCanReleaseThenStartAgain() {
        var writes = new ArrayList<Long>();
        var pool = new PoolReservations(writes::add);
        var vm = UUID.randomUUID();
        pool.reserve(vm, 2560);
        pool.release(vm);
        pool.reserve(vm, 512);
        pool.release(vm);
        assertEquals(List.of(2560L, 0L, 512L, 0L), writes);
    }

    @Test
    public void unregisteredAndRepeatedExitsDoNotWrite() {
        var writes = new ArrayList<Long>();
        var pool = new PoolReservations(writes::add);
        var vm = UUID.randomUUID();
        pool.release(vm);
        pool.reserve(vm, 0);
        assertTrue(writes.isEmpty());
        pool.reserve(vm, 256);
        pool.release(vm);
        pool.release(vm);
        assertEquals(List.of(256L, 0L), writes);
    }

    @Test
    public void concurrentStartsAndExitsCannotOverwriteRemainingReservation() throws Exception {
        var writes = new ArrayList<Long>();
        var pool = new PoolReservations(writes::add);
        var retained = UUID.randomUUID();
        pool.reserve(retained, 100);
        var workers = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        var results = new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int i = 0; i < 20; i++) {
                results.add(workers.submit(() -> {
                    start.await();
                    var vm = UUID.randomUUID();
                    pool.reserve(vm, 256);
                    pool.release(vm);
                    return null;
                }));
            }
            start.countDown();
            for (var result : results) result.get(5, TimeUnit.SECONDS);
            assertEquals(Long.valueOf(100), writes.get(writes.size() - 1));
            assertTrue(writes.stream().allMatch(pages -> pages >= 100));
            pool.release(retained);
            assertEquals(Long.valueOf(0), writes.get(writes.size() - 1));
        } finally {
            workers.shutdownNow();
        }
    }
}
