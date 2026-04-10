package cn.classfun.droidvm.lib.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class JsonUtils {
    @NonNull
    @SuppressWarnings("unused")
    public static String getMultiLanguageString(@NonNull Map<String, String> strings) {
        String text;
        if (strings.isEmpty()) return "";
        var locale = Locale.getDefault();
        var tag = locale.toLanguageTag();
        if (!tag.isEmpty()) {
            text = strings.get(tag.toLowerCase());
            if (text != null && !text.isEmpty()) return text.trim();
        }
        var lang = locale.getLanguage();
        if (!lang.isEmpty()) {
            text = strings.get(lang.toLowerCase());
            if (text != null && !text.isEmpty()) return text.trim();
        }
        text = strings.get("default");
        if (text != null && !text.isEmpty()) return text.trim();
        text = strings.get("en-us");
        if (text != null && !text.isEmpty()) return text.trim();
        text = strings.get("en");
        if (text != null && !text.isEmpty()) return text.trim();
        for (var val : strings.values())
            if (val != null && !val.isEmpty()) return val.trim();
        return "";
    }

    @NonNull
    @SuppressWarnings("unused")
    public static String getMultiLanguageString(
        @NonNull JSONObject strings
    ) throws JSONException {
        return getMultiLanguageString(objectToStringMap(strings));
    }

    @SuppressWarnings("unchecked")
    public static <T> void forEachObject(
        @NonNull JSONObject obj,
        @NonNull KeyConsumer<T> cb
    ) throws JSONException {
        var keys = obj.keys();
        while (keys.hasNext()) {
            var key = keys.next();
            cb.invoke(key, (T) obj.get(key));
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> void forEachArray(
        @NonNull JSONArray arr,
        @NonNull IndexConsumer<T> cb
    ) throws JSONException {
        for (int i = 0; i < arr.length(); i++)
            cb.invoke(i, (T) arr.get(i));
    }

    @SuppressWarnings("unused")
    public static <T> void forEachObject(
        @NonNull JSONObject obj,
        @NonNull String key,
        @NonNull KeyConsumer<T> cb
    ) throws JSONException {
        forEachObject(obj.getJSONObject(key), cb);
    }

    @SuppressWarnings("unused")
    public static <T> void forEachArray(
        @NonNull JSONObject obj,
        @NonNull String key,
        @NonNull IndexConsumer<T> cb
    ) throws JSONException {
        forEachArray(obj.getJSONArray(key), cb);
    }

    @SuppressWarnings("unused")
    public static <T> void forEachObject(
        @NonNull JSONObject obj,
        @NonNull SimpleConsumer<T> cb
    ) throws JSONException {
        forEachObject(obj, (String k, T v) -> cb.invoke(v));
    }

    @SuppressWarnings("unused")
    public static <T> void forEachArray(
        @NonNull JSONArray arr,
        @NonNull SimpleConsumer<T> cb
    ) throws JSONException {
        forEachArray(arr, (int i, T v) -> cb.invoke(v));
    }

    @SuppressWarnings("unused")
    public static <T> void forEachObject(
        @NonNull JSONObject obj,
        @NonNull String key,
        @NonNull SimpleConsumer<T> cb
    ) throws JSONException {
        forEachObject(obj.getJSONObject(key), cb);
    }

    @SuppressWarnings("unused")
    public static <T> void forEachArray(
        @NonNull JSONObject obj,
        @NonNull String key,
        @NonNull SimpleConsumer<T> cb
    ) throws JSONException {
        forEachArray(obj.getJSONArray(key), cb);
    }

    @SuppressWarnings("unused")
    public static void mergeJSONObject(
        @NonNull JSONObject target,
        @NonNull JSONObject source
    ) throws JSONException {
        forEachObject(source, target::put);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static Map<String, String> objectToStringMap(
        @NonNull JSONObject obj
    ) throws JSONException {
        return objectToMap(obj, (s, v) -> {
            if (v instanceof String)
                return (String) v;
            return null;
        });
    }

    @NonNull
    @SuppressWarnings("unused")
    public static Map<String, String> objectToStringMap(
        @NonNull JSONObject obj,
        @NonNull String key
    ) throws JSONException {
        return objectToStringMap(obj.getJSONObject(key));
    }

    @NonNull
    @SuppressWarnings("unused")
    public static <T, V> Map<String, V> objectToMap(
        @NonNull JSONObject obj,
        @NonNull ObjectValueMapper<T, V> cb
    ) throws JSONException {
        var map = new HashMap<String, V>();
        forEachObject(obj, (String k, T v) -> map.put(k, cb.invoke(k, v)));
        return map;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static <T, V> Map<String, V> objectToMap(
        @NonNull JSONObject obj,
        @NonNull String key,
        @NonNull ObjectValueMapper<T, V> cb
    ) throws JSONException {
        return objectToMap(obj.getJSONObject(key), cb);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static <T, V> List<V> objectToList(
        @NonNull JSONObject obj,
        @NonNull ObjectValueMapper<T, V> cb
    ) throws JSONException {
        var list = new ArrayList<V>();
        forEachObject(obj, (String k, T v) -> list.add(cb.invoke(k, v)));
        return list;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static <T, V> List<V> objectToList(
        @NonNull JSONObject obj,
        @NonNull String key,
        @NonNull ObjectValueMapper<T, V> cb
    ) throws JSONException {
        return objectToList(obj.getJSONObject(key), cb);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static <T, V> Map<String, V> objectToMap(
        @NonNull JSONObject obj,
        @NonNull ValueMapper<T, V> cb
    ) throws JSONException {
        var map = new HashMap<String, V>();
        forEachObject(obj, (String k, T v) -> map.put(k, cb.invoke((T) v)));
        return map;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static <T, V> Map<String, V> objectToMap(
        @NonNull JSONObject obj,
        @NonNull String key,
        @NonNull ValueMapper<T, V> cb
    ) throws JSONException {
        return objectToMap(obj.getJSONObject(key), cb);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static <T, V> List<V> objectToList(
        @NonNull JSONObject obj,
        @NonNull ValueMapper<T, V> cb
    ) throws JSONException {
        var list = new ArrayList<V>();
        forEachObject(obj, (T v) -> list.add(cb.invoke(v)));
        return list;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static <T, V> List<V> objectToList(
        @NonNull JSONObject obj,
        @NonNull String key,
        @NonNull ValueMapper<T, V> cb
    ) throws JSONException {
        return objectToList(obj.getJSONObject(key), cb);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static <T, V> List<V> arrayToList(
        @NonNull JSONArray obj,
        @NonNull ArrayValueMapper<T, V> cb
    ) throws JSONException {
        var list = new ArrayList<V>();
        forEachArray(obj, (int i, T v) -> list.add(cb.invoke(i, v)));
        return list;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static <T, V> List<V> arrayToList(
        @NonNull JSONObject obj,
        @NonNull String key,
        @NonNull ValueMapper<T, V> cb
    ) throws JSONException {
        return arrayToList(obj.getJSONArray(key), cb);
    }

    @NonNull
    @SuppressWarnings("unused")
    public static <T, V> List<V> arrayToList(
        @NonNull JSONArray obj,
        @NonNull ValueMapper<T, V> cb
    ) throws JSONException {
        var list = new ArrayList<V>();
        forEachArray(obj, (T v) -> list.add(cb.invoke(v)));
        return list;
    }

    @NonNull
    @SuppressWarnings("unused")
    public static <T, V> List<V> arrayToList(
        @NonNull JSONObject obj,
        @NonNull String key,
        @NonNull ArrayValueMapper<T, V> cb
    ) throws JSONException {
        return arrayToList(obj.getJSONArray(key), cb);
    }

    public interface ObjectValueMapper<T, R> {
        @Nullable
        @SuppressWarnings({"RedundantThrows", "unused"})
        R invoke(@NonNull String key, @NonNull T value) throws JSONException;
    }

    public interface ArrayValueMapper<T, R> {
        @Nullable
        @SuppressWarnings({"RedundantThrows", "unused"})
        R invoke(int index, @NonNull T value) throws JSONException;
    }

    public interface ValueMapper<T, R> {
        @Nullable
        @SuppressWarnings({"RedundantThrows", "unused"})
        R invoke(@NonNull T value) throws JSONException;
    }

    public interface KeyConsumer<T> {
        @SuppressWarnings({"RedundantThrows", "unused"})
        void invoke(@NonNull String key, @NonNull T value) throws JSONException;
    }

    public interface IndexConsumer<T> {
        @SuppressWarnings({"RedundantThrows", "unused"})
        void invoke(int index, @NonNull T value) throws JSONException;
    }

    public interface SimpleConsumer<T> {
        @SuppressWarnings({"RedundantThrows", "unused"})
        void invoke(@NonNull T value) throws JSONException;
    }
}
