package cn.classfun.droidvm.lib.data;

import static cn.classfun.droidvm.lib.utils.AssetUtils.loadYAMLFromAssets;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Map;

public final class Language {
    private static final String LANGUAGES_ASSET = "data/languages.yaml";

    private String tag;
    private String name;

    private Language() {
    }

    public String getTag() {
        return tag;
    }

    public String getName() {
        return name;
    }

    @Nullable
    private static String getString(@NonNull Map<String, Object> map, @NonNull String key) {
        var val = map.get(key);
        return val != null ? val.toString() : null;
    }

    public static class List extends ArrayList<Language> {
        @SuppressWarnings("unchecked")
        public List(@NonNull Context ctx) {
            try {
                Map<String, Object> root = loadYAMLFromAssets(ctx, LANGUAGES_ASSET);
                if (root == null) throw new IllegalStateException("Failed to load languages");
                var entries = (ArrayList<Map<String, Object>>) root.get("languages");
                if (entries == null)
                    throw new IllegalStateException("No languages found in the data");
                for (var entry : entries) {
                    var item = new Language();
                    item.tag = getString(entry, "tag");
                    item.name = getString(entry, "name");
                    if (item.tag != null && item.name != null)
                        add(item);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to load languages from asset", e);
            }
        }

        @NonNull
        public String[] getTagsArray() {
            var arr = new String[size() + 1];
            arr[0] = "";
            for (int i = 0; i < size(); i++)
                arr[i + 1] = get(i).getTag();
            return arr;
        }

        @NonNull
        public String[] getNamesArray() {
            var arr = new String[size() + 1];
            arr[0] = "";
            for (int i = 0; i < size(); i++)
                arr[i + 1] = get(i).getName();
            return arr;
        }
    }
}
