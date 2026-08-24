// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm;

import static cn.classfun.droidvm.lib.utils.FileUtils.deleteFile;
import static cn.classfun.droidvm.lib.utils.FileUtils.readFile;
import static cn.classfun.droidvm.lib.utils.FileUtils.writeFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.threadSleep;

import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * A fixed pool of CDC-ACM functions on the device's USB gadget, shared by every VM serial
 * port with the USB_ACM backend. crosvm opens the pool member's {@code /dev/ttyGSn} with its
 * {@code type=dev} serial; the external host enumerates one USB serial port per member
 * (Windows usbser.sys / Linux cdc_acm, both in-box).
 *
 * <p>Why a pool instead of one function per VM: adding or removing a gadget function only
 * takes effect through a UDC rebind, and a rebind re-enumerates the whole gadget -- every
 * other ACM port and a USB-cable adb connection drop with it. So the pool is built once, in
 * one rebind, and VM starts/stops merely attach and release members -- zero rebinds, and the
 * host's COM numbering stays stable (usbser remembers ports per interface). An idle member
 * is just a quiet COM port on the host. Only a pool rebuild (size change, or the framework
 * wiping the config on a USB mode switch) rebinds again.</p>
 *
 * <p>Android's init mounts configfs at {@code /config} with labels this root daemon can use,
 * and the framework's gadget lives at {@code g1}, so members are grafted onto that gadget (a
 * UDC binds only one gadget at a time). configfs refuses config-link edits while the UDC is
 * bound (EINVAL), and the vendor USB HAL races to grab the UDC back within about a second of
 * an unbind -- so unbind, edit, bind runs as one uninterrupted sequence. The framework owns
 * g1: a USB mode switch or cable event may silently drop the members; the next acquire
 * notices and rebuilds.</p>
 */
public final class UsbAcmPool {
    private static final String TAG = "UsbAcmPool";
    private static final String GADGET = "/config/usb_gadget/g1";
    private static final String FUNCTIONS_DIR = GADGET + "/functions";
    private static final String CONFIG_DIR = GADGET + "/configs/b.1";
    private static final String UDC_FILE = GADGET + "/UDC";
    private static final String UDC_CLASS_DIR = "/sys/class/udc";
    private static final String INSTANCE_PREFIX = "dvmpool";
    private static final int NODE_WAIT_MS = 3000;
    private static final int NODE_POLL_MS = 50;
    /** Same keys the settings screen writes; they flow to the daemon via set_app_config. */
    public static final String KEY_USB_ACM_ENABLE = "usb_acm_enable";
    public static final String KEY_USB_ACM_PORTS = "usb_acm_ports";
    /** Off by default: the pool is a standing gadget change every daemon start would replay
     *  (USB re-enumeration plus host COM ports nobody may be using). */
    public static final boolean DEFAULT_ENABLE = false;
    public static final int DEFAULT_PORTS = 4;
    // u_serial tops out around 8 ports, and the gadget's endpoint budget (after mtp+adb)
    // fits 4-6 ACMs comfortably.
    public static final int MAX_PORTS = 6;

    /** Pool member instance name -> owner token; entries only exist while attached. */
    private static final Map<String, String> owners = new HashMap<>();

    private UsbAcmPool() {
    }

    /** A configfs edit that must run while the UDC is unbound. */
    private interface GadgetOp {
        void run() throws Exception;
    }

    /**
     * A slot that exists but cannot be attached right now: held by another running VM, or
     * outside the configured pool. Callers refuse the VM start (a silently reassigned COM
     * port on the host is worse than not booting) instead of degrading to a sink.
     */
    public static final class SlotUnavailableException extends IOException {
        SlotUnavailableException(@NonNull String message) {
            super(message);
        }
    }

    /** Whether the feature is switched on at all; without it every acquire refuses. */
    public static boolean enabledOf(@NonNull DataItem appConfig) {
        return appConfig.optBoolean(KEY_USB_ACM_ENABLE, DEFAULT_ENABLE);
    }

    /** The configured pool size, clamped to what the gadget can actually carry. */
    public static int portsOf(@NonNull DataItem appConfig) {
        var n = (int) appConfig.optLong(KEY_USB_ACM_PORTS, DEFAULT_PORTS);
        return Math.max(1, Math.min(MAX_PORTS, n));
    }

    /**
     * Brings the pool in line with the app config: builds it when the feature is enabled,
     * tears the unowned members down when it is not. Called whenever the daemon receives the
     * app config, so toggling the setting takes effect without a VM start. Failures only log:
     * the config write itself must not fail over gadget trouble.
     */
    public static synchronized void applyConfig(@NonNull DataItem appConfig) {
        try {
            reconcile(enabledOf(appConfig) ? portsOf(appConfig) : 0);
        } catch (IOException e) {
            Log.w(TAG, "USB ACM pool reconcile failed", e);
        }
    }

