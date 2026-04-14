package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.Constants.PATH_EDK2_FIRMWARE;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataConfig;

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
}
