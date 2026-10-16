// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm;

import static org.junit.Assert.assertEquals;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class VmRunningQueryTest {
    @Test public void missingCandidateIsNotEvidenceOfStoppedVm() {
        assertEquals(List.of("renamed"), VmRunningQuery.inUseFromStates(List.of("renamed", "stopped"), Map.of("stopped", "STOPPED")));
    }
    @Test public void allTransitionStatesAndUnknownStateRemainInUse() {
        var states = Map.of("a", "STARTING", "b", "RUNNING", "c", "SUSPENDED", "d", "STOPPING", "e", "REBOOTING", "f", "FUTURE", "g", "");
        var candidates = List.of("a", "b", "c", "d", "e", "f", "g");
        assertEquals(candidates, VmRunningQuery.inUseFromStates(candidates, states));
    }
    @Test public void onlyConfirmedStoppedCandidatesAreReleased() {
        assertEquals(List.of("running"), VmRunningQuery.inUseFromStates(List.of("stopped", "running"), Map.of("stopped", "stopped", "running", "RUNNING", "unrelated", "RUNNING")));
    }
}
