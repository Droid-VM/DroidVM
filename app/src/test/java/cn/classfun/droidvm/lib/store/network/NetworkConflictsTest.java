package cn.classfun.droidvm.lib.store.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Test;

import java.util.List;
import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * The rule the whole thing turns on: a conflict is only a conflict between networks that share a
 * stack. Two Linux bridges, or two gVisor ones, cannot hold the same prefix; one of each can.
 */
public class NetworkConflictsTest {
    @NonNull
    private static NetworkConfig l3(
        @NonNull BridgeType type, @NonNull String name,
        @Nullable String v4, @Nullable String v6
    ) {
        var config = new NetworkConfig();
        config.setId(UUID.randomUUID());
        config.setName(name);
        config.setBridgeName(name);
        config.setBridgeType(type);
        config.setUplinkMode(UplinkMode.L3);
        var vlan = VlanConfig.createDefault(0);
        if (v4 != null) vlan.ipv4().set("cidr", v4);
        if (v6 != null) vlan.ipv6().set("cidr", v6);
        var vlans = DataItem.newArray();
        vlans.append(vlan.item);
        config.l3().set("vlans", vlans);
        return config;
    }

    @NonNull
    private static NetworkConfig l2(@NonNull String name, @NonNull String uplink) {
        var config = new NetworkConfig();
        config.setId(UUID.randomUUID());
        config.setName(name);
        config.setBridgeName(name);
        config.setBridgeType(BridgeType.LINUX);
        config.setUplinkMode(UplinkMode.L2);
        config.l2().set("uplink", uplink);
        return config;
    }

    @Test
    public void sameBridgeTypeOverlapConflicts() {
        var mine = l3(BridgeType.LINUX, "brmine", "192.168.50.1/24", null);
        var theirs = l3(BridgeType.LINUX, "brtheirs", "192.168.50.1/24", null);
        var conflict = NetworkConflicts.find(mine, List.of(theirs), null);
        assertNotNull(conflict);
        assertEquals(NetworkConflicts.Kind.IPV4, conflict.kind);
        assertEquals("brtheirs", conflict.otherName());
    }

    @Test
    public void gvisorPairAlsoConflicts() {
        var mine = l3(BridgeType.GVISOR, "brmine", "192.168.50.1/24", null);
        var theirs = l3(BridgeType.GVISOR, "brtheirs", "192.168.50.128/25", null);
        assertNotNull(NetworkConflicts.find(mine, List.of(theirs), null));
    }

    @Test
    public void acrossBridgeTypesTheSamePrefixIsFine() {
        // gVisor's addressing lives in its own process; the kernel never sees the prefix
        var mine = l3(BridgeType.GVISOR, "brmine", "192.168.50.1/24", "fd00:50::1/64");
        var theirs = l3(BridgeType.LINUX, "brtheirs", "192.168.50.1/24", "fd00:50::1/64");
        assertNull(NetworkConflicts.find(mine, List.of(theirs), null));
    }

    @Test
    public void ipv6OverlapConflicts() {
        var mine = l3(BridgeType.LINUX, "brmine", "192.168.50.1/24", "fd00:50::1/64");
        var theirs = l3(BridgeType.LINUX, "brtheirs", "10.9.9.1/24", "fd00:50::1/64");
        var conflict = NetworkConflicts.find(mine, List.of(theirs), null);
        assertNotNull(conflict);
        assertEquals(NetworkConflicts.Kind.IPV6, conflict.kind);
    }

    @Test
    public void excludedNetworkIsNotItsOwnConflict() {
        var mine = l3(BridgeType.LINUX, "brmine", "192.168.50.1/24", null);
        assertNull(NetworkConflicts.find(mine, List.of(mine), mine.getId()));
    }

    @Test
    public void oneUplinkTakesOneL2Network() {
        var mine = l2("brmine", "wlan0");
        var same = l2("brtheirs", "wlan0");
        var other = l2("brother", "eth0");
        var conflict = NetworkConflicts.find(mine, List.of(other, same), null);
        assertNotNull(conflict);
        assertEquals(NetworkConflicts.Kind.UPLINK, conflict.kind);
        assertEquals("brtheirs", conflict.otherName());
        assertNull(NetworkConflicts.find(mine, List.of(other), null));
    }

    @Test
    public void l2AndL3NeverCollide() {
        // an L2 network has no prefix, and an L3 one has no uplink to take
        var bridged = l2("brmine", "wlan0");
        var routed = l3(BridgeType.LINUX, "brtheirs", "192.168.50.1/24", null);
        assertNull(NetworkConflicts.find(bridged, List.of(routed), null));
        assertNull(NetworkConflicts.find(routed, List.of(bridged), null));
    }

    @Test
    public void selfOverlapIsFoundWithoutAnyOtherNetwork() {
        var config = l3(BridgeType.LINUX, "brmine", "192.168.50.1/24", null);
        var second = VlanConfig.createDefault(10);
        second.ipv4().set("cidr", "192.168.50.129/25");
        config.l3().get("vlans").append(second.item);
        var overlap = NetworkConflicts.findSelfOverlap(config);
        assertNotNull(overlap);
        assertEquals(2, overlap.length);
    }
}
