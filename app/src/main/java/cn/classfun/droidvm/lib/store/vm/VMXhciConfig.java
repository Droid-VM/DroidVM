// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

/**
 * The xHCI USB controllers of a VM: the entries of its "peripherals" array whose type is
 * {@link PeripheralType#XHCI_USB}, and the rules for minting and finding their ids.
 *
 * <p>A controller carries an id because it is the one peripheral something outside the VM config
 * points at: an automatic USB attach rule names {@code vm} plus {@code controller}, and it has to
 * keep naming the same device across an edit. Index in the array cannot do that -- removing the
 * row above one shifts it, and the editor writes the whole array back on every save.</p>
 *
 * <p>The id is a slug and not a UUID because this runs twice, in two processes: {@code vm_create}
 * and {@code vm_modify} build a {@code new VMConfig(JSONObject)} from the config the app pushed,
 * so every migration in that constructor also runs inside the daemon, on a copy the daemon never
 * writes back. A random id minted there would differ from the one the app saved and every rule
 * keyed on it would dangle; {@code xhci-0} is what both processes compute from the same input.
 * The same reasoning made the screens' ids stable strings -- see {@link VMScreenConfig}.</p>
 *
 * <p>{@link #KEY_NEXT} is what keeps a deleted id from being handed out again: without it,
 * deleting {@code xhci-0} and adding a controller would silently adopt every rule that pointed at
 * the deleted one. It only ever increases, and only a writer that persists bumps it -- the
 * daemon's re-run of the migration computes the same value from the same input and drops it.</p>
 */
public final class VMXhciConfig {
    /** Key of the peripherals array on the VM config. */
    public static final String KEY_PERIPHERALS = "peripherals";
    /** Key of a controller's stable id inside its peripheral entry. */
    public static final String KEY_ID = "id";
    public static final String KEY_USB2 = "usb2_ports";
    public static final String KEY_USB3 = "usb3_ports";
    /** Next controller index to hand out; never reused, so a deleted id cannot be re-adopted. */
    public static final String KEY_NEXT = "xhci_next";
    /**
     * The VM-level boolean this list replaced. Read once, by {@link #migrate}, and never
     * written: it is the import signal of a config an older build wrote -- a vm or a vmpkg that
     * may never be saved again -- and nothing else in this codebase asks it anything.
     */
    private static final String KEY_USB = "usb";
    /** Prefix of every minted id; the number after it is {@link #KEY_NEXT}'s. */
    public static final String ID_PREFIX = "xhci-";
    /** crosvm's fixed geometry, and the value that raises no warning on the default backend. */
    public static final int DEFAULT_PORTS = 8;
    public static final int MIN_PORTS = 0;
    public static final int MAX_PORTS = 15;

    private VMXhciConfig() {
    }

    /** The VM's xHCI controllers, in array order. The first one is the crosvm-effective one. */
    @NonNull
    public static List<VMPeripheralConfig> listControllers(@NonNull DataItem vmItem) {
        var out = new ArrayList<VMPeripheralConfig>();
        for (var peripheral : VMPeripheralConfig.listOf(vmItem))
            if (peripheral.getType() == PeripheralType.XHCI_USB) out.add(peripheral);
        return out;
    }

    /**
     * The controller [id] names, or the first one when [id] is null -- which is what a rule with
     * no controller field means. Null when the VM has no such controller, or none at all.
     */
    @Nullable
    public static VMPeripheralConfig findController(@NonNull DataItem vmItem, @Nullable String id) {
        var controllers = listControllers(vmItem);
        if (controllers.isEmpty()) return null;
        if (id == null) return controllers.get(0);
        for (var controller : controllers)
            if (id.equals(controller.getControllerId())) return controller;
        return null;
    }

    /** The id a rule that names no controller means; null when the VM has none. */
    @Nullable
    public static String firstControllerId(@NonNull DataItem vmItem) {
        var first = findController(vmItem, null);
        if (first == null) return null;
        var id = first.getControllerId();
        return id.isEmpty() ? null : id;
    }

    /** Whether the VM has a USB controller at all. This is what replaced the "usb" boolean. */
    public static boolean isEnabled(@NonNull DataItem vmItem) {
        return !listControllers(vmItem).isEmpty();
    }

