package cn.classfun.droidvm.lib.store.network;

import static org.junit.Assert.assertThrows;

import androidx.annotation.NonNull;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.base.DataItem;

public class NetworkConfigValidatorTest {
    @NonNull
    private static NetworkConfig l3Config(@NonNull BridgeType type) {
        var config = new NetworkConfig();
        config.setName("test");
        config.setBridgeName("brtest");
        config.setBridgeType(type);
        config.setUplinkMode(UplinkMode.L3);
        config.l3().set("mac_address", "02:11:22:33:44:55");
        var vlans = DataItem.newArray();
        var vlan = VlanConfig.createDefault(0);
        vlan.ipv4().set("cidr", "10.181.7.1/24");
        vlans.append(vlan.item);
        config.l3().set("vlans", vlans);
        return config;
    }

    @Test
    public void validL3LinuxPasses() {
        NetworkConfigValidator.validate(l3Config(BridgeType.LINUX));
    }

    @Test
    public void l3WithZeroVlansPasses() {
        // VM-internal network: host doesn't participate (replaces "none")
        var config = l3Config(BridgeType.LINUX);
        config.l3().set("vlans", DataItem.newArray());
        NetworkConfigValidator.validate(config);
    }

    @Test
    public void emptyNameRejected() {
        var config = l3Config(BridgeType.LINUX);
        config.setName("  ");
        assertThrows(IllegalArgumentException.class,
            () -> NetworkConfigValidator.validate(config));
    }

    @Test
    public void badBridgeNameRejected() {
        var config = l3Config(BridgeType.LINUX);
        config.setBridgeName("0bad name");
        assertThrows(IllegalArgumentException.class,
            () -> NetworkConfigValidator.validate(config));
    }

    @Test
    public void gatewayAtNetworkAddressRejected() {
        var config = l3Config(BridgeType.LINUX);
        config.getVlans().get(0).ipv4().set("cidr", "10.181.7.0/24");
        assertThrows(IllegalArgumentException.class,
            () -> NetworkConfigValidator.validate(config));
    }

    @Test
    public void duplicateVlanIdRejected() {
        var config = l3Config(BridgeType.LINUX);
        var another = VlanConfig.createDefault(0);
        another.ipv4().set("cidr", "10.181.8.1/24");
        config.l3().get("vlans").append(another.item);
        assertThrows(IllegalArgumentException.class,
            () -> NetworkConfigValidator.validate(config));
    }

    @Test
    public void dhcpOffsetsBeyondNetworkRejected() {
        var config = l3Config(BridgeType.LINUX);
        var dhcp = config.getVlans().get(0).ipv4().get("dhcp");
        dhcp.set("offset_end", 300L);
        assertThrows(IllegalArgumentException.class,
            () -> NetworkConfigValidator.validate(config));
    }

    @Test
    public void ipv6SnatOnLinuxIgnored() {
        var config = l3Config(BridgeType.LINUX);
        var vlan = config.getVlans().get(0);
        vlan.ipv6().set("cidr", "fd00:7::1/64");
        vlan.ipv6().set("snat", true);
        NetworkConfigValidator.validate(config);
    }

    @Test
    public void ipv6SnatOnGvisorPasses() {
        var config = l3Config(BridgeType.GVISOR);
        var vlan = config.getVlans().get(0);
        vlan.ipv6().set("cidr", "fd00:7::1/64");
        vlan.ipv6().set("snat", true);
        NetworkConfigValidator.validate(config);
    }

    @Test
    public void pdOnGvisorRejected() {
        var config = l3Config(BridgeType.GVISOR);
        var vlan = config.getVlans().get(0);
        vlan.ipv6().set("source", "dhcp_pd");
        var pd = DataItem.newObject();
        pd.set("uplink", "WiFi");
        vlan.ipv6().set("pd", pd);
        assertThrows(IllegalArgumentException.class,
            () -> NetworkConfigValidator.validate(config));
    }

    @Test
    public void pdOnLinuxPasses() {
        var config = l3Config(BridgeType.LINUX);
        var vlan = config.getVlans().get(0);
        vlan.ipv6().set("source", "dhcp_pd");
        var pd = DataItem.newObject();
        pd.set("uplink", "WiFi");
        vlan.ipv6().set("pd", pd);
        NetworkConfigValidator.validate(config);
    }

    @Test
    public void secondaryOnGvisorRejected() {
        var config = l3Config(BridgeType.GVISOR);
        var sec = DataItem.newArray();
        sec.append(new DataItem("192.168.50.1/24"));
        config.getVlans().get(0).item.set("ipv4_secondary", sec);
        assertThrows(IllegalArgumentException.class,
            () -> NetworkConfigValidator.validate(config));
    }

    @Test
    public void l2OnGvisorRejected() {
        var config = l3Config(BridgeType.GVISOR);
        config.setUplinkMode(UplinkMode.L2);
        config.l2().set("uplink", "WiFi");
        assertThrows(IllegalArgumentException.class,
            () -> NetworkConfigValidator.validate(config));
    }

    @Test
    public void l2OnLinuxPasses() {
        var config = l3Config(BridgeType.LINUX);
        config.setUplinkMode(UplinkMode.L2);
        config.l2().set("uplink", "WiFi");
        config.l2().set("pseudo_bridge", "auto");
        NetworkConfigValidator.validate(config);
    }

    @Test
    public void slaacRequiresSlash64() {
        var config = l3Config(BridgeType.LINUX);
        var vlan = config.getVlans().get(0);
        vlan.ipv6().set("cidr", "fd00:7::1/80");
        vlan.ipv6().get("slaac").set("enabled", true);
        assertThrows(IllegalArgumentException.class,
            () -> NetworkConfigValidator.validate(config));
    }
}
