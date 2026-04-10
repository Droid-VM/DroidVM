package cn.classfun.droidvm.lib.crypt;

import static cn.classfun.droidvm.lib.utils.FileUtils.loadJSONFile;
import static cn.classfun.droidvm.lib.utils.FileUtils.saveJSONFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.dirname;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.JSONSerialize;
import cn.classfun.droidvm.lib.utils.JsonUtils;

public final class HashFile extends HashMap<String, HashItem> implements JSONSerialize {
    private static final String TAG = "HashFile";

    public HashFile() {
        super();
    }

    @SuppressWarnings("unused")
    public HashFile(@NonNull JSONObject obj) throws JSONException {
        super();
        JsonUtils.forEachArray(obj, "hash", this::putObject);
    }

    @SuppressWarnings("unused")
    public HashFile(@NonNull File file) throws JSONException, IOException {
        this(loadJSONFile(file));
    }

    @SuppressWarnings("unused")
    public HashFile(@NonNull File file, @NonNull String path) throws JSONException, IOException {
        this(loadJSONFile(file, path));
    }

    @SuppressWarnings("unused")
    public HashFile(@NonNull String file, @NonNull String path) throws JSONException, IOException {
        this(loadJSONFile(file, path));
    }

    @SuppressWarnings("unused")
    public HashFile(@NonNull String file) throws JSONException, IOException {
        this(loadJSONFile(file));
    }

    @SuppressWarnings("unused")
    public void putObject(
        @NonNull JSONObject obj
    ) {
        put(new HashItem(obj));
    }

    @SuppressWarnings("unused")
    public void put(
        @NonNull HashItem item
    ) {
        put(item.file, item);
    }

    @SuppressWarnings("unused")
    public void put(
        @NonNull String file,
        @Nullable String source,
        @NonNull String sha256
    ) {
        put(new HashItem(file, source, sha256));
    }

    @SuppressWarnings("unused")
    public void put(
        @NonNull String file,
        @NonNull String sha256
    ) {
        put(new HashItem(file, sha256));
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var obj = new JSONObject();
        var arr = new JSONArray();
        for (var item : values())
            arr.put(item.toJson());
        obj.put("hash", arr);
        return obj;
    }

    @SuppressWarnings("unused")
    public void save(@NonNull File file) throws JSONException, IOException {
        saveJSONFile(file, toJson());
    }

    @SuppressWarnings("unused")
    public void save(@NonNull String file) throws JSONException, IOException {
        saveJSONFile(file, toJson());
    }

    @SuppressWarnings("unused")
    public void save(@NonNull String parent, @NonNull String child) throws JSONException, IOException {
        saveJSONFile(parent, child, toJson());
    }

    public @NonNull String lookupSHA256(@NonNull String file) {
        var item = get(file);
        if (item == null) throw new RuntimeException(fmt("No hash item for %s", file));
        return item.sha256;
    }

    @NonNull
    @SuppressWarnings("unused")
    public List<String> toFileList(String folder) {
        var lst = new ArrayList<String>();
        if (folder == null) folder = "";
        if (!folder.isEmpty() && !folder.endsWith("/"))
            folder += "/";
        for (var item : values())
            lst.add(folder + item.file);
        return lst;
    }

    @NonNull
    @SuppressWarnings("unused")
    public List<String> toFolderList(String folder) {
        var lst = new ArrayList<String>();
        if (folder == null) folder = "";
        if (!folder.isEmpty() && !folder.endsWith("/"))
            folder += "/";
        for (var item : values()) {
            var path = folder + item.file;
            path = dirname(path);
            if (!lst.contains(path))
                lst.add(path);
        }
        return lst;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static HashFile loadOrCreate(@NonNull File file) {
        HashFile hashFile = null;
        if (file.exists()) try {
            hashFile = new HashFile(file);
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to load %s", file.getAbsolutePath()), e);
        }
        if (hashFile == null)
            hashFile = new HashFile();
        return hashFile;
    }
}
