package cn.classfun.droidvm.daemon.network.backend.pd;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * DHCPv6 DUID helpers. The default is DUID-LL (type 3, hw type 1)
 * derived from a MAC address; user-supplied DUIDs are colon-hex strings.
 */
public final class Duid {
    private Duid() {
    }

    @NonNull
    public static byte[] fromLinkLayer(@NonNull String mac) {
        var parts = mac.split(":");
        if (parts.length != 6)
            throw new IllegalArgumentException(fmt("Invalid MAC for DUID-LL: %s", mac));
        var out = new byte[4 + 6];
        out[0] = 0x00;
        out[1] = 0x03; // DUID-LL
        out[2] = 0x00;
        out[3] = 0x01; // hw type: ethernet
        for (int i = 0; i < 6; i++)
            out[4 + i] = (byte) Integer.parseInt(parts[i], 16);
        return out;
    }

    @NonNull
    public static byte[] parse(@NonNull String hex) {
        var parts = hex.split(":");
        var out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++)
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        return out;
    }

    @NonNull
    public static String format(@Nullable byte[] duid) {
        if (duid == null) return "";
        var sb = new StringBuilder();
        for (var b : duid) {
            if (sb.length() > 0) sb.append(':');
            sb.append(fmt("%02x", b));
        }
        return sb.toString();
    }
}
