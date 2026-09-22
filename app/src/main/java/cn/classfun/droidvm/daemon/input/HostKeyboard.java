// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.input;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import cn.classfun.droidvm.lib.natives.UnixHelper;

/**
 * One {@code /dev/input/eventN} node that a person types on, as the grab needs to know it: where
 * it is, what it calls itself, and what bus and ids it came in on.
 *
 * <p>A phone's input directory is mostly not keyboards -- a touchscreen, a lid switch, the power
 * key, a headset's play button all live there and all carry EV_KEY -- so membership is decided by
 * asking for the keys nothing but a typing keyboard has: {@code q}, {@code a}, {@code z}, space
 * and enter. That test is why the phone's own volume rocker is never grabbed out from under
 * Android, and why a Bluetooth keyboard is picked up whatever the vendor called it.</p>
 *
 * <p>The ids matter as much as the path, because the path is not stable: a Bluetooth keyboard
 * that drops and reconnects comes back as whichever {@code eventN} is free, so what is re-grabbed
 * is matched by name and vendor/product, not by node.</p>
 */
final class HostKeyboard {
    private static final String TAG = "HostKeyboard";

    static final String DEV_DIR = "/dev/input";
    private static final String NODE_PREFIX = "event";

    // linux/input-event-codes.h -- the keys the test asks for.
    private static final int KEY_Q = 16;
    private static final int KEY_A = 30;
    private static final int KEY_Z = 44;
    private static final int KEY_ENTER = 28;
    private static final int KEY_SPACE = 57;
    private static final int[] TYPING_KEYS = {KEY_Q, KEY_A, KEY_Z, KEY_ENTER, KEY_SPACE};

    @NonNull
    final String path;
    @NonNull
    final String name;
    final int bus;
    final int vendor;
    final int product;

    private HostKeyboard(@NonNull String path, @NonNull String name,
                         int bus, int vendor, int product) {
        this.path = path;
        this.name = name;
        this.bus = bus;
        this.vendor = vendor;
        this.product = product;
    }

    /**
     * Every typing keyboard the host has right now, node order. Each node is opened to be asked
     * what it is and closed again: nothing here holds a descriptor, so a scan can run while
     * another one of these is grabbed without either disturbing the other.
     */
    @NonNull
    static List<HostKeyboard> scan() {
        var out = new ArrayList<HostKeyboard>();
        var names = new File(DEV_DIR).list();
        if (names == null) {
            Log.w(TAG, fmt("cannot list %s; no physical keyboard will be found", DEV_DIR));
            return out;
        }
        Arrays.sort(names);
        for (var node : names) {
            if (!node.startsWith(NODE_PREFIX)) continue;
            var path = fmt("%s/%s", DEV_DIR, node);
            int fd = UnixHelper.nativeEvdevOpen(path);
            if (fd < 0) continue;
            try {
                var kb = describe(path, fd);
                if (kb != null) out.add(kb);
            } finally {
                UnixHelper.nativeCloseFd(fd);
            }
        }
        return out;
    }

    /** What [fd] is, or null when it is not a typing keyboard. */
    @Nullable
    static HostKeyboard describe(@NonNull String path, int fd) {
        var bits = UnixHelper.nativeEvdevKeyBits(fd);
        if (bits == null || !isTypingKeyboard(bits)) return null;
        var name = UnixHelper.nativeEvdevName(fd);
        var ids = UnixHelper.nativeEvdevIds(fd);
        return new HostKeyboard(
            path,
            name == null ? "" : name.trim(),
            ids == null ? 0 : ids[0],
            ids == null ? 0 : ids[1],
            ids == null ? 0 : ids[2]);
    }

    /** True when the EV_KEY bitmap holds every key a person types with. */
    private static boolean isTypingKeyboard(@NonNull byte[] bits) {
        for (var key : TYPING_KEYS)
            if (!hasKey(bits, key)) return false;
        return true;
    }

    private static boolean hasKey(@NonNull byte[] bits, int code) {
        int index = code / 8;
        return index < bits.length && (bits[index] & (1 << (code % 8))) != 0;
    }

    /**
     * Whether [other] is this keyboard come back on a different node: same name, same vendor and
     * product. The identity a re-grab after a Bluetooth reconnect is decided on.
     */
    boolean sameDevice(@NonNull HostKeyboard other) {
        return name.equals(other.name) && vendor == other.vendor && product == other.product;
    }

    @NonNull
    JSONObject toJson() throws JSONException {
        var obj = new JSONObject();
        obj.put("path", path);
        obj.put("name", name);
        obj.put("bus", fmt("%04x", bus));
        obj.put("vendor", fmt("%04x", vendor));
        obj.put("product", fmt("%04x", product));
        return obj;
    }

    @NonNull
    @Override
    public String toString() {
        return fmt("%s \"%s\" %04x:%04x", path, name, vendor, product);
    }
}
