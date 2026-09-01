// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.network.backend;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import cn.classfun.droidvm.lib.Constants;

/**
 * The phone's own addresses, as a list something can be asked to listen on.
 *
 * <p>Both families, unlike {@link Netbox#hostIpv4}, and one shot rather than a watched set: this
 * answers a picker that is open for a few seconds, not a firewall rule that has to follow the
 * network. The policy is otherwise the same one, and lives here because the daemon is the only
 * side that can apply it -- see the two exclusions below, neither of which
 * {@code java.net.NetworkInterface} can see.</p>
 */
public final class HostAddressScan {
    private static final String TAG = "HostAddressScan";

    /**
     * Interface-name prefixes whose addresses count as the phone's own reachable IPs: Wi-Fi,
     * cellular, VPN, ethernet, and hotspot/USB/BT tethering. Cellular names other than
     * {@code rmnet_data} (ccmni, pdp_ip...) are intentionally not matched -- the same assumption
     * the iptables EXT_IFACES list already makes.
     *
     * <p>One list, two readers: the port-forward DNAT scoping this was written for (through
     * netbox's own {@code host-ips} policy) and the VNC listen-address picker. They are asking the
     * same question, so they must not answer it from two lists.</p>
     */
    public static final List<String> HOST_IFACE_PREFIXES = List.of(
        "wlan", "rmnet_data", "tun", "eth",
        "ap", "swlan", "softap", "rndis", "usb", "bt-pan"
    );

    private HostAddressScan() {
    }

    /**
     * Every address the phone itself holds, as {@code [{addr, ifname, family}]} -- v4 and v6,
     * global scope only, in netlink's dump order. Empty on any failure, which the caller shows as
     * "nothing was found" rather than as an error: the picker's two fixed entries and its custom
     * dialog work without this list.
     *
     * <p>Four things are dropped, and the first two are the whole reason this is not done in the
     * app process:</p>
     *
     * <ul>
     *   <li>pbridge's offload-proxy addresses. An L2 pseudo-bridged guest's IP is parked on the
     *   phone's uplink so the Wi-Fi firmware answers ARP/NS for it, which makes it look exactly
     *   like an address of the phone's own. It is tagged with {@link Constants#PBRIDGE_OFFLOAD_MAGIC}
     *   as IFA_RT_PRIORITY for precisely this -- and that tag is netlink-only, invisible to
     *   {@code java.net.NetworkInterface} and to OEM {@code ip -j} builds too old to emit it.</li>
     *   <li>a host-route-only address ({@code noprefixroute} on a /32 or /128), which is the shape
     *   pbridge parks, so a proxy address from a build that predates the tag is caught anyway. Both
     *   halves are required: a plain /32 is how plenty of cellular interfaces are configured, and
     *   dropping those would lose the phone's real address.</li>
     *   <li>IPv6 privacy addresses ({@code temporary}). There are normally several at once --
     *   the live one plus however many are still inside their valid lifetime and deprecated -- and
     *   every one of them is replaced within hours, so naming one in a config is naming something
     *   that will be gone. The address kept is the stable one the phone keeps alongside them. The
     *   flag is IFA_F_SECONDARY, which on v4 means an ordinary secondary address and is not
     *   dropped; netbox reports the v6 reading only.</li>
     *   <li>bridge devices -- ours, holding a VM network's gateway address rather than the
     *   phone's.</li>
     *   <li>anything outside {@link #HOST_IFACE_PREFIXES}, and any address that is not global
     *   scope: that is where link-local (fe80::/10, 169.254/16) and loopback go, neither of which
     *   is an address the phone can be reached at.</li>
     * </ul>
     */
    @NonNull
    public static JSONArray list() {
        var out = new JSONArray();
        var bridges = bridgeNames();
        var rows = Netbox.addrList(null, null);
        for (int i = 0; i < rows.length(); i++) {
            var r = rows.optJSONObject(i);
            if (r == null) continue;
            var addr = r.optString("local", "");
            var ifname = r.optString("ifname", "");
            if (addr.isEmpty() || ifname.isEmpty()) continue;
            if (!"global".equals(r.optString("scope", ""))) continue;
            if (bridges.contains(ifname)) continue;
            if (!isHostIface(ifname)) continue;
            if (r.optLong("metric", 0) == Constants.PBRIDGE_OFFLOAD_MAGIC) continue;
            var family = r.optInt("family", 0);
            if (family != 4 && family != 6) continue;
            if (r.optBoolean("temporary", false)) continue;
            var prefixlen = r.optInt("prefixlen", 0);
            if (r.optBoolean("noprefixroute", false)
                && prefixlen == (family == 4 ? 32 : 128)) continue;
            try {
                var entry = new JSONObject();
                entry.put("addr", addr);
                entry.put("ifname", ifname);
                entry.put("family", family);
                out.put(entry);
            } catch (Exception e) {
                Log.w(TAG, fmt("Failed to build host address entry for %s", addr), e);
            }
        }
        return out;
    }

    private static boolean isHostIface(@NonNull String ifname) {
        for (var prefix : HOST_IFACE_PREFIXES)
            if (ifname.startsWith(prefix)) return true;
        return false;
    }

    @NonNull
    private static Set<String> bridgeNames() {
        var names = new HashSet<String>();
        var rows = Netbox.linkList(null, true);
        for (int i = 0; i < rows.length(); i++) {
            var r = rows.optJSONObject(i);
            if (r == null) continue;
            var name = r.optString("ifname", "");
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }
}
