package cn.classfun.droidvm.lib.data;

import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.utils.AssetUtils.loadYAMLFromAssets;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.lib.api.ApiManager;
import cn.classfun.droidvm.lib.utils.JsonUtils;

public final class Repos {
    private static final String TAG = "Repos";
    private static final String REPO_ASSET = "data/repo.yaml";
    private final RepoList repos;
    private final MirrorList mirrors;

    @SuppressWarnings("unchecked")
    public Repos(@NonNull Map<String, Object> root) {
        var reposMap = (Map<String, Object>) root.get("repos");
        var mirrorsMap = (Map<String, Object>) root.get("mirrors");
        if (reposMap == null)
            throw new IllegalStateException("No repos found in the data");
        if (mirrorsMap == null)
            throw new IllegalStateException("No mirrors found in the data");
        repos = new RepoList(reposMap);
        mirrors = new MirrorList(mirrorsMap);
    }

    public Repos(@NonNull JSONObject json) throws JSONException {
        var reposObj = json.getJSONObject("repos");
        var mirrorsObj = json.getJSONObject("mirrors");
        repos = new RepoList(reposObj);
        mirrors = new MirrorList(mirrorsObj);
    }

    @Nullable
    public static Repos loadYAML(@NonNull Context ctx) {
        try {
            Map<String, Object> root = loadYAMLFromAssets(ctx, REPO_ASSET);
            if (root == null) return null;
            return new Repos(root);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load repo from asset", e);
            return null;
        }
    }

    @Nullable
    public static Repos loadJSON(@NonNull JSONObject json) {
        try {
            return new Repos(json);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load repo from JSON", e);
            return null;
        }
    }

    @Nullable
    public static Repos load(@NonNull Context ctx) {
        Repos ret;
        try {
            var api = ApiManager.create(ctx);
            var json = api.fetchApiWithVersion("software_mirror_metadata");
            if ((ret = loadJSON(json)) == null)
                throw new IllegalStateException("Failed to parse repo metadata from api");
            if (ret.repos.isEmpty() || ret.mirrors.isEmpty())
                throw new IllegalStateException("No items found in the api repo data");
            return ret;
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch repo metadata from api, fallback to asset", e);
        }
        try {
            if ((ret = loadYAML(ctx)) == null)
                throw new IllegalStateException("Failed to parse repo metadata from asset");
            if (ret.repos.isEmpty() || ret.mirrors.isEmpty())
                throw new IllegalStateException("No items found in the asset repo data");
            return ret;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load repo metadata from asset", e);
        }
        return null;
    }

    @NonNull
    public RepoList getRepo() {
        return repos;
    }

    @NonNull
    public MirrorList getMirror() {
        return mirrors;
    }

    @NonNull
    @SuppressWarnings("unused")
    public String findMirrorUrlFor(@NonNull String url, @NonNull String mirror) {
        if (mirror.isEmpty() || mirror.equals("official"))
            return url;
        var mirrorInfo = mirrors.get(mirror);
        if (mirrorInfo == null) return url;
        var repoInfo = repos.find(url);
        if (repoInfo == null) return url;
        var mirrorUrl = mirrorInfo.getRepoUrl(repoInfo.id);
        if (mirrorUrl == null) return url;
        var suffix = url.substring(repoInfo.url.length());
        return pathJoin(mirrorUrl, suffix);
    }

    @NonNull
    @SuppressWarnings("unused")
    public List<Mirror> getMirrorsFor(@NonNull String url) {
        var repoInfo = repos.find(url);
        if (repoInfo == null) return new ArrayList<>();
        return mirrors.getMirrorsByRepoId(repoInfo.id);
    }

    @NonNull
    @SuppressWarnings("unused")
    public List<String> listMirrorsFor(@NonNull String url) {
        var result = new ArrayList<String>();
        getMirrorsFor(url).forEach(mirror -> result.add(mirror.getId()));
        return result;
    }

    public final class Repo {
        private String id;
        private String url;
        private Map<String, String> name = new HashMap<>();

        private Repo() {
        }

        private Repo(@NonNull String id, @NonNull JSONObject obj) throws JSONException {
            this.id = id;
            this.url = obj.getString("url");
            this.name = JsonUtils.objectToStringMap(obj, "name");
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
        public String getId() {
            return id;
        }

        @NonNull
        @SuppressWarnings("unused")
        public List<Mirror> getMirrors() {
            return Repos.this.getMirror().getMirrorsByRepoId(id);
        }

        @Nullable
        @SuppressWarnings("unused")
        public Mirror getMirror(@NonNull String id) {
            var mirror = mirrors.get(id);
            if (mirror == null)
                return null;
            if (!mirror.hasRepo(this.id))
                return null;
            return mirror;
        }
    }

