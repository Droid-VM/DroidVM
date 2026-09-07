// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

import cn.classfun.droidvm.lib.store.vm.VMState;

/**
 * Decides what happens to each plugged device: layers in order, then the fifth zone. Pure -- it
 * holds the rules and the per-device flags and turns a list of devices into a list of decisions
 * -- while acting on one, broadcasting and persisting are the manager's, which also owns the
 * lock every call here is made under.
 *
 * <p>What a device is doing is not kept here or anywhere else: it is read off the drivers bound
 * to its interfaces every time a device is scanned ({@link UsbHostDevice.State}) and arrives on
 * the {@link Device} the caller builds. What is kept is what the kernel cannot say: {@code lock}
 * is the user's own answer for a device -- the management page saying where it belongs, or a
 * manual detach -- so no rule pass touches it again, and {@code failedFor} is the VM an attach
 * failed for, so a rule that keeps failing is not retried on every plug. A lock is about the
 * instance it was made for, so a replug drops it whatever else happens; both go when the
 * inventory sees the node itself go, and neither is cleared by a rules save. A failure is about
 * one run of one VM: it also goes when that VM next reaches RUNNING, because the attach that
 * failed may have failed for that instance alone -- a control socket not yet answering, a VM
 * that went down between the decision and the CLI -- and a reboot would otherwise release the
 * device and never take it back.</p>
 *
 * <p>The rule set also carries the master switch, and the two answers this class gives -- what
 * takes a device, and what a whole pass would take -- are where it is honoured: a trigger asks
 * the engine before it acts, so switching the rules off makes every trigger a no-op here rather
 * than at each of the five places one is raised.</p>
 */
public final class UsbRuleEngine {
    /** As much of a host device as a rule can see, plus what makes it a candidate. */
    public static final class Device {
        public final String sysfs;
        public final String id;
        public final String port;
        /** The instance at that address: a lock is about this number and no other. */
        public final int devnum;
        /** Read from the bound drivers by whoever scanned the device; never stored anywhere. */
        public final UsbHostDevice.State state;

        public Device(@NonNull String sysfs, @NonNull String id, @NonNull String port, int devnum,
                      @NonNull UsbHostDevice.State state) {
            this.sysfs = sysfs;
            this.id = id;
            this.port = port;
            this.devnum = devnum;
            this.state = state;
        }

        @NonNull
        public static Device of(@NonNull UsbHostDevice device) {
            return new Device(device.sysfs, device.id, device.port, device.devnum, device.state());
        }
    }

    /**
     * The rule that took a device: where it sits in the rule set, what it does with the device,
     * and the VM when that is where it goes.
     */
    public static final class Decision {
        public final Device device;
        /** Null for the fifth zone's rule, which is not in the file; see {@link #where()}. */
        @Nullable
        public final UsbRules.Layer layer;
        /** Its index in that layer, or -1 for the fifth zone's one rule. */
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

        Decision(@NonNull Device device, @Nullable UsbRules.Layer layer, int index,
                 @NonNull UsbRules.Target target, @Nullable String vm,
                 @Nullable String controller) {
            this.device = device;
            this.layer = layer;
            this.index = index;
            this.target = target;
            this.vm = vm;
            this.controller = controller;
        }

        /** Which rule this was, for a log line: {@code device[0]}, or the fifth zone by name. */
        @NonNull
        public String where() {
            return layer == null ? "the default rule" : fmt("%s[%d]", layer.key, index);
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
        /** The devnum the user's lock was made for; null when the user has said nothing. */
        @Nullable
        Integer lockedFor = null;
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

    /**
     * Whether the user has spoken for this very instance of [sysfs]. A replug is a new instance
     * and answers false without anything having to clear it, which is the whole reason the
     * devnum is part of the answer rather than only of the bookkeeping.
     */
    public boolean isLocked(@NonNull String sysfs, int devnum) {
        var f = flags.get(sysfs);
        return f != null && f.lockedFor != null && f.lockedFor == devnum;
    }

    /**
     * The user decided about this instance: no pass may touch it until they say otherwise or it
     * is unplugged. What they decided is not kept -- the device's own state says that -- so this
     * is a boolean and not a target.
     */
    public void lock(@NonNull String sysfs, int devnum) {
        flags.computeIfAbsent(sysfs, k -> new Flags()).lockedFor = devnum;
    }

    /**
     * The user handed the device back to the rules. Taking a lock off leaves nothing behind, the
     * way {@link #forgetFailuresFor} does: a device nothing is known about is a device with no
     * entry, and a map of all-default entries would be a slow leak.
     */
    public void unlock(@NonNull String sysfs) {
        var f = flags.get(sysfs);
        if (f == null) return;
        f.lockedFor = null;
        if (f.failedFor == null) flags.remove(sysfs);
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
            if (f.lockedFor == null) it.remove();
        }
    }

