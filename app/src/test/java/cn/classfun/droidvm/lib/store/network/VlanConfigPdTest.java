package cn.classfun.droidvm.lib.store.network;

import static org.junit.Assert.assertEquals;

import androidx.annotation.NonNull;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * PD route-table persistence on the VLAN config: the id is bridgedhcp's
 * crash-cleanup key, so a value written through one wrapper must be seen
 * by every later read of the same stored item — that is what makes the
 * allocation stable across network restarts.
 */
public class VlanConfigPdTest {
    @NonNull
    private static NetworkConfig configWithVlan() {
        var config = new NetworkConfig();
        config.setName("test");
        config.setBridgeName("brtest");
        config.setBridgeType(BridgeType.LINUX);
        config.setUplinkMode(UplinkMode.L3);
        var vlans = DataItem.newArray();
        var vlan = VlanConfig.createDefault(0);
        vlan.ipv4().set("cidr", "10.181.7.1/24");
        vlans.append(vlan.item);
        config.l3().set("vlans", vlans);
        return config;
    }

    @Test
    public void routeTableDefaultsToUnallocated() {
        var config = configWithVlan();
        assertEquals(0, config.getVlans().get(0).getPdRouteTable());
    }

    @Test
    public void routeTablePersistsAcrossWrappers() {
        var config = configWithVlan();
        config.getVlans().get(0).setPdRouteTable(9991);
        // a fresh wrapper over the same stored item must see the value
        assertEquals(9991, config.getVlans().get(0).getPdRouteTable());
    }

    @Test
    public void routeTableCoexistsWithDuid() {
        var config = configWithVlan();
        var vlan = config.getVlans().get(0);
        vlan.setPdDuid("00:03:00:01:aa:bb:cc:dd:ee:ff");
        vlan.setPdRouteTable(9992);
        var reread = config.getVlans().get(0);
        assertEquals("00:03:00:01:aa:bb:cc:dd:ee:ff", reread.getPdDuid());
        assertEquals(9992, reread.getPdRouteTable());
    }
}