    public final class RepoList extends HashMap<String, Repo> {
        @SuppressWarnings("unchecked")
        private RepoList(@NonNull Map<String, Object> repo) {
            for (var entry : repo.entrySet()) {
                var val = entry.getValue();
                if (!(val instanceof Map)) continue;
                var map = (Map<String, Object>) val;
                var item = new Repo();
                item.id = entry.getKey();
                var name = map.get("name");
                var url = map.get("url");
                if (name != null) item.name = (Map<String, String>) name;
                if (url != null) item.url = url.toString();
                put(item.id, item);
            }
        }

        private RepoList(@NonNull JSONObject obj) throws JSONException {
            JsonUtils.forEachObject(obj, (String key, JSONObject val) -> {
                var item = new Repo(key, val);
                put(key, item);
            });
        }

        @Nullable
        @SuppressWarnings("unused")
        public Repo find(@NonNull String url) {
            int matchLen = 0;
            Repo bestMatch = null;
            for (var repo : values()) {
                if (url.startsWith(repo.url) && repo.url.length() > matchLen) {
                    bestMatch = repo;
                    matchLen = repo.url.length();
                }
            }
            return bestMatch;
        }
    }

    public final class Mirror {
        private String id;
        private String region;
        private String url;
        private Map<String, String> name = new HashMap<>();
        private Map<String, String> repoMap = new HashMap<>();

        private Mirror() {
        }

        private Mirror(@NonNull String id, @NonNull JSONObject obj) throws JSONException {
            this.id = id;
            this.region = obj.getString("region");
            this.url = obj.getString("url");
            this.name = JsonUtils.objectToStringMap(obj, "name");
            this.repoMap = JsonUtils.objectToStringMap(obj, "repos");
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
        public String getId() {
            return id;
        }

        @NonNull
        @SuppressWarnings("unused")
        public String getRegion() {
            return region;
        }

        @NonNull
        @SuppressWarnings("unused")
        public Map<String, String> getRepoMap() {
            return repoMap;
        }

        @SuppressWarnings("unused")
        public boolean hasRepo(@NonNull String id) {
            return repoMap.containsKey(id);
        }

        @Nullable
        @SuppressWarnings("unused")
        public String getRepoUrl(@NonNull String id) {
            if (!repoMap.containsKey(id)) return null;
            return pathJoin(url, requireNonNull(repoMap.get(id)));
        }

        @Nullable
        @SuppressWarnings("unused")
        public String getRepoUrl(@NonNull Repo id) {
            return getRepoUrl(id.id);
        }

        @NonNull
        @SuppressWarnings("unused")
        public List<Repo> getRepos() {
            var result = new ArrayList<Repo>();
            for (var repoId : repoMap.keySet()) {
                var repo = repos.get(repoId);
                if (repo != null) result.add(repo);
            }
            return result;
        }
    }

    public final class MirrorList extends HashMap<String, Mirror> {
        @SuppressWarnings("unchecked")
        private MirrorList(@NonNull Map<String, Object> mirror) {
            for (var entry : mirror.entrySet()) {
                var val = entry.getValue();
                if (!(val instanceof Map)) continue;
                var map = (Map<String, Object>) val;
                var item = new Mirror();
                item.id = entry.getKey();
                var name = map.get("name");
                var region = map.get("region");
                var url = map.get("url");
                if (name != null) item.name = (Map<String, String>) name;
                if (region != null) item.region = region.toString();
                if (url != null) item.url = url.toString();
                var repos = map.get("repos");
                if (repos instanceof Map) {
                    var reposMap = (Map<String, Object>) repos;
                    for (var re : reposMap.entrySet())
                        if (re.getValue() != null)
                            item.repoMap.put(re.getKey(), re.getValue().toString());
                }
                put(item.id, item);
            }
        }

        private MirrorList(@NonNull JSONObject obj) throws JSONException {
            JsonUtils.forEachObject(obj, (String key, JSONObject val) ->
                put(key, new Mirror(key, val)));
        }

        @NonNull
        @SuppressWarnings("unused")
        public ArrayList<Mirror> getMirrorsByRepoId(@NonNull String id) {
            var result = new ArrayList<Mirror>();
            for (var mirror : values())
                if (mirror.hasRepo(id))
                    result.add(mirror);
            return result;
        }
    }
}
