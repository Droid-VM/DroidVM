// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.pkg;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import cn.classfun.droidvm.lib.network.IPv4Network;
import cn.classfun.droidvm.lib.network.IPv6Network;
import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkConfigValidator;
import cn.classfun.droidvm.lib.store.network.NetworkConflicts;
import cn.classfun.droidvm.lib.store.network.UplinkMode;

/**
 * What happens to each network a package carries: join one this phone already has, create it, or
 * leave it behind. One instance answers for one import against one set of existing networks, and
 * remembers the names its own creations have claimed, so two packaged networks that want the same
 * name do not both end up asking for it.
 *
 * <p>The screen and the import task share this class so that what the user is shown is what gets
 * built: the same rules pick which networks may be joined, decide whether creating is possible at
 * all, and settle the name a created network ends up with.
 */
public final class NetworkImportPlan {
    /** The manifest field carrying a packaged network's reference key. */
    public static final String REF_KEY = "pkg_network_ref";

    private final List<NetworkConfig> existing;
    private final Set<String> takenNames = new HashSet<>();
    private final Set<String> takenBridges = new HashSet<>();
    private final Set<String> takenIds = new HashSet<>();

    public NetworkImportPlan(@NonNull DataStore<? extends NetworkConfig> store) {
        this(NetworkConflicts.snapshot(store));
    }

    public NetworkImportPlan(@NonNull List<NetworkConfig> existing) {
        this.existing = existing;
        for (var net : existing) {
            var name = net.getName();
            if (name != null) takenNames.add(name);
            var bridge = net.getBridgeName();
            if (bridge != null && !bridge.isEmpty()) takenBridges.add(bridge);
            var id = net.item.optString("id", "");
            if (!id.isEmpty()) takenIds.add(id);
        }
    }

    /** What to do with one packaged network. */
    public enum Action {
        JOIN("join"),
        CREATE("create"),
        SKIP("skip");

        private final String key;

        Action(@NonNull String key) {
            this.key = key;
        }

        @NonNull
        public String key() {
            return key;
        }

        /** The action for a wire key; unknown keys read as {@link #CREATE}, the old default. */
        @NonNull
        public static Action fromKey(@Nullable String key) {
            for (var v : values())
                if (v.key.equals(key)) return v;
            return CREATE;
        }
    }

    /** One decision, keyed by the packaged network's {@link #REF_KEY}. */
    public static final class Entry {
        @NonNull
        public final String ref;
        @NonNull
        public final Action action;
        /** The network to join, for {@link Action#JOIN}. */
        @Nullable
        public final String networkId;
        /** The network to create, for {@link Action#CREATE}; null to derive it on the spot. */
        @Nullable
        public final NetworkConfig config;

        public Entry(
            @NonNull String ref,
            @NonNull Action action,
            @Nullable String networkId,
            @Nullable NetworkConfig config
        ) {
            this.ref = ref;
            this.action = action;
            this.networkId = networkId;
            this.config = config;
        }

        @NonNull
        public JSONObject toJson() throws JSONException {
            var o = new JSONObject();
            o.put("ref", ref);
            o.put("action", action.key());
            if (networkId != null) o.put("network_id", networkId);
            if (config != null) o.put("config", config.toJson());
            return o;
        }

        @NonNull
        public static Entry fromJson(@NonNull JSONObject o) throws JSONException {
            var cfgJson = o.optJSONObject("config");
            NetworkConfig cfg = null;
            if (cfgJson != null) cfg = new NetworkConfig(cfgJson);
            var id = o.optString("network_id", "");
            return new Entry(
                o.optString("ref", ""),
                Action.fromKey(o.optString("action", "")),
                id.isEmpty() ? null : id,
                cfg
            );
        }
    }

