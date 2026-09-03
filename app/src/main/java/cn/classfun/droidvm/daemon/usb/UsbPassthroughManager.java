// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.ServerContext;
import cn.classfun.droidvm.daemon.vm.VMInstance;
import cn.classfun.droidvm.lib.store.vm.VMState;

/**
 * The daemon's single authority over host USB devices: what the host has, who has it, and what
 * happens to it when a VM stops or the user pulls the cable.
 *
 * <p>A device belongs to at most one VM. The VMM claims every interface of a device it is handed,
 * which takes it away from whatever host driver held it, so the record kept here is the only
 * place that knows the device has to be given back -- the kernel does not rebind a driver by
 * itself once the claim is released, and crosvm does not notice an unplug until the next
 * transfer.</p>
 */
public final class UsbPassthroughManager {
    private static final String TAG = "UsbPassthroughManager";
    private static final String SYSFS_ROOT = "/sys/bus/usb/devices";
    private static final String DEV_ROOT = "/dev/bus/usb";
    /** The VMM already dropped the device; a detach for it is a no-op, not a failure. */
    private static final String TOKEN_NO_SUCH_PORT = "no_such_port";
    private static final String TOKEN_NO_SUCH_DEVICE = "no_such_device";
    /** Long enough for the drivers_probe writes of the last VM to go out. */
    private static final long SHUTDOWN_WAIT_SECONDS = 5;
    /** The driver an interface shows while the VMM holds it: a claim to wait out, not an owner. */
    private static final String DRIVER_USBFS = "usbfs";
    /** How long a released interface may still show that claim before the host is offered it. */
    private static final long USBFS_RELEASE_TIMEOUT_MS = 10_000;
    private static final long USBFS_POLL_MS = 200;

    /** One host device lent to one VM, for as long as both are alive. */
    static final class Attachment {
        final String vmId;
        final String vmName;
        final int port;
        final String sysfs;
        final String vid;
        final String pid;
        final String node;

        Attachment(@NonNull String vmId, @NonNull String vmName, int port, @NonNull String sysfs,
                   @NonNull String vid, @NonNull String pid, @NonNull String node) {
            this.vmId = vmId;
            this.vmName = vmName;
            this.port = port;
            this.sysfs = sysfs;
            this.vid = vid;
            this.pid = pid;
            this.node = node;
        }
    }

