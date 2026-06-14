package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates host L2 uplink candidates and resolves the logical
 * "WiFi"/"Ethernet"/"Tethering" identifiers to a concrete interface at
 * network start: device names drift across reboots (wlan0 ⇄ wlan1), so the
 * identifier is stored and re-resolved live; a literal interface name is
 * pinned and used as-is.
 */
public final class UplinkResolver {
    private static final String TAG = "UplinkResolver";
    public static final String ID_WIFI = "WiFi";
    public static final String ID_ETHERNET = "Ethernet";
    public static final String ID_TETHERING = "Tethering";

    /** ARPHRD_ETHER: only L2 ethernet-type devices can join a Linux bridge. */
    private static final int ARPHRD_ETHER = 1;
    /**
     * Android hotspot / USB-tether interface name prefixes. Deliberately NOT
     * rmnet: cellular rawip is an L3 device and cannot be bridged.
     */
    private static final String[] TETHER_PREFIXES =
        {"ap", "swlan", "softap", "rndis", "usb", "bt-pan"};

    public static final class Iface {
        public final String name;
        public final boolean wireless, up, ether, physical, leaf, virtual;

        Iface(@NonNull String name, boolean wireless, boolean up, boolean ether,
              boolean physical, boolean leaf, boolean virtual) {
            this.name = name;
            this.wireless = wireless;
            this.up = up;
            this.ether = ether;
            this.physical = physical;
            this.leaf = leaf;
            this.virtual = virtual;
        }

        /**
         * A real, non-stacked L2 interface (excludes dummy/ifb/veth/vlan/
         * tunnels): ethernet-type, a leaf netdev (iflink == ifindex), not a
         * virtual DEVTYPE, and either up or backed by hardware. A USB-tether
         * (rndis) or modem-ethernet has no backing device, so being up admits
         * it; a real but unplugged NIC stays via its backing device.
         */
        boolean realL2() {
            return ether && leaf && !virtual && (up || physical);
        }
    }

    private UplinkResolver() {
    }

    /** All non-loopback, non-bridge, non-tun interfaces with their properties. */
    @NonNull
    private static List<Iface> scan() {
        var out = new ArrayList<Iface>();
        var entries = new File("/sys/class/net").listFiles();
        if (entries == null) return out;
        for (var entry : entries) {
            var name = entry.getName();
            if (name.equals("lo")) continue;
            if (new File(entry, "bridge").exists()) continue;    // a bridge
            if (new File(entry, "tun_flags").exists()) continue; // tun/tap
            boolean wireless = new File(entry, "phy80211").exists()
                || new File(entry, "wireless").exists();
            boolean physical = new File(entry, "device").exists();
            boolean ether = readInt(new File(entry, "type")) == ARPHRD_ETHER;
            // leaf = not stacked on another netdev (drops veth/vlan/rmnet_data@…)
            boolean leaf = readInt(new File(entry, "ifindex"))
                == readInt(new File(entry, "iflink"));
            out.add(new Iface(name, wireless, isUp(entry), ether,
                physical, leaf, isVirtualDevtype(entry)));
        }
        return out;
    }

    private static boolean isUp(@NonNull File entry) {
        try {
            var s = Files.readAllLines(new File(entry, "operstate").toPath());
            return !s.isEmpty() && s.get(0).trim().equals("up");
        } catch (Exception e) {
            return false;
        }
    }

