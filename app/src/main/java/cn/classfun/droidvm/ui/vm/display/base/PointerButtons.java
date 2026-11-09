// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm.display.base;

import java.util.HashMap;
import java.util.Map;

/** A guest button remains held until every host device holding it releases it. */
public final class PointerButtons {
    private final Map<Integer, Integer> devices = new HashMap<>();
    private final PointerEventQueue.Output output;
    private int combined;
    public PointerButtons(PointerEventQueue.Output output) { this.output = output; }
    public void set(int deviceId, int buttons) {
        if (buttons == 0) devices.remove(deviceId);
        else devices.put(deviceId, buttons & 31);
        int next = 0;
        for (int value : devices.values()) next |= value;
        int changed = combined ^ next;
        combined = next;
        for (int bit = 0; bit < 5; bit++)
            if ((changed & (1 << bit)) != 0) output.button(0x110 + bit, (next & (1 << bit)) != 0);
    }
    public void reset() {
        devices.clear();
        int held = combined; combined = 0;
        for (int bit = 0; bit < 5; bit++)
            if ((held & (1 << bit)) != 0) output.button(0x110 + bit, false);
    }
}
