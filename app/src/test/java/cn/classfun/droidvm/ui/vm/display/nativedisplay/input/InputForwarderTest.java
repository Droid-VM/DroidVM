// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import static cn.classfun.droidvm.lib.store.vm.NativeDisplay.MOUSE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import cn.classfun.droidvm.ui.vm.display.base.InputMode;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class InputForwarderTest {
    @Test
    public void queuedButtonsKeepTheChannelSelectedAtSubmission() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        List<Integer> channels = Collections.synchronizedList(new ArrayList<>());
        var forwarder = new InputForwarder((channel, data) -> {
            channels.add(channel);
            if (channels.size() == 1) {
                firstEntered.countDown();
                try {
                    return releaseFirst.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        });

        forwarder.sendPointerButton(EvdevEncoder.BTN_LEFT, true);
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
        forwarder.sendPointerButton(EvdevEncoder.BTN_RIGHT, true);
        forwarder.setInputMode(InputMode.TABLET);
        releaseFirst.countDown();
        forwarder.close();

        assertEquals(List.of(MOUSE, MOUSE), channels);
    }

    @Test
    public void closeDrainsQueuedButtonChanges() {
        List<Integer> channels = Collections.synchronizedList(new ArrayList<>());
        var forwarder = new InputForwarder((channel, data) -> {
            channels.add(channel);
            return true;
        });

        forwarder.sendPointerButton(EvdevEncoder.BTN_LEFT, true);
        forwarder.sendPointerButton(EvdevEncoder.BTN_LEFT, false);
        forwarder.close();

        assertEquals(List.of(MOUSE, MOUSE), channels);
    }
}
