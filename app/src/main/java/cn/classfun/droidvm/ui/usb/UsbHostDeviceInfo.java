// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cn.classfun.droidvm.R;

/**
 * One entry of {@code usb_host_list} as the two USB pages need it: what the device is, where it
 * is plugged in and who holds it. The rule id and port path come from the daemon; should a
 * daemon that predates them answer, both are derived here the way plan section 2.4 defines
 * them, so the rows still describe their rules sensibly.
 */
public final class UsbHostDeviceInfo {
    /**
     * Why a device is deauthorized, when this daemon is the one that did it. A record naming no
     * layer is one the user asked for directly, which the page says with the pin instead.
     */
    public static final class Sink {
        @Nullable
        public final UsbRuleLayer layer;
        /** Its index inside that layer; meaningless when {@link #layer} is null. */
        public final int index;

        private Sink(@NonNull JSONObject obj) {
            layer = UsbRuleLayer.fromValue(obj.opt("layer"));
            index = obj.optInt("index", -1);
        }
    }

    public final String sysfs;
    public final String vid;
    public final String pid;
    public final String manufacturer;
    public final String product;
    public final String serial;
    /** Megabits per second, as sysfs reports them; see {@link UsbSpeed}. */
    public final String speed;
    /** {@code vid:pid:serial}, or {@code vid:pid} for a device without a serial. */
    public final String id;
    /** Port chain without the bus: sysfs {@code 1-1.2.2} is port {@code 1.2.2}. */
    public final String port;
    /**
     * The instance behind the sysfs name: it changes on every re-enumeration, so a device
     * replugged into the same socket is a different one to anybody holding on to a choice.
     */
    public final int devnum;
    /**
     * Whether the kernel lets the device be configured at all. False is the sink: nothing for
     * Android, a host driver or a VM to bind to.
     */
    public final boolean authorized;
    /** Pinned by hand: the daemon skips it until it is unplugged. */
    public final boolean held;
    public final boolean hostInUse;
    @Nullable
    public final String attachedVm;
    @Nullable
    public final String attachedVmName;
    /** Which xHCI controller of that VM it landed on, when the daemon recorded one. */
    @Nullable
    public final String attachedController;
    /** Why it is hidden, when this daemon hid it. */
    @Nullable
    public final Sink sink;
    /** The rule that attached it, when a rule did. */
    @Nullable
    public final UsbRuleLayer autoRuleLayer;
    /** 0-based index within {@link #autoRuleLayer}; meaningless when that is null. */
    public final int autoRuleIndex;

    private UsbHostDeviceInfo(@NonNull JSONObject obj) {
        sysfs = obj.optString("sysfs", "");
        vid = obj.optString("vid", "").toLowerCase(Locale.ROOT);
        pid = obj.optString("pid", "").toLowerCase(Locale.ROOT);
        manufacturer = optText(obj, "manufacturer");
        product = optText(obj, "product");
        serial = optText(obj, "serial");
        speed = optText(obj, "speed");
        var wireId = optText(obj, "id");
        id = wireId.isEmpty() ? deriveId(vid, pid, serial) : wireId;
        var wirePort = optText(obj, "port");
        port = wirePort.isEmpty() ? derivePort(sysfs) : wirePort;
        devnum = obj.optInt("devnum", -1);
        authorized = obj.optBoolean("authorized", true);
        held = obj.optBoolean("held", false);
        hostInUse = obj.optBoolean("host_in_use", false);
        attachedVm = optNullable(obj, "attached_vm");
        attachedVmName = optNullable(obj, "attached_vm_name");
        attachedController = optNullable(obj, "attached_controller");
        var hidden = obj.optJSONObject("sink");
        sink = hidden == null ? null : new Sink(hidden);
        var rule = obj.optJSONObject("auto_rule");
        autoRuleLayer = rule == null ? null : UsbRuleLayer.fromValue(rule.opt("layer"));
        autoRuleIndex = rule == null ? -1 : rule.optInt("index", -1);
    }

    @NonNull
    public static List<UsbHostDeviceInfo> fromArray(@Nullable JSONArray arr) {
        var out = new ArrayList<UsbHostDeviceInfo>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            var obj = arr.optJSONObject(i);
            if (obj != null) out.add(new UsbHostDeviceInfo(obj));
        }
        return out;
    }

    /** A string field, with JSON null and absence both reading as empty. */
    @NonNull
    private static String optText(@NonNull JSONObject obj, @NonNull String key) {
        var value = optNullable(obj, key);
        return value == null ? "" : value;
    }

    /** A string field, with JSON null, absence and the empty string all reading as null. */
    @Nullable
    private static String optNullable(@NonNull JSONObject obj, @NonNull String key) {
        if (obj.isNull(key)) return null;
        var value = obj.optString(key, "");
        return value.isEmpty() ? null : value;
    }

    /** The rule id: lower-case {@code vid:pid}, with the serial appended when there is one. */
    @NonNull
    static String deriveId(@NonNull String vid, @NonNull String pid, @NonNull String serial) {
        var base = fmt("%s:%s", vid, pid);
        return serial.isEmpty() ? base : fmt("%s:%s", base, serial);
    }

    /** The port path: the sysfs name with its bus prefix dropped, so both buses agree. */
    @NonNull
    static String derivePort(@NonNull String sysfs) {
        int dash = sysfs.indexOf('-');
        return dash < 0 ? sysfs : sysfs.substring(dash + 1);
    }

    /** The name a person knows the device by; the id when the descriptor carries none. */
    @NonNull
    public String displayName(@NonNull Context context) {
        if (!product.isEmpty()) {
            if (manufacturer.isEmpty() || product.contains(manufacturer)) return product;
            return context.getString(R.string.usb_rules_device_name_fmt, product, manufacturer);
        }
        if (!manufacturer.isEmpty()) return manufacturer;
        return context.getString(R.string.usb_rules_unknown_device, id);
    }
}
