// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.Constants.PATH_EDK2_FIRMWARE;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_INITRD;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_KERNEL;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Consumer;

import cn.classfun.droidvm.lib.store.base.DataConfig;
import cn.classfun.droidvm.lib.store.base.DataItem;

public class VMConfig extends DataConfig {
    public static final boolean NEW_VM_DEFAULT_HUGEPAGES = true;
    public static final boolean NEW_VM_DEFAULT_PMU = true;
    public static final boolean NEW_VM_DEFAULT_RNG = true;
    public static final boolean NEW_VM_DEFAULT_SMT = true;
    public static final boolean NEW_VM_DEFAULT_USB = true;
    public static final ProtectedVM NEW_VM_DEFAULT_PROTECTED_VM =
        ProtectedVM.PSEUDO_UNPROTECTED;

    public VMConfig() {
        setId(UUID.randomUUID());
        item.set("created_at", System.currentTimeMillis());
    }

    public VMConfig(@NonNull JSONObject obj) throws JSONException {
        item.set(obj);
        if (!obj.has("use_uefi") && obj.optString("kernel", "").equals(PATH_EDK2_FIRMWARE)) {
            item.set("use_uefi", true);
            item.remove("kernel");
        }
        migrateBoot();
        migrateNicForwards();
        // Configs from before "screens" describe one display chosen by an either/or backend
        // enum; fold that into the per-screen bindings, dropping the legacy keys so nothing
        // downstream can read a second, disagreeing answer.
        VMScreenConfig.migrate(item);
        // Configs from before "serial_ports" implicitly meant "COM1 = app console, rest sinks";
        // make that explicit so every reader sees the same list.
        VMSerialConfig.ensureDefaults(item);
        // Configs from before the xHCI peripheral say "usb": true; turn that into the one
        // controller it meant, so a USB rule has something to point at. Deterministic, because
        // the daemon runs this too on the config the app pushes and never writes the result back.
        VMXhciConfig.migrate(item);
    }

    /**
     * Materializes the values shown by every tab when Customize opens for a new VM. Quick
     * creation starts here and only replaces the fields it exposes, so both creation paths keep
     * the same defaults as those defaults evolve.
     */
    @NonNull
    public static VMConfig createWithCustomizeDefaults(@NonNull Context context) {
        var config = new VMConfig();
        var item = config.item;
        try {
            // Embed the same static template as the daemon, including nested defaults.
            // Merge into the new config so its UUID and creation time stay intact.
            var defaults = new JSONObject(cn.classfun.droidvm.BuildConfig.VM_DEFAULTS_JSON);
            var keys = defaults.keys();
            while (keys.hasNext()) {
                var key = keys.next();
                item.set(key, DataItem.from(defaults.get(key)));
            }
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid embedded VM defaults", e);
        }
        item.set(LendMthpMode.KEY, LendMthpMode.defaultForDevice(context));
        var hypervisor = VMHypervisor.defaultForNewVm(VMBackend.DEFAULT);
        item.set("hypervisor", hypervisor);
        item.set("protected_vm", ProtectedVM.defaultFor(VMBackend.DEFAULT, hypervisor));
        var boot = BootConfig.of(config);
        boot.setKernel(PATH_BUILTIN_KERNEL);
        boot.setInitrd(PATH_BUILTIN_INITRD);
        return config;
    }

    /**
     * Folds the legacy flat boot keys (use_uefi/kernel/initrd/cmdline/bios)
     * into the "boot" object on load. The legacy keys are left in place so
     * the config file still boots under an older daemon/APK; everything in
     * this codebase reads only the "boot" object from here on.
     */
    private void migrateBoot() {
        if (item.opt("boot", null) != null) return;
        var legacy = item.opt("use_uefi", null) != null
            || !item.optString("kernel", "").isEmpty()
            || !item.optString("initrd", "").isEmpty()
            || !item.optString("cmdline", "").isEmpty()
            || !item.optString("bios", "").isEmpty();
        if (!legacy) return;
        var boot = BootConfig.of(this);
        var uefi = item.optBoolean("use_uefi", true);
        // legacy QEMU oddity: use_uefi=false + "bios" + no kernel meant
        // "boot a custom firmware" -- that is UEFI protocol in the new model
        if (!uefi && item.optString("kernel", "").isEmpty()
            && !item.optString("bios", "").isEmpty())
            uefi = true;
        boot.setProtocol(uefi ? BootConfig.Protocol.UEFI : BootConfig.Protocol.LINUX);
        boot.setUefiFirmware(item.optString("bios", ""));
        boot.setKernel(item.optString("kernel", ""));
        boot.setInitrd(item.optString("initrd", ""));
        boot.setCmdline(item.optString("cmdline", ""));
    }

