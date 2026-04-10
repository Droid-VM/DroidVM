package cn.classfun.droidvm.lib.data;

import static android.os.Build.SUPPORTED_ABIS;
import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.utils.AssetUtils.loadYAMLFromAssets;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.lib.api.ApiManager;
import cn.classfun.droidvm.lib.utils.JsonUtils;

public final class Images {
    private static final String TAG = "Images";
    private static final String IMAGES_ASSET = "data/images.yaml";
    private final List<ImageRepo> repos;

    @SuppressWarnings("unchecked")
    private Images(@NonNull Map<String, Object> map) {
        var list = new ArrayList<ImageRepo>();
        for (var entry : map.entrySet()) {
            var val = entry.getValue();
            if (val instanceof Map)
                list.add(new ImageRepo(entry.getKey(), (Map<String, Object>) val));
        }
        repos = list;
    }

    private Images(@NonNull JSONObject obj) throws JSONException {
        repos = JsonUtils.objectToList(obj, ImageRepo::create);
    }

    @NonNull
    @SuppressWarnings("unused")
    public List<ImageRepo> getRepos() {
        return repos;
    }

    @Nullable
    @SuppressWarnings("unused")
    public static Images loadYAML(@NonNull Context ctx) {
        try {
            Map<String, Object> root = loadYAMLFromAssets(ctx, IMAGES_ASSET);
            if (root == null) return null;
            return loadYAML(root);
        } catch (Exception e) {
            Log.e(TAG, fmt("Failed to load %s", IMAGES_ASSET), e);
            return null;
        }
    }

    @Nullable
    @SuppressWarnings({"unchecked", "unused"})
    public static Images loadYAML(@NonNull Map<String, Object> yaml) {
        try {
            var imagesMap = (Map<String, Object>) yaml.get("images");
            if (imagesMap == null) return null;
            return new Images(imagesMap);
        } catch (Exception e) {
            Log.e(TAG, fmt("Failed to load %s", IMAGES_ASSET), e);
            return null;
        }
    }

    @Nullable
    @SuppressWarnings("unused")
    public static Images loadJSON(@NonNull JSONObject json) {
        try {
            var imagesObj = json.optJSONObject("images");
            if (imagesObj == null) return null;
            return new Images(imagesObj);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load images from JSON", e);
            return null;
        }
    }

    @Nullable
    @SuppressWarnings("unused")
    public static Images load(@NonNull Context ctx) {
        Images ret;
        try {
            var api = ApiManager.create(ctx);
            var json = api.fetchApiWithVersion("dl_images_metadata");
            if ((ret = loadJSON(json)) == null)
                throw new IllegalStateException("Failed to parse images metadata from api");
            if (ret.repos.isEmpty())
                throw new IllegalStateException("No items found in the api images data");
            return ret;
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch images metadata from api, fallback to asset", e);
        }
        try {
            if ((ret = loadYAML(ctx)) == null)
                throw new IllegalStateException("Failed to parse images metadata from asset");
            if (ret.repos.isEmpty())
                throw new IllegalStateException("No items found in the asset images data");
            return ret;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load images metadata from asset", e);
        }
        return null;
    }

    public static final class ImageRepo {
        private final String id;
        private final String url;
        private final List<Image> images;
        private final Map<String, String> name;

        @SuppressWarnings("unchecked")
        private ImageRepo(@NonNull String id, @NonNull Map<String, Object> map) {
            this.id = id;
            this.url = (String) requireNonNull(map.get("url"));
            this.name = (Map<String, String>) requireNonNull(map.get("name"));
            this.images = new ArrayList<>();
            var imageList = (List<Map<String, Object>>) map.get("images");
            if (imageList != null)
                for (var imgMap : imageList)
                    images.add(new Image(imgMap));
        }

        private ImageRepo(
            @NonNull String id,
            @NonNull JSONObject obj
        ) throws JSONException {
            this.id = id;
            this.url = obj.getString("url");
            this.name = JsonUtils.objectToStringMap(obj, "name");
            this.images = JsonUtils.arrayToList(obj, "images", this::createImage);
        }

        @NonNull
        private static ImageRepo create(
            @NonNull String id,
            @NonNull JSONObject obj
        ) throws JSONException {
            return new ImageRepo(id, obj);
        }

        @NonNull
        private Image createImage(JSONObject jo) throws JSONException {
            return new Image(jo);
        }

        @NonNull
        @SuppressWarnings("unused")
        public String getId() {
            return id;
        }

        @NonNull
        @SuppressWarnings("unused")
        public String getName() {
            return JsonUtils.getMultiLanguageString(name);
        }

        @NonNull
        @SuppressWarnings("unused")
        public String getUrl() {
            return url;
        }

        @NonNull
        @SuppressWarnings("unused")
        public List<Image> getImages() {
            return images;
        }

        public final class Image {
            private final String path;
            private final String arch;
            private final long mtime;
            private final long size;

            private Image(@NonNull Map<String, Object> map) {
                this.path = (String) requireNonNull(map.get("path"));
                this.arch = (String) requireNonNull(map.get("arch"));
                this.size = ((Number) requireNonNull(map.get("size"))).longValue();
                this.mtime = ((Number) requireNonNull(map.get("mtime"))).longValue();
            }

            private Image(@NonNull JSONObject obj) throws JSONException {
                this.path = obj.getString("path");
                this.arch = obj.getString("arch");
                this.size = obj.getLong("size");
                this.mtime = obj.getLong("mtime");
            }

            @NonNull
            @SuppressWarnings("unused")
            public String getPath() {
                return path;
            }

            @NonNull
            @SuppressWarnings("unused")
            public String getArch() {
                return arch;
            }

            @SuppressWarnings("unused")
            public boolean isCompatible() {
                for (var abi : SUPPORTED_ABIS) {
                    if (abi.equalsIgnoreCase(arch)) return true;
                    if (abi.equalsIgnoreCase("arm64-v8a") && arch.equals("aarch64")) return true;
                }
                return false;
            }

            @SuppressWarnings("unused")
            public long getModifiedTimestamp() {
                return mtime;
            }

            @NonNull
            @SuppressWarnings("unused")
            public Date getModifiedDate() {
                return new Date(mtime * 1000);
            }

            @SuppressWarnings("unused")
            public long getSize() {
                return size;
            }

            public ImageRepo getRepo() {
                return ImageRepo.this;
            }

            @NonNull
            @SuppressWarnings("unused")
            public String getUrl() {
                return pathJoin(
                    ImageRepo.this.getUrl(),
                    Image.this.getPath()
                );
            }

            @NonNull
            @SuppressWarnings("unused")
            public String getUrl(@Nullable Repos.Mirror mirror) {
                if (mirror == null) return getUrl();
                var mirrorUrl = mirror.getRepoUrl(ImageRepo.this.getId());
                if (mirrorUrl == null) return getUrl();
                return pathJoin(
                    mirrorUrl,
                    Image.this.getPath()
                );
            }
        }
    }
}