    private final Map<String, Attachment> attachments = new HashMap<>();
    /**
     * How many times each VM has started going down, so an attach that is still inside the CLI
     * can tell that the release pass for its VM has already walked past the record it is about
     * to file. Guarded by [lock], like {@link #attachments}.
     */
    private final Map<String, Integer> stopEpochs = new HashMap<>();
    private final Object lock = new Object();
    private final UsbHostInventory inventory = new UsbHostInventory(SYSFS_ROOT, DEV_ROOT);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "usb-manager");
        t.setDaemon(true);
        return t;
    });
    private final ServerContext context;
    private volatile Consumer<JSONObject> broadcaster = null;

    public UsbPassthroughManager(@NonNull ServerContext context) {
        this.context = context;
    }

    /** Starts the inventory watch. Called once, from the server, and never throws. */
    public void start(@NonNull Consumer<JSONObject> broadcaster) {
        this.broadcaster = broadcaster;
        try {
            inventory.start(this::onInventoryChanged);
            var devices = inventory.snapshot();
            var summary = new StringBuilder();
            for (var device : devices) {
                if (summary.length() > 0) summary.append(", ");
                summary.append(fmt("%s:%s %s", device.vid, device.pid, device.sysfs));
            }
            Log.i(TAG, fmt("Host USB devices: %d [%s]", devices.size(), summary));
        } catch (Exception e) {
            Log.w(TAG, "Failed to start the USB host inventory", e);
        }
    }

    /**
     * Stops the watch and lets the queued releases finish. Called on daemon shutdown, after the
     * VMs have been stopped: those stops only queue their releases, and the drivers_probe writes
     * that hand the interfaces back would otherwise be cut off when the process exits.
     */
    public void shutdown() {
        try {
            inventory.stop();
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop the USB host inventory", e);
        }
        try {
            worker.shutdown();
            if (!worker.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS))
                Log.w(TAG, "USB releases did not finish before shutdown");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop the USB worker", e);
        }
    }

    /** Every host device, each carrying who holds it right now. */
    @NonNull
    public JSONArray hostList() throws JSONException {
        var array = new JSONArray();
        // A fresh scan, not the snapshot: binding or unbinding a driver creates and removes no
        // device node, so the inotify watch never sees it and the snapshot's driver fields go
        // stale the moment a device is claimed or given back. The list is read on demand and a
        // scan is four directories, so it is cheaper to read sysfs than to be wrong.
        for (var device : inventory.scan()) {
            Attachment attachment;
            synchronized (lock) {
                attachment = attachments.get(device.sysfs);
            }
            var obj = device.toJson();
            obj.put("attached_vm", attachment == null ? JSONObject.NULL : attachment.vmId);
            obj.put("attached_vm_name", attachment == null ? JSONObject.NULL : attachment.vmName);
            obj.put("attached_port", attachment == null ? JSONObject.NULL : attachment.port);
            array.put(obj);
        }
        return array;
    }

    /** Hands the device named by [sysfs] to [vm] and returns the guest port it landed on. */
    public int attach(@NonNull VMInstance vm, @NonNull String sysfs) {
        if (vm.getState() != VMState.RUNNING)
            throw new RequestException("VM is not running");
        if (!vm.item.optBoolean("usb", false))
            throw new RequestException("USB is disabled for this VM (usb=false)");
        var socket = vm.getControlSocketPath();
        if (socket == null)
            throw new RequestException("VM has no control socket");
        // A fresh scan, not the snapshot: the bus/device numbers behind the node path are
        // reassigned on every re-enumeration, and a stale node is a different device.
        UsbHostDevice device = null;
        for (var candidate : inventory.rescanNow()) {
            if (!candidate.sysfs.equals(sysfs)) continue;
            device = candidate;
            break;
        }
        // A scan leaves hubs out, so a name it does not carry is either not there at all or is
        // the one thing that must not be lent out; read the directory itself to tell those apart.
        if (device == null) device = readUnlistedDevice(sysfs);
        if (device.isHub())
            throw new RequestException("refusing to attach a hub");
        var vmId = vm.getId().toString();
        var vmName = vm.getName();
        int epoch;
        synchronized (lock) {
            var existing = attachments.get(sysfs);
            if (existing != null)
                throw new RequestException(fmt("device already attached to VM %s", existing.vmName));
            epoch = stopEpoch(vmId);
        }
        var control = new CrosvmUsbControl(getPrebuiltBinaryPath("crosvm"), socket);
        int port;
        // Outside the lock: the CLI opens the node and claims every interface, which takes as
        // long as the slowest host driver needs to let go.
        try {
            port = control.attach(device.node);
        } catch (UsbControlException e) {
            // A refusal can come after the VMM has already claimed part of the device, and it
            // never hands those interfaces back on its own. No record exists to release them
            // later either, so this is the only chance to give them to the host.
            restoreHostDrivers(sysfs);
            throw new RequestException(fmt("crosvm usb attach failed: %s", e.token));
        } catch (IOException e) {
            restoreHostDrivers(sysfs);
            throw new RequestException(fmt("crosvm usb attach failed: %s", e.getMessage()));
        }
        String lostTo = null;
        boolean stopped = false;
        synchronized (lock) {
            var existing = attachments.get(sysfs);
            if (existing != null) lostTo = existing.vmName;
            else if (stopEpoch(vmId) != epoch) stopped = true;
            else attachments.put(sysfs, new Attachment(
                vmId, vmName, port, sysfs, device.vid, device.pid, device.node));
        }
        if (stopped) {
            // The VM went down while we were in the CLI and its release pass has already run.
            // Filing the record now would strand the device on a dead VM forever, and there is
            // nobody left to send a detach to, so just hand it straight back to the host.
            restoreHostDrivers(sysfs);
            throw new RequestException("VM is not running");
        }
        if (lostTo != null) {
            // A concurrent attach of the same device won while we were in the CLI; give the port
            // we were handed back before reporting the conflict.
            try {
                control.detach(port);
            } catch (Exception e) {
                Log.w(TAG, fmt("Failed to undo a lost attach on port %d: %s", port, e.getMessage()));
            }
            throw new RequestException(fmt("device already attached to VM %s", lostTo));
        }
        Log.i(TAG, fmt("Attached USB %s (%s:%s) to VM %s on port %d",
            sysfs, device.vid, device.pid, vmName, port));
        broadcastVm(vmId, vmName);
        broadcastHost();
        return port;
    }

    /** The device behind a sysfs name the scan did not return, or the step-4 refusal. */
    @NonNull
    private UsbHostDevice readUnlistedDevice(@NonNull String sysfs) {
        // [sysfs] comes straight from the request; keep the read inside the device root.
        var dir = new File(SYSFS_ROOT, sysfs);
        if (!SYSFS_ROOT.equals(dir.getParent()))
            throw new RequestException(fmt("no such USB device: %s", sysfs));
        try {
            return UsbHostDevice.fromSysfs(dir, DEV_ROOT);
        } catch (IOException e) {
            throw new RequestException(fmt("no such USB device: %s", sysfs));
        }
    }

    /** Must be called under [lock]. */
    private int stopEpoch(@NonNull String vmId) {
        var epoch = stopEpochs.get(vmId);
        return epoch == null ? 0 : epoch;
    }

    /** Takes a device back from [vm], addressed either by its sysfs name or by its guest port. */
    public void detach(@NonNull VMInstance vm, @Nullable String sysfs, @Nullable Integer port) {
        var vmId = vm.getId().toString();
        Attachment attachment = null;
        synchronized (lock) {
            for (var candidate : attachments.values()) {
                if (!candidate.vmId.equals(vmId)) continue;
                if (sysfs != null && !sysfs.isEmpty()) {
                    if (!candidate.sysfs.equals(sysfs)) continue;
                } else if (port == null || candidate.port != port) {
                    continue;
                }
                attachment = candidate;
                break;
            }
        }
        if (attachment == null)
            throw new RequestException("device not attached to this VM");
        var socket = vm.getControlSocketPath();
        var state = vm.getState();
        if (socket == null || state == VMState.STOPPING || state == VMState.STOPPED
            || state == VMState.REBOOTING) {
            // There is no VMM left to take the device from, and this is the only path that can
            // still drop a record the stop hook missed -- refusing here would strand it.
            synchronized (lock) {
                attachments.remove(attachment.sysfs);
            }
            Log.i(TAG, fmt("Dropped USB %s: VM %s is gone", attachment.sysfs, vm.getName()));
            restoreHostDrivers(attachment.sysfs);
            broadcastVm(vmId, vm.getName());
            broadcastHost();
            return;
        }
        var control = new CrosvmUsbControl(getPrebuiltBinaryPath("crosvm"), socket);
        try {
            control.detach(attachment.port);
        } catch (UsbControlException e) {
            if (!TOKEN_NO_SUCH_PORT.equals(e.token) && !TOKEN_NO_SUCH_DEVICE.equals(e.token))
                throw new RequestException(fmt("crosvm usb detach failed: %s", e.token));
            Log.i(TAG, fmt("VMM had already dropped port %d (%s)", attachment.port, e.token));
        } catch (IOException e) {
            // The record stays: the device is still the VM's until we know it is not.
            throw new RequestException(fmt("crosvm usb detach failed: %s", e.getMessage()));
        }
        synchronized (lock) {
            attachments.remove(attachment.sysfs);
        }
        Log.i(TAG, fmt("Detached USB %s from VM %s", attachment.sysfs, vm.getName()));
        restoreHostDrivers(attachment.sysfs);
        broadcastVm(vmId, vm.getName());
        broadcastHost();
    }

    /** What [vmId] currently holds. */
    @NonNull
    public JSONArray vmList(@NonNull String vmId) throws JSONException {
        var array = new JSONArray();
        var owned = new ArrayList<Attachment>();
        synchronized (lock) {
            for (var attachment : attachments.values())
                if (attachment.vmId.equals(vmId)) owned.add(attachment);
        }
        for (var attachment : owned) {
            var obj = new JSONObject();
            obj.put("sysfs", attachment.sysfs);
            obj.put("vid", attachment.vid);
            obj.put("pid", attachment.pid);
            obj.put("port", attachment.port);
            obj.put("node", attachment.node);
            array.put(obj);
        }
        return array;
    }

    /**
     * Called from {@code VMInstance.setState} for every transition. Bookkeeping is released only
     * once the VMM is gone: at STOPPING crosvm is still alive and still holds every interface it
     * claimed, so there is nothing to hand back yet and that transition only fences -- an attach
     * still inside the CLI is refused, but the records stay, and hostList and vmList go on showing
     * the devices as attached, which is the truth. STOPPED and REBOOTING drop the records without
     * a word to the VMM -- that process is gone -- and a state transition is never blocked on it.
     */
    public void onVmState(@NonNull VMInstance vm, @NonNull VMState state) {
        if (state != VMState.STOPPING && state != VMState.STOPPED && state != VMState.REBOOTING)
            return;
        try {
            var vmId = vm.getId().toString();
            var vmName = vm.getName();
            // Bumped here, on the transition thread, so it is ordered before the release runs:
            // an attach still inside the CLI then sees that its VM went down.
            synchronized (lock) {
                stopEpochs.put(vmId, stopEpoch(vmId) + 1);
            }
            if (state == VMState.STOPPING) return;
            worker.execute(() -> releaseAll(vmId, vmName));
        } catch (Exception e) {
            Log.w(TAG, "Failed to schedule the USB release of a stopping VM", e);
        }
    }

    private void releaseAll(@NonNull String vmId, @NonNull String vmName) {
        try {
            var released = new ArrayList<Attachment>();
            synchronized (lock) {
                var it = attachments.values().iterator();
                while (it.hasNext()) {
                    var attachment = it.next();
                    if (!attachment.vmId.equals(vmId)) continue;
                    released.add(attachment);
                    it.remove();
                }
            }
            // Most state changes concern a VM that never held a device; say nothing about those.
            if (released.isEmpty()) return;
            for (var attachment : released) {
                Log.i(TAG, fmt("Released USB %s (%s:%s) from VM %s",
                    attachment.sysfs, attachment.vid, attachment.pid, vmName));
                restoreHostDrivers(attachment.sysfs);
            }
            broadcastVm(vmId, vmName);
            broadcastHost();
        } catch (Exception e) {
            Log.w(TAG, "Failed to release the USB devices of a stopping VM", e);
        }
    }

    private void onInventoryChanged(@NonNull List<UsbHostDevice> all,
                                    @NonNull List<UsbHostDevice> added,
                                    @NonNull List<UsbHostDevice> removed) {
        for (var device : removed) {
            Attachment attachment;
            synchronized (lock) {
                attachment = attachments.get(device.sysfs);
            }
            if (attachment == null) continue;
            worker.execute(() -> releaseUnplugged(attachment));
        }
        for (var device : added) onDeviceAdded(device);
        broadcastHost();
    }

    /**
     * A device that was pulled while attached. crosvm only finds out on the next transfer, so the
     * detach has to come from here; the host has nothing to rebind, the device is gone.
     */
    private void releaseUnplugged(@NonNull Attachment attachment) {
        try {
            var inst = context.getVMs().findById(attachment.vmId);
            var socket = inst == null ? null : inst.getControlSocketPath();
            if (socket != null) {
                try {
                    new CrosvmUsbControl(getPrebuiltBinaryPath("crosvm"), socket)
                        .detach(attachment.port);
                } catch (Exception e) {
                    Log.w(TAG, fmt("Detach of unplugged %s failed: %s",
                        attachment.sysfs, e.getMessage()));
                }
            }
            synchronized (lock) {
                attachments.remove(attachment.sysfs);
            }
            Log.i(TAG, fmt("USB %s (%s:%s) was unplugged while attached to VM %s",
                attachment.sysfs, attachment.vid, attachment.pid, attachment.vmName));
            broadcastVm(attachment.vmId, attachment.vmName);
            broadcastHost();
        } catch (Exception e) {
            Log.w(TAG, "Failed to release an unplugged USB device", e);
        }
    }

    private void onDeviceAdded(@NonNull UsbHostDevice device) {
        // M4 auto-attach rules go here.
    }

    /**
     * Offers every unbound interface of [sysfs] back to the host. The kernel does not rebind a
     * driver once the VMM's claim is released, so nothing happens until something writes to
     * drivers_probe. Best effort: an interface no driver wants stays unbound, which is fine.
     */
    void restoreHostDrivers(@NonNull String sysfs) {
        try {
            var entries = new File(SYSFS_ROOT).listFiles();
            if (entries == null) return;
            var prefix = fmt("%s:", sysfs);
            for (var entry : entries) {
                var name = entry.getName();
                if (!name.startsWith(prefix)) continue;
                if (!awaitReleased(entry)) continue;
                var result = run("echo %s > /sys/bus/usb/drivers_probe", name);
                Log.i(TAG, fmt("drivers_probe %s: code=%d %s",
                    name, result.getCode(), result.getErrString()));
            }
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to hand the interfaces of %s back to the host", sysfs), e);
        }
    }

    /**
     * Whether the host may be offered [ifaceDir]. A driver that is not usbfs already holds it, so
     * there is nothing to give back. A usbfs link is the VMM's own claim, which outlives the
     * moment the device stopped being the VM's by however long the process needs to drop its fd,
     * so it is waited out rather than read as an owner -- probing through it does nothing and the
     * interface would stay unbound for good. Blocking is why this runs on the worker (or on an
     * IPC thread that has already sent the detach).
     */
    private boolean awaitReleased(@NonNull File ifaceDir) {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(USBFS_RELEASE_TIMEOUT_MS);
        while (true) {
            var driver = readDriver(ifaceDir);
            if (driver.isEmpty()) return true;
            if (!DRIVER_USBFS.equals(driver)) return false;
            if (System.nanoTime() - deadline >= 0) {
                Log.w(TAG, fmt("%s is still claimed through usbfs after %d ms; leaving it unbound",
                    ifaceDir.getName(), USBFS_RELEASE_TIMEOUT_MS));
                return false;
            }
            try {
                Thread.sleep(USBFS_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Basename of the interface's {@code driver} symlink, {@code ""} when nothing holds it. */
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

    private void broadcastHost() {
        try {
            var data = new JSONObject();
            data.put("devices", hostList());
            broadcast("usb_host_changed", data);
        } catch (Exception e) {
            Log.w(TAG, "Failed to build usb_host_changed", e);
        }
    }

    private void broadcastVm(@NonNull String vmId, @Nullable String vmName) {
        try {
            var data = new JSONObject();
            data.put("vm_id", vmId);
            data.put("vm_name", vmName == null ? JSONObject.NULL : vmName);
            data.put("devices", vmList(vmId));
            broadcast("usb_vm_changed", data);
        } catch (Exception e) {
            Log.w(TAG, "Failed to build usb_vm_changed", e);
        }
    }

    private void broadcast(@NonNull String event, @NonNull JSONObject data) {
        var target = broadcaster;
        if (target == null) return;
        try {
            data.put("event", event);
            var envelope = new JSONObject();
            envelope.put("type", "event");
            envelope.put("data", data);
            target.accept(envelope);
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to broadcast %s", event), e);
        }
    }
}
