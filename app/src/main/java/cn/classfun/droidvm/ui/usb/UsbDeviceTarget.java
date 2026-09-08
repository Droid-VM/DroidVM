// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

import cn.classfun.droidvm.daemon.usb.UsbHostDevice;
import cn.classfun.droidvm.daemon.usb.UsbRules;

/**
 * Where a device should end up: on the host, hidden, or on one VM's controller.
 *
 * <p>The kind is the daemon's own {@link UsbRules.Target} rather than a second copy of the
 * vocabulary -- a picker that offered a fourth answer, or spelled one of the three differently,
 * would be a rule the save refuses. What is added here is the pair a rule row carries beside it:
 * which VM, and which controller inside it, with null meaning that VM's first one.</p>
 */
public final class UsbDeviceTarget {
    @NonNull
    public final UsbRules.Target kind;
    /** The VM id, for -- and only for -- a {@link UsbRules.Target#VM} target. */
    @Nullable
    public final String vmId;
    /** One xHCI controller inside that VM; null means whichever is its first. */
    @Nullable
    public final String controller;

    private UsbDeviceTarget(@NonNull UsbRules.Target kind, @Nullable String vmId,
                            @Nullable String controller) {
        this.kind = kind;
        this.vmId = vmId;
        this.controller = controller;
    }

    @NonNull
    public static UsbDeviceTarget host() {
        return new UsbDeviceTarget(UsbRules.Target.HOST, null, null);
    }

    @NonNull
    public static UsbDeviceTarget sink() {
        return new UsbDeviceTarget(UsbRules.Target.SINK, null, null);
    }

    @NonNull
    public static UsbDeviceTarget vm(@NonNull String vmId, @Nullable String controller) {
        return new UsbDeviceTarget(UsbRules.Target.VM, vmId, controller);
    }

    /**
     * What a rule row says, read the way the daemon reads it: a row carrying no target is one
     * written before there was a target to write, and its vm is the whole of what it said.
     *
     * <p>A token this build has no word for reads as that same derivation. The row is not
     * rewritten by being displayed, so the save still sends the token back and the daemon still
     * refuses it by name -- which is a better answer than a page that quietly repairs it.</p>
     */
    @NonNull
    public static UsbDeviceTarget of(@Nullable String target, @Nullable String vm,
                                     @Nullable String controller) {
        var kind = target == null ? null : UsbRules.Target.fromKey(target);
        if (kind == null) kind = vm == null ? UsbRules.Target.HOST : UsbRules.Target.VM;
        if (kind != UsbRules.Target.VM) return new UsbDeviceTarget(kind, null, null);
        return new UsbDeviceTarget(kind, vm, controller);
    }

    /**
     * Where a device is right now, said in the words the menu speaks: the VM that holds it, the
     * host, or nobody -- and null when it is in none of the three.
     *
     * <p>Read off the device's state rather than remembered, because the state is the only copy
     * of it there is. A device claimed through usbfs that this daemon has no attachment for is
     * held by somebody these three words cannot name: a VMM still dying with it, or an ordinary
     * Android app that opened the device. Answering "the host" for that would be the one thing
     * such a device certainly is not, so the answer is nothing at all, and a caller that has to
     * show something is left to find a word of its own for it.</p>
     */
    @Nullable
    public static UsbDeviceTarget current(@NonNull UsbHostDevice.State state,
                                          @Nullable String attachedVm,
                                          @Nullable String attachedController) {
        if (attachedVm != null) return vm(attachedVm, attachedController);
        if (state == UsbHostDevice.State.VMUSE) return null;
        return state == UsbHostDevice.State.IDLE ? sink() : host();
    }

    /** Whether two targets say the same thing, so a picker can mark the row that is current. */
    public boolean sameAs(@Nullable UsbDeviceTarget other) {
        if (other == null) return false;
        return kind == other.kind && Objects.equals(vmId, other.vmId)
            && Objects.equals(controller, other.controller);
    }

    /** The {@code target} key of a rule row. */
    @NonNull
    public String toRuleTarget() {
        return kind.key;
    }
}
