// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The timing policy around a rule pass, walked by hand: a scheduler that only records, a probe
 * that answers from a script, and counters where the manager would run things.
 */
public final class UsbRulePassTimingTest {
    /** Keeps what was asked for and runs it only when told, so a test moves time itself. */
    private static final class FakeScheduler implements UsbRulePassTiming.Scheduler {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delayMs) {
            tasks.add(task);
            delays.add(delayMs);
        }

        boolean runNext() {
            var task = tasks.poll();
            if (task == null) return false;
            task.run();
            return true;
        }

        void runAll() {
            //noinspection StatementWithEmptyBody
            while (runNext()) {
            }
        }
    }

    private static final class Counter implements Runnable {
        int runs = 0;

        @Override
        public void run() {
            runs++;
        }
    }

    /** Answers from the script, repeating its last answer; counts how often it was asked. */
    private static final class Script implements BooleanSupplier {
        private final boolean[] answers;
        int asked = 0;

        Script(boolean... answers) {
            this.answers = answers;
        }

        @Override
        public boolean getAsBoolean() {
            var answer = answers[Math.min(asked, answers.length - 1)];
            asked++;
            return answer;
        }
    }

    @Test
    public void thePassRunsOnTheFirstProbeThatSaysReady() {
        var scheduler = new FakeScheduler();
        var wanted = new Script(true);
        var ready = new Script(false, false, true);
        var then = new Counter();
        var abandoned = new Counter();
        var gaveUp = new Counter();
        UsbRulePassTiming.whenReady(scheduler, wanted, ready, then, abandoned, gaveUp, 1000, 60);
        // Nothing has happened yet: the first probe is itself a scheduled task, on the worker.
        assertEquals(0, ready.asked);
        scheduler.runAll();
        assertEquals(3, ready.asked);
        assertEquals(1, then.runs);
        assertEquals(0, abandoned.runs);
        assertEquals(0, gaveUp.runs);
        assertEquals(Arrays.asList(0L, 1000L, 1000L), scheduler.delays);
    }

    @Test
    public void aVmThatIsReadyAtOnceWaitsForNothing() {
        var scheduler = new FakeScheduler();
        var then = new Counter();
        UsbRulePassTiming.whenReady(scheduler, new Script(true), new Script(true), then,
            new Counter(), new Counter(), 1000, 60);
        scheduler.runAll();
        assertEquals(1, then.runs);
        assertEquals(List.of(0L), scheduler.delays);
    }

    @Test
    public void theWaitGivesUpAfterTheLastProbe() {
        var scheduler = new FakeScheduler();
        var ready = new Script(false);
        var then = new Counter();
        var abandoned = new Counter();
        var gaveUp = new Counter();
        UsbRulePassTiming.whenReady(scheduler, new Script(true), ready, then, abandoned, gaveUp,
            1000, 3);
        scheduler.runAll();
        assertEquals(3, ready.asked);
        assertEquals(0, then.runs);
        assertEquals(0, abandoned.runs);
        assertEquals(1, gaveUp.runs);
        // Three probes, and nothing scheduled after the one that gave up.
        assertEquals(Arrays.asList(0L, 1000L, 1000L), scheduler.delays);
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    public void theWaitIsAbandonedWhenTheVmLeavesRunning() {
        var scheduler = new FakeScheduler();
        var wanted = new Script(true, true, false);
        var ready = new Script(false);
        var then = new Counter();
        var abandoned = new Counter();
        var gaveUp = new Counter();
        UsbRulePassTiming.whenReady(scheduler, wanted, ready, then, abandoned, gaveUp, 1000, 60);
        scheduler.runAll();
        // Wanted is asked before the probe each time, and the probe is not asked once it is not.
        assertEquals(3, wanted.asked);
        assertEquals(2, ready.asked);
        assertEquals(0, then.runs);
        assertEquals(1, abandoned.runs);
        assertEquals(0, gaveUp.runs);
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    public void aRetryStopsAtTheFirstPassThatReachedTheVmm() {
        var scheduler = new FakeScheduler();
        var attempt = new Script(false, true);
        UsbRulePassTiming.retry(scheduler, attempt, 2000, 3);
        // Scheduled, not run: the first pass of the trigger has just finished.
        assertEquals(0, attempt.asked);
        scheduler.runAll();
        assertEquals(2, attempt.asked);
        assertEquals(Arrays.asList(2000L, 2000L), scheduler.delays);
    }

    @Test
    public void aRetryIsBounded() {
        var scheduler = new FakeScheduler();
        var attempt = new Script(false);
        UsbRulePassTiming.retry(scheduler, attempt, 2000, 3);
        scheduler.runAll();
        assertEquals(3, attempt.asked);
        assertEquals(Arrays.asList(2000L, 2000L, 2000L), scheduler.delays);
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    public void noBudgetSchedulesNothing() {
        var scheduler = new FakeScheduler();
        var attempt = new Script(false);
        UsbRulePassTiming.retry(scheduler, attempt, 2000, 0);
        assertFalse(scheduler.runNext());
        assertEquals(0, attempt.asked);
    }

    @Test
    public void theConstantsAreTheAgreedOnes() {
        assertEquals(1000, UsbRulePassTiming.READY_POLL_MS);
        assertEquals(60, UsbRulePassTiming.READY_MAX_POLLS);
        assertEquals(2000, UsbRulePassTiming.RETRY_DELAY_MS);
        assertEquals(3, UsbRulePassTiming.MAX_RETRIES);
    }
}
