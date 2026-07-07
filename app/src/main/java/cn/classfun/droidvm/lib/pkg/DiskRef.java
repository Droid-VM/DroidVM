package cn.classfun.droidvm.lib.pkg;

import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.base.JSONSerialize;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;

public final class DiskRef implements JSONSerialize {
    public int index;
    public String path;
    public boolean readonly;
    public DiskBus bus;

    public DiskRef(int index, @NonNull JSONObject jo) {
        this.index = index;
        this.path = jo.optString("path", "");
        this.readonly = jo.optBoolean("readonly", false);
        this.bus = optEnum(jo, "bus", DiskBus.VIRTIO);
    }

    public DiskRef(int index, @NonNull DataItem o) {
        this.index = index;
        this.path = o.optString("path", "");
        this.readonly = o.optBoolean("readonly", false);
        this.bus = optEnum(o, "bus", DiskBus.VIRTIO);
    }

    @NonNull
    public DiskFormat getFormat() {
        return DiskFormat.fromFilename(requireNonNull(path));
    }

    public boolean isCDROM() {
        return getFormat() == DiskFormat.ISO;
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var d = new JSONObject();
        d.put("readonly", readonly);
        d.put("bus", bus);
        d.put("path", path);
        return d;
    }
}
