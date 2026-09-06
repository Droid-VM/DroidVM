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
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * run over everything plugged in and unclaimed whenever the daemon starts, a device appears, a
 * VM reaches RUNNING, or the rules change -- and never when a VM goes down, so a stop releases
 * devices but takes nothing. All five of those triggers take nothing at all while the rules carry
 * their master switch off; what a pass still does then is give back, which is the half of it that
 * is not the rules acting. Only the direct actions -- attach, detach and the management page --
 * still work, because those are how a user takes a device by hand. Turning that switch off is
 * itself the one trigger that only gives: it hands back everything this daemon had taken.
 * The deciding is the {@link UsbRuleEngine}'s and happens under the same lock as the manual
 * bookkeeping; the attaching goes through the same path as a manual attach, so both kinds share
 * one record and one set of conflicts. When a pass runs is {@link UsbRulePassTiming}'s: a VM
 * is RUNNING before its control socket listens, so the pass for that edge waits for the socket,
 * and a pass that could not reach the VMM comes back rather than blame the device.</p>
 *
 * <p>A rule may also ask for a device to be hidden rather than lent out: writing {@code 0} to
 * its {@code authorized} attribute makes the kernel drop the device's configuration and every
 * interface, so Android never binds a driver to it and never raises its own dialog about it.
 * That is the sink. It is the one outcome this daemon has to reach before Android does, which is
 * why it has a lane of its own ({@link #onNodeAppearedFast}) beside the debounced rule pass; and
 * it is the one outcome nothing on disk records, so what is sinked is worked out again at every
 * pass from the host's own {@code authorized} flags and the rules ({@link #reconcileSinks}).</p>
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
    /**
     * How long a device that has just been authorized again is given to come back before it is
     * attached anyway. The kernel re-reads the configuration and binds the drivers itself, which
     * took about a second on the test phone; this is three times that.
     */
    private static final long AUTHORIZE_TIMEOUT_MS = 3000;
    private static final long AUTHORIZE_POLL_MS = 50;
    /** usbfs's char device major, and how many device minors each bus is given. */
    private static final int USBFS_MAJOR = 189;
    private static final int USBFS_DEVICES_PER_BUS = 128;
    /** The kernel's own index of char devices, which answers "what is behind this node". */
    private static final String CHAR_DEV_ROOT = "/sys/dev/char";

    /** One host device lent to one VM, for as long as both are alive. */
    static final class Attachment {
        final String vmId;
        final String vmName;
        final int port;
        final String sysfs;
        final String vid;
        final String pid;
        final String node;
        /**
         * The controller it landed on: what the rule named, or the VM's first one when it named
         * none. Recorded because a list is asked which xHCI a device is on, and only the attach
         * knows -- crosvm emulates the controller and takes no argument for it.
         */
        @Nullable
        final String controller;
        /** The rule that made this attachment, or null when the user asked for it. */
        @Nullable
        final UsbRuleEngine.Decision rule;

        Attachment( // arity-ok: a value object; these parameters are its fields
            @NonNull String vmId,
            @NonNull String vmName,
            int port,
            @NonNull String sysfs,
            @NonNull String vid,
            @NonNull String pid,
            @NonNull String node,
            @Nullable String controller,
            @Nullable UsbRuleEngine.Decision rule
        ) {
            this.vmId = vmId;
            this.vmName = vmName;
            this.port = port;
            this.sysfs = sysfs;
            this.vid = vid;
            this.pid = pid;
            this.node = node;
            this.controller = controller;
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
    /** The devices this daemon deauthorized, and why. Guarded by [lock]. */
    private final UsbSinks sinks = new UsbSinks();
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
            // Before the watch, so no node can appear without the lane hearing about it.
            inventory.setFastListener(this::onNodeAppearedFast);
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
        // And one pass whatever is running, which those per-VM ones cannot stand in for: with no
        // VM up there is nothing to attach, but a device a rule sinks still has to be sinked, and
        // a device the previous run left deauthorized has to be adopted or given back. Queued
        // after the rules are loaded and the first scan is in, and skipped like any other trigger
        // when the switch says the rules do not run.
        schedule(() -> runPassQuietly("start"), 0);
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
            UsbRuleEngine.Pin pin;
            UsbSinks.Record sink;
            synchronized (lock) {
                attachment = attachments.get(device.sysfs);
                pin = engine.pinOf(device.sysfs);
                sink = sinks.get(device.sysfs);
            }
            var obj = device.toJson();
            obj.put("attached_vm", attachment == null ? JSONObject.NULL : attachment.vmId);
            obj.put("attached_vm_name", attachment == null ? JSONObject.NULL : attachment.vmName);
            obj.put("attached_port", attachment == null ? JSONObject.NULL : attachment.port);
            obj.put("attached_controller", attachment == null || attachment.controller == null
                ? JSONObject.NULL : attachment.controller);
            obj.put("pin", pin.key);
            // What "held" always meant -- a device no trigger may touch -- so a reader that only
            // knows the boolean still reads the truth.
            obj.put("held", pin != UsbRuleEngine.Pin.NONE);
            obj.put("sink", sink == null ? JSONObject.NULL : sinkJson(sink));
            obj.put("auto_rule", attachment == null || attachment.rule == null
                ? JSONObject.NULL : ruleJson(attachment.rule));
            array.put(obj);
        }
        return array;
    }

    /** Which controller [sysfs] is attached to, null when it is not attached at all. */
    @Nullable
    public String attachedController(@NonNull String sysfs) {
        synchronized (lock) {
            var attachment = attachments.get(sysfs);
            return attachment == null ? null : attachment.controller;
        }
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
     * that run acted on -- attached, hidden, or given back after the rule that hid them was
     * deleted. A save is not a reason to take anything away -- devices already lent out are not
     * candidates -- and does not clear a pin either.
     *
     * <p>The one save that takes rather than gives is the master switch's falling edge: a save
     * that turns it off gives every device this daemon took back to the host, once, and runs no
     * pass. A save that finds it already off does neither, and one that turns it on runs the
     * ordinary pass, so the rules apply the moment they are allowed to.</p>
     */
    public int setRules(@NonNull JSONObject json) {
        var rules = UsbRules.fromJson(json, this::targetExists);
        UsbRuleEngine.Save owed;
        synchronized (lock) {
            // Asked and installed under one lock: the falling edge is a comparison with what the
            // daemon held a moment ago, and the answer would be worth nothing if another save
            // could land between the two. The new rules go in before the release runs, not
            // after: with the switch already off no trigger can act, so nothing can hide or lend
            // out a device behind the release's back.
            owed = engine.saveOwes(rules);
            engine.setRules(rules);
        }
        try {
            UsbRulesFile.save(rulesFile, rules);
        } catch (IOException e) {
            // The rules are in force regardless; only the copy for the next daemon start is missing.
            Log.w(TAG, fmt("Failed to write %s", rulesFile), e);
        }
        Log.i(TAG, fmt("USB rules set: enabled=%s exact=%d port=%d device=%d any=%d",
            rules.isEnabled(),
            rules.layer(UsbRules.Layer.EXACT).size(), rules.layer(UsbRules.Layer.PORT).size(),
            rules.layer(UsbRules.Layer.DEVICE).size(), rules.layer(UsbRules.Layer.ANY).size()));
        switch (owed) {
            case RELEASE:
                return releaseEverythingAndWait();
            case NOTHING:
                return 0;
            default:
                return runAutoAttachAndWait("rules");
        }
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
                obj.put("authorized", device.authorized);
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
        obj.put("target", decision.target.key);
        obj.put("vm", decision.vm == null ? JSONObject.NULL : decision.vm);
        return obj;
    }

    /** What a sink record says about a device: which rule did it, or that the user did. */
    @NonNull
    private static JSONObject sinkJson(@NonNull UsbSinks.Record record) throws JSONException {
        var obj = new JSONObject();
        obj.put("layer", record.layer == null ? JSONObject.NULL : record.layer.key);
        obj.put("index", record.index);
        obj.put("manual", record.manual);
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
            return attach(vm, sysfs, null, null);
        } catch (UsbVmmUnreachableException e) {
            // To a caller this is a refusal like any other; only a rule pass acts on the difference.
            throw new RequestException(fmt("crosvm usb attach failed: %s", e.getMessage()));
        }
    }

    /**
     * The one attach path. [wanted] is the controller it should land on, null for the VM's
     * first; [rule] is the decision that asked for this, or null when the user did, and only
     * goes on the record, so a device attached by rule and one attached by hand are the same
     * thing to every release, conflict and listing.
     *
     * @throws UsbVmmUnreachableException when the CLI never reached the VMM: nothing is known
     *                                    against the device, and a sink this call had to lift
     *                                    to make the attempt is put back before it is thrown.
     */
    private int attach(@NonNull VMInstance vm, @NonNull String sysfs, @Nullable String wanted,
                       @Nullable UsbRuleEngine.Decision rule) throws UsbVmmUnreachableException {
        if (vm.getState() != VMState.RUNNING)
            throw new RequestException("VM is not running");
        // What was asked for, or the VM's first controller when nothing was. crosvm's
        // `usb attach` takes no controller argument -- it emulates one -- so this is the whole of
        // resolving it today; what it buys is that a rule naming a controller the VM does not
        // have is refused rather than quietly landing on another one.
        var controller = VMXhciConfig.findController(vm.item, wanted);
        if (controller == null)
            throw new RequestException(wanted == null
                ? "VM has no USB controller"
                : fmt("VM has no USB controller %s", wanted));
        var controllerId = controller.getControllerId();
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
        // A sinked device has no configuration and no interface for the VMM to claim, so it has
        // to be given back to the kernel first -- and then waited for, because the drivers it
        // binds on the way back are what the VMM takes over. Whatever fails after this point
        // re-sinks it rather than restoring the host drivers: that restore is a drivers_probe,
        // which is exactly the event a sink exists to prevent.
        boolean wasSinked;
        boolean wasManual;
        synchronized (lock) {
            var record = sinks.get(sysfs);
            wasSinked = record != null;
            // Carried through to the undo: a sink the user asked for by hand is the one no rule
            // pass may give back, and putting the device back weaker than this call found it
            // would have the next pass authorize something the user hid.
            wasManual = record != null && record.manual;
        }
        if (wasSinked || !device.authorized) {
            if (!setAuthorized(sysfs, true))
                throw new RequestException(fmt("could not authorize %s", sysfs));
            synchronized (lock) {
                sinks.remove(sysfs);
            }
            awaitInterfaces(sysfs);
            // Re-read for the interfaces and the node; busnum and devnum do not change, because
            // deauthorizing is not an unplug and nothing was re-enumerated.
            device = readUnlistedDevice(sysfs);
            if (!device.authorized)
                throw new RequestException(fmt("%s is still deauthorized", sysfs));
            wasSinked = true;
        }
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
            // The CLI opens the node before it connects and claims nothing until the VMM has the
            // fd, so the host still holds every interface and there is nothing to restore. A
            // device this call took out of the sink is another matter: it is authorized and
            // visible to Android now, and a VMM that never answered is no reason to undo the
            // rule that hid it -- nothing else would, until the next trigger decides it again.
            if (wasSinked) undoAttach(sysfs, vmId, true, wasManual);
            throw e;
        } catch (UsbControlException e) {
            // A refusal can come after the VMM has already claimed part of the device, and it
            // never hands those interfaces back on its own. No record exists to release them
            // later either, so this is the only chance to give them to the host.
            undoAttach(sysfs, vmId, wasSinked, wasManual);
            throw new RequestException(CrosvmUsbControl.attachFailureMessage(
                e.token, e.stderr, vmmLogSince(vmmLog, vmmMark)));
        } catch (IOException e) {
            undoAttach(sysfs, vmId, wasSinked, wasManual);
            throw new RequestException(fmt("crosvm usb attach failed: %s", e.getMessage()));
        }
        String lostTo = null;
        boolean stopped = false;
        synchronized (lock) {
            var existing = attachments.get(sysfs);
            if (existing != null) lostTo = existing.vmName;
            else if (stopEpoch(vmId) != epoch) stopped = true;
            else {
                attachments.put(sysfs, new Attachment(vmId, vmName, port, sysfs, device.vid,
                    device.pid, device.node, controllerId.isEmpty() ? null : controllerId, rule));
                // The VMM holds the whole device now, and this record's release will give the
                // whole device back; whatever an earlier restore could not is not owed twice.
                leftovers.forgetDevice(sysfs);
            }
        }
        if (stopped) {
            // The VM went down while we were in the CLI and its release pass has already run.
            // Filing the record now would strand the device on a dead VM forever, and there is
            // nobody left to send a detach to, so just hand it straight back.
            undoAttach(sysfs, vmId, wasSinked, wasManual);
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

    /**
     * The recovery after an attach that did not land. A device that was hidden goes back to
     * being hidden, and to being hidden the way it was: the drivers_probe
     * {@link #restoreHostDrivers} writes is precisely what makes Android bind the device and ask
     * the user about it, and [wasManual] is what keeps the next rule pass from authorizing a
     * device the user hid by hand once the attempt this undoes has failed.
     */
    private void undoAttach(@NonNull String sysfs, @NonNull String vmId, boolean wasSinked,
                            boolean wasManual) {
        if (!wasSinked) {
            restoreHostDrivers(sysfs, vmId);
            return;
        }
        var device = deviceAt(sysfs);
        sinkDevice(sysfs, device == null ? -1 : device.devnum, null, wasManual);
    }

    /**
     * Waits for a device that has just been authorized again to come back with its interfaces.
     * Gives up quietly: a configuration with no interface at all is rare but legal, and crosvm's
     * own refusal is a better message than one invented here. What must not happen is attaching
     * a device that is still deauthorized, and that is read back by the caller.
     */
    private static void awaitInterfaces(@NonNull String sysfs) {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AUTHORIZE_TIMEOUT_MS);
        while (interfacesOf(sysfs).isEmpty()) {
            if (System.nanoTime() - deadline >= 0) {
                Log.w(TAG, fmt("USB %s still has no interface %d ms after being authorized",
                    sysfs, AUTHORIZE_TIMEOUT_MS));
                return;
            }
            try {
                Thread.sleep(AUTHORIZE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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
        // Before the VMM is told, not after: a rule pass runs on the worker while this runs on
        // an IPC thread, and a device that is momentarily neither attached nor spoken for is one
        // that pass can take.
        synchronized (lock) {
            engine.pin(attachment.sysfs, UsbRuleEngine.Pin.HOST);
        }
        dropAttachment(attachment, vm);
        Log.i(TAG, fmt("Detached USB %s from VM %s; held until unplugged",
            attachment.sysfs, vm.getName()));
        restoreHostDrivers(attachment.sysfs, vmId);
        broadcastVm(vmId, vm.getName());
        broadcastHost();
    }

    /**
     * Tells [vm]'s VMM to drop [attachment] and takes the record with it, leaving the device
     * itself alone: what happens to it next is the caller's, because a detach hands it back to
     * the host while the management page may be hiding it or moving it to another VM.
     *
     * @throws RequestException when the CLI could not be reached; the record stays, because the
     *                          device is still the VM's until we know it is not.
     */
    private void dropAttachment(@NonNull Attachment attachment, @NonNull VMInstance vm) {
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
            throw new RequestException(fmt("crosvm usb detach failed: %s", e.getMessage()));
        }
        synchronized (lock) {
            attachments.remove(attachment.sysfs);
        }
    }

    /**
     * The management page's direct action: [sysfs] goes where [target] says, right now, without
     * the rules being consulted or run again. Returns what it did, and what the device was
     * before, so the page can say so and a retry after a half-applied batch is not blind. Runs
     * on the IPC thread, the way attach and detach already do.
     *
     * <p>What keeps the next rule pass from undoing it depends on the target. A host or a sink
     * is pinned, and a pin lasts until the device is unplugged -- a rules save does not clear it
     * -- which is what a manual override has to mean, and what the page has to say on the row. A
     * VM needs no pin: the attachment record is already the shield, and when that VM stops the
     * rules own the device again, which is the honest reading of "put it on this VM".</p>
     *
     * <p>The pin is set before the device is touched, never after: this runs while a pass may be
     * running on the worker, and a device that is momentarily neither attached nor pinned is one
     * that pass can take.</p>
     */
    @NonNull
    public JSONObject setTarget(@NonNull String sysfs, @NonNull UsbRules.Target target,
                                @Nullable VMInstance vm, @Nullable String controller)
        throws JSONException {
        // Read from sysfs rather than from the snapshot: this validates the name the request
        // sent before anything is written to it.
        var device = readUnlistedDevice(sysfs);
        if (target != UsbRules.Target.HOST && device.isHub())
            throw new RequestException("refusing to take a hub away from the host");
        Attachment previous;
        UsbSinks.Record sinked;
        UsbRuleEngine.Pin pinned;
        synchronized (lock) {
            previous = attachments.get(sysfs);
            sinked = sinks.get(sysfs);
            pinned = engine.pinOf(sysfs);
        }
        var res = new JSONObject();
        res.put("device", sysfs);
        res.put("target", target.key);
        res.put("previous", previousJson(device, previous));
        Integer port = null;
        switch (target) {
            case SINK:
                setTargetSink(device, previous, pinned);
                break;
            case VM:
                if (vm == null) throw new RequestException("missing vm_id");
                port = setTargetVm(vm, sysfs, controller, previous, pinned);
                break;
            default:
                setTargetHost(device, previous, sinked);
                break;
        }
        String landedOn = null;
        if (target == UsbRules.Target.VM) {
            synchronized (lock) {
                var now = attachments.get(sysfs);
                landedOn = now == null ? null : now.controller;
            }
        }
        res.put("vm_id", vm == null ? JSONObject.NULL : vm.getId().toString());
        res.put("controller", landedOn == null ? JSONObject.NULL : landedOn);
        res.put("port", port == null ? JSONObject.NULL : port);
        return res;
    }

    /**
     * What a device was before the direct action, in the words the request uses. Read from the
     * device rather than from the sink records, the way the page's rows are: a device the host
     * shows deauthorized was hidden whoever hid it, and a response that called it "host" would
     * disagree with the row the user was looking at when they asked.
     */
    @NonNull
    private static JSONObject previousJson(@NonNull UsbHostDevice device,
                                           @Nullable Attachment attachment) throws JSONException {
        var target = UsbRules.Target.HOST;
        if (attachment != null) target = UsbRules.Target.VM;
        else if (!device.authorized) target = UsbRules.Target.SINK;
        var obj = new JSONObject();
        obj.put("target", target.key);
        obj.put("vm_id", attachment == null ? JSONObject.NULL : attachment.vmId);
        obj.put("port", attachment == null ? JSONObject.NULL : attachment.port);
        return obj;
    }

    /** The direct action's "leave it on the host": the pin, then whatever has to be undone. */
    private void setTargetHost(@NonNull UsbHostDevice device, @Nullable Attachment previous,
                               @Nullable UsbSinks.Record sinked) {
        var sysfs = device.sysfs;
        synchronized (lock) {
            engine.pin(sysfs, UsbRuleEngine.Pin.HOST);
        }
        if (previous != null) {
            var holder = context.getVMs().findById(previous.vmId);
            // The manual take-back, whole: the CLI, the record, the restore and the broadcasts.
            if (holder != null) {
                detach(holder, sysfs, null);
                return;
            }
            synchronized (lock) {
                attachments.remove(sysfs);
            }
            restoreHostDrivers(sysfs, previous.vmId);
            broadcastVm(previous.vmId, previous.vmName);
            broadcastHost();
            return;
        }
        // Nothing owed to the host here: the kernel re-reads the configuration on the way back
        // and binds the drivers itself, so there is no drivers_probe to write.
        if ((sinked != null || !device.authorized) && !unsink(sysfs))
            throw new RequestException(fmt("could not authorize %s", sysfs));
        broadcastHost();
    }

    /** The direct action's "hide it": the pin, the VMM's copy taken away, then authorized=0. */
    private void setTargetSink(@NonNull UsbHostDevice device, @Nullable Attachment previous,
                               @NonNull UsbRuleEngine.Pin pinned) {
        var sysfs = device.sysfs;
        synchronized (lock) {
            engine.pin(sysfs, UsbRuleEngine.Pin.SINK);
        }
        if (previous != null) dropFromHolder(previous);
        // And deliberately no restoreHostDrivers on the way through, though the VMM has just
        // let go: probing would bind the host drivers this is about to take away again, and
        // Android's dialog rides on precisely that.
        if (!sinkDevice(sysfs, device.devnum, null, true) && !isSinked(sysfs)) {
            synchronized (lock) {
                // Back to what the request found, not to nothing: a device the user had already
                // pinned by hand is still pinned, whatever this request could not do.
                engine.pin(sysfs, pinned);
            }
            if (previous != null) restoreHostDrivers(sysfs, previous.vmId);
            throw new RequestException(fmt("could not deauthorize %s", sysfs));
        }
        if (previous != null) broadcastVm(previous.vmId, previous.vmName);
        broadcastHost();
    }

    /**
     * The direct action's move to a VM: the previous holder loses the device first, which is
     * what the user asked for. Nothing is pinned afterwards, the attachment being the shield --
     * but the window between the two halves is fenced with a host pin, or a pass running on the
     * worker could take a device that belongs to nobody for that moment.
     */
    private int setTargetVm(@NonNull VMInstance vm, @NonNull String sysfs,
                            @Nullable String controller, @Nullable Attachment previous,
                            @NonNull UsbRuleEngine.Pin pinned) {
        var vmId = vm.getId().toString();
        // crosvm emulates the controller and takes no argument for it, so there is nothing a
        // move inside one VM could change, and a detach and attach could only lose the device.
        if (previous != null && previous.vmId.equals(vmId)) return previous.port;
        synchronized (lock) {
            engine.pin(sysfs, UsbRuleEngine.Pin.HOST);
        }
        var landed = false;
        try {
            if (previous != null) {
                dropFromHolder(previous);
                // The claim outlives the detach that released it -- a VMM keeps its usbfs fds
                // until it has run the release, and one whose xHCI had died kept them until the
                // process exited -- and the attach below claims every interface. Waited out
                // here, without a drivers_probe: binding the host drivers would only give the
                // next claim something more to take away.
                awaitVmmReleased(sysfs);
                Log.i(TAG, fmt("Took USB %s from VM %s for VM %s",
                    sysfs, previous.vmName, vm.getName()));
                broadcastVm(previous.vmId, previous.vmName);
            }
            var port = attach(vm, sysfs, controller, null);
            landed = true;
            return port;
        } catch (UsbVmmUnreachableException e) {
            // To this caller a VMM that never answered is a refusal like any other.
            throw new RequestException(fmt("crosvm usb attach failed: %s", e.getMessage()));
        } finally {
            // A move that did not land leaves the device on the host -- through the detach
            // above, or through the attach's own recovery -- and the rules own it again.
            if (!landed && previous != null) restoreHostDrivers(sysfs, previous.vmId);
            synchronized (lock) {
                // What the request found, not nothing: a move that failed has said nothing
                // about a device the user had pinned by hand, and clearing the pin would hand
                // it to the next rule pass on the strength of a request that did not land.
                engine.pin(sysfs, landed ? UsbRuleEngine.Pin.NONE : pinned);
            }
        }
    }

    /** Takes [attachment] away from whichever VM holds it, or from the records when it is gone. */
    private void dropFromHolder(@NonNull Attachment attachment) {
        var holder = context.getVMs().findById(attachment.vmId);
        if (holder != null) {
            dropAttachment(attachment, holder);
            return;
        }
        synchronized (lock) {
            attachments.remove(attachment.sysfs);
        }
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
            obj.put("controller", attachment.controller == null
                ? JSONObject.NULL : attachment.controller);
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
                // A stop is still not a reason to hand a device to another VM -- nothing here
                // attaches -- but it is a reason to ask whether the rules want it hidden: the
                // drivers_probe a restore writes is what makes Android bind the device and ask
                // the user about it, and a device the rules sink must never see that.
                if (sinkIfRulesSay(attachment.sysfs)) continue;
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
                // A replug is a clean slate by construction: authorized resets to 1 on
                // re-enumeration, so whatever we deauthorized is not what comes back.
                sinks.remove(device.sysfs);
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
        UsbRulePassTiming.whenReady(this::schedule, new UsbRulePassTiming.Wait(
                () -> vm.getState() == VMState.RUNNING,
                () -> controlSocketReady(vm),
                () -> runPassQuietly(reason),
                () -> Log.i(TAG, fmt("USB rules pass (%s) dropped: VM left RUNNING", reason)),
                () -> Log.w(TAG, fmt(
                    "USB rules pass (%s) skipped: control socket not ready after %d s", reason,
                    UsbRulePassTiming.READY_POLL_MS * UsbRulePassTiming.READY_MAX_POLLS / 1000))),
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
     * Gives everything back on the worker and waits for it, the way a pass is run and waited
     * on: a pass already queued runs first, finds the switch off and does nothing.
     */
    private int releaseEverythingAndWait() {
        try {
            return worker.submit(() -> releaseEverything()).get();
        } catch (RejectedExecutionException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (ExecutionException e) {
            Log.w(TAG, "Giving the USB devices back to the host failed", e.getCause());
            return 0;
        }
    }

    /**
     * The master switch went off: every device this daemon took is given back to the host, once.
     * The VMs lose theirs, the hidden ones are authorized again, and the records and the pins go
     * with them -- nothing this daemon said about a device outlives the switch that let it speak.
     * Returns how many devices were given back, which is what the save reports.
     *
     * <p>Best effort, per device: a VMM that will not answer must not leave the rest of the
     * devices hidden, so each failure is logged and the next device is tried. Nothing here
     * decides anything -- the rules are already off by the time this runs -- and no pass follows
     * it, which is why this is the one path that may hand a device back without asking.</p>
     *
     * <p>The un-hiding is driven by the scan rather than by the sink records, the way the
     * reconcile is: a device the host shows deauthorized is one Android cannot see, whoever hid
     * it, and giving USB back to the host is a statement about what the host can see.</p>
     */
    private int releaseEverything() {
        List<Attachment> held;
        synchronized (lock) {
            held = new ArrayList<>(attachments.values());
        }
        var given = 0;
        var vms = new LinkedHashMap<String, String>();
        for (var attachment : held) {
            try {
                dropFromHolder(attachment);
                restoreHostDrivers(attachment.sysfs, attachment.vmId);
                vms.put(attachment.vmId, attachment.vmName);
                given++;
                Log.i(TAG, fmt("Took USB %s (%s:%s) back from VM %s", attachment.sysfs,
                    attachment.vid, attachment.pid, attachment.vmName));
            } catch (Exception e) {
                Log.w(TAG, fmt("Could not take USB %s back from VM %s: %s", attachment.sysfs,
                    attachment.vmName, e.getMessage()));
            }
        }
        for (var device : scanHost()) {
            if (device.authorized) continue;
            // unsink says what it could not write; a device whose write fails is left hidden
            // for now, and the reconcile of the next pass -- which runs whether the switch is
            // on or off -- is what tries again rather than the user having to unplug it.
            if (unsink(device.sysfs)) given++;
        }
        synchronized (lock) {
            sinks.clear();
            engine.clearPins();
        }
        Log.i(TAG, fmt("USB passthrough turned off: %d device(s) given back to the host", given));
        for (var vm : vms.entrySet()) broadcastVm(vm.getKey(), vm.getValue());
        broadcastHost();
        return given;
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
     * One pass of the rules over everything plugged in, unclaimed and not pinned. Which rule
     * matches is decided under the lock against the inventory's snapshot -- a question the
     * snapshot answers as well as a scan does -- while a sink re-reads the device it is about,
     * because {@code authorized} is the one thing that snapshot cannot be trusted on (see
     * {@link #freshDeviceAt}). The attaches themselves run outside the lock, one after another,
     * through the same path a manual attach takes. A device whose attach
     * fails is marked for the VM it failed for, so the next pass passes that rule over instead
     * of failing the same way again -- until the device is replugged or that VM next comes up.
     * The exception is a VMM the CLI could not reach: that says nothing about the device, so
     * nothing is remembered against it, the VM's other devices are left for the rerun rather
     * than fail one by one, and the result says the pass should come back.
     *
     * <p>Every pass also reconciles the sinks, whatever the plan held and even when there are no
     * rules at all -- an empty rule set is exactly when every hidden device has to come back.</p>
     *
     * <p>The master switch gates the plan and only the plan: off, no rule takes anything, so
     * every trigger is the no-op it promises to be. The reconcile is deliberately outside that
     * gate, because it is the sink design's own housekeeping rather than a rule acting -- it is
     * the only thing that ever writes authorized=1 for a device nobody claims, so gating it too
     * would leave a device the falling edge could not reach, or one whose manual record died
     * with the daemon, hidden from Android and from every VM until it was unplugged. With the
     * rules answering nothing it reads as "no rule hides this device, give it back", and a
     * device the user hid by hand is still left alone.</p>
     */
    @NonNull
    private PassResult runAutoAttach(@NonNull String reason) {
        List<UsbRuleEngine.Decision> plan;
        synchronized (lock) {
            if (!engine.rulesEnabled()) {
                Log.i(TAG, fmt("USB rules pass (%s): passthrough is off, reconciling only",
                    reason));
                plan = Collections.emptyList();
            } else {
                var plugged = new ArrayList<UsbRuleEngine.Device>();
                for (var device : inventory.snapshot())
                    plugged.add(UsbRuleEngine.Device.of(device));
                plan = engine.plan(plugged, attachments::containsKey, this::stateOf,
                    this::vmHasController);
            }
        }
        var attached = 0;
        var sinked = 0;
        // Sink decisions this pass took no action on. Counted for the summary line alone: the
        // reason is logged where it is known, and a pass that decides to hide a device and then
        // does not must never read as one that had nothing to do.
        var skipped = 0;
        var unreachable = new HashSet<String>();
        for (var decision : plan) {
            var device = decision.device;
            var where = fmt("%s[%d]", decision.layer.key, decision.index);
            // The rule saying the host keeps it, which takes no action.
            if (decision.target == UsbRules.Target.HOST) continue;
            if (decision.target == UsbRules.Target.SINK) {
                // From sysfs and not from the snapshot the plan was made against: both the flag
                // this decides on and the devnum the record is minted with have to be the
                // device's own truth right now. See freshDeviceAt.
                var host = freshDeviceAt(device.sysfs);
                if (host == null) {
                    // Unplugged since the plan; the removal diff is on its way.
                    skipped++;
                    Log.i(TAG, fmt("USB %s is gone; rule %s hides nothing", device.sysfs, where));
                    continue;
                }
                UsbSinks.Owed owed;
                synchronized (lock) {
                    owed = sinks.owedBySink(device.sysfs, host.authorized);
                }
                if (owed == UsbSinks.Owed.ADOPT) {
                    // Already hidden and not this run's doing -- the previous daemon run's
                    // devices at start-up. The reconcile below adopts them: a record is minted,
                    // nothing is written, and nothing is announced about something that happened
                    // before the daemon was there to announce it. Logged all the same, because a
                    // decision that writes nothing is precisely what the summary line cannot say.
                    skipped++;
                    Log.i(TAG, fmt("USB %s is hidden already and not this run's doing; rule %s "
                        + "leaves it to the reconcile", device.sysfs, where));
                    continue;
                }
                if (sinkDevice(device.sysfs, host.devnum, decision, false)) {
                    sinked++;
                    broadcastAuto("usb_auto_sinked", null, decision, null);
                } else if (!isSinked(device.sysfs)) {
                    // The write failed; setAuthorized said which file, this says whose rule.
                    skipped++;
                    broadcastAuto("usb_auto_failed", null, decision,
                        fmt("could not deauthorize %s", device.sysfs));
                }
                continue;
            }
            // A VM decision always names one; the other two targets returned above.
            var vmId = decision.vm;
            if (vmId == null || unreachable.contains(vmId)) continue;
            var inst = context.getVMs().findById(vmId);
            if (inst == null) continue;
            try {
                attach(inst, device.sysfs, decision.controller, decision);
                attached++;
                broadcastAuto("usb_auto_attached", inst, decision, null);
            } catch (UsbVmmUnreachableException e) {
                unreachable.add(vmId);
                Log.w(TAG, fmt("Auto attach of USB %s (%s) to VM %s by rule %s: %s; will retry",
                    device.sysfs, device.id, inst.getName(), where, e.getMessage()));
            } catch (Exception e) {
                synchronized (lock) {
                    engine.markFailed(device.sysfs, vmId);
                }
                Log.w(TAG, fmt("Auto attach of USB %s (%s) to VM %s by rule %s failed: %s",
                    device.sysfs, device.id, inst.getName(), where, e.getMessage()));
                broadcastAuto("usb_auto_failed", inst, decision, e.getMessage());
            }
        }
        var restored = reconcileSinks();
        // An attach broadcasts for itself; these two are the pass's own doing.
        if (sinked > 0 || restored > 0) broadcastHost();
        Log.i(TAG, fmt("USB rules pass (%s): %d decision(s), %d attached, %d sinked, "
                + "%d given back%s%s", reason, plan.size(), attached, sinked, restored,
            skipped == 0 ? "" : fmt(", %d sink decision(s) not acted on", skipped),
            unreachable.isEmpty() ? "" : ", VMM unreachable"));
        return new PassResult(attached + sinked + restored, !unreachable.isEmpty());
    }

    /** [vm] is null for an outcome that names no VM, which is what a sink is. */
    private void broadcastAuto(@NonNull String event, @Nullable VMInstance vm,
                               @NonNull UsbRuleEngine.Decision decision, @Nullable String error) {
        try {
            var data = new JSONObject();
            data.put("vm_id", vm == null ? JSONObject.NULL : vm.getId().toString());
            data.put("vm_name", vm == null ? JSONObject.NULL : vm.getName());
            data.put("target", decision.target.key);
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
     * Writes one byte to {@code <sysfs>/authorized} and says whether it landed.
     *
     * <p>Not through {@code run("echo ...")}, the way {@link #restoreHostDrivers} writes
     * drivers_probe: that forks a shell, and this write sits on the path that has to reach a
     * freshly plugged device before Android does -- microseconds against milliseconds.</p>
     */
    private static boolean setAuthorized(@NonNull String sysfs, boolean value) {
        var file = new File(new File(SYSFS_ROOT, sysfs), "authorized");
        try (var out = new FileOutputStream(file)) {
            out.write(value ? '1' : '0');
            return true;
        } catch (IOException e) {
            Log.w(TAG, fmt("Cannot write %s: %s", file, e.getMessage()));
            return false;
        }
    }

    /**
     * Hides [sysfs] from everything: no configuration, no interfaces, nothing for Android or a
     * host driver to bind to. [by] is the rule that asked, null when the user did, and [manual]
     * says which of those it was -- a manual sink is the one no rule pass may give back.
     *
     * <p>Idempotent for a device already sinked as the same instance, which is what lets the
     * debounced pass walk over what {@link #onNodeAppearedFast} did without writing again.
     * Returns whether this call is what sank it.</p>
     */
    private boolean sinkDevice(@NonNull String sysfs, int devnum,
                               @Nullable UsbRuleEngine.Decision by, boolean manual) {
        synchronized (lock) {
            var record = sinks.get(sysfs);
            if (record != null && record.devnum == devnum) {
                // Already ours. A manual ask over a rule-made sink still upgrades the record,
                // which is the half that keeps the reconcile from giving the device back.
                if (manual && !record.manual) sinks.put(sysfs, by, true, devnum);
                return false;
            }
        }
        if (!setAuthorized(sysfs, false)) {
            Log.w(TAG, fmt("Failed to deauthorize USB %s", sysfs));
            return false;
        }
        synchronized (lock) {
            sinks.put(sysfs, by, manual, devnum);
            // Deauthorizing takes the interfaces away from whoever held them, so nothing is owed
            // to the host any more; authorizing the device again has the kernel bind them itself.
            leftovers.forgetDevice(sysfs);
        }
        String why;
        if (by != null) why = fmt("rule %s[%d]", by.layer.key, by.index);
        else if (manual) why = "manual";
        else why = "put back after a failed attach";
        Log.i(TAG, fmt("Sinked USB %s (%s)", sysfs, why));
        return true;
    }

    /** Gives a sinked device back. False when the write failed and it is still hidden. */
    private boolean unsink(@NonNull String sysfs) {
        if (!setAuthorized(sysfs, true)) {
            Log.w(TAG, fmt("Failed to authorize USB %s", sysfs));
            return false;
        }
        synchronized (lock) {
            sinks.remove(sysfs);
        }
        return true;
    }

    private boolean isSinked(@NonNull String sysfs) {
        synchronized (lock) {
            return sinks.has(sysfs);
        }
    }

    /**
     * Sinks [sysfs] if the rules decide that for it right now, and says whether it did. Only
     * that outcome is acted on: a decision naming a running VM is ignored and the device is left
     * where it is, which is how the release path can ask the rules a question without ever
     * taking a device from anyone.
     *
     * <p>A write that fails is announced before the false is returned, because the caller's
     * fallback is the drivers_probe this exists to avoid: what the user sees next is Android's
     * dialog about a device the rules say is hidden, and nothing else would say why.</p>
     */
    private boolean sinkIfRulesSay(@NonNull String sysfs) {
        var device = deviceAt(sysfs);
        if (device == null) return false;
        UsbRuleEngine.Decision decision;
        synchronized (lock) {
            // The user's word outranks the rules, in both directions.
            if (engine.pinOf(sysfs) != UsbRuleEngine.Pin.NONE) return false;
            decision = engine.decide(UsbRuleEngine.Device.of(device), this::stateOf,
                this::vmHasController);
        }
        if (decision == null || decision.target != UsbRules.Target.SINK) return false;
        if (sinkDevice(sysfs, device.devnum, decision, false) || isSinked(sysfs)) return true;
        broadcastAuto("usb_auto_failed", null, decision,
            fmt("could not deauthorize %s", sysfs));
        return false;
    }

    /**
     * Every device the host shows deauthorized, against what the rules say about it now, and
     * how many were given back. This is the un-sink, and nothing else can do it: a device no
     * rule takes is absent from a plan in exactly the way a device a rule leaves on the host is,
     * so a deleted sink rule leaves no trace for a plan to act on.
     *
     * <p>Driven by the scan rather than by the records, which is what makes it the whole answer
     * at daemon start too: a device the previous run sank, or somebody's shell did, is adopted
     * when the rules do sink it and authorized back when they do not.</p>
     */
    private int reconcileSinks() {
        var restored = 0;
        for (var device : scanHost()) {
            if (device.authorized) continue;
            UsbSinks.Reconcile owed;
            synchronized (lock) {
                var attached = attachments.containsKey(device.sysfs);
                if (attached)
                    Log.w(TAG, fmt("USB %s is attached and deauthorized at once", device.sysfs));
                var decision = engine.decide(UsbRuleEngine.Device.of(device), this::stateOf,
                    this::vmHasController);
                owed = sinks.reconcile(device.sysfs, attached, engine.pinOf(device.sysfs),
                    decision != null && decision.target == UsbRules.Target.SINK);
                if (owed == UsbSinks.Reconcile.ADOPT)
                    sinks.put(device.sysfs, decision, false, device.devnum);
            }
            if (owed == UsbSinks.Reconcile.ADOPT) {
                Log.i(TAG, fmt("USB %s is deauthorized and the rules say so; adopting it",
                    device.sysfs));
                continue;
            }
            if (owed != UsbSinks.Reconcile.RESTORE) continue;
            Log.i(TAG, fmt("USB %s was left deauthorized; nothing sinks it, giving it back",
                device.sysfs));
            if (unsink(device.sysfs)) restored++;
        }
        return restored;
    }

    /**
     * A device node appeared: the fast lane, on the inventory's FileObserver thread, before any
     * debounce. Sinking is the only thing it ever does, and the only thing that cannot wait --
     * Android binds the drivers and raises its dialog within a couple of hundred milliseconds of
     * the node appearing, while the debounced pass is 700 ms away at best and queued behind
     * whatever the worker is doing. Attaching, un-sinking, the leftovers and the authoritative
     * broadcast all stay on that pass, which arrives to find this work done and does nothing.
     *
     * <p>Bounded, as {@link UsbHostInventory.FastListener} requires: a readlink, four small
     * reads and one write, with the decision taken under the lock and the write outside it.</p>
     */
    private void onNodeAppearedFast(int busnum, int devnum) {
        try {
            // Before the readlink, not after the decision: with the rules off this lane has
            // nothing to do with a plug at all, and it runs on the thread every further inotify
            // event for this bus is queued behind.
            synchronized (lock) {
                if (!engine.rulesEnabled()) return;
            }
            var sysfs = sysfsNameOf(busnum, devnum);
            if (sysfs == null) return;
            var dir = new File(SYSFS_ROOT, sysfs);
            // A hub carries the rest of the tree; hiding one would take its children with it.
            if (UsbHostDevice.isHubClass(readAttribute(dir, "bDeviceClass"))) return;
            var vid = readAttribute(dir, "idVendor");
            var pid = readAttribute(dir, "idProduct");
            // Gone again already, or never a device: the rescan reports whatever this was.
            if (vid.isEmpty() || pid.isEmpty()) return;
            var device = new UsbRuleEngine.Device(sysfs,
                UsbHostDevice.deriveId(vid, pid, readAttribute(dir, "serial")),
                UsbHostDevice.derivePort(sysfs));
            UsbRuleEngine.Decision decision;
            synchronized (lock) {
                // The diff that drops the previous instance's flags is still a quiet period
                // away, and a pin or a sink record left by the unit that was in this socket
                // before must not decide anything about this one.
                if (!isKnownInstance(sysfs, devnum)) {
                    engine.forget(sysfs);
                    sinks.remove(sysfs);
                }
                if (attachments.containsKey(sysfs)) return;
                if (engine.pinOf(sysfs) == UsbRuleEngine.Pin.HOST) return;
                decision = engine.decide(device, this::stateOf, this::vmHasController);
            }
            if (decision == null || decision.target != UsbRules.Target.SINK) return;
            if (!sinkDevice(sysfs, devnum, decision, false)) return;
            // Asked again after the write, not only before it: this lane runs on the observer's
            // thread while the falling edge runs on the worker, so the switch can have gone off
            // in between -- and that release may have scanned this device while it was still
            // authorized and walked past it. Reading the switch still on here proves the release
            // has not scanned yet (it installs the rules first), so its own scan will find this
            // device and hand it back; reading it off means that scan may be past, so this
            // thread undoes its own write rather than leave the device hidden until a later
            // pass reconciles it.
            boolean off;
            synchronized (lock) {
                off = !engine.rulesEnabled();
            }
            if (off) {
                Log.i(TAG, fmt("USB %s was hidden as passthrough went off; giving it back",
                    sysfs));
                unsink(sysfs);
                // The release's own broadcast may have gone out while this device was hidden,
                // so the host list is worth one more scan -- off this thread, like the one below.
                schedule(this::broadcastHost, 0);
                return;
            }
            // Off the hot path: a broadcast takes a full scan, and every further inotify event
            // for this bus is waiting behind this thread.
            schedule(() -> {
                broadcastAuto("usb_auto_sinked", null, decision, null);
                broadcastHost();
            }, 0);
        } catch (Exception e) {
            Log.w(TAG, fmt("Fast USB pass for bus %d device %d failed", busnum, devnum), e);
        }
    }

    /** Whether the last scan already knows this instance of [sysfs]. Must be called under [lock]. */
    private boolean isKnownInstance(@NonNull String sysfs, int devnum) {
        for (var device : inventory.snapshot())
            if (device.sysfs.equals(sysfs)) return device.devnum == devnum;
        return false;
    }

    /**
     * The sysfs directory name of the device behind {@code /dev/bus/usb/<bus>/<dev>}, through
     * the kernel's own char-device index -- one readlink and no scan. usbfs hands each bus 128
     * device minors. A kernel that does not publish the link falls back to reading busnum and
     * devnum out of the device directories, which is still well under a millisecond.
     */
    @Nullable
    private static String sysfsNameOf(int busnum, int devnum) {
        var minor = (busnum - 1) * USBFS_DEVICES_PER_BUS + (devnum - 1);
        try {
            var link = Files.readSymbolicLink(
                new File(CHAR_DEV_ROOT, fmt("%d:%d", USBFS_MAJOR, minor)).toPath());
            var name = link.getFileName();
            if (name != null) return name.toString();
        } catch (IOException | UnsupportedOperationException e) {
            // No such index on this kernel; the readdir below is the answer.
        }
        var entries = new File(SYSFS_ROOT).listFiles();
        if (entries == null) return null;
        for (var entry : entries) {
            if (!entry.isDirectory()) continue;
            if (!String.valueOf(busnum).equals(readAttribute(entry, "busnum"))) continue;
            if (!String.valueOf(devnum).equals(readAttribute(entry, "devnum"))) continue;
            return entry.getName();
        }
        return null;
    }

    /** One small sysfs attribute, trimmed; {@code ""} when it is not there to be read. */
    @NonNull
    private static String readAttribute(@NonNull File dir, @NonNull String name) {
        try {
            return new String(Files.readAllBytes(new File(dir, name).toPath()),
                StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** The device at [sysfs] as the last scan saw it, read from sysfs when the scan has not. */
    @Nullable
    private UsbHostDevice deviceAt(@NonNull String sysfs) {
        for (var device : inventory.snapshot())
            if (device.sysfs.equals(sysfs)) return device;
        return freshDeviceAt(sysfs);
    }

    /**
     * The device at [sysfs] as sysfs describes it this moment, or null when it is not there.
     *
     * <p>What {@link #deviceAt} is not, and the difference decides things wherever {@code
     * authorized} or {@code devnum} does: deauthorizing a device creates and removes no
     * {@code /dev/bus/usb} node, so the inventory's watch never fires, nothing invalidates its
     * snapshot, and a device this daemon hid and then gave back keeps a stale {@code authorized}
     * there until it is replugged. A handful of small reads, which is what every list here
     * already pays for the same reason.</p>
     */
    @Nullable
    private static UsbHostDevice freshDeviceAt(@NonNull String sysfs) {
        // [sysfs] can have come from a request; keep the read inside the device root.
        var dir = new File(SYSFS_ROOT, sysfs);
        if (!SYSFS_ROOT.equals(dir.getParent()) || !dir.isDirectory()) return null;
        try {
            return UsbHostDevice.fromSysfs(dir, DEV_ROOT);
        } catch (IOException e) {
            // Disconnected mid-read: the removal diff is on its way.
            return null;
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

    /**
     * Waits for the VMM's claim on every interface of [sysfs] to be gone, and offers nothing to
     * the host on the way: this is the wait {@link #restoreInterfaces} does before a
     * drivers_probe, for the caller that is about to claim the device itself rather than give it
     * back. An interface the wait gives up on is left as it is -- the attach that follows says
     * what crosvm made of it, which is a better answer than one invented here.
     */
    private void awaitVmmReleased(@NonNull String sysfs) {
        for (var name : interfacesOf(sysfs)) {
            var dir = new File(SYSFS_ROOT, name);
            if (dir.isDirectory()) awaitReleased(dir);
        }
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
