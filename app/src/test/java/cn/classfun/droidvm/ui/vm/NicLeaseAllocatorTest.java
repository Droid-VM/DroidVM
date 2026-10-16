// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.network.VlanConfig;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;

public final class NicLeaseAllocatorTest {
    private static NetworkConfig network(String cidr) {
        var net = new NetworkConfig(); net.setName("network");
        net.item.set("uplink_mode", "l3");
        var vlan = VlanConfig.createDefault(0); vlan.ipv4().set("cidr", cidr);
        var vlans = DataItem.newArray(); vlans.append(vlan.item);
        var l3 = DataItem.newObject(); l3.set("vlans", vlans); net.item.set("l3", l3);
        return net;
    }

    private static VMConfig vm(NetworkConfig net, long... offsets) {
        var vm = new VMConfig(); vm.setName(vm.getId().toString());
        var nics = DataItem.newArray();
        for (long offset : offsets) {
            var nic = DataItem.newObject(); nic.set("network_id", net.getId().toString());
            var lease = DataItem.newObject(); lease.set("enabled", true);
            if (offset > 0) lease.set("offset", offset);
            nic.set("dhcp4_lease", lease); nics.append(nic);
        }
        vm.item.set("networks", nics);
        return vm;
    }

    private static long offset(VMConfig vm, int index) {
        return vm.item.get("networks").get(index).get("dhcp4_lease").optLong("offset", -1);
    }

    @Test
    public void reservesOtherVmsAndEarlierNicsWithoutChangingAssignedLease() {
        var net = network("10.0.0.1/24"); var networks = new NetworkStore(); networks.add(net);
        var observed = new VMStore(); observed.add(vm(net, 64));
        var target = vm(net, 70, 0, 0);
        NicLeaseAllocator.resolve(target, observed, networks);
        assertEquals(70, offset(target, 0));
        assertEquals(65, offset(target, 1));
        assertEquals(66, offset(target, 2));
    }

    @Test
    public void missingNetworkRejectsAllocation() {
        var target = vm(network("10.0.0.1/24"), 0);
        assertThrows(IllegalStateException.class, () -> NicLeaseAllocator.resolve(target, new VMStore(), new NetworkStore()));
        assertEquals(-1, offset(target, 0));
    }

    @Test
    public void networkWithoutDhcpRejectsAllocation() {
        var net = network("10.0.0.1/24");
        net.findVlan(0).ipv4().get("dhcp").set("enabled", false);
        var networks = new NetworkStore(); networks.add(net);
        var target = vm(net, 0);
        assertThrows(IllegalStateException.class, () -> NicLeaseAllocator.resolve(target, new VMStore(), networks));
        assertEquals(-1, offset(target, 0));
    }

    @Test
    public void exhaustedAddressSpaceStopsInsteadOfStartingWithMissingLease() {
        var net = network("10.0.0.1/27"); var networks = new NetworkStore(); networks.add(net);
        var target = vm(net, 0);
        assertThrows(IllegalStateException.class, () -> NicLeaseAllocator.resolve(target, new VMStore(), networks));
        assertEquals(-1, offset(target, 0));
    }
}
