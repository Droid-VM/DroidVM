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
        item.set("memory_mb", 512L);
        item.set("cpu_count", 1L);
        item.set("swiotlb_mb", 64L);
        item.set("balloon", false);
        item.set("pmu", NEW_VM_DEFAULT_PMU);
        item.set("rng", NEW_VM_DEFAULT_RNG);
        item.set("smt", NEW_VM_DEFAULT_SMT);
        item.set("usb", NEW_VM_DEFAULT_USB);
        item.set("sandbox", false);
        item.set("hugepages", NEW_VM_DEFAULT_HUGEPAGES);
        item.set("strace", false);
        item.set("gpu_vram_folio_threshold_kb", 1024L);
        item.set(LendMthpMode.KEY, LendMthpMode.defaultForDevice(context));
        item.set("protected_vm", NEW_VM_DEFAULT_PROTECTED_VM);
        item.set("backend", VMBackend.DEFAULT);
        item.set("hypervisor", VMHypervisor.defaultForNewVm(VMBackend.DEFAULT));
        item.set("extra_options", DataItem.newArray());
        item.set("environment_variables", DataItem.newArray());
        item.set(CpuPlacementPlan.KEY_AFFINITY, "");
        item.set(CpuPlacementPlan.KEY_AUTO, true);
        item.set(CpuPlacementPlan.KEY_CAPACITY, "");
        item.set(CpuPlacementPlan.KEY_CLUSTERS, "");

        var boot = BootConfig.of(config);
        boot.setProtocol(BootConfig.Protocol.UEFI);
        boot.setUefiFirmware("");
        boot.setUefiVarsEnabled(true);
        boot.setUefiVars("");
        boot.setLinuxSource(BootConfig.LinuxSource.MANUAL);
        boot.setKernel(PATH_BUILTIN_KERNEL);
        boot.setInitrd(PATH_BUILTIN_INITRD);
        boot.setCmdline(BootConfig.DEFAULT_MANUAL_CMDLINE);
        boot.setImageCmdline("");
        boot.setImageDisk(0);
        boot.setVdafix(true);
        boot.setBootWait(BootConfig.DEFAULT_BOOT_WAIT);
        item.set("auto_up", false);

        item.set("disks", DataItem.newArray());
        item.set("shared_dirs", DataItem.newArray());
        item.set("networks", DataItem.newArray());

        var gpu = VMScreenConfig.of(item, VMScreenConfig.ID_GPU0);
        gpu.setEnabled(false);
        gpu.setExporter(DisplayExporter.NONE);
        gpu.setTransportCap(DisplayTransportCap.defaultFor(
            VMScreenConfig.ID_GPU0, DisplayExporter.NATIVE));
        gpu.setInputEnabled(true);
        gpu.setWidth(VMScreenConfig.DEFAULT_WIDTH);
        gpu.setHeight(VMScreenConfig.DEFAULT_HEIGHT);
        gpu.setRefreshRate(VMScreenConfig.DEFAULT_REFRESH_RATE);
        gpu.setDpiH(VMScreenConfig.DEFAULT_DPI);
        gpu.setDpiV(VMScreenConfig.DEFAULT_DPI);

        var simpleFb = VMScreenConfig.of(item, VMScreenConfig.ID_SIMPLEFB);
        simpleFb.setEnabled(true);
        simpleFb.setExporter(DisplayExporter.NATIVE);
        simpleFb.setTransportCap(DisplayTransportCap.defaultFor(
            VMScreenConfig.ID_SIMPLEFB, DisplayExporter.NATIVE));
        simpleFb.setInputEnabled(true);
        simpleFb.setWidth(VMScreenConfig.DEFAULT_WIDTH);
        simpleFb.setHeight(VMScreenConfig.DEFAULT_HEIGHT);
        simpleFb.setPollHz(VMScreenConfig.NEW_VM_DEFAULT_POLL_HZ);
        item.set("display_blit_provider", GpuBlitProvider.TURNIP);
        item.set(CpuPlacementPlan.KEY_GPU_CGROUP, false);
        item.set(CpuPlacementPlan.KEY_GPU_CGROUP_PATH,
            CpuPlacementPlan.DEFAULT_GPU_CGROUP_PATH);
        item.set(CpuPlacementPlan.KEY_GPU_CGROUP_CPUS, "");
        VpuConfig.setEnabled(item, false);
        VpuConfig.setHostPoolMb(item, VpuConfig.DEFAULT_HOST_POOL_MB);
        VpuConfig.setGuestPoolMb(item, VpuConfig.DEFAULT_GUEST_POOL_MB);

        var peripherals = DataItem.newArray();
        peripherals.append(VMPeripheralConfig.createDefaultVirtioSound().item);
        item.set("peripherals", peripherals);
        VMSerialConfig.ensureDefaults(item);
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

    /** Iterates this VM's NIC entries (the "networks" array). */
    public final void forEachNic(@NonNull Consumer<VMNicConfig> consumer) {
        var nets = item.opt("networks", null);
        if (nets == null || !nets.is(DataItem.Type.ARRAY)) return;
        for (var entry : nets.asArray())
            if (entry.is(DataItem.Type.OBJECT))
                consumer.accept(new VMNicConfig(entry));
    }
}
