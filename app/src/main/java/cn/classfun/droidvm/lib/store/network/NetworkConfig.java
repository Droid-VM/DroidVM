package cn.classfun.droidvm.lib.store.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataConfig;
import cn.classfun.droidvm.lib.store.base.DataItem;

public class NetworkConfig extends DataConfig {
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
     * L2 mode: configured uplink — a literal interface name or a
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
