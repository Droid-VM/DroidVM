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
