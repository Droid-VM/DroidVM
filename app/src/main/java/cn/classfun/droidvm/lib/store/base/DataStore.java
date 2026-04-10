package cn.classfun.droidvm.lib.store.base;

import static cn.classfun.droidvm.lib.utils.FileUtils.loadJSONFile;
import static cn.classfun.droidvm.lib.utils.FileUtils.saveJSONFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

import cn.classfun.droidvm.lib.utils.JsonUtils;

public abstract class DataStore<T extends DataConfig> implements JSONSerialize {
    protected static final String TAG = "DataStore";
    protected final List<T> dataMap = new ArrayList<>();
    protected final Map<UUID, Integer> idMap = new HashMap<>();
    protected final Map<String, UUID> nameMap = new HashMap<>();
    protected final ReentrantLock ioLock = new ReentrantLock();

    public DataStore() {
    }

    public DataStore(@NonNull JSONObject obj) {
        if (!load(this, obj))
            throw new IllegalArgumentException("Failed to load data from JSON");
    }

    public DataStore(@NonNull File file) {
        if (!load(this, file)) throw new IllegalArgumentException(fmt(
            "Failed to load data from file: %s",
            file.getAbsolutePath()
        ));
    }

    public DataStore(@NonNull Context context) {
        if (!load(this, context))
            throw new IllegalArgumentException(fmt("Failed to load data from default path"));
    }

    protected boolean load(@NonNull DataStore<T> store, @NonNull JSONObject obj) {
        try {
            store.clear();
            JsonUtils.forEachArray(obj, getTypeName(), store::addObject);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load data config", e);
            store.clear();
            return false;
        }
    }

    protected final boolean load(@NonNull DataStore<T> store, @NonNull File file) {
        try {
            if (!file.exists()) {
                Log.w(TAG, fmt("Data config file does not exist: %s", file.getAbsolutePath()));
                return false;
            }
            return load(store, loadJSONFile(file));
        } catch (Exception e) {
            Log.e(TAG, fmt("Failed to load data config from %s", file.getAbsolutePath()), e);
            return false;
        }
    }

    public final boolean load(@NonNull DataStore<T> store, @NonNull Context context) {
        return load(store, getStoreFile(context));
    }

    public final synchronized boolean load(@NonNull JSONObject obj) {
        try {
            var store = createEmpty();
            if (!load(store, obj))
                return false;
            replace(store);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse data config", e);
            return false;
        }
    }

