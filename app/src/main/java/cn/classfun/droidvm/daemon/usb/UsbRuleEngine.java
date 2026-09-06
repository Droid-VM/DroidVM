// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

import cn.classfun.droidvm.lib.store.vm.VMState;

/**
 * Decides which plugged device goes to which VM. Pure: it holds the rules and the per-device
 * flags and turns a list of devices into a list of decisions; attaching, broadcasting and
 * persisting are the manager's, which also owns the lock every call here is made under.
 *
 * <p>The flags are keyed by sysfs name and mean the device instance at that address: {@code pin}
 * is the user's own answer for the device -- a manual detach, or the management page saying
 * where it belongs -- so no trigger touches it again; {@code failedFor} is the VM an attach
 * failed for, so a rule that keeps failing is not retried on every plug. A pin lasts until the
 * inventory sees the node go, and only then -- a rules save does not clear it. A failure is
 * about one run of one VM: it also goes when that VM next reaches RUNNING, because the attach
 * that failed may have failed for that instance alone -- a control socket not yet answering, a
 * VM that went down between the decision and the CLI -- and a reboot would otherwise release
 * the device and never take it back.</p>
 *
 * <p>The rule set also carries the master switch, and the two answers this class gives -- what
 * takes a device, and what a whole pass would take -- are where it is honoured: a trigger asks
 * the engine before it acts, so switching the rules off makes every trigger a no-op here rather
 * than at each of the five places one is raised.</p>
 */
public final class UsbRuleEngine {
    /** As much of a host device as a rule can see. */
    public static final class Device {
        public final String sysfs;
        public final String id;
        public final String port;

        public Device(@NonNull String sysfs, @NonNull String id, @NonNull String port) {
            this.sysfs = sysfs;
            this.id = id;
            this.port = port;
        }

        @NonNull
        public static Device of(@NonNull UsbHostDevice device) {
            return new Device(device.sysfs, device.id, device.port);
        }
    }

    /**
     * The rule that took a device: where it sits in the rule set, what it does with the device,
     * and the VM when that is where it goes.
     */
    public static final class Decision {
        public final Device device;
        public final UsbRules.Layer layer;
        public final int index;
        /** Carried rather than inferred from {@link #vm}, so every reader says the same thing. */
        public final UsbRules.Target target;
        @Nullable
        public final String vm;
        /**
         * The controller the rule named, verbatim -- null stays null rather than becoming the
         * VM's first controller here. What "the first one" is depends on the config the VM was
         * started with, so it is resolved once, where the attach happens.
         */
        @Nullable
        public final String controller;

        Decision(@NonNull Device device, @NonNull UsbRules.Layer layer, int index,
                 @NonNull UsbRules.Target target, @Nullable String vm,
                 @Nullable String controller) {
            this.device = device;
            this.layer = layer;
            this.index = index;
            this.target = target;
            this.vm = vm;
            this.controller = controller;
        }
    }

    /**
     * What the user said about a device, which outranks every rule until it is unplugged. A
     * rule-made sink sets none of these: it is the rules' doing, so the rules may undo it.
     */
    public enum Pin {
        /** Nothing was said; the rules decide. */
        NONE("none"),
        /** The user took it back by hand, or asked for it to stay on the host. */
        HOST("host"),
        /** The user asked for it to be hidden from everything. */
        SINK("sink");

        /** The key this pin goes under on the wire. */
        public final String key;

        Pin(@NonNull String key) {
            this.key = key;
        }
    }

    /** What replacing the rules owes the devices this daemon has already taken. */
    public enum Save {
        /** Nothing: the switch was off before the save and is off after it. */
        NOTHING,
        /** The switch went off: everything taken is given back, once, and no pass runs. */
        RELEASE,
        /** The ordinary pass a save runs, which is also how turning the switch on applies. */
        PASS,
    }

    private static final class Flags {
        Pin pin = Pin.NONE;
        @Nullable
        String failedFor = null;
    }

    private UsbRules rules = UsbRules.empty();
    private final Map<String, Flags> flags = new HashMap<>();

    @NonNull
    public UsbRules getRules() {
        return rules;
    }

    public void setRules(@NonNull UsbRules rules) {
        this.rules = rules;
    }

    /** Whether the rules run at all right now: the master switch the rule set carries. */
    public boolean rulesEnabled() {
        return rules.isEnabled();
    }

    /**
     * What saving [next] over the rules held right now owes, asked before they are replaced.
     *
     * <p>The release is the switch's falling edge and only that: it is the one save that has to
     * undo work rather than do some, and a save that finds the switch already off has nothing
     * left to give back. Turning it on runs the ordinary pass, because a save applies its rules
     * and "the rules now run" is the biggest change a save can carry.</p>
     */
    @NonNull
    public Save saveOwes(@NonNull UsbRules next) {
        if (rules.isEnabled() && !next.isEnabled()) return Save.RELEASE;
        return next.isEnabled() ? Save.PASS : Save.NOTHING;
    }

    /** Whether anything the user said about this device stops a trigger from touching it. */
    public boolean isHeld(@NonNull String sysfs) {
        return pinOf(sysfs) != Pin.NONE;
    }

    /** What the user said about this device, {@link Pin#NONE} when nothing. */
    @NonNull
    public Pin pinOf(@NonNull String sysfs) {
        var f = flags.get(sysfs);
        return f == null ? Pin.NONE : f.pin;
    }