    /**
     * Folds the legacy VM-level "port_forwards" array into the new per-NIC
     * DHCPv4 lease model. The old model forwarded a host port to a free-form
     * guest IP with no managed lease; the new model rides forwards on a static
     * lease. This does the structural conversion only: the matching NIC's
     * lease is enabled and the forwards are moved onto it, but the lease
     * "offset" (the guest IP position) is left unset. Forwards are decoupled
     * from the static IP, so an empty offset is a valid "assign on boot"
     * state -- it is allocated, with a cross-VM conflict check, when the VM is
     * started. The legacy array is dropped once folded, so this is a no-op on
     * later loads.
     */
    private void migrateNicForwards() {
        var pfs = item.opt("port_forwards", null);
        var nics = item.opt("networks", null);
        if (pfs == null || !pfs.is(DataItem.Type.ARRAY) || pfs.isEmpty()
            || nics == null || !nics.is(DataItem.Type.ARRAY)) {
            item.remove("port_forwards");
            return;
        }

        // network_id -> first NIC on that network (forwards were keyed only by
        // network, so they land on the first matching NIC)
        var nicByNet = new LinkedHashMap<String, DataItem>();
        for (var nic : nics.asArray()) {
            if (!nic.is(DataItem.Type.OBJECT)) continue;
            var netId = nic.optString("network_id", "");
            if (!netId.isEmpty()) nicByNet.putIfAbsent(netId, nic);
        }

        for (var pf : pfs.asArray()) {
            if (!pf.is(DataItem.Type.OBJECT)) continue;
            if (!pf.optBoolean("enabled", true)) continue; // matched old runtime
            var nic = nicByNet.get(pf.optString("network_id", ""));
            if (nic == null) continue; // forward for a network this VM no longer attaches

            var lease = nic.opt("dhcp4_lease", null);
            if (lease == null || !lease.is(DataItem.Type.OBJECT)) {
                nic.set("dhcp4_lease", DataItem.newObject());
                lease = nic.get("dhcp4_lease");
            }
            // enable the lease but leave "offset" unset -- it is allocated,
            // with a conflict check, when the VM boots
            lease.set("enabled", true);

            var forwards = lease.opt("forwards", null);
            if (forwards == null || !forwards.is(DataItem.Type.ARRAY)) {
                lease.set("forwards", DataItem.newArray());
                forwards = lease.get("forwards");
            }
            var proto = pf.optString("protocol", "tcp");
            if (proto.isEmpty()) proto = "tcp";
            var forward = DataItem.newObject();
            forward.set("proto", proto);
            // host_ip had no equivalent in the new model (forwards listen on
            // all host addresses); only the port pair carries over
            forward.set("host", String.valueOf(pf.optLong("host_port", 0)));
            forward.set("guest", String.valueOf(pf.optLong("guest_port", 0)));
            forwards.append(forward);
        }

        item.remove("port_forwards");
    }

    /**
     * Free-form Markdown the user keeps with this VM -- what it is for, how to log in, what not
     * to touch. Empty when unset, never null, so every reader can ask {@code isEmpty()}. It rides
     * along in a package like any other field, which is the point: the notes are about the VM,
     * not about the phone it happens to be on.
     */
    @NonNull
    public final String getNotes() {
        var notes = item.optString("notes", "");
        return notes == null ? "" : notes;
    }

    public final void setNotes(@NonNull String notes) {
        if (notes.isEmpty()) item.remove("notes");
        else item.set("notes", notes);
    }

    /** Iterates this VM's NIC entries (the "networks" array). */
    public final void forEachNic(@NonNull Consumer<VMNicConfig> consumer) {
        var nets = item.opt("networks", null);
        if (nets == null || !nets.is(DataItem.Type.ARRAY)) return;
        for (var entry : nets.asArray())
            if (entry.is(DataItem.Type.OBJECT))
                consumer.accept(new VMNicConfig(entry));
    }
}
