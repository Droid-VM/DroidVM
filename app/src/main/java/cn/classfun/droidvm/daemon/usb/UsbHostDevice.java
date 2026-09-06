// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One host USB device as {@code /sys/bus/usb/devices/<sysfs>} described it at the moment of the
 * scan, plus its interfaces and whichever host driver holds each of them.
 *
 * <p>Immutable, and free of {@code android.*} on purpose: everything here is sysfs parsing, which
 * is the part worth covering with a unit test over a temporary directory.</p>
 */
public final class UsbHostDevice {
    /** Hub class, in the two-hex form both bDeviceClass and bInterfaceClass use. */
    private static final String CLASS_HUB = "09";
    /** The interface driver the VMM's own claim leaves behind; not a host owner. */
    private static final String DRIVER_USBFS = "usbfs";

    public static final class Interface {
        public final String name;
        public final String cls;
        public final String subcls;
        public final String proto;
        public final String driver;

        Interface(@NonNull String name, @NonNull String cls, @NonNull String subcls,
                  @NonNull String proto, @NonNull String driver) {
            this.name = name;
            this.cls = cls;
            this.subcls = subcls;
            this.proto = proto;
            this.driver = driver;
        }
    }

    public final String sysfs;
    /**
     * What a rule names the device by: {@code vid:pid:serial}, or {@code vid:pid} when it has no
     * serial. Survives a replug; two serial-less units of the same model share it.
     */
    public final String id;
    /**
     * Where it is plugged in: the sysfs name without its {@code <bus>-} prefix ({@code 1-1.2.2}
     * is {@code 1.2.2}). The bus is dropped on purpose -- the same physical socket enumerates a
     * USB2 device on bus 1 and a USB3 device on bus 2, and only the port chain names the socket.
     */
    public final String port;
    public final int busnum;
    public final int devnum;
    public final String node;
    public final String vid;
    public final String pid;
    public final String manufacturer;
    public final String product;
    public final String serial;
    public final String speed;
    public final String deviceClass;
    /**
     * Whether the kernel lets this device be configured at all. False is the sink: no
     * configuration, no interfaces, nothing for Android or a host driver to bind to.
     */
    public final boolean authorized;
    public final List<Interface> interfaces;

    private UsbHostDevice( // arity-ok: a value object; these parameters are its fields
        @NonNull String sysfs,
        int busnum,
        int devnum,
        @NonNull String node,
        @NonNull String vid,
        @NonNull String pid,
        @NonNull String manufacturer,
        @NonNull String product,
        @NonNull String serial,
        @NonNull String speed,
        @NonNull String deviceClass,
        boolean authorized,
        @NonNull List<Interface> interfaces
    ) {
        this.sysfs = sysfs;
        this.id = deriveId(vid, pid, serial);
        this.port = derivePort(sysfs);
        this.busnum = busnum;
        this.devnum = devnum;
        this.node = node;
        this.vid = vid;
        this.pid = pid;
        this.manufacturer = manufacturer;
        this.product = product;
        this.serial = serial;
        this.speed = speed;
        this.deviceClass = deviceClass;
        this.authorized = authorized;
        this.interfaces = interfaces;
    }

    /**
     * Reads one device directory. [devRoot] is normally {@code /dev/bus/usb} and only builds
     * {@link #node}; nothing under it is opened here.
     *
     * @throws IOException when a file every USB device has is missing or unreadable -- such an
     *                     entry is a device being torn down mid-scan, not a device we can attach.
     */
    @NonNull
    public static UsbHostDevice fromSysfs(@NonNull File devDir, @NonNull String devRoot)
        throws IOException {
        var sysfs = devDir.getName();
        var vid = readRequired(devDir, "idVendor").toLowerCase(Locale.ROOT);
        var pid = readRequired(devDir, "idProduct").toLowerCase(Locale.ROOT);
        var busnum = readRequiredInt(devDir, "busnum");
        var devnum = readRequiredInt(devDir, "devnum");
        var deviceClass = readRequired(devDir, "bDeviceClass").toLowerCase(Locale.ROOT);
        var node = pathJoin(devRoot, fmt("%03d", busnum), fmt("%03d", devnum));
        var interfaces = readInterfaces(devDir, sysfs);
        return new UsbHostDevice(
            sysfs, busnum, devnum, node, vid, pid,
            readOptional(devDir, "manufacturer"),
            readOptional(devDir, "product"),
            readOptional(devDir, "serial"),
            readOptional(devDir, "speed"),
            deviceClass, authorizedAt(devDir), interfaces
        );
    }

    /**
     * Whether the device directory shows the device authorized, read this moment. Absent reads
     * as authorized: a kernel or a device without the attribute is not a device somebody
     * deauthorized.
     *
     * <p>Split out of {@link #fromSysfs} and public because this one flag is what a reader who
     * already has a device cannot take from it: writing {@code authorized} creates and removes
     * no {@code /dev/bus/usb} node, so nothing tells an inotify watch to look again and a
     * cached copy stays whatever the last plug event left there. One small read, against a
     * whole device, and the rule for reading it lives in exactly one place.</p>
     */
    public static boolean authorizedAt(@NonNull File devDir) {
        return !"0".equals(readOptional(devDir, "authorized"));
    }