    /** Reads a plan array; entries that cannot be parsed are dropped rather than failing it. */
    @NonNull
    public static List<Entry> parse(@Nullable JSONArray arr) {
        var out = new ArrayList<Entry>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            var o = arr.optJSONObject(i);
            if (o == null) continue;
            try {
                var entry = Entry.fromJson(o);
                if (!entry.ref.isEmpty()) out.add(entry);
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    /** The entry for a ref, or null when the plan says nothing about it. */
    @Nullable
    public static Entry findRef(@NonNull List<Entry> plan, @NonNull String ref) {
        for (var entry : plan)
            if (entry.ref.equals(ref)) return entry;
        return null;
    }

    /**
     * The networks this packaged one may be joined to, closest match first.
     *
     * <p>Only networks of the same kind qualify: joining is what carries the packaged VM's
     * kind-specific settings across intact -- an L3 network's DHCP pool offsets, a gVisor
     * network's IPv6 SNAT -- and none of that survives being attached to a network built the
     * other way. An empty list means this package's network has nothing here to join.
     */
    @NonNull
    public List<NetworkConfig> candidates(@NonNull NetworkConfig packaged) {
        var out = new ArrayList<NetworkConfig>();
        for (var net : existing)
            if (NetworkConflicts.sameKind(packaged, net)) out.add(net);
        out.sort(Comparator
            .comparingLong((NetworkConfig net) -> distance(packaged, net))
            .thenComparing(net -> net.getName() == null ? "" : net.getName()));
        return out;
    }

    /**
     * Why creating this packaged network here would collide, or null when it would not. Same
     * rules as the network editor: an overlapping prefix on a network of the same bridge type,
     * or an L2 uplink another network already bridges.
     */
    @Nullable
    public NetworkConflicts.Conflict createConflict(@NonNull NetworkConfig packaged) {
        return NetworkConflicts.find(packaged, existing, null);
    }

    /**
     * The network a packaged one would be created as: its own config, given a fresh id and
     * whatever name and bridge name are still free. Names are the one thing an import is allowed
     * to change quietly -- they must be unique across every network on the phone whatever its
     * kind, and a collision there says nothing about whether the network itself fits.
     *
     * <p>Claims the names it hands out, so calling this once per network being created gives each
     * of them a different one.
     */
    @NonNull
    public NetworkConfig prepareCreate(@NonNull NetworkConfig packaged) {
        NetworkConfig cfg;
        try {
            cfg = new NetworkConfig(packaged.toJson());
        } catch (JSONException e) {
            throw new IllegalArgumentException("packaged network is not serializable", e);
        }
        cfg.item.remove(REF_KEY);
        cfg.item.remove("id");
        return adopt(cfg);
    }

    /**
     * Settles a config that is already meant to be created here: a free id, and names that are
     * still free. Applied to what the screen prepared as well, because the two run against their
     * own copies of the store and a network may have appeared in between -- an import that
     * renames one network too many is a great deal better than one that fails on a duplicate.
     *
     * <p>Mutates and returns {@code cfg}, and claims what it hands out.
     */
    @NonNull
    public NetworkConfig adopt(@NonNull NetworkConfig cfg) {
        var id = cfg.item.optString("id", "");
        if (id.isEmpty() || takenIds.contains(id)) {
            id = UUID.randomUUID().toString();
            cfg.setId(id);
        }
        takenIds.add(id);
        var name = uniqueName(cfg.getName());
        cfg.setName(name);
        takenNames.add(name);
        var bridge = cfg.getBridgeName();
        if (bridge != null && !bridge.isEmpty()) {
            var unique = uniqueBridge(bridge);
            cfg.setBridgeName(unique);
            takenBridges.add(unique);
        }
        return cfg;
    }

    /** {@code base} or the first free {@code base_N}. */
    @NonNull
    private String uniqueName(@Nullable String base) {
        var name = base == null || base.trim().isEmpty() ? "network" : base;
        if (!takenNames.contains(name)) return name;
        for (int i = 1; ; i++) {
            var candidate = fmt("%s_%d", name, i);
            if (!takenNames.contains(candidate)) return candidate;
        }
    }

    /**
     * {@code base} or the first free {@code baseN}, trimmed so the suffix still fits the
     * interface-name cap -- a bridge name over it is refused outright, so growing one past it to
     * dodge a duplicate would only trade a collision for a rejection.
     */
    @NonNull
    private String uniqueBridge(@NonNull String base) {
        if (!takenBridges.contains(base)) return base;
        for (int i = 1; i < 100000; i++) {
            var suffix = String.valueOf(i);
            int room = NetworkConfigValidator.MAX_BRIDGE_NAME_LEN - suffix.length();
            var head = base.length() > room ? base.substring(0, Math.max(1, room)) : base;
            var candidate = fmt("%s%s", head, suffix);
            if (!takenBridges.contains(candidate)) return candidate;
        }
        return base;
    }

    /**
     * How far a candidate is from the packaged network, lower being closer: for L2 the uplink it
     * bridges, for L3 the primary IPv4 prefix, falling back to IPv6 when the packaged network has
     * no IPv4 of its own. The point is that the network the user most likely means -- the same
     * segment, the same uplink, carried over from the other phone -- is the one already selected.
     */
    private static long distance(@NonNull NetworkConfig packaged, @NonNull NetworkConfig other) {
        if (packaged.getUplinkMode() == UplinkMode.L2) {
            var mine = packaged.getL2Uplink();
            var theirs = other.getL2Uplink();
            if (mine == null || theirs == null) return 1000;
            if (mine.trim().equalsIgnoreCase(theirs.trim())) return 0;
            return 1000 - commonChars(mine, theirs);
        }
        var mine4 = primaryV4(packaged);
        if (mine4 != null) {
            var theirs4 = primaryV4(other);
            return theirs4 == null ? 1000 : 32 - commonBits4(mine4, theirs4);
        }
        var mine6 = primaryV6(packaged);
        if (mine6 != null) {
            var theirs6 = primaryV6(other);
            return theirs6 == null ? 1000 : 128 - commonBits6(mine6, theirs6);
        }
        return 500;
    }

    /** The first IPv4 network this config addresses, untagged VLAN first. */
    @Nullable
    private static IPv4Network primaryV4(@NonNull NetworkConfig cfg) {
        IPv4Network first = null;
        for (var vlan : cfg.getVlans()) {
            var net = vlan.getIpv4Network();
            if (net == null) continue;
            if (vlan.isUntagged()) return net;
            if (first == null) first = net;
        }
        return first;
    }

    @Nullable
    private static IPv6Network primaryV6(@NonNull NetworkConfig cfg) {
        IPv6Network first = null;
        for (var vlan : cfg.getVlans()) {
            var net = vlan.getIpv6Network();
            if (net == null) continue;
            if (vlan.isUntagged()) return net;
            if (first == null) first = net;
        }
        return first;
    }

    private static long commonBits4(@NonNull IPv4Network a, @NonNull IPv4Network b) {
        long diff = a.networkAddress().value() ^ b.networkAddress().value();
        int bits = 0;
        for (int i = 31; i >= 0 && ((diff >> i) & 1L) == 0; i--) bits++;
        return bits;
    }

    private static long commonBits6(@NonNull IPv6Network a, @NonNull IPv6Network b) {
        var diff = a.networkAddress().value().xor(b.networkAddress().value());
        int bits = 0;
        for (int i = 127; i >= 0 && !diff.testBit(i); i--) bits++;
        return bits;
    }

    private static long commonChars(@NonNull String a, @NonNull String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && Character.toLowerCase(a.charAt(i)) == Character.toLowerCase(b.charAt(i)))
            i++;
        return i;
    }
}
