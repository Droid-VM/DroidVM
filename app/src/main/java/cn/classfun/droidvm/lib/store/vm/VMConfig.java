package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.Constants.PATH_EDK2_FIRMWARE;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;
import java.util.function.Consumer;

import cn.classfun.droidvm.lib.store.base.DataConfig;
import cn.classfun.droidvm.lib.store.base.DataItem;

public class VMConfig extends DataConfig {
    public VMConfig() {
        setId(UUID.randomUUID());
    }

    public VMConfig(@NonNull JSONObject obj) throws JSONException {
        item.set(obj);
        if (!obj.has("use_uefi") && obj.optString("kernel", "").equals(PATH_EDK2_FIRMWARE)) {
            item.set("use_uefi", true);
            item.remove("kernel");
        }
        migrateBoot();
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
        // "boot a custom firmware" — that is UEFI protocol in the new model
        if (!uefi && item.optString("kernel", "").isEmpty()
            && !item.optString("bios", "").isEmpty())
            uefi = true;
        boot.setProtocol(uefi ? BootConfig.Protocol.UEFI : BootConfig.Protocol.LINUX);
        boot.setUefiFirmware(item.optString("bios", ""));
        boot.setKernel(item.optString("kernel", ""));
        boot.setInitrd(item.optString("initrd", ""));
        boot.setCmdline(item.optString("cmdline", ""));
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