    @NonNull
    private static List<Interface> readInterfaces(@NonNull File devDir, @NonNull String sysfs) {
        // The kernel names an interface directory <device>:<config>.<interface>, inside the
        // device's own directory; anything else under it is an attribute or a child device.
        var pattern = Pattern.compile(fmt("^%s:\\d+\\.\\d+$", Pattern.quote(sysfs)));
        var entries = devDir.listFiles();
        var result = new ArrayList<Interface>();
        if (entries == null) return result;
        for (var entry : entries) {
            if (!entry.isDirectory()) continue;
            if (!pattern.matcher(entry.getName()).matches()) continue;
            result.add(new Interface(
                entry.getName(),
                readOptional(entry, "bInterfaceClass").toLowerCase(Locale.ROOT),
                readOptional(entry, "bInterfaceSubClass").toLowerCase(Locale.ROOT),
                readOptional(entry, "bInterfaceProtocol").toLowerCase(Locale.ROOT),
                readDriver(entry)
            ));
        }
        result.sort(Comparator.comparing((Interface iface) -> iface.name));
        return Collections.unmodifiableList(result);
    }

    /** Basename of the interface's {@code driver} symlink, or {@code ""} when nothing holds it. */
    @NonNull
    private static String readDriver(@NonNull File ifaceDir) {
        try {
            var target = Files.readSymbolicLink(ifaceDir.toPath().resolve("driver"));
            var name = target.getFileName();
            return name == null ? "" : name.toString();
        } catch (IOException | UnsupportedOperationException e) {
            return "";
        }
    }

    @NonNull
    private static String readRequired(@NonNull File dir, @NonNull String name) throws IOException {
        var file = new File(dir, name);
        var bytes = Files.readAllBytes(file.toPath());
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    private static int readRequiredInt(@NonNull File dir, @NonNull String name) throws IOException {
        var raw = readRequired(dir, name);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IOException(fmt("%s/%s is not a number: %s", dir.getName(), name, raw), e);
        }
    }

    @NonNull
    private static String readOptional(@NonNull File dir, @NonNull String name) {
        try {
            return readRequired(dir, name);
        } catch (IOException e) {
            return "";
        }
    }

    /** The rule identifier of a device: lowercase {@code vid:pid}, then the serial when it has one. */
    @NonNull
    public static String deriveId(@NonNull String vid, @NonNull String pid, @NonNull String serial) {
        var base = fmt("%s:%s", vid.toLowerCase(Locale.ROOT), pid.toLowerCase(Locale.ROOT));
        return serial.isEmpty() ? base : fmt("%s:%s", base, serial);
    }

    /** The port chain of a sysfs device name: {@code 2-1.4} is {@code 1.4}. */
    @NonNull
    public static String derivePort(@NonNull String sysfs) {
        var dash = sysfs.indexOf('-');
        return dash < 0 ? sysfs : sysfs.substring(dash + 1);
    }

    /** Whether a {@code bDeviceClass} value is the hub class, for a reader that has only that. */
    public static boolean isHubClass(@NonNull String deviceClass) {
        // Normalised here rather than by the caller: the kernel prints the class %02x, but this
        // is the one guard between an any-layer sink rule and a hub taking its whole subtree
        // down with it, and the fast lane reads the attribute raw.
        return CLASS_HUB.equals(deviceClass.trim().toLowerCase(Locale.ROOT));
    }

    /** A hub carries the rest of the tree; handing one to a VM would take its own children away. */
    public boolean isHub() {
        if (isHubClass(deviceClass)) return true;
        for (var iface : interfaces)
            if (CLASS_HUB.equals(iface.cls)) return true;
        return false;
    }

    /** True while a host driver still owns an interface, so attaching will take it away. */
    public boolean hostInUse() {
        for (var iface : interfaces)
            if (!iface.driver.isEmpty() && !DRIVER_USBFS.equals(iface.driver)) return true;
        return false;
    }

    /**
     * The wire fields, in wire order. Kept free of org.json so a test can assert on it: unit tests
     * run against the stubbed android.jar, where a JSONObject accepts everything and reports
     * nothing back (see the {@code isReturnDefaultValues} comment in app/build.gradle.kts).
     */
    @NonNull
    public LinkedHashMap<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("sysfs", sysfs);
        map.put("id", id);
        map.put("port", port);
        map.put("busnum", busnum);
        map.put("devnum", devnum);
        map.put("node", node);
        map.put("vid", vid);
        map.put("pid", pid);
        map.put("manufacturer", manufacturer);
        map.put("product", product);
        map.put("serial", serial);
        map.put("speed", speed);
        map.put("device_class", deviceClass);
        map.put("host_in_use", hostInUse());
        map.put("authorized", authorized);
        var list = new ArrayList<LinkedHashMap<String, Object>>();
        for (var iface : interfaces) {
            var item = new LinkedHashMap<String, Object>();
            item.put("name", iface.name);
            item.put("class", iface.cls);
            item.put("subclass", iface.subcls);
            item.put("protocol", iface.proto);
            item.put("driver", iface.driver);
            list.add(item);
        }
        map.put("interfaces", list);
        return map;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        var obj = new JSONObject();
        for (var entry : toMap().entrySet()) {
            var value = entry.getValue();
            if (!(value instanceof List)) {
                obj.put(entry.getKey(), value);
                continue;
            }
            var array = new JSONArray();
            for (var item : (List<?>) value) {
                var child = new JSONObject();
                for (var field : ((Map<?, ?>) item).entrySet())
                    child.put(String.valueOf(field.getKey()), field.getValue());
                array.put(child);
            }
            obj.put(entry.getKey(), array);
        }
        return obj;
    }
}
