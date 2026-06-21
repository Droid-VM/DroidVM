package cn.classfun.droidvm.lib.store.network;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import cn.classfun.droidvm.lib.network.IPv4Address;
import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.store.base.DataConfig;
import cn.classfun.droidvm.lib.store.base.DataItem;

public class NetworkConfig extends DataConfig {
    private static final String TAG = "NetworkConfig";

    public static final int SCHEMA_VERSION = 2;

    public NetworkConfig() {
        setId(UUID.randomUUID());
        item.set("schema", (long) SCHEMA_VERSION);
    }

    public NetworkConfig(@NonNull JSONObject obj) throws JSONException {
        item.set(obj);
        if (!isSupportedSchema(obj)) throw new JSONException(
            "Unsupported network config schema (expected " + SCHEMA_VERSION + ")"
        );
    }

    public static boolean isSupportedSchema(@NonNull JSONObject obj) {
        return obj.optInt("schema", 0) == SCHEMA_VERSION;
    }

    /**
     * Upgrades a stored network entry to the current schema. Returns {@code obj}
     * unchanged when it is already current, a freshly built v2 object when it
     * came from the pre-rework "flat" layout (no/zero schema), or {@code null}
     * when it cannot be migrated -- a schema newer than this build understands,
     * or a legacy entry that fails to convert. The caller is expected to run
     * the result through {@link NetworkConfigValidator} before trusting it, so
     * a structurally complete but semantically invalid migration (e.g. a DHCP
     * pool offset that lands outside the network) is reported and skipped
     * rather than loaded.
     */
    @Nullable
    public static JSONObject migrate(@NonNull JSONObject obj) {
        int v = obj.optInt("schema", 0);
        if (v == SCHEMA_VERSION) return obj;
        if (v > SCHEMA_VERSION) return null; // forward configs we can't read
        try {
            return migrateLegacyToV2(obj);
        } catch (Exception e) {
            Log.w(TAG, "Failed to migrate legacy network config: " + obj.optString("name"), e);
            return null;
        }
    }

    /**
     * Converts a pre-rework network entry to schema v2. The old model was a
     * standalone Linux bridge owning its own addresses, optional NAT and an
     * optional IPv4 DHCP range -- exactly the new L3 routed uplink mode -- so
     * the whole legacy config folds into a single untagged VLAN.
     */
    private static JSONObject migrateLegacyToV2(@NonNull JSONObject obj) throws JSONException {
        var src = DataItem.newObject();
        src.set(obj);

        var out = DataItem.newObject();
        out.set("schema", (long) SCHEMA_VERSION);
        // keep id/name stable so VM NIC references (network_id) stay valid
        var id = src.optString("id", "");
        if (!id.isEmpty()) out.set("id", id);
        var name = src.optString("name", "");
        if (!name.isEmpty()) out.set("name", name);
        var bridgeName = src.optString("bridge_name", "");
        if (!bridgeName.isEmpty()) out.set("bridge_name", bridgeName);
        out.set("bridge_type", BridgeType.LINUX.key());
        out.set("uplink_mode", UplinkMode.L3.key());
        out.set("stp", src.optBoolean("stp", false));
        out.set("auto_up", src.optBoolean("auto_up", false));
        out.set("l2", DataItem.newObject());

        var l3 = DataItem.newObject();
        var mac = src.optString("mac_address", "");
        if (!mac.isEmpty()) l3.set("mac_address", mac);
        var vlans = DataItem.newArray();
        vlans.append(migrateLegacyVlan(src));
        l3.set("vlans", vlans);
        out.set("l3", l3);

        return out.toJson();
    }

    /** Builds the single untagged VLAN that carries a legacy bridge's addressing. */
    private static DataItem migrateLegacyVlan(@NonNull DataItem src) {
        var ipv4Addrs = legacyAddresses(src, "ipv4_addresses");
        var ipv6Addrs = legacyAddresses(src, "ipv6_addresses");
        String ipv4Cidr = ipv4Addrs.isEmpty() ? null : ipv4Addrs.get(0);
        String ipv6Cidr = ipv6Addrs.isEmpty() ? null : ipv6Addrs.get(0);

        var vlan = DataItem.newObject();
        vlan.set("vlan_id", 0L);

        var ipv4 = DataItem.newObject();
        ipv4.set("cidr", ipv4Cidr);
        ipv4.set("snat", src.optBoolean("nat", false));
        var dhcp4 = DataItem.newObject();
        dhcp4.set("enabled", src.optBoolean("dhcp_enabled", false) && ipv4Cidr != null);
        var offsets = legacyDhcp4Offsets(ipv4Cidr,
            src.optString("dhcp_range_start", ""),
            src.optString("dhcp_range_end", ""));
        dhcp4.set("offset_start", offsets[0]);
        dhcp4.set("offset_end", offsets[1]);
        ipv4.set("dhcp", dhcp4);
        vlan.set("ipv4", ipv4);

        // the legacy UI had no DHCPv6 / SLAAC controls, so keep IPv6 as a plain
        // static gateway address rather than switching on services that the
        // old bridge never ran
        var ipv6 = DataItem.newObject();
        ipv6.set("source", Ipv6Source.STATIC.key());
        ipv6.set("cidr", ipv6Cidr);
        ipv6.set("snat", false);
        var dhcp6 = DataItem.newObject();
        dhcp6.set("enabled", false);
        dhcp6.set("offset_start", 128L);
        dhcp6.set("offset_end", 192L);
        ipv6.set("dhcp", dhcp6);
        var slaac = DataItem.newObject();
        slaac.set("enabled", false);
        ipv6.set("slaac", slaac);
        vlan.set("ipv6", ipv6);

        vlan.set("dns_servers", DataItem.newArray());
        vlan.set("ipv4_secondary", legacySecondary(ipv4Addrs));
        vlan.set("ipv6_secondary", legacySecondary(ipv6Addrs));
        return vlan;
    }

