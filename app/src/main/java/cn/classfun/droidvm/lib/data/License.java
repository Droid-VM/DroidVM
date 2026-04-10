package cn.classfun.droidvm.lib.data;

import static cn.classfun.droidvm.lib.utils.AssetUtils.loadYAMLFromAssets;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class License {
    private static final String TAG = "License";
    private static final String LICENSE_ASSET = "data/license.yaml";

    private String name;
    private String description;
    private String license;
    private List<String> urls;

    private License() {
    }

    public List<String> getUrls() {
        return urls;
    }

    public String getLicense() {
        return license;
    }

    public String getDescription() {
        return description;
    }

    public String getName() {
        return name;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static List<License> load(@NonNull Context ctx) {
        try {
            Map<String, Object> root = loadYAMLFromAssets(ctx, LICENSE_ASSET);
            if (root == null) return null;
            var entries = (List<Map<String, Object>>) root.get("license");
            if (entries == null) return null;
            var result = new ArrayList<License>();
            for (var entry : entries) {
                var item = new License();
                item.name = getString(entry, "name");
                item.description = getString(entry, "description");
                item.license = getString(entry, "license");
                var urls = entry.get("urls");
                if (urls instanceof List) {
                    item.urls = new ArrayList<>();
                    for (var u : (List<?>) urls)
                        if (u != null) item.urls.add(u.toString());
                }
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load licenses", e);
            return null;
        }
    }

    @Nullable
    private static String getString(@NonNull Map<String, Object> map, @NonNull String key) {
        var val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
