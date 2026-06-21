package cn.classfun.droidvm.daemon.network.backend.gvisor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.List;

import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.UplinkMode;
import cn.classfun.droidvm.lib.store.network.VlanConfig;

/**
 * Generates the gvswitch startup config (gateways only; VM ports, static
 * leases and forwards are managed over the REST API as VMs attach).
 */
final class GvswitchConfigBuilder {
    private GvswitchConfigBuilder() {
    }

    @NonNull
    static JSONObject build(@NonNull NetworkConfig config) throws JSONException {
        var root = new JSONObject();
        if (config.getUplinkMode() != UplinkMode.L3) return root;
        var gateways = new JSONArray();
        var bridgeMac = config.getBridgeMacAddress();
        for (var vlan : config.getVlans()) {
            var gw = buildGateway(vlan, vlan.isUntagged() ? bridgeMac : null);
            if (gw != null) gateways.put(gw);
        }
        if (gateways.length() > 0) root.put("gateways", gateways);
        return root;
    }

    @Nullable
    private static JSONObject buildGateway(
        @NonNull VlanConfig vlan, @Nullable String mac
    ) throws JSONException {
        var net4 = vlan.getIpv4Network();
        var net6 = vlan.getIpv6Network();
        if (net4 == null && net6 == null) return null;
        var gw = new JSONObject();
        gw.put("vlan", vlan.getVlanId());
        if (mac != null) gw.put("mac", mac);
        // gvswitch has a single internet-routing switch for both families;
        // per-family SNAT toggles are expressed via deny ranges
        boolean snat4 = net4 != null && vlan.isIpv4Snat();
        boolean snat6 = net6 != null && vlan.isIpv6Snat();
        boolean routing = snat4 || snat6;
        gw.put("enable_internet_routing", routing);
        // The single routing switch serves both families, so once it's on we
        // deny each family that isn't SNAT-enabled. !snat covers both cases:
        // SNAT toggled off, and the family having no network at all.
        var deny = new JSONArray();
        if (routing) {
            if (!snat4) deny.put("0.0.0.0/0");
            // slirpnetstack's range parser splits on bare ':' outside
            // brackets, so an IPv6 CIDR must be bracketed ("[::]/0"); a
            // plain "::/0" fails with "error parsing ipportrange".
            if (!snat6) deny.put("[::]/0");
        }
        if (deny.length() > 0) gw.put("deny", deny);
        // No explicit DNS: gvswitch advertises the gateway IP via DHCP and
        // the dns_proxy answers it through the Android system resolver
        // (dnsproxyd/resnsend), so guest lookups behave like the host's
        if (vlan.getDnsServers().isEmpty()) gw.put("dns_proxy", true);
        if (net4 != null) {
            var v4 = new JSONObject();
            v4.put("address", net4.address().toString());
            v4.put("prefix_len", net4.prefix());
            gw.put("ipv4", v4);
            if (vlan.isDhcp4Enabled()) {
                var dhcp = new JSONObject();
                dhcp.put("enabled", true);
                dhcp.put("pool_start",
                    net4.addressAtOffset(vlan.getDhcp4OffsetStart()).toString());
                dhcp.put("pool_end",
                    net4.addressAtOffset(vlan.getDhcp4OffsetEnd()).toString());
                var dns = dnsOf(vlan.getDnsServers(), false);
                if (dns.length() > 0) dhcp.put("dns", dns);
                gw.put("dhcp4", dhcp);
            }
        }
        if (net6 != null) {
            var v6 = new JSONObject();
            v6.put("address", net6.address().toString());
            v6.put("prefix_len", net6.prefix());
            gw.put("ipv6", v6);
            if (vlan.isDhcp6Enabled()) {
                var dhcp = new JSONObject();
                dhcp.put("enabled", true);
                dhcp.put("pool_start", net6.addressAtOffset(
                    BigInteger.valueOf(vlan.getDhcp6OffsetStart())).toString());
                dhcp.put("pool_end", net6.addressAtOffset(
                    BigInteger.valueOf(vlan.getDhcp6OffsetEnd())).toString());
                var dns = dnsOf(vlan.getDnsServers(), true);
                if (dns.length() > 0) dhcp.put("dns", dns);
                gw.put("dhcp6", dhcp);
            }
            if (vlan.isSlaacEnabled()) {
                var slaac = new JSONObject();
                slaac.put("enabled", true);
                slaac.put("managed", vlan.isDhcp6Enabled());
                slaac.put("other", vlan.isDhcp6Enabled());
                var prefix = new JSONObject();
                prefix.put("prefix", net6.toNetworkString());
                prefix.put("on_link", true);
                prefix.put("autonomous", true);
                var prefixes = new JSONArray();
                prefixes.put(prefix);
                slaac.put("prefixes", prefixes);
                gw.put("slaac", slaac);
            }
        }
        return gw;
    }

    @NonNull
    private static JSONArray dnsOf(@NonNull List<String> servers, boolean v6) {
        // empty list = omitted = gvswitch advertises the gateway IP, which
        // the backend serves by forwarding gateway:53 to the Android
        // system's DNS (see GvisorBridgeBackend.dnsForwardRules)
        var arr = new JSONArray();
        for (var s : servers)
            if (s.contains(":") == v6) arr.put(s);
        return arr;
    }
}