    /** Non-empty string entries of a legacy CIDR array, in order. */
    @NonNull
    private static List<String> legacyAddresses(@NonNull DataItem src, @NonNull String key) {
        var out = new ArrayList<String>();
        var arr = src.opt(key, null);
        if (arr == null || !arr.is(DataItem.Type.ARRAY)) return out;
        for (var e : arr.asArray()) {
            if (!e.is(DataItem.Type.STRING)) continue;
            var s = e.asString();
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** Everything after the first address becomes a secondary network. */
    @NonNull
    private static DataItem legacySecondary(@NonNull List<String> addrs) {
        var arr = DataItem.newArray();
        for (int i = 1; i < addrs.size(); i++)
            arr.append(DataItem.newString(addrs.get(i)));
        return arr;
    }

    /**
     * Converts the legacy absolute DHCP range (start/end IPs) into pool offsets
     * relative to the primary IPv4 network. Falls back to the standard
     * 128..192 window when there is no usable range; the result is not clamped,
     * so an out-of-range legacy range surfaces as an invalid config that the
     * validator rejects (and the loader then warns about and skips).
     */
    @NonNull
    private static long[] legacyDhcp4Offsets(
        @Nullable String cidr, @NonNull String start, @NonNull String end
    ) {
        long[] def = {128L, 192L};
        if (cidr == null || start.isEmpty() || end.isEmpty()) return def;
        try {
            var net = IPv4Network.parse(cidr);
            long base = net.networkAddress().value();
            long os = IPv4Address.parse(start).value() - base;
            long oe = IPv4Address.parse(end).value() - base;
            return new long[]{os, oe};
        } catch (Exception e) {
            return def;
        }
    }

    @Nullable
    public String getBridgeName() {
        return item.optString("bridge_name", null);
    }

    public void setBridgeName(@NonNull String name) {
        item.set("bridge_name", name);
    }

    @NonNull
    public BridgeType getBridgeType() {
        return BridgeType.fromKey(item.optString("bridge_type", null));
    }

    public void setBridgeType(@NonNull BridgeType type) {
        item.set("bridge_type", type.key());
    }

    @NonNull
    public UplinkMode getUplinkMode() {
        return UplinkMode.fromKey(item.optString("uplink_mode", null));
    }

    public void setUplinkMode(@NonNull UplinkMode mode) {
        item.set("uplink_mode", mode.key());
    }

    public boolean isStp() {
        return item.optBoolean("stp", false);
    }

    public boolean isAutoUp() {
        return item.optBoolean("auto_up", false);
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
    public DataItem l2() {
        return section("l2");
    }

    @NonNull
    public DataItem l3() {
        return section("l3");
    }

    /**
     * L2 mode: configured uplink -- a literal interface name or a
     * "WiFi"/"Ethernet"/"Tethering" logical identifier resolved at start.
     */
    @Nullable
    public String getL2Uplink() {
        return l2().optString("uplink", null);
    }

    /** L2 mode: whether to pseudo-bridge (force-on for non-bridgeable uplinks). */
    public boolean isL2PseudoBridge() {
        return l2().optBoolean("pseudo_bridge", true);
    }

    /** MAC of the bridge itself for the current uplink mode, if configured. */
    @Nullable
    public String getBridgeMacAddress() {
        switch (getUplinkMode()) {
            case L3:
                return emptyToNull(l3().optString("mac_address", null));
            default:
                return null;
        }
    }

    @NonNull
    public List<VlanConfig> getVlans() {
        var out = new ArrayList<VlanConfig>();
        if (getUplinkMode() != UplinkMode.L3) return out;
        var arr = l3().opt("vlans", null);
        if (arr == null || !arr.is(DataItem.Type.ARRAY)) return out;
        for (var e : arr.asArray())
            if (e.is(DataItem.Type.OBJECT)) out.add(new VlanConfig(e));
        return out;
    }

    @Nullable
    public VlanConfig findVlan(int vlanId) {
        for (var vlan : getVlans())
            if (vlan.getVlanId() == vlanId) return vlan;
        return null;
    }

    /** True if any VLAN id other than the untagged (0) domain is configured. */
    public boolean hasTaggedVlans() {
        for (var vlan : getVlans())
            if (!vlan.isUntagged()) return true;
        return false;
    }

    @Nullable
    private static String emptyToNull(@Nullable String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
