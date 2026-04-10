package cn.classfun.droidvm.ui.disk.lxc;

import static android.os.Build.SUPPORTED_ABIS;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class LxcImageParser {
    private static final String TAG = "LxcImageParser";
    private static final String TARGET_FTYPE = "disk-kvm.img";

    @Nullable
    private static String getCurrentArch() {
        for (var arch : SUPPORTED_ABIS) {
            switch (arch.toLowerCase()) {
                case "arm64-v8a":
                case "aarch64":
                case "arm64":
                    return "arm64";
                case "x86_64":
                case "amd64":
                    return "amd64";
                case "armv7":
                case "armv7a":
                case "armeabi-v7a":
                case "arm32":
                case "armhf":
                    return "armhf";
                case "riscv64":
                    return "riscv64";
            }
        }
        return null;
    }

    @SuppressWarnings("unused")
    public static List<LxcImage> parse(@NonNull String json) {
        try {
            var obj = new JSONObject(json);
            return parse(obj);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse lxc images metadata json", e);
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unused")
    public static List<LxcImage> parse(@NonNull JSONObject json) {
        var currentArch = getCurrentArch();
        var result = new ArrayList<LxcImage>();
        try {
            var products = json.optJSONObject("products");
            if (products == null) return result;
            var productKeys = products.keys();
            while (productKeys.hasNext()) {
                var productId = productKeys.next();
                var product = products.optJSONObject(productId);
                if (product == null) continue;
                var arch = product.optString("arch", "");
                if (currentArch != null && !arch.equals(currentArch)) continue;
                var distro = product.optString("os", "");
                var release = product.optString("release", "");
                var releaseTitle = product.optString("release_title", release);
                var variant = product.optString("variant", "default");
                var aliases = product.optString("aliases", "");
                var versions = product.optJSONObject("versions");
                if (versions == null) continue;
                var versionKeys = versions.keys();
                while (versionKeys.hasNext()) {
                    var buildSerial = versionKeys.next();
                    var version = versions.optJSONObject(buildSerial);
                    if (version == null) continue;
                    var items = version.optJSONObject("items");
                    if (items == null) continue;
                    var itemKeys = items.keys();
                    while (itemKeys.hasNext()) {
                        var itemKey = itemKeys.next();
                        var item = items.optJSONObject(itemKey);
                        if (item == null) continue;
                        var fType = item.optString("ftype", "");
                        if (!TARGET_FTYPE.equals(fType)) continue;
                        var img = new LxcImage();
                        img.setProductId(productId);
                        img.setDistro(distro);
                        img.setDistroVersion(release);
                        img.setReleaseTitle(releaseTitle);
                        img.setArch(arch);
                        img.setVariant(variant);
                        img.setAliases(aliases);
                        img.setBuildSerial(buildSerial);
                        img.setDownloadPath(item.optString("path", ""));
                        img.setSize(item.optLong("size", 0));
                        img.setSha256(item.optString("sha256", ""));
                        result.add(img);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load lxc images metadata", e);
            return result;
        }
        result.sort((a, b) -> {
            int c = a.getDistro().compareToIgnoreCase(b.getDistro());
            if (c != 0) return c;
            c = a.getDistroVersion().compareToIgnoreCase(b.getDistroVersion());
            if (c != 0) return c;
            c = a.getVariant().compareToIgnoreCase(b.getVariant());
            if (c != 0) return c;
            return b.getBuildSerial().compareTo(a.getBuildSerial());
        });
        return result;
    }
}

