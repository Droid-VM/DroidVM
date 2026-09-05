// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import androidx.annotation.NonNull;

import java.util.function.BooleanSupplier;

/**
 * When a rule pass runs, as opposed to what it does: the wait for a VM's control socket after
 * RUNNING, and the retry of a pass that could not reach the VMM. Pure -- time is a
 * {@link Scheduler} and readiness a probe -- so the policy is tested with neither a VM nor a
 * clock, and the manager only supplies the worker, the probe and the pass.
 */
final class UsbRulePassTiming {
    /** How often a VM that has just reached RUNNING is asked whether its control socket is up. */
    static final long READY_POLL_MS = 1_000;
    /** How many times, before the pass is given up on: a minute, which no start needs. */
    static final int READY_MAX_POLLS = 60;
    /** How long after a pass found the VMM unreachable it is run again. */
    static final long RETRY_DELAY_MS = 2_000;
    /** How many such reruns one trigger gets; beyond that the next trigger owns the device. */
    static final int MAX_RETRIES = 3;

    /** Runs a task later, on whatever thread the owner keeps for these. */
    interface Scheduler {
        void schedule(@NonNull Runnable task, long delayMs);
    }

    private UsbRulePassTiming() {
    }

    /**
     * Probes [ready] on the scheduler, now and then once per [pollMs], and runs [then] on the
     * first yes. The wait ends without it when [wanted] turns false -- [abandoned] runs, and the
     * probe is not asked -- or once [maxPolls] probes have said no -- [gaveUp] runs.
     */
    static void whenReady(@NonNull Scheduler scheduler, @NonNull BooleanSupplier wanted,
                          @NonNull BooleanSupplier ready, @NonNull Runnable then,
                          @NonNull Runnable abandoned, @NonNull Runnable gaveUp,
                          long pollMs, int maxPolls) {
        scheduler.schedule(() -> probe(scheduler, wanted, ready, then, abandoned, gaveUp,
            pollMs, maxPolls, 1), 0);
    }

    private static void probe(@NonNull Scheduler scheduler, @NonNull BooleanSupplier wanted,
                              @NonNull BooleanSupplier ready, @NonNull Runnable then,
                              @NonNull Runnable abandoned, @NonNull Runnable gaveUp,
                              long pollMs, int maxPolls, int attempt) {
        if (!wanted.getAsBoolean()) {
            abandoned.run();
            return;
        }
        if (ready.getAsBoolean()) {
            then.run();
            return;
        }
        if (attempt >= maxPolls) {
            gaveUp.run();
            return;
        }
        scheduler.schedule(() -> probe(scheduler, wanted, ready, then, abandoned, gaveUp,
            pollMs, maxPolls, attempt + 1), pollMs);
    }

    /**
     * Runs [attempt] after [delayMs] and, for as long as it answers false, again after the same
     * delay, [maxAttempts] times at most. The pass that already ran is not one of them: this is
     * the tail of a trigger whose first pass could not reach the VMM.
     */
    static void retry(@NonNull Scheduler scheduler, @NonNull BooleanSupplier attempt,
                      long delayMs, int maxAttempts) {
        if (maxAttempts <= 0) return;
        scheduler.schedule(() -> {
            if (!attempt.getAsBoolean()) retry(scheduler, attempt, delayMs, maxAttempts - 1);
        }, delayMs);
    }
}
