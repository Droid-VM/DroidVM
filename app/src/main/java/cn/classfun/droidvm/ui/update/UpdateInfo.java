package cn.classfun.droidvm.ui.update;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

import cn.classfun.droidvm.lib.store.base.JSONSerialize;
import cn.classfun.droidvm.lib.utils.JsonUtils;

public final class UpdateInfo implements JSONSerialize {
    private final Map<String, String> changelog;
    private final String packageName;
    private final String version;
    private final int versionCode;
    private final String downloadUrl;
    private final String pageUrl;

    public UpdateInfo(@NonNull JSONObject obj) throws JSONException {
        if (!obj.optBoolean("success", false)) {
            var msg = obj.optString("message", "unknown");
            throw new RuntimeException(fmt("Update check failed: %s", msg));
        }
        this.packageName = obj.getString("package");
        this.version = obj.getString("version");
        this.versionCode = obj.getInt("version_code");
        this.changelog = JsonUtils.objectToStringMap(obj, "changelog");
        this.downloadUrl = obj.optString("download_url", "");
        this.pageUrl = obj.optString("page_url", "");
    }

    @Nullable
    @SuppressWarnings("unused")
    public String getPackageName() {
        return packageName;
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getVersion() {
        return version;
    }

    @SuppressWarnings("unused")
    public int getVersionCode() {
        return versionCode;
    }

    @NonNull
    @SuppressWarnings("unused")
    public Map<String, String> getChangelogs() {
        return changelog;
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getChangelog() {
        return JsonUtils.getMultiLanguageString(changelog);
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getDownloadUrl() {
        return downloadUrl;
    }

    @NonNull
    @SuppressWarnings("unused")
    public String getPageUrl() {
        return pageUrl;
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var obj = new JSONObject();
        obj.put("success", true);
        obj.put("package", packageName);
        obj.put("version", version);
        obj.put("version_code", versionCode);
        var cl = new JSONObject();
        for (var entry : changelog.entrySet())
            cl.put(entry.getKey(), entry.getValue());
        obj.put("changelog", cl);
        obj.put("download_url", downloadUrl);
        obj.put("page_url", pageUrl);
        return obj;
    }

    @NonNull
    @Override
    public String toString() {
        try {
            return toJson().toString();
        } catch (JSONException e) {
            return super.toString();
        }
    }
}
