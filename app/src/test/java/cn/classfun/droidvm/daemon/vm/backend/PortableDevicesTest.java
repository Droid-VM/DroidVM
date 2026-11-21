// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VpuConfig;

/**
 * Two devices a VM used to get only through hand-written extra options -- the battery, and the
 * switch that moves its media devices to virtio-mmio -- now come from its settings, so a
 * configuration carries no phone's paths or uids and moves between phones as it is (a vmpkg).
 * What is asserted is the exact argument, and that a VM still carrying the old extra option
 * does not get the device twice.
 */
public final class PortableDevicesTest {
    private static DataItem vm(boolean vpu, String... extra) {
        var item = VMConfig.createWithCustomizeDefaults(null).item;
        VpuConfig.setEnabled(item, vpu);
        var options = DataItem.newArray();
        for (var option : extra) options.append(DataItem.newString(option));
        item.set("extra_options", options);
        return item;
    }

    private static Set<String> extraOf(DataItem item) {
        var set = new HashSet<String>();
        item.opt("extra_options", DataItem.newArray()).forEach(arg -> set.add(arg.getValue().asString()));
        return set;
    }

    @Test
    public void mmioMediaFollowsTheVideoSwitch() {
        var on = vm(true);
        List<String> args = new ArrayList<>();
        CrosvmBackendInstance.appendMmioMediaSwitch(args, on, extraOf(on));
        assertEquals(List.of("--virtio-mmio-media"), args);

        var off = vm(false);
        args = new ArrayList<>();
        CrosvmBackendInstance.appendMmioMediaSwitch(args, off, extraOf(off));
        assertTrue(args.isEmpty());
    }

    @Test
    public void mmioMediaNotRepeatedFromExtraOptions() {
        var item = vm(true, "--virtio-mmio-media");
        List<String> args = new ArrayList<>();
        CrosvmBackendInstance.appendMmioMediaSwitch(args, item, extraOf(item));
        assertTrue(args.isEmpty());
    }

    @Test
    public void batteryRowIsTheGoldfishBattery() {
        List<String> args = new ArrayList<>();
        CrosvmBackendInstance.appendBattery(args, vm(false));
        assertEquals(List.of("--battery", "type=goldfish"), args);
    }

    @Test
    public void batteryNotRepeatedFromExtraOptions() {
        List<String> args = new ArrayList<>();
        CrosvmBackendInstance.appendBattery(args, vm(false, "--battery", "type=goldfish"));
        assertTrue(args.isEmpty());
    }
}
