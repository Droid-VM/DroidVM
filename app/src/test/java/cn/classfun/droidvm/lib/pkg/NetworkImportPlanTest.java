package cn.classfun.droidvm.lib.pkg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Test;

import java.util.List;
import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.network.BridgeType;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.UplinkMode;
import cn.classfun.droidvm.lib.store.network.VlanConfig;

/** Which networks a packaged one may join, in which order, and what creating it would be called. */
public class NetworkImportPlanTest {
    @NonNull
    private static NetworkConfig l3(
        @NonNull BridgeType type, @NonNull String name, @Nullable String v4, @Nullable String v6
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
    public void onlyNetworksOfTheSameKindCanBeJoined() {
        var linux = l3(BridgeType.LINUX, "brlinux", "192.168.50.1/24", null);
        var gvisor = l3(BridgeType.GVISOR, "brgvisor", "192.168.60.1/24", null);
        var bridged = l2("brl2", "wlan0");
        var plan = new NetworkImportPlan(List.of(linux, gvisor, bridged));
        var packaged = l3(BridgeType.LINUX, "brpkg", "192.168.70.1/24", null);
        var candidates = plan.candidates(packaged);
        assertEquals(1, candidates.size());
        assertEquals("brlinux", candidates.get(0).getName());
    }

    @Test
    public void theNearestSubnetComesFirst() {
        var far = l3(BridgeType.LINUX, "brfar", "10.0.0.1/24", null);
        var near = l3(BridgeType.LINUX, "brnear", "192.168.50.1/24", null);
        var plan = new NetworkImportPlan(List.of(far, near));
        var packaged = l3(BridgeType.LINUX, "brpkg", "192.168.51.1/24", null);
        assertEquals("brnear", plan.candidates(packaged).get(0).getName());
    }

    @Test
    public void withoutIpv4TheIpv6PrefixDecidesTheOrder() {
        var far = l3(BridgeType.LINUX, "brfar", null, "fd00:9::1/64");
        var near = l3(BridgeType.LINUX, "brnear", null, "fd00:50::1/64");
        var plan = new NetworkImportPlan(List.of(far, near));
        var packaged = l3(BridgeType.LINUX, "brpkg", null, "fd00:50::1/64");
        assertEquals("brnear", plan.candidates(packaged).get(0).getName());
    }

    @Test
    public void theMatchingUplinkComesFirst() {
        var other = l2("brother", "eth0");
        var same = l2("brsame", "wlan0");
        var plan = new NetworkImportPlan(List.of(other, same));
        assertEquals("brsame", plan.candidates(l2("brpkg", "wlan0")).get(0).getName());
    }

    @Test
    public void creatingIsRefusedOnlyByItsOwnKind() {
        var gvisor = l3(BridgeType.GVISOR, "brgvisor", "192.168.50.1/24", null);
        var plan = new NetworkImportPlan(List.of(gvisor));
        assertNull(plan.createConflict(l3(BridgeType.LINUX, "brpkg", "192.168.50.1/24", null)));
        assertNotNull(plan.createConflict(l3(BridgeType.GVISOR, "brpkg", "192.168.50.1/24", null)));
    }

    @Test
    public void aTakenNameIsChangedRatherThanRefused() {
        var existing = l3(BridgeType.LINUX, "home", "192.168.50.1/24", null);
        var plan = new NetworkImportPlan(List.of(existing));
        var incoming = l3(BridgeType.LINUX, "home", "192.168.70.1/24", null);
        plan.adopt(incoming);
        assertEquals("home_1", incoming.getName());
    }

    @Test
    public void aRenamedBridgeStaysWithinTheInterfaceNameCap() {
        var existing = l3(BridgeType.LINUX, "brabcdefghij", "192.168.50.1/24", null);
        var plan = new NetworkImportPlan(List.of(existing));
        var incoming = l3(BridgeType.LINUX, "other", "192.168.70.1/24", null);
        incoming.setBridgeName("brabcdefghij");
        plan.adopt(incoming);
        var bridge = incoming.getBridgeName();
        assertNotNull(bridge);
        assertNotEquals("brabcdefghij", bridge);
        assertTrue(bridge.length() <= 12);
    }

    @Test
    public void twoCreationsCannotClaimTheSameName() {
        var plan = new NetworkImportPlan(List.of());
        var first = l3(BridgeType.LINUX, "guest", "192.168.70.1/24", null);
        var second = l3(BridgeType.LINUX, "guest", "192.168.71.1/24", null);
        plan.adopt(first);
        plan.adopt(second);
        assertEquals("guest", first.getName());
        assertEquals("guest_1", second.getName());
    }

    @Test
    public void anIdAlreadyInUseIsReplaced() {
        var existing = l3(BridgeType.LINUX, "home", "192.168.50.1/24", null);
        var plan = new NetworkImportPlan(List.of(existing));
        var incoming = l3(BridgeType.LINUX, "guest", "192.168.70.1/24", null);
        incoming.setId(existing.getId());
        plan.adopt(incoming);
        assertNotEquals(existing.getId(), incoming.getId());
    }
}
