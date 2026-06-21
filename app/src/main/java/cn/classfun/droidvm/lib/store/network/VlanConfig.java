package cn.classfun.droidvm.lib.store.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * Wrapper over one entry of an L3 network's "vlans" array.
 * VLAN id 0 means the untagged domain.
 */
public final class VlanConfig {
    public final DataItem item;

    public VlanConfig(@NonNull DataItem item) {
        this.item = item;
    }

    @NonNull
    public static VlanConfig createDefault(int vlanId) {
        var item = DataItem.newObject();
        item.set("vlan_id", (long) vlanId);
        var ipv4 = DataItem.newObject();
        var dhcp4 = DataItem.newObject();
        dhcp4.set("enabled", true);
        dhcp4.set("offset_start", 128L);
        dhcp4.set("offset_end", 192L);
        ipv4.set("cidr", (String) null);
        ipv4.set("snat", true);
        ipv4.set("dhcp", dhcp4);
        item.set("ipv4", ipv4);
        var ipv6 = DataItem.newObject();
        var dhcp6 = DataItem.newObject();
        dhcp6.set("enabled", true);
        dhcp6.set("offset_start", 128L);
        dhcp6.set("offset_end", 192L);
        var slaac = DataItem.newObject();
        slaac.set("enabled", true);
        ipv6.set("source", Ipv6Source.STATIC.key());
        ipv6.set("cidr", (String) null);
        ipv6.set("snat", false);
        ipv6.set("dhcp", dhcp6);
        ipv6.set("slaac", slaac);
        item.set("ipv6", ipv6);
        item.set("dns_servers", DataItem.newArray());
        item.set("ipv4_secondary", DataItem.newArray());
        item.set("ipv6_secondary", DataItem.newArray());
        return new VlanConfig(item);
    }

    public int getVlanId() {
        return (int) item.optLong("vlan_id", 0);
    }

    public boolean isUntagged() {
        return getVlanId() == 0;
    }

    @NonNull
    private DataItem section(@NonNull String key) {
        var s = item.opt(key, null);
        if (s == null || !s.is(DataItem.Type.OBJECT)) {
            // set() stores a copy; re-read so callers mutate the stored item
            item.set(key, DataItem.newObject());
            s = item.get(key);
        }
        return s;
    }

    @NonNull
    public DataItem ipv4() {
        return section("ipv4");
    }

    @NonNull
    public DataItem ipv6() {
        return section("ipv6");
    }

    @Nullable
    public String getIpv4Cidr() {
        var cidr = ipv4().optString("cidr", null);
        return cidr == null || cidr.isEmpty() ? null : cidr;
    }

    @Nullable
    public IPv4Network getIpv4Network() {
        var cidr = getIpv4Cidr();
        if (cidr == null) return null;
        try {
            return IPv4Network.parse(cidr);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isIpv4Snat() {
        return ipv4().optBoolean("snat", false);
    }

    public boolean isDhcp4Enabled() {
        return getIpv4Cidr() != null
            && ipv4().opt("dhcp", DataItem.newObject()).optBoolean("enabled", false);
    }

    public long getDhcp4OffsetStart() {
        return ipv4().opt("dhcp", DataItem.newObject()).optLong("offset_start", 128);
    }

    public long getDhcp4OffsetEnd() {
        return ipv4().opt("dhcp", DataItem.newObject()).optLong("offset_end", 192);
    }

    @NonNull
    public Ipv6Source getIpv6Source() {
        return Ipv6Source.fromKey(ipv6().optString("source", Ipv6Source.STATIC.key()));
    }

    @Nullable
    public String getIpv6Cidr() {
        var cidr = ipv6().optString("cidr", null);
        return cidr == null || cidr.isEmpty() ? null : cidr;
    }

    @Nullable
    public IPv6Network getIpv6Network() {
        if (getIpv6Source() != Ipv6Source.STATIC) return null;
        var cidr = getIpv6Cidr();
        if (cidr == null) return null;
        try {
            return IPv6Network.parse(cidr);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean hasIpv6() {
        return getIpv6Source() == Ipv6Source.DHCP_PD || getIpv6Cidr() != null;
    }

    @Nullable
    public String getPdUplink() {
        return ipv6().opt("pd", DataItem.newObject()).optString("uplink", null);
    }

    @Nullable
    public String getPdDuid() {
        return ipv6().opt("pd", DataItem.newObject()).optString("duid", null);
    }

    public void setPdDuid(@NonNull String duid) {
        pdSection().set("duid", duid);
    }

    /**
     * Persisted routing-table id for bridgedhcp's routed-PD mode; 0 = not
     * yet allocated. The id is bridgedhcp's crash-cleanup key, so once
     * allocated it must stay stable for this VLAN across restarts.
     */
    public long getPdRouteTable() {
        return ipv6().opt("pd", DataItem.newObject()).optLong("route_table", 0);
    }

    public void setPdRouteTable(long table) {
        pdSection().set("route_table", table);
    }

    /** The live (mutable) "pd" object of the ipv6 section, created on demand. */
    @NonNull
    private DataItem pdSection() {
        var ipv6 = ipv6();
        var pd = ipv6.opt("pd", null);
        if (pd == null || !pd.is(DataItem.Type.OBJECT)) {
            // set() stores a copy; re-read so callers mutate the stored item
            ipv6.set("pd", DataItem.newObject());
            pd = ipv6.get("pd");
        }
        return pd;
    }

    public boolean isIpv6Snat() {
        return ipv6().optBoolean("snat", false);
    }

    public boolean isDhcp6Enabled() {
        return hasIpv6()
            && ipv6().opt("dhcp", DataItem.newObject()).optBoolean("enabled", false);
    }

    public long getDhcp6OffsetStart() {
        return ipv6().opt("dhcp", DataItem.newObject()).optLong("offset_start", 128);
    }

    public long getDhcp6OffsetEnd() {
        return ipv6().opt("dhcp", DataItem.newObject()).optLong("offset_end", 192);
    }

    public boolean isSlaacEnabled() {
        return hasIpv6()
            && ipv6().opt("slaac", DataItem.newObject()).optBoolean("enabled", false);
    }

    @NonNull
    public List<String> getDnsServers() {
        return stringList("dns_servers");
    }

    @NonNull
    public List<String> getIpv4Secondary() {
        return stringList("ipv4_secondary");
    }

    @NonNull
    public List<String> getIpv6Secondary() {
        return stringList("ipv6_secondary");
    }

    @NonNull
    private List<String> stringList(@NonNull String key) {
        var out = new ArrayList<String>();
        var arr = item.opt(key, null);
        if (arr == null || !arr.is(DataItem.Type.ARRAY)) return out;
        for (var e : arr.asArray()) {
            if (!e.is(DataItem.Type.STRING)) continue;
            var s = e.asString();
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return out;
    }
}