    /**
     * Attaches pool slot {@code slot} to {@code owner} and returns its {@code /dev/ttyGSn}.
     * The slot is part of the VM config, not first-free: boot order must never decide which
     * host COM port a VM lands on. Builds or repairs the pool first -- the only paths that
     * rebind the UDC. Throws {@link SlotUnavailableException} when the slot is taken or out
     * of range (refuse the boot), plain {@link IOException} when the gadget itself is
     * unusable (degrade to sink).
     */
    @NonNull
    public static synchronized String acquire(
        int slot, @NonNull String owner, @NonNull DataItem appConfig
    ) throws IOException {
        if (!enabledOf(appConfig))
            throw new SlotUnavailableException(
                "USB serial (ACM) is disabled; enable it in the app settings first");
        var poolSize = portsOf(appConfig);
        if (slot < 0 || slot >= poolSize)
            throw new SlotUnavailableException(fmt(
                "USB serial slot %d is outside the pool (size %d); raise the USB serial"
                    + " ports setting or pick a lower slot", slot, poolSize));
        var instance = fmt("%s%d", INSTANCE_PREFIX, slot);
        var holder = owners.get(instance);
        if (holder != null)
            throw new SlotUnavailableException(fmt(
                "USB serial slot %d is busy (held by %s)", slot, holder));
        reconcile(poolSize);
        var devPath = devPathOf(instance);
        waitForNode(devPath);
        owners.put(instance, owner);
        Log.i(TAG, fmt("acm slot %d (%s) -> %s", slot, devPath, owner));
        return devPath;
    }

    /** Releases every slot {@code owner} holds. Never rebinds; the member just idles. */
    public static synchronized void release(@NonNull String owner) {
        owners.values().removeIf(owner::equals);
    }

    /**
     * Brings the gadget to exactly {@code poolSize} pool members (0 = feature off): members
     * below the size exist and are linked, members at or above it are removed -- except ones a
     * running VM holds, which are left alone (yanking a function under an open crosvm fd
     * hangs the port up). All link edits share a single unbind/bind window; when nothing has
     * to change there is no rebind at all. Only functions named {@code acm.dvmpool*} are ever
     * touched: another app's (or the vendor's) acm/gser functions share the ttyGS number
     * space, and grabbing whatever exists would fight them -- ownership is by name, always.
     */
    private static void reconcile(int poolSize) throws IOException {
        var toRemove = new java.util.ArrayList<File>();
        var namePrefix = fmt("acm.%s", INSTANCE_PREFIX);
        var existing = new File(FUNCTIONS_DIR)
            .listFiles(f -> f.getName().startsWith(namePrefix));
        if (existing != null) for (var funcDir : existing) {
            var instance = funcDir.getName().substring("acm.".length());
            int index;
            try {
                index = Integer.parseInt(instance.substring(INSTANCE_PREFIX.length()));
            } catch (NumberFormatException e) {
                continue;
            }
            if (index >= poolSize && !owners.containsKey(instance))
                toRemove.add(funcDir);
        }
        var missingLink = false;
        for (int i = 0; i < poolSize; i++) {
            var funcDir = funcDirOf(fmt("%s%d", INSTANCE_PREFIX, i));
            // mkdir works while bound; only the config links need the unbound window.
            if (!funcDir.isDirectory() && !funcDir.mkdir())
                throw new IOException(fmt("cannot create %s", funcDir));
            if (!new File(CONFIG_DIR, funcDir.getName()).exists())
                missingLink = true;
        }
        if (!missingLink && toRemove.isEmpty()) return;
        withUdcUnbound(() -> {
            for (int i = 0; i < poolSize; i++) {
                var funcDir = funcDirOf(fmt("%s%d", INSTANCE_PREFIX, i));
                var link = new File(CONFIG_DIR, funcDir.getName());
                if (!link.exists())
                    Os.symlink(funcDir.getAbsolutePath(), link.getAbsolutePath());
            }
            for (var funcDir : toRemove) {
                deleteFile(new File(CONFIG_DIR, funcDir.getName()).getAbsolutePath());
                if (funcDir.isDirectory() && !funcDir.delete())
                    Log.w(TAG, fmt("cannot remove %s", funcDir));
            }
        });
        Log.i(TAG, fmt("USB ACM pool reconciled to %d members", poolSize));
    }

    @NonNull
    private static File funcDirOf(@NonNull String instance) {
        return new File(FUNCTIONS_DIR, fmt("acm.%s", instance));
    }

    /**
     * The member's character device. The kernel assigns the ttyGS index at function creation
     * and reports it in port_num -- the instance name says nothing about it (gser/acm share
     * one number space, and other functions may hold lower indexes).
     */
    @NonNull
    private static String devPathOf(@NonNull String instance) throws IOException {
        try {
            var portNum = readFile(new File(funcDirOf(instance), "port_num")).trim();
            return fmt("/dev/ttyGS%d", Integer.parseInt(portNum));
        } catch (NumberFormatException e) {
            throw new IOException("cannot parse acm port_num", e);
        }
    }

    /** The node only appears after the gadget binds, not at function-creation time. */
    private static void waitForNode(@NonNull String devPath) throws IOException {
        var node = new File(devPath);
        for (int waited = 0; !node.exists(); waited += NODE_POLL_MS) {
            if (waited >= NODE_WAIT_MS)
                throw new IOException(fmt("%s did not appear after bind", devPath));
            threadSleep(NODE_POLL_MS);
        }
    }

    /**
     * Runs a configfs edit inside an unbind/bind window, back-to-back with no waiting in
     * between, and with the bind in a finally so a failed edit never strands the gadget
     * unbound (which would kill adb-over-USB for good).
     */
    private static void withUdcUnbound(@NonNull GadgetOp op) throws IOException {
        var udc = "";
        try {
            udc = readFile(UDC_FILE).trim();
        } catch (IOException ignored) {
        }
        if (udc.isEmpty()) {
            var names = new File(UDC_CLASS_DIR).list();
            if (names == null || names.length == 0)
                throw new IOException("no UDC available");
            udc = names[0];
        }
        try {
            writeFile(UDC_FILE, "");
        } catch (IOException ignored) {
            // Already unbound; the bind below is what matters.
        }
        try {
            op.run();
        } catch (Exception e) {
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        } finally {
            writeFile(UDC_FILE, udc);
        }
    }

}