    /** The node is gone; whatever instance was there is forgotten, flags and all. */
    public void forget(@NonNull String sysfs) {
        flags.remove(sysfs);
    }

    /**
     * Every lock dropped. The switch going off hands the devices back, and the user's answers
     * about them go the same way: a lock says which pass may not touch a device, and there are
     * no passes left to keep out. Failures are left where they are -- they are about an attach
     * that did not work, not about who owns the device.
     */
    public void clearLocks() {
        var it = flags.entrySet().iterator();
        while (it.hasNext()) {
            var f = it.next().getValue();
            f.lockedFor = null;
            if (f.failedFor == null) it.remove();
        }
    }

    /**
     * Whether a pass may decide about [device] at all: its state is hostuse or idle and the user
     * has not locked it. A vmuse device is in use -- some VMM's fd holds an interface -- and a
     * locked one is the user's, and neither is any pass's business.
     */
    public boolean isCandidate(@NonNull Device device) {
        return device.state != UsbHostDevice.State.VMUSE
            && !isLocked(device.sysfs, device.devnum);
    }

    /**
     * What a trigger would do: one decision per candidate ({@link #isCandidate}), and none at
     * all while the switch is off. A device appears at most once. Every candidate gets a
     * decision, the fifth zone seeing to that, so the answer is as long as the candidate list.
     *
     * @param stateOf  the state of a VM by id, null when there is no such VM.
     * @param hasController whether a VM still has the controller a rule names; a null controller
     *                      asks whether it has any.
     */
    @NonNull
    public List<Decision> plan(@NonNull List<Device> plugged,
                               @NonNull Function<String, VMState> stateOf,
                               @NonNull BiPredicate<String, String> hasController) {
        var decisions = new ArrayList<Decision>();
        // Nothing to plan while the switch is off. Said here as well as in decide, because a
        // pass that walked an empty plan would still have taken a lock per device to be told so.
        if (!rules.isEnabled()) return decisions;
        for (var device : plugged) {
            if (!isCandidate(device)) continue;
            var decision = decide(device, stateOf, hasController);
            if (decision != null) decisions.add(decision);
        }
        return decisions;
    }

    /**
     * What takes [device], regardless of whether anything is stopping it from being acted on:
     * the four layers the file carries in order, list order within a layer, and then the fifth
     * zone. A host or a sink hit stops the search, both being something that is true here and
     * now -- a sink in particular is never held back by a failure, which is one VM's business
     * and not the host's; a rule whose VM is not running, whose VM no longer has the controller
     * it names, or that already failed for this device, is passed over and the search goes on.
     *
     * <p>The fifth zone is one rule, {@code any -> host}, that the user never sees and cannot
     * edit: it is a constant here rather than a row in the file, so it cannot be deleted, cannot
     * be reordered, and every rules file ever written has it. What it buys is that "no rule
     * matched" cannot happen while the switch is on -- and with the autoprobe gate shut, a
     * device nothing matched would otherwise sit driverless forever, which is a keyboard that
     * does not type and a hub whose whole subtree is dead.</p>
     *
     * <p>A missing controller is a skip and not an attach that fails: it is a configuration fact
     * rather than something that might work next time, so remembering it against the VM would
     * block that VM from every other rule for this device and would never be cleared by the VM
     * coming back. Falling through also lets a lower-priority rule have its turn, which is what
     * the dry run should be reporting.</p>
     *
     * @return null only while the master switch is off, which is the whole of "the rules do not
     *         run": with it on there is always an answer.
     */
    @Nullable
    public Decision decide(@NonNull Device device, @NonNull Function<String, VMState> stateOf,
                           @NonNull BiPredicate<String, String> hasController) {
        // The master switch, at the one place every trigger passes through: with it off no rule
        // takes anything, not even the fifth zone's. The dry run reads the same answer, and says
        // nothing would happen -- which is the truth.
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
        return new Decision(device, null, -1, UsbRules.Target.HOST, null, null);
    }
}
