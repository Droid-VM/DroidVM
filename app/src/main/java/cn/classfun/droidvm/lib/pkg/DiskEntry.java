package cn.classfun.droidvm.lib.pkg;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

import cn.classfun.droidvm.lib.store.base.JSONSerialize;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;

public final class DiskEntry implements JSONSerialize {
    public DiskRef ref;
    public String name = null;
    public DiskFormat format = null;
    public long size = 0;
    public String archivePath = null;
    public File target = null;

    public DiskEntry(DiskRef ref) {
        this.ref = ref;
    }

    public DiskEntry(@NonNull JSONObject o) {
        ref = new DiskRef(0, o);
        name = o.optString("name", "disk.img");
        format = optEnum(o, "format", DiskFormat.RAW);
        size = o.optLong("size");
        archivePath = o.optString("archive_path");
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var o = ref.toJson();
        if (target != null) {
            o.put("path", target.getPath());
            o.put("name", target.getName());
        } else {
            o.put("name", name);
        }
        o.put("format", format.name().toLowerCase());
        o.put("size", size);
        o.put("archive_path", archivePath);
        return o;
    }

    private static @NonNull String sanitize(@NonNull String name) {
        var sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20) c = '_';
            sb.append(c);
        }
        return sb.toString();
    }

    public void build() {
        var file = new File(ref.path);
        name = file.getName();
        archivePath = sanitize(name);
        format = DiskFormat.fromFilename(name);
        size = file.length();
    }

    @NonNull
    public static DiskEntry from(Object o) throws JSONException {
        if (o instanceof JSONObject)
            return new DiskEntry((JSONObject) o);
        throw new JSONException("object is not json");
    }
}
