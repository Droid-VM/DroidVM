// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.enums.Enums;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

/**
 * Wrapper over one entry of a VM config's "serial_ports" array -- one entry, one guest serial
 * port.
 *
 * <p>Configs from before this array existed carry no entries at all, and their VMs behaved as
 * "COM1 is the app console, COM2-4 are sinks" (that was hard-coded in the backend). So that is
 * exactly what {@link #ensureDefaults} materializes: readers must call it (or accept the same
 * defaults) rather than treating a missing array as "no serial ports".</p>
 */
public final class VMSerialConfig {
    /** Key of the array on the VM config. */
    public static final String KEY = "serial_ports";

    public final DataItem item;

    public VMSerialConfig(@NonNull DataItem item) {
        this.item = item;
    }

    @NonNull
    public SerialHardware getHardware() {
        return Enums.optEnum(item, "hardware", SerialHardware.SERIAL);
    }

    public void setHardware(@NonNull SerialHardware hardware) {
        item.set("hardware", hardware);
    }

    /** 1-based port number within its hardware kind (crosvm's {@code num=}). */
    public int getNum() {
        return (int) item.optLong("num", 1);
    }

    public void setNum(int num) {
        item.set("num", num);
    }

    @NonNull
    public SerialBackend getBackend() {
        return Enums.optEnum(item, "backend", SerialBackend.SINK);
    }

    public void setBackend(@NonNull SerialBackend backend) {
        item.set("backend", backend);
    }

    /** Host path for path-based backends; for PTY, the optional stable symlink to the slave. */
    @NonNull
    public String getPath() {
        var v = item.optString("path", "");
        return v == null ? "" : v;
    }

    public void setPath(@NonNull String path) {
        item.set("path", path);
    }

    /**
     * Whether this port is the guest console -- the one the firmware's SPCR points at (which
     * is what Windows EMS/SAC attaches to) and the one earlycon lands on. Explicit and
     * single-select in the UI, because "first interactive port wins" made the fixed COM
     * quartet unbeatable: an SBSA port could never take the console without sinking COM1 and
     * losing its firmware-log tab. Configs from before this key have it on no port; readers
     * fall back to the historical first-interactive rule so their behavior is unchanged.
     */
    public boolean isConsole() {
        return item.optBoolean("console", false);
    }

    public void setConsole(boolean console) {
        item.set("console", console);
    }

    /**
     * Which USB ACM pool slot this port attaches to ({@link SerialBackend#USB_ACM} only).
     * Part of the config on purpose: first-free allocation would let boot order decide which
     * host COM port a VM lands on.
     */
    public int getUsbSlot() {
        return (int) item.optLong("usb_slot", 0);
    }

    public void setUsbSlot(int slot) {
        item.set("usb_slot", slot);
    }

    /** True for the built-in 16550 quartet: backend is editable, the row is not removable. */
    public boolean isFixed() {
        return item.optBoolean("fixed", false);
    }

    public void setFixed(boolean fixed) {
        item.set("fixed", fixed);
    }

    /**
     * Console-stream name for this port when its backend is {@link SerialBackend#APP_CONSOLE}.
     * Doubles as the stable identity the console UI shows, so it is defined here rather than in
     * the daemon.
     */
    @NonNull
    public String getStreamName() {
        switch (getHardware()) {
            case SBSA: return fmt("sbsa%d", getNum());
            case VIRTIO_CONSOLE: return fmt("vcon%d", getNum());
            case SERIAL:
            default: return fmt("serial%d", getNum());
        }
    }

    /** Human-facing name of the port itself: "Serial 1", "SBSA 1", ... */
    @NonNull
    public String getDisplayName(@NonNull Context ctx) {
        return fmt("%s %d", getHardware().getDisplayString(ctx), getNum());
    }

    /** Wraps every entry of {@code config}'s "serial_ports" array, in order. */
    @NonNull
    public static List<VMSerialConfig> listOf(@NonNull DataItem config) {
        var out = new ArrayList<VMSerialConfig>();
        var arr = config.opt(KEY, (DataItem) null);
        if (arr == null || !arr.is(DataItem.Type.ARRAY)) return out;
        for (var iter : arr)
            out.add(new VMSerialConfig(iter.getValue()));
        return out;
    }

    /**
     * Makes {@code config}'s serial list explicit, preserving what older configs meant.
     *
     * <p>A missing/invalid array becomes the historical layout: COM1 as the app console, COM2-4
     * as sinks. An array that exists but is missing one of the fixed COM ports (a config saved
     * by a build with fewer of them, or hand-edited) gets the missing ones appended as sinks.
     * Existing entries are never touched.</p>
     */
    public static void ensureDefaults(@NonNull DataItem config) {
        var arr = config.opt(KEY, (DataItem) null);
        if (arr == null || !arr.is(DataItem.Type.ARRAY)) {
            config.set(KEY, DataItem.newArray());
            arr = config.opt(KEY, (DataItem) null);
        }
        var have = new boolean[SerialHardware.SERIAL.getMaxPorts() + 1];
        var haveConsole = false;
        for (var iter : arr) {
            var port = new VMSerialConfig(iter.getValue());
            var num = port.getNum();
            if (port.getHardware() == SerialHardware.SERIAL
                && num >= 1 && num < have.length) have[num] = true;
            if (port.isConsole()) haveConsole = true;
        }
        for (int num = 1; num < have.length; num++) {
            if (have[num]) continue;
            var entry = DataItem.newObject();
            arr.append(entry);
            var port = new VMSerialConfig(entry);
            port.setHardware(SerialHardware.SERIAL);
            port.setNum(num);
            port.setBackend(num == 1 ? SerialBackend.APP_CONSOLE : SerialBackend.SINK);
            // The console is single-select: a config that already names one -- a shipped
            // template whose SBSA carries it -- must not gain a second flag here.
            if (num == 1 && !haveConsole) port.setConsole(true);
            port.setFixed(true);
        }
    }
}