    public final boolean load(@NonNull File file) {
        ioLock.lock();
        try {
            if (!file.exists()) {
                Log.w(TAG, fmt("Data config file does not exist: %s", file.getAbsolutePath()));
                return false;
            }
            return load(loadJSONFile(file));
        } catch (Exception e) {
            Log.e(TAG, fmt("Failed to load data config from %s", file.getAbsolutePath()), e);
            return false;
        } finally {
            ioLock.unlock();
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public final boolean load(@NonNull Context context) {
        return load(getStoreFile(context));
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var obj = new JSONObject();
        var arr = new JSONArray();
        for (var d : dataMap)
            arr.put(d.toJson());
        obj.put(getTypeName(), arr);
        return obj;
    }

    @Nullable
    public final JSONObject dump() {
        try {
            return toJson();
        } catch (Exception e) {
            Log.e(TAG, "Failed to dump store", e);
            return null;
        }
    }

    public final boolean save(@NonNull File file) {
        ioLock.lock();
        try {
            var data = dump();
            if (data == null) return false;
            saveJSONFile(file, data);
            return true;
        } catch (Exception e) {
            Log.e(TAG, fmt("Failed to save store to %s", file.getAbsolutePath()), e);
            return false;
        } finally {
            ioLock.unlock();
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public final boolean save(@NonNull Context context) {
        return save(getStoreFile(context));
    }

    public final void addObject(@NonNull JSONObject obj) throws JSONException {
        add(create(obj));
    }

    public final synchronized void add(@NonNull T config) {
        if (config.getId() == null)
            config.setId(UUID.randomUUID());
        if (config.getName() == null)
            throw new IllegalArgumentException("Config name cannot be null");
        if (idMap.containsKey(config.getId()))
            throw new IllegalArgumentException(fmt("Config ID already exists: %s", config.getId()));
        if (!isNameUnique(config.getName()))
            throw new IllegalArgumentException(fmt("Config name already exists: %s", config.getName()));
        if (!dataMap.add(config))
            throw new IllegalStateException("Failed to add config to data map");
        refreshIndex();
    }

    public final synchronized void update(@NonNull T config) {
        if (config.getId() == null)
            throw new IllegalArgumentException("Config ID cannot be null");
        if (!isNameUnique(config.getName(), List.of(config.getId())))
            throw new IllegalArgumentException(fmt("Config name already exists: %s", config.getName()));
        var idx = idMap.get(config.getId());
        if (idx == null)
            throw new IllegalStateException(fmt("Config does not exists: %s", config.getId()));
        dataMap.set(idx, config);
        refreshIndex();
    }

    public final void refreshIndex() {
        idMap.clear();
        nameMap.clear();
        for (int i = 0; i < dataMap.size(); i++) {
            var config = dataMap.get(i);
            idMap.put(config.getId(), i);
            nameMap.put(config.getName(), config.getId());
        }
    }

    public final T get(int pos) {
        if (pos < 0 || pos >= dataMap.size())
            throw new IndexOutOfBoundsException(fmt("Position out of bounds: %d", pos));
        return dataMap.get(pos);
    }

    public final int size() {
        return dataMap.size();
    }

    @SuppressWarnings("unused")
    public final int findIndexByConfig(@NonNull T config) {
        var idx = idMap.get(config.getId());
        if (idx == null)
            throw new IllegalStateException(fmt("Config does not exists: %s", config.getId()));
        return idx;
    }

    @Nullable
    public final T findById(@NonNull UUID id) {
        var idx = idMap.get(id);
        if (idx == null) return null;
        return dataMap.get(idx);
    }

    @Nullable
    public final T findById(@NonNull String id) {
        try {
            return findById(UUID.fromString(id));
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public final T findByName(@NonNull String name) {
        var id = nameMap.get(name);
        if (id == null) return null;
        return findById(id);
    }

    public final synchronized void removeById(@NonNull UUID id) {
        if (idMap.containsKey(id)) {
            var idx = idMap.get(id);
            if (idx == null) return;
            dataMap.remove(idx.intValue());
            refreshIndex();
        }
    }

    public final void forEach(@NonNull BiConsumer<UUID, T> consumer) {
        dataMap.forEach(config -> consumer.accept(config.getId(), config));
    }

    public final boolean isEmpty() {
        return dataMap.isEmpty();
    }

    public final void clear() {
        dataMap.clear();
        idMap.clear();
        nameMap.clear();
    }

    public final synchronized void replace(@NonNull DataStore<T> store) {
        clear();
        store.dataMap.forEach(this::add);
    }

    public final boolean isNameUnique(String name) {
        return isNameUnique(name, (UUID) null);
    }

    public final boolean isNameUnique(String name, List<UUID> excludes) {
        if (name == null) return false;
        for (var item : dataMap) {
            if (excludes != null && excludes.contains(item.getId()))
                continue;
            if (name.equals(item.getName())) return false;
        }
        return true;
    }

    public final boolean isNameUnique(String name, UUID exclude) {
        List<UUID> excludes = null;
        if (exclude != null)
            excludes = List.of(exclude);
        return isNameUnique(name, excludes);
    }

    @NonNull
    public String getFileName() {
        return fmt("%s.json", getTypeName());
    }

    @NonNull
    public File getStoreFile(@NonNull Context context) {
        return new File(context.getFilesDir(), getFileName());
    }

    @Override
    public final int hashCode() {
        return dataMap.hashCode();
    }

    @Override
    public final boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        var other = (DataStore<?>) obj;
        return dataMap.equals(other.dataMap);
    }

    @NonNull
    @SuppressWarnings("unused")
    protected abstract T create();

    @NonNull
    @SuppressWarnings("unused")
    protected abstract T create(@NonNull JSONObject obj) throws JSONException;

    @NonNull
    @SuppressWarnings("unused")
    protected abstract DataStore<T> createEmpty();

    @NonNull
    @SuppressWarnings("unused")
    protected abstract String getTypeName();

    @NonNull
    @Override
    public String toString() {
        try {
            return toJson().toString();
        } catch (Exception ignored) {
            return super.toString();
        }
    }
}