    /**
     * Appends a controller with a fresh id and the default port counts, and returns it.
     *
     * <p>The one place an id is minted, so that {@link #KEY_NEXT} is bumped exactly when one is
     * handed out: a caller that appended an entry itself could hand the same id out twice.</p>
     */
    @NonNull
    public static VMPeripheralConfig addController(@NonNull DataItem vmItem) {
        var controller = VMPeripheralConfig.createDefaultXhci(nextId(vmItem));
        peripherals(vmItem).append(controller.item);
        return controller;
    }

    /** Gives every id-less xHCI entry the next unused id. Idempotent. */
    public static void ensureIds(@NonNull DataItem vmItem) {
        for (var controller : listControllers(vmItem)) {
            if (!controller.getControllerId().isEmpty()) continue;
            controller.setControllerId(nextId(vmItem));
        }
    }

    /**
     * Brings a config up to the current USB schema: the VM-level "usb" boolean of an imported
     * vm or vmpkg becomes the one xHCI controller it meant.
     *
     * <p>An absent "usb" key reads as off, which is what crosvm and the passthrough manager
     * always made of it; QEMU defaulted it to on, and no longer does -- "no controller" is
     * uniformly "no USB".</p>
     *
     * <p>Idempotent, and identical in both processes: run twice, or once here and once in the
     * daemon on the same input, and the same controller with the same id comes out. That matters
     * more than it looks -- an imported config may never be saved, so every read of it has to
     * produce the id a rule is pointing at.</p>
     */
    public static void migrate(@NonNull DataItem vmItem) {
        // First, so a hand-written or half-converted entry gets its id before anything counts.
        ensureIds(vmItem);
        if (!listControllers(vmItem).isEmpty()) return;
        // The counter is this fold's evidence that it has already run on this config, the way
        // each of VMScreenConfig's has one: without it, a VM whose last controller the user
        // removed would grow a new one -- under a new id, dangling every rule that named the old
        // one -- every time the config was read back.
        var converted = vmItem.optLong(KEY_NEXT, -1) >= 0;
        if (converted || !vmItem.optBoolean(KEY_USB, false)) return;
        addController(vmItem);
    }

    /** A stored port count, held to what the editor can express. */
    public static int clampPorts(long ports) {
        if (ports < MIN_PORTS) return MIN_PORTS;
        if (ports > MAX_PORTS) return MAX_PORTS;
        return (int) ports;
    }

    /**
     * The next id, bumping the counter past it.
     *
     * <p>The counter is raised past every id already in the list first: a config that carries ids
     * but no counter -- written by hand, or by a build that lost the key -- would otherwise mint
     * one it is already using.</p>
     */
    @NonNull
    private static String nextId(@NonNull DataItem vmItem) {
        var next = vmItem.optLong(KEY_NEXT, 0);
        for (var controller : listControllers(vmItem)) {
            var index = indexOf(controller.getControllerId());
            if (index >= next) next = index + 1;
        }
        vmItem.set(KEY_NEXT, next + 1);
        return fmt("%s%d", ID_PREFIX, next);
    }

    /** The number in "xhci-<n>", or -1 for an id this class did not mint. */
    private static long indexOf(@NonNull String id) {
        if (!id.startsWith(ID_PREFIX)) return -1;
        try {
            return Long.parseLong(id.substring(ID_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The peripherals array, created when the config has none.
     *
     * <p>Re-read after it is created rather than kept from the local variable: {@code set} stores
     * a copy, so appending to the value handed to it would build a list nothing else can see --
     * the trap {@link VMPeripheralConfig#addEndpoint} documents.</p>
     */
    @NonNull
    private static DataItem peripherals(@NonNull DataItem vmItem) {
        var arr = vmItem.opt(KEY_PERIPHERALS, (DataItem) null);
        if (arr == null || !arr.is(DataItem.Type.ARRAY)) {
            vmItem.set(KEY_PERIPHERALS, DataItem.newArray());
            arr = vmItem.opt(KEY_PERIPHERALS, (DataItem) null);
        }
        return arr;
    }
}
