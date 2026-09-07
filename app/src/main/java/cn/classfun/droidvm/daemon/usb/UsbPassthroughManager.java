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
 * <p>Devices also get lent out without being asked for: the rules are run over every candidate
 * whenever the daemon starts, a device appears, a VM reaches RUNNING, or the rules change. A VM
 * letting go of what it held asks the rules one question only -- whether to leave the device
 * idle -- and never whether to hand it to somebody else, so a stop releases devices but takes
 * nothing. All of those triggers take nothing at all while the rules carry their master switch
 * off; only the direct actions -- attach, detach and the management page -- still work, because
 * those are how a user takes a device by hand. Turning that switch off is itself the one
 * trigger that only gives: it hands back everything this daemon had taken.
 * The deciding is the {@link UsbRuleEngine}'s and happens under the same lock as the manual
 * bookkeeping; the attaching goes through the same path as a manual attach, so both kinds share
 * one record and one set of conflicts. When a pass runs is {@link UsbRulePassTiming}'s: a VM
 * is RUNNING before its control socket listens, so the pass for that edge waits for the socket,
 * and a pass that could not reach the VMM comes back rather than blame the device.</p>
 *
 * <p>What a device is doing is never stored: it is read off the drivers bound to its interfaces
 * at every scan -- a driver that is not usbfs means the host has it, a usbfs claim means a VMM's
 * fd does, nothing bound at all means nobody does -- so this daemon cannot come to disagree with
 * the kernel about who owns a device, however it changed hands. The only thing the kernel cannot
 * say is whether the user decided about a device by hand, and that is the engine's lock.</p>
 *
 * <p>What makes that work is the gate: {@code /sys/bus/usb/drivers_autoprobe} is set to 0 while
 * the master switch is on, so a device plugged in enumerates fully -- sysfs entry, device node,
 * descriptors, interfaces -- and lands with no driver bound to any of it. Android does not mount
 * it, does not claim it and does not ask the user about it, and the rules decide what happens
 * next: host offers the interfaces to the host drivers, a VM has it handed over, and a sink is
 * the do-nothing that leaves the device exactly where the gate put it. The flag is global and
 * outlives this process, which is why it is written from the switch at every start, restored to
 * 1 the moment the switch goes off, and restored again on shutdown.</p>
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
     * The gate. 0 means the kernel binds no driver to a device it enumerates, which is how a
     * plugged device reaches the rules untouched; 1 is the kernel's own default and what the
     * host expects when this daemon is not in charge.
     */
    private static final String AUTOPROBE_PATH = "/sys/bus/usb/drivers_autoprobe";
    /** Where a driver is taken off one interface; the mirror of {@link #AUTOPROBE_PATH}'s gate. */
    private static final String DRIVERS_DIR = "/sys/bus/usb/drivers";
    /**
     * How long the start-up migration's devices are given to publish their interfaces again
     * before the first pass looks at them. Authorizing a device has the kernel re-read its
     * configuration and publish its interfaces afresh, which took about a second on the test
     * phone; a pass that arrived first would find a device with nothing to probe and no later
     * trigger would ever come, because binding a driver creates no node for the watch to see.
     * Waited out rather than slept through, and only when there was something to migrate.
     */
    private static final long MIGRATION_SETTLE_TIMEOUT_MS = 3000;
    private static final long MIGRATION_POLL_MS = 100;

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
        // Before the watch, so nothing can be plugged in between the rules being read and the
        // gate matching them -- and written either way, never only to 0: the flag outlives this
        // process, so a daemon that was killed with the switch on left it there, and every
        // device plugged in from then on would land driverless with nobody to hand it over.
        setAutoprobe(!rules.isEnabled());
        try {
            inventory.start(this::onInventoryChanged, this::onBusesChanged);
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
        var migrated = authorizeWhatAnOlderBuildHid();
        // autoUp spawned these moments ago, so they get the same wait their edge would have.
        context.getVMs().forEach((id, inst) -> {
            if (inst.getState() == VMState.RUNNING) queueAutoAttachWhenReady(inst);
        });
        // And one pass whatever is running, which those per-VM ones cannot stand in for: with no
        // VM up there is nothing to attach, but every device the gate has left idle -- since the
        // last run, or by the migration a moment ago -- is still waiting to be told where it
        // belongs. The one pass that runs with the switch off as well: a daemon that was killed
        // with the gate shut, or a release that never finished, left devices nobody else will
        // ever ask about, and this is where they are handed back.
        schedule(() -> {
            awaitInterfaces(migrated);
            runPassQuietly("start", true);
        }, 0);
    }

    /**
     * Undoes the one thing an older build of this daemon did that this one cannot: it hid a
     * device by writing {@code authorized=0}, which left it without a configuration and without
     * interfaces, and nothing in the state model can see such a device, let alone give it back.
     * So every device found deauthorized at start is authorized, once, before the first pass;
     * the kernel re-reads the configuration and the gate decides whether anything binds to it.
     * Returns the devices it touched, which is what the first pass waits for.
     *
     * <p>The cost is that a device somebody deauthorized by hand, outside this daemon, is
     * authorized at the next daemon start. That is the price of {@code authorized} having
     * exactly one meaning again -- a device is authorized -- and it is a state nothing here can
     * tell apart from the one this migration exists for.</p>
     */
    @NonNull
    private List<String> authorizeWhatAnOlderBuildHid() {
        var migrated = new ArrayList<String>();
        for (var device : inventory.snapshot()) {
            if (device.authorized) continue;
            Log.i(TAG, fmt("USB %s was left deauthorized by an older build; authorizing it",
                device.sysfs));
            if (setAuthorized(device.sysfs)) migrated.add(device.sysfs);
        }
        return migrated;
    }

    /**
     * Waits for the devices the migration authorized to publish their interfaces again, up to
     * {@link #MIGRATION_SETTLE_TIMEOUT_MS} for all of them together. A device with no interface
     * is one the first pass can do nothing at all for -- there is nothing to probe and nothing
     * to hand over -- and no second chance is coming: writing {@code authorized} creates and
     * removes no device node, so the watch never fires and no trigger follows. One that is
     * unplugged meanwhile, or that never comes back, is left with a line in the log rather than
     * waited on for good. Runs on the worker, where every other wait here runs.
     */
    private static void awaitInterfaces(@NonNull List<String> devices) {
        if (devices.isEmpty()) return;
        var deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(MIGRATION_SETTLE_TIMEOUT_MS);
        for (var sysfs : devices) {
            while (interfacesOf(sysfs).isEmpty()) {
                if (!new File(SYSFS_ROOT, sysfs).isDirectory()) break;
                if (System.nanoTime() - deadline >= 0) {
                    Log.w(TAG, fmt("USB %s published no interface within %d ms of being "
                            + "authorized; the rules have nothing to give anybody until it is "
                            + "replugged", sysfs, MIGRATION_SETTLE_TIMEOUT_MS));
                    return;
                }
                try {
                    Thread.sleep(MIGRATION_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Writes {@code authorized=1} to one device. The only write to that attribute left in the
     * daemon, and it only ever writes 1: hiding a device is not a thing that is done to it any
     * more, it is a state -- idle -- that a device is left in.
     */
    private static boolean setAuthorized(@NonNull String sysfs) {
        var file = new File(new File(SYSFS_ROOT, sysfs), "authorized");
        try (var out = new FileOutputStream(file)) {
            out.write('1');
            return true;
        } catch (IOException e) {
            Log.w(TAG, fmt("Cannot write %s: %s", file, e.getMessage()));
            return false;
        }
    }

    /**
     * Opens or shuts the gate. [autoprobe] true is the kernel's own default, which is what the
     * host is owed whenever this daemon is not deciding; false is the rules being in charge.
     *
     * <p>Best effort and loud about it: a gate that could not be shut means devices reach
     * Android as they always did, which is a feature that does not work rather than a phone that
     * does not, and a gate that could not be opened is worth a line in the log because every
     * device plugged in afterwards will look dead.</p>
     */
    private static void setAutoprobe(boolean autoprobe) {
        try (var out = new FileOutputStream(AUTOPROBE_PATH)) {
            out.write(autoprobe ? '1' : '0');
            Log.i(TAG, fmt("USB drivers_autoprobe = %d", autoprobe ? 1 : 0));
        } catch (IOException e) {
            Log.w(TAG, fmt("Cannot write %s: %s", AUTOPROBE_PATH, e.getMessage()));
        }
    }

    /**
     * Stops the watch and lets the queued releases finish. Called on daemon shutdown, after the
     * VMs have been stopped: those stops only queue their releases, and the drivers_probe writes
     * that hand the interfaces back would otherwise be cut off when the process exits.
     */
    public void shutdown() {
        // First, before anything that could throw or hang: the flag is global and the host is
        // owed it back whatever else this shutdown manages to do. Devices already idle stay
        // idle -- there is nobody left to decide about them -- and come back on their next plug.
        setAutoprobe(true);
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
            // A hub is the tree rather than a device: the scan carries them so that a pass can
            // give one its driver back, and nothing else -- no list, no rule, no page -- has any
            // business with one.
            if (device.isHub()) continue;
            Attachment attachment;
            boolean locked;
            synchronized (lock) {
                attachment = attachments.get(device.sysfs);
                locked = engine.isLocked(device.sysfs, device.devnum);
            }
            var obj = device.toJson();
            obj.put("attached_vm", attachment == null ? JSONObject.NULL : attachment.vmId);
            obj.put("attached_vm_name", attachment == null ? JSONObject.NULL : attachment.vmName);
            obj.put("attached_port", attachment == null ? JSONObject.NULL : attachment.port);
            obj.put("attached_controller", attachment == null || attachment.controller == null
                ? JSONObject.NULL : attachment.controller);
            // Where the device is comes from the device itself, in its own "state" field; this
            // is the one thing about it the kernel cannot say -- that the user decided it by
            // hand, so no pass may decide it again until they say otherwise or it is unplugged.
            obj.put("lock", locked);
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
     * Replaces the rules: validated, kept, written out, the gate set from the switch they
     * carry, and run once. Returns how many devices that run acted on -- attached, or handed to
     * the host drivers. A save is not a reason to take anything away -- a device a VM is using
     * is no candidate -- and does not clear a lock either.
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
            // after: with the switch already off no trigger can act, so nothing can lend out a
            // device behind the release's back.
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
        // On this thread and before either edge runs, so that a device plugged in while the pass
        // or the release is still working lands on the side of the gate the save asked for.
        // Written on every save and not only on an edge: the flag is global, so a save is also
        // the moment to notice that something else has moved it.
        setAutoprobe(!rules.isEnabled());
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
     * for locked devices and for devices a VM is using too -- the row says so, and "where would
     * this go" is the question a user editing the rules is asking.
     */
    @NonNull
    public JSONArray testRules() throws JSONException {
        var array = new JSONArray();
        // A fresh scan, for the same reason hostList takes one.
        var devices = scanHost();
        synchronized (lock) {
            for (var device : devices) {
                if (device.isHub()) continue;
                var attachment = attachments.get(device.sysfs);
                var decision = engine.decide(UsbRuleEngine.Device.of(device), this::stateOf,
                    this::vmHasController);
                var obj = new JSONObject();
                obj.put("sysfs", device.sysfs);
                obj.put("id", device.id);
                obj.put("port", device.port);
                obj.put("state", device.state().key);
                obj.put("lock", engine.isLocked(device.sysfs, device.devnum));
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
        // Null for the fifth zone's own rule, which is in no file and at no index: a reader that
        // was given "any[0]" for it would go looking for a row the user cannot see or edit.
        obj.put("layer", decision.layer == null ? JSONObject.NULL : decision.layer.key);
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
     *                                    against the device, and nothing was taken from it.
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
        // A scan skips a device whose files went while it was being read; the directory says
        // whether this name is one of those or is not there at all.
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
            // The CLI opens the node before it connects and claims nothing until the VMM has the
            // fd, so the host still holds every interface and there is nothing to give back.
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
                : fmt("auto, %s", rule.where())));
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
     * This is the user's doing, so the device is locked: no pass offers it to a VM again until
     * they say otherwise or it is unplugged -- without that the rule that lent it out would take
     * it straight back and a manual detach would be a thing the user cannot do. The releases a
     * stop or an unplug make are not detaches and lock nothing.
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
        lockDevice(attachment.sysfs);
        dropAttachment(attachment, vm);
        Log.i(TAG, fmt("Detached USB %s from VM %s; locked until unplugged",
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
     * <p>What keeps the next rule pass from undoing it is the lock, which lasts until the user
     * says otherwise or the device is unplugged -- a rules save does not clear it -- and which
     * says only that the user decided: what they decided is the device's own state, so a page
     * that reads the state reads the answer. A move to a VM clears it instead of setting it: the
     * attachment record is already the shield, and when that VM stops the rules own the device
     * again, which is the honest reading of "put it on this VM".</p>
     *
     * <p>The lock is taken before the device is touched, never after: this runs while a pass may
     * be running on the worker, and a device that is momentarily neither in use nor locked is
     * one that pass can take.</p>
     */
    @NonNull
    public JSONObject setTarget(@NonNull String sysfs, @NonNull UsbRules.Target target,
                                @Nullable VMInstance vm, @Nullable String controller)
        throws JSONException {
        // Read from sysfs rather than from the snapshot: this validates the name the request
        // sent before anything is written to it, and the state and devnum it carries are the
        // device's own truth this moment.
        var device = readUnlistedDevice(sysfs);
        if (target != UsbRules.Target.HOST && device.isHub())
            throw new RequestException("refusing to take a hub away from the host");
        Attachment previous;
        boolean wasLocked;
        synchronized (lock) {
            previous = attachments.get(sysfs);
            wasLocked = engine.isLocked(sysfs, device.devnum);
        }
        var res = new JSONObject();
        res.put("device", sysfs);
        res.put("target", target.key);
        res.put("previous", previousJson(device, previous));
        Integer port = null;
        switch (target) {
            case SINK:
                setTargetSink(device, previous);
                break;
            case VM:
                if (vm == null) throw new RequestException("missing vm_id");
                port = setTargetVm(vm, device, controller, previous, wasLocked);
                break;
            default:
                setTargetHost(device, previous);
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
        // Read back rather than assumed. The page draws its row from the state, and an action
        // is a handful of sysfs writes the kernel may not have honoured -- a driver that will
        // not unbind, an interface no driver wants -- so what happened is read, not believed.
        var after = freshDeviceAt(sysfs);
        res.put("state", after == null ? JSONObject.NULL : after.state().key);
        return res;
    }

    /**
     * What a device was before the direct action, said in the same terms the page's row was
     * drawn in: the device's own state, plus which VM held it when one did.
     */
    @NonNull
    private static JSONObject previousJson(@NonNull UsbHostDevice device,
                                           @Nullable Attachment attachment) throws JSONException {
        var obj = new JSONObject();
        obj.put("state", device.state().key);
        obj.put("vm_id", attachment == null ? JSONObject.NULL : attachment.vmId);
        obj.put("port", attachment == null ? JSONObject.NULL : attachment.port);
        return obj;
    }

    /** The direct action's "the host keeps it": the lock, the VM's copy taken away, then probe. */
    private void setTargetHost(@NonNull UsbHostDevice device, @Nullable Attachment previous) {
        var sysfs = device.sysfs;
        lockDevice(sysfs, device.devnum);
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
        // The host action, the same write a host rule makes: with the gate shut nothing binds a
        // driver by itself, so an idle device stays idle until this is written.
        probeHostDrivers(sysfs);
        broadcastHost();
    }

    /**
     * The direct action's "leave it to nobody": the lock, the VM's copy taken away, whatever the
     * host holds taken off it, and no drivers_probe on the way out -- that write is the one that
     * would hand the device to Android. What is left is a device with no driver on any
     * interface, which is what idle is.
     *
     * <p>The wait for the VMM matters here as much as it does on the way to another VM: the
     * usbfs claim outlives the detach that released it, and without the wait the state this
     * reads back -- and the page redraws from -- would say a VM still has the device.</p>
     */
    private void setTargetSink(@NonNull UsbHostDevice device, @Nullable Attachment previous) {
        var sysfs = device.sysfs;
        lockDevice(sysfs, device.devnum);
        if (previous != null) {
            dropFromHolder(previous);
            awaitVmmReleased(sysfs);
            broadcastVm(previous.vmId, previous.vmName);
        }
        unbindHostDrivers(sysfs);
        broadcastHost();
    }

    /**
     * The direct action's move to a VM: the previous holder loses the device first, which is
     * what the user asked for. Nothing is locked afterwards, the attachment being the shield --
     * but the window between the two halves is fenced with a lock, or a pass running on the
     * worker could take a device that belongs to nobody for that moment.
     */
    private int setTargetVm(@NonNull VMInstance vm, @NonNull UsbHostDevice device,
                            @Nullable String controller, @Nullable Attachment previous,
                            boolean wasLocked) {
        var sysfs = device.sysfs;
        var vmId = vm.getId().toString();
        // crosvm emulates the controller and takes no argument for it, so there is nothing a
        // move inside one VM could change, and a detach and attach could only lose the device.
        if (previous != null && previous.vmId.equals(vmId)) {
            synchronized (lock) {
                engine.unlock(sysfs);
            }
            return previous.port;
        }
        lockDevice(sysfs, device.devnum);
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
                // A move that landed hands the device over and the attachment shields it from
                // there. One that did not has said nothing about a device the user had locked by
                // hand, so the lock goes back to what the request found rather than to nothing.
                if (landed || !wasLocked) engine.unlock(sysfs);
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
                // attaches -- but it is a reason to ask whether the rules want it left alone:
                // the drivers_probe a restore writes is what makes Android bind the device, and
                // a device the rules sink must never see that.
                if (rulesLeaveItIdle(attachment.sysfs)) {
                    Log.i(TAG, fmt("USB %s stays idle: the rules sink it", attachment.sysfs));
                    continue;
                }
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
        if (!added.isEmpty()) scheduleAutoAttach("plug");
        // The broadcast lists the host afresh, and that scan is read for leftovers on the way.
        broadcastHost();
    }

    /**
     * A USB controller was registered or removed. Nothing was plugged in and no device changed,
     * so no rule has anything to say -- but a root hub that came up while the gate was shut has
     * no driver, and a root hub with no driver is a bus whose ports are never scanned. Nothing
     * below it can enumerate, so this is the only trigger that will ever be raised for it: the
     * pass sweeps the root hubs ({@link #driverlessRootHubs}) and hands the bus back its driver.
     */
    private void onBusesChanged() {
        scheduleAutoAttach("controller");
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
    private void scheduleAutoAttach(@NonNull String reason) {
        synchronized (lock) {
            if (pendingAuto != null) pendingAuto.cancel(false);
            try {
                pendingAuto = worker.schedule(() -> runPassQuietly(reason, false),
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
                () -> runPassQuietly(reason, false),
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
            return worker.submit(() -> runPass(reason, false)).get();
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
     * The VMs lose theirs, everything the gate left idle is offered to the host drivers, and the
     * locks go with them -- nothing this daemon said about a device outlives the switch that let
     * it speak. Returns how many devices were given back, which is what the save reports.
     *
     * <p>The gate itself is already open before this runs: {@link #setRules} writes it on the
     * thread the save came in on, so a device plugged in while this is working binds by itself
     * and this pass owes it nothing. That ordering is also what makes the rest of it survivable
     * -- best effort, per device, each failure logged and the next device tried -- because the
     * one thing that must not be left half done is the flag every future plug depends on.</p>
     *
     * <p>Which devices are idle is read from the scan rather than from anything remembered:
     * "give USB back to the host" is a statement about what the host has, and a device left
     * driverless by a rule, by a VMM that died, or by a daemon that was killed is the same
     * device to whoever is holding the phone. Nothing here decides anything -- the rules are
     * already off by the time this runs -- and no pass follows it.</p>
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
        // The root hubs first, and not from the scan: one the gate left driverless is a bus
        // whose ports were never scanned, so every device on it is missing from the scan below
        // -- and stays missing, because opening the gate only decides what the kernel does with
        // the next device to enumerate, and none will until the hub has its driver.
        for (var hub : driverlessRootHubs()) {
            try {
                if (probeHostDrivers(hub) == 0) continue;
                given++;
                Log.i(TAG, fmt("USB root hub %s had no driver; the host gets its bus back", hub));
            } catch (Exception e) {
                Log.w(TAG, fmt("Could not give USB root hub %s back to the host: %s", hub,
                    e.getMessage()));
            }
        }
        for (var device : scanHost()) {
            // Hubs included, and first in the scan's own order by luck rather than design: a
            // hub without its driver is a whole subtree of devices that never enumerate.
            if (device.state() != UsbHostDevice.State.IDLE) continue;
            try {
                if (probeHostDrivers(device.sysfs) == 0) continue;
                given++;
                Log.i(TAG, fmt("USB %s was idle; the host gets it back", device.sysfs));
            } catch (Exception e) {
                Log.w(TAG, fmt("Could not give USB %s back to the host: %s", device.sysfs,
                    e.getMessage()));
            }
        }
        synchronized (lock) {
            engine.clearLocks();
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
    private int runPass(@NonNull String reason, boolean daemonStart) {
        var result = runAutoAttach(reason, daemonStart);
        if (result.unreachable) {
            var again = fmt("%s, retry", reason);
            UsbRulePassTiming.retry(this::schedule, () -> {
                var r = runAutoAttachOrNull(again, daemonStart);
                return r == null || !r.unreachable;
            }, UsbRulePassTiming.RETRY_DELAY_MS, UsbRulePassTiming.MAX_RETRIES);
        }
        return result.applied;
    }

    private void runPassQuietly(@NonNull String reason, boolean daemonStart) {
        try {
            runPass(reason, daemonStart);
        } catch (Exception e) {
            Log.w(TAG, fmt("USB rules pass (%s) failed", reason), e);
        }
    }

    /** A pass that threw is over, not one to try again. */
    @Nullable
    private PassResult runAutoAttachOrNull(@NonNull String reason, boolean daemonStart) {
        try {
            return runAutoAttach(reason, daemonStart);
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
     * One pass of the rules over every candidate. The scan is fresh, the plan is made under the
     * lock, and the actions run outside it, one after another, through the same path a manual
     * attach takes: host writes drivers_probe, a VM has the device handed over, and a sink does
     * nothing at all, which is what leaves the device where the gate put it. A device whose
     * attach fails is marked for the VM it failed for, so the next pass passes that rule over
     * instead of failing the same way again -- until the device is replugged or that VM next
     * comes up. The exception is a VMM the CLI could not reach: that says nothing about the
     * device, so nothing is remembered against it, the VM's other devices are left for the rerun
     * rather than fail one by one, and the result says the pass should come back.
     *
     * <p>The scan may not be the inventory's snapshot, however tempting: a device's state is the
     * drivers bound to its interfaces, and binding or unbinding one creates and removes no
     * device node, so the inotify watch never hears about it and the snapshot's drivers are as
     * old as the last plug event on that bus. The candidate filter is that state.</p>
     *
     * <p>Two kinds of device never reach a rule. A hub is the tree rather than a device -- it is
     * refused everywhere a device is lent out -- but it does need the fifth zone's answer, and
     * urgently: a hub the gate left driverless takes its whole subtree with it, and nothing
     * below it will ever enumerate to raise a trigger. The root hubs are swept for the same
     * reason and are not in the scan at all ({@link #driverlessRootHubs}). And with the master
     * switch off no trigger acts: no rule runs and nothing is handed back either, which is what
     * "the rules do not run" has to mean if an interface a developer unbound by hand is to stay
     * unbound. The one exception is [daemonStart], where a gate left shut by a killed daemon, or
     * a release that never finished, is itself the reason a device is idle and nobody else is
     * ever going to ask about it.</p>
     */
    @NonNull
    private PassResult runAutoAttach(@NonNull String reason, boolean daemonStart) {
        var devices = scanHost();
        List<UsbRuleEngine.Decision> plan;
        // Devices no rule will speak for, which must not be left driverless all the same.
        var probeBack = new ArrayList<UsbRuleEngine.Device>();
        boolean recover;
        synchronized (lock) {
            var enabled = engine.rulesEnabled();
            recover = engine.recoversIdleDevices(daemonStart);
            var plugged = new ArrayList<UsbRuleEngine.Device>();
            for (var device : devices) {
                // The record as well as the state: a device a VM holds reads vmuse and is no
                // candidate already, but the record is this daemon's own word on who has it,
                // and the two disagreeing is exactly when nothing may be decided about it.
                if (attachments.containsKey(device.sysfs)) continue;
                if (enabled && !device.isHub()) {
                    plugged.add(UsbRuleEngine.Device.of(device));
                    continue;
                }
                if (!recover) continue;
                if (device.state() != UsbHostDevice.State.IDLE) continue;
                // A locked device is the user's answer, and "leave it to nobody" is one of the
                // two answers they can give: the host does not get it back behind their back.
                if (engine.isLocked(device.sysfs, device.devnum)) continue;
                probeBack.add(UsbRuleEngine.Device.of(device));
            }
            plan = enabled
                ? engine.plan(plugged, this::stateOf, this::vmHasController)
                : Collections.emptyList();
        }
        var attached = 0;
        var probed = 0;
        var idle = 0;
        var unreachable = new HashSet<String>();
        for (var device : probeBack) {
            if (!stillFree(device.sysfs, device.devnum)) continue;
            if (probeHostDrivers(device.sysfs) == 0) continue;
            probed++;
            Log.i(TAG, fmt("USB %s was idle and no rule speaks for it; the host gets it",
                device.sysfs));
        }
        // No lock and no record to ask about: a bus is not a device anybody can decide about,
        // and a root hub without its driver is every device below it not existing.
        if (recover)
            for (var hub : driverlessRootHubs()) {
                if (probeHostDrivers(hub) == 0) continue;
                probed++;
                Log.i(TAG, fmt("USB root hub %s had no driver; the host gets its bus back", hub));
            }
        for (var decision : plan) {
            var device = decision.device;
            var where = decision.where();
            // Asked again, at the moment of the write rather than when the plan was made: the
            // lock was released before any of this ran, an attach spends seconds inside the
            // crosvm CLI, and the management page acts on an IPC thread the whole time. A device
            // the user has decided about since is no longer this pass's to touch.
            if (!stillFree(device.sysfs, device.devnum)) continue;
            if (decision.target == UsbRules.Target.SINK) {
                // The whole of a sink: the search stopped here and nothing is done, so a device
                // the gate handed over driverless stays that way. A device the host already has
                // stays the host's, which is the one thing "do nothing" cannot undo -- it comes
                // back idle on its next plug, and this rule is what keeps it there.
                idle++;
                continue;
            }
            if (decision.target == UsbRules.Target.HOST) {
                // Nothing to do for a device the host already has, which is what makes this the
                // cheap steady state of every pass for most devices on the phone.
                if (probeHostDrivers(device.sysfs) == 0) continue;
                probed++;
                Log.i(TAG, fmt("USB %s (%s) goes to the host by %s", device.sysfs, device.id,
                    where));
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
        // An attach broadcasts for itself; the drivers_probe writes are the pass's own doing.
        if (probed > 0) broadcastHost();
        Log.i(TAG, fmt("USB rules pass (%s): %d decision(s), %d attached, %d given to the host, "
                + "%d left idle%s", reason, plan.size(), attached, probed, idle,
            unreachable.isEmpty() ? "" : ", VMM unreachable"));
        return new PassResult(attached + probed, !unreachable.isEmpty());
    }

    /** [vm] is null for an outcome that names no VM, which every outcome but an attach is. */
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
            // Null for the fifth zone's rule, as everywhere a decision goes on the wire; only
            // an attach broadcasts today, and those name a rule from the file.
            data.put("layer", decision.layer == null ? JSONObject.NULL : decision.layer.key);
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
     * The host action: offers everything of [sysfs] that nothing holds to the host drivers, and
     * says how many offers it made. With the gate shut nothing binds by itself, so these writes
     * are the whole of "the host keeps it".
     *
     * <p>The device itself is offered first, and only when no driver holds it. The gate is a bus
     * flag: it keeps the kernel from binding {@code usb_generic_driver} to the device as much as
     * it keeps a host driver off an interface, and that generic driver is the one that reads the
     * configuration and publishes the interfaces. A device that never got it therefore has no
     * interface for the loop below to walk, and offering the interfaces alone would do nothing
     * at all. Where the device did get it -- every device this daemon has measured on the phone
     * -- the read is one symlink and the write never happens.</p>
     *
     * <p>An interface something already holds is skipped, which is what makes a pass over a
     * device the host already has cost nothing and log nothing -- the steady state of most of
     * the devices on the phone -- and what keeps this from writing at a usbfs claim, where a
     * probe does nothing anyway.</p>
     */
    private static int probeHostDrivers(@NonNull String sysfs) {
        var probed = 0;
        if (readDriver(new File(SYSFS_ROOT, sysfs)).isEmpty()) {
            driversProbe(sysfs);
            probed++;
        }
        for (var name : interfacesOf(sysfs)) {
            if (!readDriver(new File(SYSFS_ROOT, name)).isEmpty()) continue;
            driversProbe(name);
            probed++;
        }
        return probed;
    }

    /**
     * The mirror of {@link #probeHostDrivers}: every host driver bound to an interface of
     * [sysfs] is taken off it, leaving the device idle. Returns how many it unbound.
     *
     * <p>This is the whole of "leave it to nobody" for a device the host already has. The gate
     * only decides what happens at enumeration, so a device Android bound before the switch went
     * on -- or one a host rule handed it -- stays bound until something writes here, and the
     * management page's Sink row would otherwise do nothing for the commonest device there is.
     * Deliberately not part of what a sink RULE does: a rule's sink is the search stopping, and
     * with the gate shut its devices are already idle.</p>
     *
     * <p>A usbfs claim is left alone. That is a VMM's live fd rather than a driver the host was
     * given, unbinding does not revoke it, and the wait the caller has already done is what it
     * gets instead.</p>
     */
    private static int unbindHostDrivers(@NonNull String sysfs) {
        var unbound = 0;
        for (var name : interfacesOf(sysfs)) {
            var driver = readDriver(new File(SYSFS_ROOT, name));
            if (driver.isEmpty() || DRIVER_USBFS.equals(driver)) continue;
            var result = run("echo %s > %s/%s/unbind", name, DRIVERS_DIR, driver);
            Log.i(TAG, fmt("unbind %s from %s: code=%d %s",
                name, driver, result.getCode(), result.getErrString()));
            unbound++;
        }
        return unbound;
    }

    /**
     * The root hubs the gate has left without a driver, which no scan can report and no rule can
     * speak for: {@code UsbHostInventory} lists devices, and a root hub is a bus.
     *
     * <p>They are swept all the same, and it is the most urgent sweep there is. The gate is a
     * bus flag and a host controller registered while it is shut -- a phone's dual-role port
     * switching to host when the OTG cable goes in -- hands its root hub no driver either. A
     * root hub with no driver is a hub whose ports are never scanned: nothing plugged into it
     * enumerates, no node appears, no trigger is raised, and the bus stays dead until the cable
     * is pulled. Nobody would ever ask about it, so every pass that may hand a device back asks
     * for it.</p>
     */
    @NonNull
    private static List<String> driverlessRootHubs() {
        var found = new ArrayList<String>();
        var entries = new File(SYSFS_ROOT).listFiles();
        if (entries == null) return found;
        for (var entry : entries) {
            var name = entry.getName();
            if (!UsbHostDevice.isRootHubName(name)) continue;
            if (!needsHostDrivers(name)) continue;
            found.add(name);
        }
        Collections.sort(found);
        return found;
    }

    /** Whether anything of [sysfs] -- the device itself, or one of its interfaces -- is unbound. */
    private static boolean needsHostDrivers(@NonNull String sysfs) {
        if (readDriver(new File(SYSFS_ROOT, sysfs)).isEmpty()) return true;
        for (var name : interfacesOf(sysfs))
            if (readDriver(new File(SYSFS_ROOT, name)).isEmpty()) return true;
        return false;
    }

    /**
     * One write to the bus's {@code drivers_probe}, which is the kernel's "try to bind this
     * device now" and the only thing that binds anything while the gate is shut. Deliberately
     * not conditional on the gate: the flag only decides what happens at enumeration, and this
     * is the ask that comes afterwards.
     */
    private static void driversProbe(@NonNull String name) {
        var result = run("echo %s > /sys/bus/usb/drivers_probe", name);
        Log.i(TAG, fmt("drivers_probe %s: code=%d %s",
            name, result.getCode(), result.getErrString()));
    }

    /**
     * Locks the instance at [sysfs] against every pass, reading which instance that is now: a
     * lock is about the unit in the socket at this moment, and one that has been replugged since
     * is not the device the user was looking at when they decided.
     */
    private void lockDevice(@NonNull String sysfs) {
        var device = freshDeviceAt(sysfs);
        if (device != null) lockDevice(sysfs, device.devnum);
    }

    /** As {@link #lockDevice(String)}, for a caller that has just read the device itself. */
    private void lockDevice(@NonNull String sysfs, int devnum) {
        synchronized (lock) {
            engine.lock(sysfs, devnum);
        }
    }

    /**
     * Whether a pass may still act on the device at [sysfs]: nothing has taken it since the plan
     * was made, and the user has not decided about it either. The counterpart of the lock
     * {@link #setTarget} takes before it touches anything -- that one fences a pass that has not
     * started, this one fences the pass that is already walking its plan.
     */
    private boolean stillFree(@NonNull String sysfs, int devnum) {
        synchronized (lock) {
            return !attachments.containsKey(sysfs) && !engine.isLocked(sysfs, devnum);
        }
    }

    /**
     * Whether the rules want [sysfs] left to nobody now that the VM holding it has let go. Only
     * that answer is acted on by the caller: a decision naming a running VM is ignored and the
     * device goes back to the host, which is the no-preemption guarantee -- a stop is never the
     * reason a device changes hands -- and the next trigger is what offers it to that VM.
     *
     * <p>A device the user locked is not the rules' to answer for, and is handed back like any
     * other: a lock is the answer to "who decided", and the one thing a released device must not
     * do is stay driverless because of a rule the user has already overruled.</p>
     */
    private boolean rulesLeaveItIdle(@NonNull String sysfs) {
        var device = freshDeviceAt(sysfs);
        if (device == null) return false;
        synchronized (lock) {
            if (engine.isLocked(sysfs, device.devnum)) return false;
            var decision = engine.decide(UsbRuleEngine.Device.of(device), this::stateOf,
                this::vmHasController);
            return decision != null && decision.target == UsbRules.Target.SINK;
        }
    }

    /**
     * The device at [sysfs] as sysfs describes it this moment, or null when it is not there.
     *
     * <p>Never the inventory's snapshot, wherever the state or the devnum decides anything:
     * binding and unbinding a driver creates and removes no {@code /dev/bus/usb} node, so the
     * watch never fires, nothing invalidates the snapshot, and its idea of who holds a device is
     * as old as the last plug event on that bus. A handful of small reads, which is what every
     * list here already pays for the same reason.</p>
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
                driversProbe(name);
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
        // Not "<sysfs>:" for every device: a root hub is usb3 and its interface is 3-0:1.0.
        var prefix = UsbHostDevice.interfacePrefix(sysfs);
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
