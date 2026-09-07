// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
    /** A root hub directory, and its bus number: {@code usb3}. */
    private static final Pattern ROOT_HUB_NAME = Pattern.compile("^usb(\\d+)$");
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

    /**
     * What a device is doing, in the only terms the kernel keeps: which driver holds each of its
     * interfaces. Derived on every read and stored nowhere -- a daemon that remembered it would
     * be remembering something a shell, a VMM's death or a driver's own probe can change without
     * telling anybody.
     */
    public enum State {
        /** At least one interface bound to a driver that is not usbfs: Android has it. */
        HOSTUSE("hostuse"),
        /** At least one interface claimed through usbfs: a VMM's fd owns it. */
        VMUSE("vmuse"),
        /** Nothing bound to anything: nobody has it, and it is free to be given away. */
        IDLE("idle");

        /** The key this state goes under on the wire. */
        public final String key;

        State(@NonNull String key) {
            this.key = key;
        }

        /**
         * The state a wire key names, or null for a key this build has no word for.
         *
         * <p>Read back by the pages, which draw a device's row out of it: the daemon ships in
         * the same APK, so an unknown key is not a version to bridge but a reading that went
         * wrong, and a null says so rather than guessing one of the three.</p>
         */
        @Nullable
        public static State fromKey(@NonNull String key) {
            for (var state : values())
                if (state.key.equals(key)) return state;
            return null;
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
     * Whether the kernel lets this device be configured at all. Nothing in this daemon writes it
     * any more -- a device is always authorized, and "nobody may use it" is said by leaving it
     * idle -- so it is read for one reason only: an older build hid devices this way, and the
     * daemon's start-up migration has to find the ones it left behind.
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
            deviceClass, !"0".equals(readOptional(devDir, "authorized")), interfaces
        );
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
        // Normalised here rather than by the caller: the kernel prints the class %02x, but a
        // caller may have read the attribute raw, and this is the one guard between an
        // any-layer sink rule and a hub taking its whole subtree down with it.
        return CLASS_HUB.equals(deviceClass.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether [sysfs] names a root hub -- {@code usb3} -- rather than a device plugged into one.
     * A root hub is a bus's own hub: it is nobody's to lend out, no rule speaks for it and no
     * page lists it, but a driver still has to be bound to it or its ports are never scanned.
     */
    public static boolean isRootHubName(@NonNull String sysfs) {
        return ROOT_HUB_NAME.matcher(sysfs).matches();
    }

    /**
     * The prefix the kernel gives the interface directories of the device [sysfs], for a caller
     * that has the name and wants the interfaces beside it in {@code /sys/bus/usb/devices}.
     *
     * <p>A root hub is the one exception the naming scheme has: the device is {@code usb3} and
     * its interface is {@code 3-0:1.0}, the bus number with the port the root hub is not on. Get
     * this wrong and a sweep over a root hub finds no interface at all and silently does
     * nothing, which is a whole bus left dead.</p>
     */
    @NonNull
    public static String interfacePrefix(@NonNull String sysfs) {
        var root = ROOT_HUB_NAME.matcher(sysfs);
        return root.matches() ? fmt("%s-0:", root.group(1)) : fmt("%s:", sysfs);
    }

    /** A hub carries the rest of the tree; handing one to a VM would take its own children away. */
    public boolean isHub() {
        if (isHubClass(deviceClass)) return true;
        for (var iface : interfaces)
            if (CLASS_HUB.equals(iface.cls)) return true;
        return false;
    }

    /**
     * What the drivers bound to this device's interfaces say it is doing, this moment.
     *
     * <p>A usbfs claim outranks a host driver on the rare device that shows both -- crosvm claims
     * every interface of a device it is handed, so a half-claimed one only appears while a VMM is
     * dying -- because that claim is a live fd somebody owns, and a device with an owner must
     * never be a candidate for anything. The leftovers ({@link UsbLeftovers}) are what eventually
     * take a dead claim off it, and it reads idle or hostuse again the moment they do.</p>
     */
    @NonNull
    public State state() {
        var claimed = false;
        var bound = false;
        for (var iface : interfaces) {
            if (iface.driver.isEmpty()) continue;
            if (DRIVER_USBFS.equals(iface.driver)) claimed = true;
            else bound = true;
        }
        if (claimed) return State.VMUSE;
        return bound ? State.HOSTUSE : State.IDLE;
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
        // The one field that says who has the device, in place of the two booleans that used to
        // half-say it: authorized is nobody's business now that nothing writes it, and
        // host_in_use was this same read with the VMM's claim left out.
        map.put("state", state().key);
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