    private static int readInt(@NonNull File f) {
        try {
            var s = Files.readAllLines(f.toPath());
            if (s.isEmpty()) return -1;
            return Integer.parseInt(s.get(0).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** DEVTYPEs that are virtual L2 constructs, never a real uplink. */
    private static final java.util.Set<String> VIRTUAL_DEVTYPES =
        java.util.Set.of("vlan", "vxlan", "gretap", "erspan");

    private static boolean isVirtualDevtype(@NonNull File entry) {
        try {
            for (var line : Files.readAllLines(new File(entry, "uevent").toPath()))
                if (line.startsWith("DEVTYPE="))
                    return VIRTUAL_DEVTYPES.contains(line.substring(8).trim());
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Concrete physical L2 devices for the picker: ethernet-type, state UP and
     * backed by real hardware (excludes br/veth/tap/vlan/vxlan/gretap, which
     * have no backing device or are filtered above).
     */
    @NonNull
    private static List<Iface> concreteDevices() {
        var out = new ArrayList<Iface>();
        for (var i : scan())
            if (i.realL2() && i.up) out.add(i);
        return out;
    }

    @Nullable
    private static String resolveWifi() {
        String fallback = null;
        for (var i : scan()) {
            if (!i.wireless || !i.leaf || i.virtual) continue;
            if (isTetherName(i.name)) continue;   // an AP/tether iface, not STA
            if (i.up) return i.name;
            if (fallback == null) fallback = i.name;
        }
        return fallback;
    }

    @Nullable
    private static String resolveEthernet() {
        String fallback = null;
        for (var i : scan()) {
            if (!i.realL2() || i.wireless || isTetherName(i.name)) continue;
            if (i.up) return i.name;
            if (fallback == null) fallback = i.name;
        }
        return fallback;
    }

    @Nullable
    private static String resolveTethering() {
        String fallback = null;
        for (var i : scan()) {
            if (!isTetherName(i.name)) continue;
            if (i.up) return i.name;
            if (fallback == null) fallback = i.name;
        }
        return fallback;
    }

    private static boolean isTetherName(@NonNull String name) {
        for (var p : TETHER_PREFIXES)
            if (name.startsWith(p)) return true;
        return false;
    }

    /**
     * Resolves a configured uplink (a logical identifier or a literal
     * interface name) to a concrete interface, or null when nothing matches.
     */
    @Nullable
    public static String resolve(@NonNull String configured) {
        if (configured.equalsIgnoreCase(ID_WIFI)) return resolveWifi();
        if (configured.equalsIgnoreCase(ID_ETHERNET)) return resolveEthernet();
        if (configured.equalsIgnoreCase(ID_TETHERING)) return resolveTethering();
        if (new File(fmt("/sys/class/net/%s/uevent", configured)).exists())
            return configured;
        Log.w(TAG, fmt("Uplink interface not found: %s", configured));
        return null;
    }

    /** Whether a logical identifier's uplink can be bridged (Wi-Fi STA can't). */
    public static boolean identifierBridgeable(@NonNull String id) {
        return !id.equalsIgnoreCase(ID_WIFI);
    }

    /**
     * Whether the interface is assumed to carry IFF_DONT_BRIDGE (not exposed
     * via sysfs): a Wi-Fi STA or any non-ethernet-type device cannot be
     * enslaved into a Linux bridge, so pseudo-bridging is mandatory.
     */
    public static boolean assumeDontBridge(@NonNull String iface) {
        var entry = new File("/sys/class/net", iface);
        boolean wireless = new File(entry, "phy80211").exists()
            || new File(entry, "wireless").exists();
        boolean ether = readInt(new File(entry, "type")) == ARPHRD_ETHER;
        return wireless || !ether;
    }

    /**
     * Picker payload: the three logical identifiers with their live-resolved
     * name (empty when none present) and whether they can be bridged, plus the
     * concrete physical L2 devices with their per-device bridgeability.
     */
    @NonNull
    public static JSONObject listUplinksJson() {
        var root = new JSONObject();
        try {
            var ids = new JSONArray();
            ids.put(identifierJson(ID_WIFI, resolveWifi(), false));
            ids.put(identifierJson(ID_ETHERNET, resolveEthernet(), true));
            ids.put(identifierJson(ID_TETHERING, resolveTethering(), true));
            root.put("identifiers", ids);
            var devices = new JSONArray();
            for (var i : concreteDevices()) {
                var obj = new JSONObject();
                obj.put("name", i.name);
                obj.put("bridgeable", !i.wireless);
                devices.put(obj);
            }
            root.put("devices", devices);
        } catch (Exception e) {
            Log.w(TAG, "Failed to build uplink list", e);
        }
        return root;
    }

    @NonNull
    private static JSONObject identifierJson(
        @NonNull String id, @Nullable String name, boolean bridgeable
    ) throws org.json.JSONException {
        var obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name == null ? "" : name);
        obj.put("bridgeable", bridgeable);
        return obj;
    }
}
