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
 * Enumerates host L2 uplink candidates and resolves the "WiFi"/"Ethernet"
 * identifier names to a concrete interface at network start (the same
 * identifier may map to wlan0 or wlan1 across reboots).
 */
public final class UplinkResolver {
    private static final String TAG = "UplinkResolver";
    public static final String ID_WIFI = "WiFi";
    public static final String ID_ETHERNET = "Ethernet";

    public static final class Uplink {
        public final String name;
        public final boolean wireless;
        public final boolean up;

        Uplink(@NonNull String name, boolean wireless, boolean up) {
            this.name = name;
            this.wireless = wireless;
            this.up = up;
        }
    }

    private UplinkResolver() {
    }

    /**
     * Lists physical, non-bridge, non-virtual interfaces that could serve
     * as an L2 uplink: wireless (phy80211) or wired with a backing device.
     */
    @NonNull
    public static List<Uplink> listUplinks() {
        var out = new ArrayList<Uplink>();
        var net = new File("/sys/class/net");
        var entries = net.listFiles();
        if (entries == null) return out;
        for (var entry : entries) {
            var name = entry.getName();
            if (name.equals("lo")) continue;
            if (new File(entry, "bridge").exists()) continue;
            if (new File(entry, "tun_flags").exists()) continue;
            boolean wireless = new File(entry, "phy80211").exists()
                || new File(entry, "wireless").exists();
            boolean physical = new File(entry, "device").exists();
            if (!wireless && !physical) continue;
            out.add(new Uplink(name, wireless, isCarrierUp(entry)));
        }
        return out;
    }

    private static boolean isCarrierUp(@NonNull File entry) {
        try {
            var operstate = Files.readAllLines(new File(entry, "operstate").toPath());
            return !operstate.isEmpty() && operstate.get(0).trim().equals("up");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolves a configured uplink ("WiFi", "Ethernet" or a literal
     * interface name) to a concrete interface name, or null when nothing
     * matches.
     */
    @Nullable
    public static String resolve(@NonNull String configured) {
        if (configured.equalsIgnoreCase(ID_WIFI))
            return pick(true);
        if (configured.equalsIgnoreCase(ID_ETHERNET))
            return pick(false);
        if (new File(fmt("/sys/class/net/%s/uevent", configured)).exists())
            return configured;
        Log.w(TAG, fmt("Uplink interface not found: %s", configured));
        return null;
    }

    @Nullable
    private static String pick(boolean wireless) {
        String fallback = null;
        for (var uplink : listUplinks()) {
            if (uplink.wireless != wireless) continue;
            if (uplink.up) return uplink.name;
            if (fallback == null) fallback = uplink.name;
        }
        return fallback;
    }

    /**
     * Whether the interface is assumed to carry IFF_DONT_BRIDGE. The flag
     * is a kernel priv_flag not exposed via sysfs; Wi-Fi STA interfaces
     * are the case that matters, so wireless implies "cannot bridge".
     */
    public static boolean assumeDontBridge(@NonNull String iface) {
        var entry = new File("/sys/class/net", iface);
        return new File(entry, "phy80211").exists()
            || new File(entry, "wireless").exists();
    }

    /** JSON for the UI picker: [{name, kind, identifiers: [..]}]. */
    @NonNull
    public static JSONArray listUplinksJson() {
        var arr = new JSONArray();
        try {
            boolean firstWifi = true, firstEth = true;
            for (var uplink : listUplinks()) {
                var obj = new JSONObject();
                obj.put("name", uplink.name);
                obj.put("kind", uplink.wireless ? "wifi" : "ethernet");
                obj.put("up", uplink.up);
                var ids = new JSONArray();
                if (uplink.wireless && firstWifi) {
                    ids.put(ID_WIFI);
                    firstWifi = false;
                } else if (!uplink.wireless && firstEth) {
                    ids.put(ID_ETHERNET);
                    firstEth = false;
                }
                obj.put("identifiers", ids);
                arr.put(obj);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to build uplink list", e);
        }
        return arr;
    }
}
