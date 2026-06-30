package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.Constants.PATH_EDK2_FIRMWARE;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Consumer;

import cn.classfun.droidvm.lib.store.base.DataConfig;
import cn.classfun.droidvm.lib.store.base.DataItem;

public class VMConfig extends DataConfig {
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
