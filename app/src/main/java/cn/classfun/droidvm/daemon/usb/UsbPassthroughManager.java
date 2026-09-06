// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import cn.classfun.droidvm.daemon.console.ConsoleStream;
import cn.classfun.droidvm.daemon.server.RequestException;
import cn.classfun.droidvm.daemon.server.ServerContext;
import cn.classfun.droidvm.daemon.vm.VMInstance;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.lib.store.vm.VMXhciConfig;

/**
 * The daemon's single authority over host USB devices: what the host has, who has it, and what
 * happens to it when a VM stops or the user pulls the cable.
 *
 * <p>A device belongs to at most one VM. The VMM claims every interface of a device it is handed,
 * which takes it away from whatever host driver held it, so the record kept here is the only
 * place that knows the device has to be given back -- the kernel does not rebind a driver by
 * itself once the claim is released, and crosvm does not notice an unplug until the next
 * transfer.</p>
 *
 * <p>Devices also get lent out without being asked for: the rules (section 2.4 of the plan) are
 * run over everything plugged in and unclaimed whenever a device appears, a VM reaches RUNNING,
 * or the rules change -- and never when a VM goes down, so a stop releases devices but takes
 * nothing.
 * The deciding is the {@link UsbRuleEngine}'s and happens under the same lock as the manual
 * bookkeeping; the attaching goes through the same path as a manual attach, so both kinds share
 * one record and one set of conflicts. When a pass runs is {@link UsbRulePassTiming}'s: a VM
 * is RUNNING before its control socket listens, so the pass for that edge waits for the socket,
 * and a pass that could not reach the VMM comes back rather than blame the device.</p>
 *
 * <p>Giving a device back is not always possible at the moment it is asked for: the VMM's usbfs
 * claim on an interface can outlive the wait -- on device, a VMM whose xHCI had died kept its
 * claims until the process exited -- and such an interface is left unbound. It is not left for
 * good: the {@link UsbLeftovers} record keeps it against its VM, and it is tried again when that
 * VM's process is gone, and whenever a scan shows the claim gone.</p>
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
    /** The VMM's own stderr, as VMInstance names it: where crosvm says why an attach failed. */
    private static final String STREAM_STDERR = "stderr";
    /**
     * How long a refused attach waits for that reason to land in the stream when it is not there
     * yet: the VMM logs it before it answers, but its reader thread still has to be scheduled.
     */
    private static final long VMM_LOG_GRACE_MS = 100;
    /**
     * A composite device's nodes show up one at a time and the inventory reports each batch; the
     * rules run once the batches have stopped, so a device is offered whole.
     */
    private static final long AUTO_ATTACH_DEBOUNCE_MS = 400;

    /** One host device lent to one VM, for as long as both are alive. */
    static final class Attachment {
        final String vmId;
        final String vmName;
        final int port;
        final String sysfs;
        final String vid;
        final String pid;
        final String node;
        /** The rule that made this attachment, or null when the user asked for it. */
        @Nullable
        final UsbRuleEngine.Decision rule;

        Attachment(@NonNull String vmId, @NonNull String vmName, int port, @NonNull String sysfs,
                   @NonNull String vid, @NonNull String pid, @NonNull String node,
                   @Nullable UsbRuleEngine.Decision rule) {
            this.vmId = vmId;
            this.vmName = vmName;
            this.port = port;
            this.sysfs = sysfs;
            this.vid = vid;
            this.pid = pid;
            this.node = node;
            this.rule = rule;
        }

        /** "manual" or "auto": who asked for this attachment. */
        @NonNull
        String source() {
            return rule == null ? "manual" : "auto";
        }
    }

    private final Map<String, Attachment> attachments = new HashMap<>();
    /**
     * How many times each VM has started going down, so an attach that is still inside the CLI
     * can tell that the release pass for its VM has already walked past the record it is about
     * to file. Guarded by [lock], like {@link #attachments}.
     */
    private final Map<String, Integer> stopEpochs = new HashMap<>();
    /** Interfaces a restore pass could not give back yet. Guarded by [lock]. */
    private final UsbLeftovers leftovers = new UsbLeftovers();
    private final Object lock = new Object();
    private final UsbHostInventory inventory = new UsbHostInventory(SYSFS_ROOT, DEV_ROOT);
    /** The rules and the per-device flags; every call into it is made under [lock]. */
    private final UsbRuleEngine engine = new UsbRuleEngine();
    private final File rulesFile = new File(pathJoin(DATA_DIR, "files"), UsbRulesFile.NAME);
    /**
     * One thread, so the releases of a stopping VM, the detach of an unplugged device and the
     * rule passes run in the order they were queued: a pass never races the release that is
     * making its candidates free.
     */
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "usb-manager");
        t.setDaemon(true);
        return t;
    });
    /** The debounced rule pass a plug event armed, if any. Guarded by [lock]. */
    private ScheduledFuture<?> pendingAuto = null;
    private final ServerContext context;
    private volatile Consumer<JSONObject> broadcaster = null;

    public UsbPassthroughManager(@NonNull ServerContext context) {
        this.context = context;
    }

    /**
     * Loads the rules and starts the inventory watch. Called once, from the server, and never
     * throws. A VM that is already RUNNING by now reached that state before anyone was listening
     * and before there was an inventory to pick from, so the pass its RUNNING edge would have
     * queued is queued here instead.
     */
    public void start(@NonNull Consumer<JSONObject> broadcaster) {
        this.broadcaster = broadcaster;
        var rules = UsbRulesFile.load(rulesFile);
        synchronized (lock) {
            engine.setRules(rules);
        }
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
        // autoUp spawned these moments ago, so they get the same wait their edge would have.
        context.getVMs().forEach((id, inst) -> {
            if (inst.getState() == VMState.RUNNING) queueAutoAttachWhenReady(inst);
        });
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
        for (var device : scanHost()) {
            Attachment attachment;
            boolean held;
            synchronized (lock) {
                attachment = attachments.get(device.sysfs);
                held = engine.isHeld(device.sysfs);
            }
            var obj = device.toJson();
            obj.put("attached_vm", attachment == null ? JSONObject.NULL : attachment.vmId);
            obj.put("attached_vm_name", attachment == null ? JSONObject.NULL : attachment.vmName);
            obj.put("attached_port", attachment == null ? JSONObject.NULL : attachment.port);
            obj.put("held", held);
            obj.put("auto_rule", attachment == null || attachment.rule == null
                ? JSONObject.NULL : ruleJson(attachment.rule));
            array.put(obj);
        }
        return array;
    }

    /** The rules as the daemon holds them. */
    @NonNull
    public UsbRules getRules() {
        synchronized (lock) {
            return engine.getRules();
        }
    }

    /**
     * Replaces the rules: validated, kept, written out, and run once. Returns how many devices
     * that run attached. A save is not a reason to take anything away -- devices already lent out
     * are not candidates -- and does not clear a hold either.
     */
    public int setRules(@NonNull JSONObject json) {
        var rules = UsbRules.fromJson(json, this::targetExists);
        synchronized (lock) {
            engine.setRules(rules);
        }
        try {
            UsbRulesFile.save(rulesFile, rules);
        } catch (IOException e) {
            // The rules are in force regardless; only the copy for the next daemon start is missing.
            Log.w(TAG, fmt("Failed to write %s", rulesFile), e);
        }
        Log.i(TAG, fmt("USB rules set: exact=%d port=%d device=%d any=%d",
            rules.layer(UsbRules.Layer.EXACT).size(), rules.layer(UsbRules.Layer.PORT).size(),
            rules.layer(UsbRules.Layer.DEVICE).size(), rules.layer(UsbRules.Layer.ANY).size()));
        return runAutoAttachAndWait("rules");
    }

    /**
     * Whether a rule's target is one the daemon can act on: a VM it knows about.
     *
     * <p>The controller a rule names is deliberately not checked here, however tempting it looks.
     * The daemon's copy of a VM config is read once at daemon start and only replaced when a VM
     * is created or started, so it cannot tell a controller that does not exist from one the
     * editor added a moment ago -- and every controller this codebase writes is added that way.
     * Refusing on that copy would refuse the very save that added the card, and would throw away
     * the whole payload, including the rules the user changed on the cards that do exist.</p>
     *
     * <p>Nothing is lost by trusting it: a rule naming a controller the VM turns out not to have
     * is passed over when a device is offered ({@link #vmHasController}), never acted on wrongly,
     * and the rules page labels it as the dangling rule it is.</p>
     */
    private boolean targetExists(@NonNull String vmId, @Nullable String controllerId) {
        return context.getVMs().findById(vmId) != null;
    }

    /**
     * Whether the VM a rule points at still has the controller it names, asked of the config that
     * VM is running with -- the controllers a running VM has are the ones it booted with. A rule
     * naming none asks whether it has any.
     */
    private boolean vmHasController(@NonNull String vmId, @Nullable String controllerId) {
        var inst = context.getVMs().findById(vmId);
        return inst != null && VMXhciConfig.findController(inst.item, controllerId) != null;
    }

    /**
     * What the rules say about every plugged device, without acting on it. The match is reported
     * for held and attached devices too -- the row says so, and "where would this go" is the
     * question a user editing the rules is asking.
     */
    @NonNull
    public JSONArray testRules() throws JSONException {
        var array = new JSONArray();
        // A fresh scan, for the same reason hostList takes one.
        var devices = scanHost();
        synchronized (lock) {
            for (var device : devices) {
                var attachment = attachments.get(device.sysfs);
                var decision = engine.decide(UsbRuleEngine.Device.of(device), this::stateOf,
                    this::vmHasController);
                var obj = new JSONObject();
                obj.put("sysfs", device.sysfs);
                obj.put("id", device.id);
                obj.put("port", device.port);
                obj.put("held", engine.isHeld(device.sysfs));
                obj.put("attached_vm", attachment == null ? JSONObject.NULL : attachment.vmId);
                obj.put("result", decision == null ? JSONObject.NULL : decisionJson(decision));
                array.put(obj);
            }
        }
        return array;
    }

    @NonNull
    private static JSONObject ruleJson(@NonNull UsbRuleEngine.Decision decision)
        throws JSONException {
        var obj = new JSONObject();
        obj.put("layer", decision.layer.key);
        obj.put("index", decision.index);
        // Null for a rule that names no controller, which means the target VM's first one.
        obj.put("controller", decision.controller == null
            ? JSONObject.NULL : decision.controller);
        return obj;
    }

    @NonNull
    private static JSONObject decisionJson(@NonNull UsbRuleEngine.Decision decision)
        throws JSONException {
        var obj = ruleJson(decision);
        obj.put("vm", decision.vm == null ? JSONObject.NULL : decision.vm);
        return obj;
    }

    /** The state of the VM [vmId] names, null when there is no such VM. */
    @Nullable
    private VMState stateOf(@NonNull String vmId) {
        var inst = context.getVMs().findById(vmId);
        return inst == null ? null : inst.getState();
    }

    /** Hands the device named by [sysfs] to [vm] and returns the guest port it landed on. */
    public int attach(@NonNull VMInstance vm, @NonNull String sysfs) {
        try {
            return attach(vm, sysfs, null);
        } catch (UsbVmmUnreachableException e) {
            // To a caller this is a refusal like any other; only a rule pass acts on the difference.
            throw new RequestException(fmt("crosvm usb attach failed: %s", e.getMessage()));
        }
    }

    /**
     * The one attach path. [rule] is the decision that asked for this, or null when the user did;
     * it only goes on the record, so a device attached by rule and one attached by hand are the
     * same thing to every release, conflict and listing.
     *
     * @throws UsbVmmUnreachableException when the CLI never reached the VMM: the device was not
     *                                    touched and nothing is known against it.
     */
    private int attach(@NonNull VMInstance vm, @NonNull String sysfs,
                       @Nullable UsbRuleEngine.Decision rule) throws UsbVmmUnreachableException {
        if (vm.getState() != VMState.RUNNING)
            throw new RequestException("VM is not running");
        // What the rule asked for, or the VM's first controller when it asked for no particular
        // one. crosvm's `usb attach` takes no controller argument -- it emulates one -- so this is
        // the whole of resolving it today; what it buys is that a rule naming a controller the VM
        // does not have is refused rather than quietly landing on another one.
        var wanted = rule == null ? null : rule.controller;
        if (VMXhciConfig.findController(vm.item, wanted) == null)
            throw new RequestException(wanted == null
                ? "VM has no USB controller"
                : fmt("VM has no USB controller %s", wanted));
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
        // Where the VMM says why it refused, if it does: a point taken now, so that a refusal
        // can read what it logged from here on. Taking it is a counter read and costs nothing.
        var vmmLog = vm.getStream(STREAM_STDERR);
        var vmmMark = vmmLog == null ? 0 : vmmLog.mark();
        int port;
        // Outside the lock: the CLI opens the node and claims every interface, which takes as
        // long as the slowest host driver needs to let go.
        try {
            port = control.attach(device.node);
        } catch (UsbVmmUnreachableException e) {
            // The CLI opens the node before it connects and claims nothing until the VMM has
            // the fd, so the host still holds every interface and there is nothing to restore.
            throw e;
        } catch (UsbControlException e) {
            // A refusal can come after the VMM has already claimed part of the device, and it
            // never hands those interfaces back on its own. No record exists to release them
            // later either, so this is the only chance to give them to the host.
            restoreHostDrivers(sysfs, vmId);
            throw new RequestException(CrosvmUsbControl.attachFailureMessage(
                e.token, e.stderr, vmmLogSince(vmmLog, vmmMark)));
        } catch (IOException e) {
            restoreHostDrivers(sysfs, vmId);
            throw new RequestException(fmt("crosvm usb attach failed: %s", e.getMessage()));
        }
        String lostTo = null;
        boolean stopped = false;
        synchronized (lock) {
            var existing = attachments.get(sysfs);
            if (existing != null) lostTo = existing.vmName;
            else if (stopEpoch(vmId) != epoch) stopped = true;
            else {
                attachments.put(sysfs, new Attachment(
                    vmId, vmName, port, sysfs, device.vid, device.pid, device.node, rule));
                // The VMM holds the whole device now, and this record's release will give the
                // whole device back; whatever an earlier restore could not is not owed twice.
                leftovers.forgetDevice(sysfs);
            }
        }
        if (stopped) {
            // The VM went down while we were in the CLI and its release pass has already run.
            // Filing the record now would strand the device on a dead VM forever, and there is
            // nobody left to send a detach to, so just hand it straight back to the host.
            restoreHostDrivers(sysfs, vmId);
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
        Log.i(TAG, fmt("Attached USB %s (%s:%s) to VM %s on port %d (%s)",
            sysfs, device.vid, device.pid, vmName, port, rule == null ? "manual"
                : fmt("auto, rule %s[%d]", rule.layer.key, rule.index)));
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

    /**
     * Takes a device back from [vm], addressed either by its sysfs name or by its guest port.
     * This is the user's doing, so the device is held: no rule offers it to a VM again until it
     * is unplugged. The releases a stop or an unplug make are not detaches and hold nothing.
     */
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
                engine.hold(attachment.sysfs);
            }
            Log.i(TAG, fmt("Dropped USB %s: VM %s is gone", attachment.sysfs, vm.getName()));
            restoreHostDrivers(attachment.sysfs, vmId);
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
            engine.hold(attachment.sysfs);
        }
        Log.i(TAG, fmt("Detached USB %s from VM %s; held until unplugged",
            attachment.sysfs, vm.getName()));
        restoreHostDrivers(attachment.sysfs, vmId);
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
     * RUNNING is the other edge the rules care about: the VM can take devices from then on, and
     * a reboot is a REBOOTING release followed by this, which is how its devices find their way
     * back. No release runs the rules -- a stop is never the reason a device changes hands.
     * The release pass of those two edges is also when the interfaces an earlier restore had to
     * leave claimed get their try: the process that held them is gone by then.
     */
    public void onVmState(@NonNull VMInstance vm, @NonNull VMState state) {
        if (state == VMState.RUNNING) {
            // Cleared here, on the transition thread, so the queued pass sees it: a failure
            // was against the instance that is gone, and this is the first moment there is a
            // new one to try. Holds stay -- those are the user's, not the VM's.
            synchronized (lock) {
                engine.forgetFailuresFor(vm.getId().toString());
            }
            queueAutoAttachWhenReady(vm);
            return;
        }
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

    /**
     * The VM's process is gone: its devices are released, and then whatever an earlier restore
     * could not give back because this VMM would not let go is tried again -- the claim went
     * with the process. Runs on the worker. The leftovers are read before the releases and
     * tried after them, so that what this pass itself gives up on is not waited for twice.
     */
    private void releaseAll(@NonNull String vmId, @NonNull String vmName) {
        try {
            var released = new ArrayList<Attachment>();
            List<String> left;
            synchronized (lock) {
                var it = attachments.values().iterator();
                while (it.hasNext()) {
                    var attachment = it.next();
                    if (!attachment.vmId.equals(vmId)) continue;
                    released.add(attachment);
                    it.remove();
                }
                left = leftovers.leftBy(vmId);
            }
            for (var attachment : released) {
                Log.i(TAG, fmt("Released USB %s (%s:%s, %s) from VM %s",
                    attachment.sysfs, attachment.vid, attachment.pid, attachment.source(),
                    vmName));
                restoreHostDrivers(attachment.sysfs, vmId);
            }
            var recovered = left.isEmpty() ? 0
                : recoverLeftovers(left, fmt("VM %s stopped", vmName));
            // Most state changes concern a VM that never held a device; say nothing about those.
            if (released.isEmpty() && recovered == 0) return;
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
            List<String> dropped;
            synchronized (lock) {
                attachment = attachments.get(device.sysfs);
                // The node is gone, and with it the instance a hold or a failure was about;
                // whatever is plugged in there next is a new device the rules may take.
                engine.forget(device.sysfs);
                // And with it the interfaces still owed to the host: there is no host driver
                // to bind to a device that is not there.
                dropped = leftovers.forgetDevice(device.sysfs);
            }
            if (!dropped.isEmpty())
                Log.i(TAG, fmt("USB %s was unplugged with %d interface(s) still owed to the host",
                    device.sysfs, dropped.size()));
            if (attachment == null) continue;
            worker.execute(() -> releaseUnplugged(attachment));
        }
        if (!added.isEmpty()) scheduleAutoAttach();
        // The broadcast lists the host afresh, and that scan is read for leftovers on the way.
        broadcastHost();
    }

    /** A fresh scan of the host, read for leftovers on the way: every on-demand list takes one. */
    @NonNull
    private List<UsbHostDevice> scanHost() {
        var devices = inventory.scan();
        noticeLeftovers(devices);
        return devices;
    }

    /**
     * Reads a scan against the leftovers. A recorded interface the scan shows free -- no driver
     * at all, so the claim that kept it is gone -- gets its try on the worker, whatever its VM
     * is doing; one a host driver holds again was given back some other way and is forgotten.
     * Nothing to do is the common case and costs one lock and a lookup.
     */
    private void noticeLeftovers(@NonNull List<UsbHostDevice> devices) {
        UsbLeftovers.Scan scan;
        synchronized (lock) {
            if (leftovers.isEmpty()) return;
            var drivers = new HashMap<String, String>();
            for (var device : devices)
                for (var iface : device.interfaces) drivers.put(iface.name, iface.driver);
            scan = leftovers.scan(drivers);
        }
        for (var entry : scan.reclaimed.entrySet())
            Log.i(TAG, fmt("USB %s is back with %s; nothing left to restore",
                entry.getKey(), entry.getValue()));
        if (scan.free.isEmpty()) return;
        schedule(() -> {
            if (recoverLeftovers(scan.free, "claim gone") > 0) broadcastHost();
        }, 0);
    }

    /**
     * A later try at interfaces a restore pass had to leave unbound; [reason] says what
     * prompted it. Runs on the worker, with the same wait for the claim as the first try. The
     * interfaces are taken off the record first -- a try that finds one still claimed puts it
     * back -- and any whose device has meanwhile been attached again is passed over, since
     * that attachment's own release will hand the whole device back. Returns how many were
     * offered to the host.
     */
    private int recoverLeftovers(@NonNull Collection<String> ifaces, @NonNull String reason) {
        Map<String, String> taken;
        synchronized (lock) {
            taken = leftovers.take(ifaces);
            taken.keySet().removeIf(iface -> attachments.containsKey(UsbLeftovers.deviceOf(iface)));
        }
        if (taken.isEmpty()) return 0;
        var probed = restoreInterfaces(taken, true);
        if (!probed.isEmpty())
            Log.i(TAG, fmt("Recovered %d USB interface(s) left unbound earlier (%s): %s",
                probed.size(), reason, String.join(", ", probed)));
        return probed.size();
    }

    /** Whether [vmId]'s process is gone: the VM is stopped, between reboots, or no more. */
    private boolean vmGone(@NonNull String vmId) {
        var state = stateOf(vmId);
        return state == null || state == VMState.STOPPED || state == VMState.REBOOTING;
    }

    /** Arms (or re-arms) the rule pass a plug owes, so a burst of nodes becomes one pass. */
    private void scheduleAutoAttach() {
        synchronized (lock) {
            if (pendingAuto != null) pendingAuto.cancel(false);
            try {
                pendingAuto = worker.schedule(() -> runPassQuietly("plug"),
                    AUTO_ATTACH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // shutdown() won the race; nothing is going to be attached any more.
                pendingAuto = null;
            }
        }
    }

    /**
     * The pass a VM's RUNNING edge owes, once the VM can take part in it. RUNNING is set the
     * moment the VMM is spawned (see VMInstance), seconds before its control loop listens, and
     * a pass run at the edge had every attach fail on the connect. So the pass waits: a probe a
     * second on the worker, for up to a minute, dropped if the VM leaves RUNNING meanwhile. A
     * VM that never becomes ready is skipped with a warning -- the next trigger will find it.
     */
    private void queueAutoAttachWhenReady(@NonNull VMInstance vm) {
        var reason = fmt("VM %s running", vm.getName());
        UsbRulePassTiming.whenReady(this::schedule,
            () -> vm.getState() == VMState.RUNNING,
            () -> controlSocketReady(vm),
            () -> runPassQuietly(reason),
            () -> Log.i(TAG, fmt("USB rules pass (%s) dropped: VM left RUNNING", reason)),
            () -> Log.w(TAG, fmt("USB rules pass (%s) skipped: control socket not ready after %d s",
                reason, UsbRulePassTiming.READY_POLL_MS * UsbRulePassTiming.READY_MAX_POLLS / 1000)),
            UsbRulePassTiming.READY_POLL_MS, UsbRulePassTiming.READY_MAX_POLLS);
    }

    /**
     * Whether [vm]'s control socket accepts a connection, which is the VMM's control loop being
     * up. Connecting and closing is all it takes: crosvm drops a tube that goes away without a
     * request. Nothing is said over it, so this is the same probe for every backend that has a
     * socket at all.
     */
    private static boolean controlSocketReady(@NonNull VMInstance vm) {
        var path = vm.getControlSocketPath();
        if (path == null) return false;
        try (var socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET)) {
            socket.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The worker as a scheduler; a task the shutting-down worker refuses has nothing to attach to. */
    private void schedule(@NonNull Runnable task, long delayMs) {
        try {
            worker.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            Log.i(TAG, "USB rules task dropped: shutting down");
        }
    }

    /** Runs a rule pass on the worker and waits for it: the caller wants the count. */
    private int runAutoAttachAndWait(@NonNull String reason) {
        try {
            return worker.submit(() -> runPass(reason)).get();
        } catch (RejectedExecutionException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (ExecutionException e) {
            Log.w(TAG, fmt("USB rules pass (%s) failed", reason), e.getCause());
            return 0;
        }
    }

    /**
     * One pass, and if it found a VMM unreachable, up to {@link UsbRulePassTiming#MAX_RETRIES}
     * more a couple of seconds apart -- the socket was not listening yet, and blaming the device
     * would keep it on the host until the next trigger. Returns what the first pass attached.
     */
    private int runPass(@NonNull String reason) {
        var result = runAutoAttach(reason);
        if (result.unreachable) {
            var again = fmt("%s, retry", reason);
            UsbRulePassTiming.retry(this::schedule, () -> {
                var r = runAutoAttachOrNull(again);
                return r == null || !r.unreachable;
            }, UsbRulePassTiming.RETRY_DELAY_MS, UsbRulePassTiming.MAX_RETRIES);
        }
        return result.applied;
    }

    private void runPassQuietly(@NonNull String reason) {
        try {
            runPass(reason);
        } catch (Exception e) {
            Log.w(TAG, fmt("USB rules pass (%s) failed", reason), e);
        }
    }

    /** A pass that threw is over, not one to try again. */
    @Nullable
    private PassResult runAutoAttachOrNull(@NonNull String reason) {
        try {
            return runAutoAttach(reason);
        } catch (Exception e) {
            Log.w(TAG, fmt("USB rules pass (%s) failed", reason), e);
            return null;
        }
    }

    /** What one pass did, and whether a VMM it needed was not there to be asked. */
    private static final class PassResult {
        final int applied;
        final boolean unreachable;

        PassResult(int applied, boolean unreachable) {
            this.applied = applied;
            this.unreachable = unreachable;
        }
    }

    /**
     * One pass of the rules over everything plugged in, unclaimed and not held. Deciding is done
     * under the lock against the inventory's snapshot; the attaches themselves run outside it,
     * one after another, through the same path a manual attach takes. A device whose attach
     * fails is marked for the VM it failed for, so the next pass passes that rule over instead
     * of failing the same way again -- until the device is replugged or that VM next comes up.
     * The exception is a VMM the CLI could not reach: that says nothing about the device, so
     * nothing is remembered against it, the VM's other devices are left for the rerun rather
     * than fail one by one, and the result says the pass should come back.
     */
    @NonNull
    private PassResult runAutoAttach(@NonNull String reason) {
        List<UsbRuleEngine.Decision> plan;
        synchronized (lock) {
            if (engine.getRules().isEmpty()) return new PassResult(0, false);
            var plugged = new ArrayList<UsbRuleEngine.Device>();
            for (var device : inventory.snapshot()) plugged.add(UsbRuleEngine.Device.of(device));
            plan = engine.plan(plugged, attachments::containsKey, this::stateOf,
                this::vmHasController);
        }
        var applied = 0;
        var unreachable = new HashSet<String>();
        for (var decision : plan) {
            // A null VM is the rule saying the host keeps it, which takes no action.
            if (decision.vm == null) continue;
            if (unreachable.contains(decision.vm)) continue;
            var inst = context.getVMs().findById(decision.vm);
            if (inst == null) continue;
            var device = decision.device;
            var where = fmt("%s[%d]", decision.layer.key, decision.index);
            try {
                attach(inst, device.sysfs, decision);
                applied++;
                broadcastAuto("usb_auto_attached", inst, decision, null);
            } catch (UsbVmmUnreachableException e) {
                unreachable.add(decision.vm);
                Log.w(TAG, fmt("Auto attach of USB %s (%s) to VM %s by rule %s: %s; will retry",
                    device.sysfs, device.id, inst.getName(), where, e.getMessage()));
            } catch (Exception e) {
                synchronized (lock) {
                    engine.markFailed(device.sysfs, decision.vm);
                }
                Log.w(TAG, fmt("Auto attach of USB %s (%s) to VM %s by rule %s failed: %s",
                    device.sysfs, device.id, inst.getName(), where, e.getMessage()));
                broadcastAuto("usb_auto_failed", inst, decision, e.getMessage());
            }
        }
        Log.i(TAG, fmt("USB rules pass (%s): %d decision(s), %d attached%s",
            reason, plan.size(), applied, unreachable.isEmpty() ? "" : ", VMM unreachable"));
        return new PassResult(applied, !unreachable.isEmpty());
    }

    private void broadcastAuto(@NonNull String event, @NonNull VMInstance vm,
                               @NonNull UsbRuleEngine.Decision decision, @Nullable String error) {
        try {
            var data = new JSONObject();
            data.put("vm_id", vm.getId().toString());
            data.put("vm_name", vm.getName());
            data.put("sysfs", decision.device.sysfs);
            data.put("id", decision.device.id);
            data.put("port", decision.device.port);
            data.put("layer", decision.layer.key);
            data.put("index", decision.index);
            data.put("controller", decision.controller == null
                ? JSONObject.NULL : decision.controller);
            if (error != null) data.put("error", error);
            broadcast(event, data);
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to build %s", event), e);
        }
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

    /**
     * Offers every unbound interface of [sysfs] back to the host, [vmId] being the VM whose VMM
     * held it. The kernel does not rebind a driver once the VMM's claim is released, so nothing
     * happens until something writes to drivers_probe. Best effort: an interface no driver wants
     * stays unbound, which is fine; one the VMM will not let go of is recorded for later.
     */
    void restoreHostDrivers(@NonNull String sysfs, @NonNull String vmId) {
        try {
            var ifaceToVm = new LinkedHashMap<String, String>();
            for (var name : interfacesOf(sysfs)) ifaceToVm.put(name, vmId);
            restoreInterfaces(ifaceToVm, false);
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to hand the interfaces of %s back to the host", sysfs), e);
        }
    }

    /**
     * Offers each of [ifaceToVm]'s interfaces back to the host once the VMM's claim on it is
     * gone, the value being the VM that held it. One the wait gives up on is left unbound and
     * recorded against that VM for a later try -- and when that VM's process is already gone,
     * the try is queued at once, because the exit that would have queued it has passed. A
     * [recovery] is such a later try: it records what it still cannot do but queues nothing, so
     * an interface nothing ever lets go of cannot keep the worker waiting on it for good.
     * Returns the interfaces that were offered.
     */
    @NonNull
    private List<String> restoreInterfaces(@NonNull Map<String, String> ifaceToVm,
                                           boolean recovery) {
        var probed = new ArrayList<String>();
        var again = new ArrayList<String>();
        for (var entry : ifaceToVm.entrySet()) {
            var name = entry.getKey();
            var dir = new File(SYSFS_ROOT, name);
            // Unplugged meanwhile: nothing to bind, and nothing to remember.
            if (!dir.isDirectory()) continue;
            var claim = awaitReleased(dir);
            if (claim == Claim.HOST) continue;
            if (claim == Claim.NONE) {
                var result = run("echo %s > /sys/bus/usb/drivers_probe", name);
                Log.i(TAG, fmt("drivers_probe %s: code=%d %s",
                    name, result.getCode(), result.getErrString()));
                probed.add(name);
                continue;
            }
            var vmId = entry.getValue();
            synchronized (lock) {
                leftovers.leave(name, vmId);
            }
            if (!recovery && vmGone(vmId)) again.add(name);
        }
        if (!again.isEmpty())
            schedule(() -> {
                if (recoverLeftovers(again, "VM already gone") > 0) broadcastHost();
            }, 0);
        return probed;
    }

    /** The interfaces of [sysfs] as sysfs lists them now, sorted; none once the device is gone. */
    @NonNull
    private static List<String> interfacesOf(@NonNull String sysfs) {
        var names = new ArrayList<String>();
        var entries = new File(SYSFS_ROOT).listFiles();
        if (entries == null) return names;
        var prefix = fmt("%s:", sysfs);
        for (var entry : entries)
            if (entry.getName().startsWith(prefix)) names.add(entry.getName());
        Collections.sort(names);
        return names;
    }

    /** What the wait for an interface found holding it. */
    private enum Claim {
        /** Nothing: it may be offered to the host. */
        NONE,
        /** A host driver: there is nothing to give back. */
        HOST,
        /** The VMM, still, when the wait ran out. */
        VMM,
    }

    /**
     * Waits for the host to be allowed [ifaceDir]. A driver that is not usbfs already holds it, so
     * there is nothing to give back. A usbfs link is the VMM's own claim, which outlives the
     * moment the device stopped being the VM's by however long the process needs to drop its fd,
     * so it is waited out rather than read as an owner -- probing through it does nothing and the
     * interface would stay unbound for good. Blocking is why this runs on the worker (or on an
     * IPC thread that has already sent the detach).
     */
    @NonNull
    private Claim awaitReleased(@NonNull File ifaceDir) {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(USBFS_RELEASE_TIMEOUT_MS);
        while (true) {
            var driver = readDriver(ifaceDir);
            if (driver.isEmpty()) return Claim.NONE;
            if (!DRIVER_USBFS.equals(driver)) return Claim.HOST;
            if (System.nanoTime() - deadline >= 0) {
                Log.w(TAG, fmt("%s is still claimed through usbfs after %d ms; leaving it unbound "
                    + "until the claim is gone", ifaceDir.getName(), USBFS_RELEASE_TIMEOUT_MS));
                return Claim.VMM;
            }
            try {
                Thread.sleep(USBFS_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Claim.VMM;
            }
        }
    }

    /**
     * What the VMM wrote to [log] since [mark], for a refusal to be explained by. Read after
     * the restore, which is when the message is composed; when no ERROR is there yet, one short
     * grace and one more read, because the VMM logs its reason before it answers the CLI and
     * the daemon's reader of that stream may simply not have run yet. Never on the success path.
     */
    @NonNull
    private static String vmmLogSince(@Nullable ConsoleStream log, long mark) {
        if (log == null) return "";
        var text = log.since(mark);
        if (CrosvmUsbControl.firstErrorLine(text) != null) return text;
        try {
            Thread.sleep(VMM_LOG_GRACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return text;
        }
        return log.since(mark);
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
