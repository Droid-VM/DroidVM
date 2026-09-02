// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.pkg;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Set;

import cn.classfun.droidvm.lib.store.base.JSONSerialize;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;

public final class DiskEntry implements JSONSerialize, ManifestFeature.Carrier {
    /** {@link DiskRef#index} of a file that fills no VM disk slot. */
    public static final int INDEX_BACKING = -1;

    public DiskRef ref;
    public String name = null;
    public DiskFormat format = null;
    public long size = 0;
    public String archivePath = null;
    /**
     * Whether this file fills a VM disk slot. False for a file the package carries only because
     * another one backs onto it: the importer has to restore it, but must not hand it to the
     * guest as a disk of its own. Absent in packages written before backing chains were packed,
     * where every entry was a disk, hence the default.
     */
    public boolean attached = true;
    /** {@link #archivePath} of this file's backing image inside the package, or "". */
    public String backingArchive = "";
    public File target = null;

    public DiskEntry(DiskRef ref) {
        this.ref = ref;
    }

    public DiskEntry(@NonNull JSONObject o) {
        ref = new DiskRef(o.optInt("index", 0), o);
        name = o.optString("name", "disk.img");
        format = optEnum(o, "format", DiskFormat.RAW);
        size = o.optLong("size");
        archivePath = o.optString("archive_path");
        attached = o.optBoolean("attached", true);
        backingArchive = o.optString("backing_archive", "");
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
        o.put("attached", attached);
        if (!backingArchive.isEmpty()) o.put("backing_archive", backingArchive);
        return o;
    }

    @Override
    public void collectFeatures(@NonNull Set<ManifestFeature> into) {
        if (!attached || !backingArchive.isEmpty()) into.add(ManifestFeature.BACKING_CHAIN);
    }

    public void build(@NonNull String archivePath) {
        var file = new File(ref.path);
        name = file.getName();
        this.archivePath = archivePath;
        format = DiskFormat.fromFilename(name);
        size = file.length();
    }

    /** The manifest entry for one file of a {@link DiskChainPlan}. */
    @NonNull
    public static DiskEntry of(@NonNull DiskChainPlan.Member member) {
        var attachment = member.attachment;
        var entry = new DiskEntry(attachment != null
            ? attachment
            : new DiskRef(INDEX_BACKING, member.path));
        entry.attached = attachment != null;
        entry.backingArchive = member.backingArchive;
        entry.build(member.archivePath);
        return entry;
    }

    @NonNull
    public static DiskEntry from(Object o) throws JSONException {
        if (o instanceof JSONObject)
            return new DiskEntry((JSONObject) o);
        throw new JSONException("object is not json");
    }
}