    /**
     * The user's answer for the device; it stands until the device is unplugged. Taking one back
     * leaves nothing behind, the way {@link #forgetFailuresFor} does: a device nothing is known
     * about is a device with no entry, and a map of all-default entries would be a slow leak.
     */
    public void pin(@NonNull String sysfs, @NonNull Pin pin) {
        if (pin == Pin.NONE) {
            var f = flags.get(sysfs);
            if (f == null) return;
            f.pin = Pin.NONE;
            if (f.failedFor == null) flags.remove(sysfs);
            return;
        }
        flags.computeIfAbsent(sysfs, k -> new Flags()).pin = pin;
    }

    /**
     * An attach to [vm] failed; do not offer that VM this device again until the device is
     * replugged or the VM comes up again.
     */
    public void markFailed(@NonNull String sysfs, @NonNull String vm) {
        flags.computeIfAbsent(sysfs, k -> new Flags()).failedFor = vm;
    }

    /** [vm] is a fresh instance; whatever failed against the previous one may work now. */
    public void forgetFailuresFor(@NonNull String vm) {
        var it = flags.entrySet().iterator();
        while (it.hasNext()) {
            var f = it.next().getValue();
            if (!vm.equals(f.failedFor)) continue;
            f.failedFor = null;
            if (f.pin == Pin.NONE) it.remove();
        }
    }

    /** The node is gone; whatever instance was there is forgotten, flags and all. */
    public void forget(@NonNull String sysfs) {
        flags.remove(sysfs);
    }

    /**
     * Every pin dropped. The switch going off hands the devices back, and the user's answers
     * about them go the same way: a pin says which trigger may not touch a device, and there
     * are no triggers left to keep out. Failures are left where they are -- they are about an
     * attach that did not work, not about who owns the device.
     */
    public void clearPins() {
        var it = flags.entrySet().iterator();
        while (it.hasNext()) {
            var f = it.next().getValue();
            f.pin = Pin.NONE;
            if (f.failedFor == null) it.remove();
        }
    }

    /**
     * What a trigger would do: one decision per device that is not attached and not pinned, and
     * none for a device no rule takes. A device appears at most once, and a device already lent
     * out or spoken for by the user is never in the answer, which is what keeps a rules save
     * from taking devices away.
     *
     * @param attached says whether a device is already some VM's.
     * @param stateOf  the state of a VM by id, null when there is no such VM.
     * @param hasController whether a VM still has the controller a rule names; a null controller
     *                      asks whether it has any.
     */
    @NonNull
    public List<Decision> plan(@NonNull List<Device> plugged, @NonNull Predicate<String> attached,
                               @NonNull Function<String, VMState> stateOf,
                               @NonNull BiPredicate<String, String> hasController) {
        var decisions = new ArrayList<Decision>();
        // Nothing to plan while the switch is off. Said here as well as in decide, because a
        // pass that walked an empty plan would still have taken a lock per device to be told so.
        if (!rules.isEnabled()) return decisions;
        for (var device : plugged) {
            if (attached.test(device.sysfs) || isHeld(device.sysfs)) continue;
            var decision = decide(device, stateOf, hasController);
            if (decision != null) decisions.add(decision);
        }
        return decisions;
    }

    /**
     * The rule that takes [device], regardless of whether anything is stopping it from being
     * acted on: layers in order, list order within a layer. A host or a sink hit stops the
     * search, both being something that is true here and now -- a sink in particular is never
     * held back by a failure, which is one VM's business and not a sysfs write's; a rule whose
     * VM is not running, whose VM no longer has the controller it names, or that already failed
     * for this device, is passed over and the search goes on. Null when nothing matches.
     *
     * <p>A missing controller is a skip and not an attach that fails: it is a configuration fact
     * rather than something that might work next time, so remembering it against the VM would
     * block that VM from every other rule for this device and would never be cleared by the VM
     * coming back. Falling through also lets a lower-priority rule have its turn, which is what
     * the dry run should be reporting.</p>
     */
    @Nullable
    public Decision decide(@NonNull Device device, @NonNull Function<String, VMState> stateOf,
                           @NonNull BiPredicate<String, String> hasController) {
        // The master switch, at the one place every trigger passes through: with it off no rule
        // takes anything, which is the whole of what "the rules do not run" means. The dry run
        // reads the same answer, and says nothing would happen -- which is the truth.
        if (!rules.isEnabled()) return null;
        var f = flags.get(device.sysfs);
        var failedFor = f == null ? null : f.failedFor;
        for (var layer : UsbRules.Layer.values()) {
            var list = rules.layer(layer);
            for (var index = 0; index < list.size(); index++) {
                var rule = list.get(index);
                if (!rule.matches(device.id, device.port)) continue;
                if (rule.target != UsbRules.Target.VM)
                    return new Decision(device, layer, index, rule.target, null, null);
                if (stateOf.apply(rule.vm) != VMState.RUNNING) continue;
                if (!hasController.test(rule.vm, rule.controller)) continue;
                if (rule.vm.equals(failedFor)) continue;
                return new Decision(device, layer, index, UsbRules.Target.VM, rule.vm,
                    rule.controller);
            }
        }
        return null;
    }
}
