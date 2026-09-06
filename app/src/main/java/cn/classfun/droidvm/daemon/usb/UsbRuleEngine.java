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
 * <p>The flags are keyed by sysfs name and mean the device instance at that address: {@code held}
 * is set by a manual detach and says the user took the device back, so no trigger touches it
 * again; {@code failedFor} is the VM an attach failed for, so a rule that keeps failing is not
 * retried on every plug. A hold lasts until the inventory sees the node go, and only then -- a
 * rules save does not clear it. A failure is about one run of one VM: it also goes when that VM
 * next reaches RUNNING, because the attach that failed may have failed for that instance alone
 * -- a control socket not yet answering, a VM that went down between the decision and the CLI
 * -- and a reboot would otherwise release the device and never take it back.</p>
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

    /** The rule that took a device: where it sits in the rule set, and the VM, null for the host. */
    public static final class Decision {
        public final Device device;
        public final UsbRules.Layer layer;
        public final int index;
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
                 @Nullable String vm, @Nullable String controller) {
            this.device = device;
            this.layer = layer;
            this.index = index;
            this.vm = vm;
            this.controller = controller;
        }
    }

    private static final class Flags {
        boolean held = false;
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

    public boolean isHeld(@NonNull String sysfs) {
        var f = flags.get(sysfs);
        return f != null && f.held;
    }

    /** The user took the device back by hand; leave it alone until it is unplugged. */
    public void hold(@NonNull String sysfs) {
        flags.computeIfAbsent(sysfs, k -> new Flags()).held = true;
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
            if (!f.held) it.remove();
        }
    }

    /** The node is gone; whatever instance was there is forgotten, flags and all. */
    public void forget(@NonNull String sysfs) {
        flags.remove(sysfs);
    }

    /**
     * What a trigger would do: one decision per device that is not attached and not held, and
     * none for a device no rule takes. A device appears at most once, and a device already lent
     * out is never in the answer, which is what keeps a rules save from taking devices away.
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
        for (var device : plugged) {
            if (attached.test(device.sysfs) || isHeld(device.sysfs)) continue;
            var decision = decide(device, stateOf, hasController);
            if (decision != null) decisions.add(decision);
        }
        return decisions;
    }

    /**
     * The rule that takes [device], regardless of whether anything is stopping it from being
     * acted on: layers in order, list order within a layer. A null-VM hit stops the search with
     * "host"; a rule whose VM is not running, whose VM no longer has the controller it names, or
     * that already failed for this device, is passed over and the search goes on. Null when
     * nothing matches.
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
        var f = flags.get(device.sysfs);
        var failedFor = f == null ? null : f.failedFor;
        for (var layer : UsbRules.Layer.values()) {
            var list = rules.layer(layer);
            for (var index = 0; index < list.size(); index++) {
                var rule = list.get(index);
                if (!rule.matches(device.id, device.port)) continue;
                if (rule.vm == null) return new Decision(device, layer, index, null, null);
                if (stateOf.apply(rule.vm) != VMState.RUNNING) continue;
                if (!hasController.test(rule.vm, rule.controller)) continue;
                if (rule.vm.equals(failedFor)) continue;
                return new Decision(device, layer, index, rule.vm, rule.controller);
            }
        }
        return null;
    }
}
