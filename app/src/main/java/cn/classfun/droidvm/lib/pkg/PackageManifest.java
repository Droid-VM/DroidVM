// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.pkg;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.alignUp;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.decodeUtf8;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.readFully;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.readZeroPadding;
import static cn.classfun.droidvm.lib.utils.JsonUtils.arrayToList;
import static cn.classfun.droidvm.lib.utils.JsonUtils.listToJSONArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import cn.classfun.droidvm.BuildConfig;
import cn.classfun.droidvm.lib.archive.Compression;
import cn.classfun.droidvm.lib.store.base.JSONSerialize;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.vm.VMConfig;

public final class PackageManifest implements JSONSerialize {
    public int manifestVersion = PackageConstants.MANIFEST_VERSION_BASE;
    public String format = PackageConstants.EXTENSION;
    public long createdAt = System.currentTimeMillis();
    public String appVersion = BuildConfig.VERSION_NAME;
    public int appVersionCode = BuildConfig.VERSION_CODE;
    public String appBuildType = BuildConfig.BUILD_TYPE;
    public Compression compression = PackageConstants.DEFAULT_COMPRESSION;
    public VMConfig vm = new VMConfig();
    public List<DiskEntry> disks = new ArrayList<>();
    public List<BootFile> boots = new ArrayList<>();
    public List<NetworkConfig> networks = new ArrayList<>();

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException{
        var o = new JSONObject();
        o.put("manifest_version", manifestVersion);
        o.put("format", format);
        o.put("created_at", createdAt);
        o.put("app_version", appVersion);
        o.put("app_version_code", appVersionCode);
        o.put("app_build_type", appBuildType);
        o.put("compression", compression);
        o.put("vm", vm.toJson());
        o.put("disks", listToJSONArray(disks));
        o.put("boots", listToJSONArray(boots));
        o.put("networks", listToJSONArray(networks));
        return o;
    }

    /** The {@link ManifestFeature}s this package's contents actually use. */
    @NonNull
    public Set<ManifestFeature> features() {
        var used = EnumSet.noneOf(ManifestFeature.class);
        for (var disk : disks) disk.collectFeatures(used);
        return used;
    }

    /**
     * The oldest manifest version that can describe this package - the highest any feature in
     * it needs - so a plain one-disk export stays importable by builds that predate the rest.
     * Ask only once everything is collected: the answer is a function of all of it.
     */
    public int resolveVersion() {
        return ManifestFeature.versionFor(features());
    }

    @Nullable
    public DiskEntry findDisk(@NonNull String archivePath) {
        for (var disk : disks)
            if (archivePath.equals(disk.archivePath)) return disk;
        return null;
    }

    @Nullable
    public BootFile findBoot(@NonNull String archivePath) {
        for (var boot : boots)
            if (archivePath.equals(boot.archivePath)) return boot;
        return null;
    }

    @NonNull
    public static PackageManifest fromStream(
        @NonNull InputStream in,
        @NonNull PackageHeader hdr
    ) throws Exception {
        readZeroPadding(in, alignUp(PackageConstants.HEADER_SIZE) - PackageConstants.HEADER_SIZE);
        var manifest = new byte[hdr.manifestSize];
        readFully(in, manifest);
        var json = new JSONObject(decodeUtf8(manifest));
        return fromJson(json);
    }

    @NonNull
    public static PackageManifest fromJson(@NonNull JSONObject o) throws JSONException {
        var m = new PackageManifest();
        m.manifestVersion = o.optInt("manifest_version");
        m.format = o.optString("format");
        m.createdAt = o.optLong("created_at");
        m.appVersion = o.optString("app_version");
        m.appVersionCode = o.optInt("app_version_code");
        m.appBuildType = o.optString("app_build_type");
        m.compression = optEnum(o, "compression", PackageConstants.DEFAULT_COMPRESSION);
        m.vm = new VMConfig(o.getJSONObject("vm"));
        m.disks = arrayToList(o, "disks", DiskEntry::from);
        m.boots = arrayToList(o, "boots", BootFile::from);
        m.networks = arrayToList(o, "networks", (JSONObject net) -> new NetworkConfig(net));
        return m;
    }
}
