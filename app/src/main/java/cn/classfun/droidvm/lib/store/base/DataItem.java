package cn.classfun.droidvm.lib.store.base;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import cn.classfun.droidvm.lib.utils.JsonUtils;

@SuppressWarnings("unused")
public final class DataItem implements JSONSerialize, Iterable<DataItem.Iter> {
    public enum Type {
        NULL,
        BOOLEAN,
        INTEGER,
        FLOAT,
        ARRAY,
        OBJECT,
        STRING,
    }

    private Type type = Type.NULL;
    private Object value = null;

    public DataItem() {
    }

    @SuppressWarnings("CopyConstructorMissesField")
    public DataItem(DataItem other) {
        set(other);
    }

    public DataItem(@Nullable Map<String, DataItem> obj) {
        set(obj);
    }

    public DataItem(@Nullable List<DataItem> arr) {
        set(arr);
    }

    public DataItem(boolean bool) {
        set(bool);
    }

    public DataItem(long integer) {
        set(integer);
    }

    public DataItem(double floating) {
        set(floating);
    }

    public DataItem(@Nullable String str) {
        set(str);
    }

    public DataItem(@Nullable JSONObject obj) throws JSONException {
        set(obj);
    }

    public DataItem(@Nullable JSONArray arr) throws JSONException {
        set(arr);
    }

    public boolean is(Type type) {
        return this.type == type;
    }

    public void check(Type expected) {
        if (type != expected) throw new IllegalStateException(fmt(
            "Expected type %s but got %s", expected, type
        ));
    }

    @NonNull
    @SuppressWarnings("unchecked")
    public List<DataItem> asArray() {
        check(Type.ARRAY);
        return (List<DataItem>) value;
    }

    @NonNull
    @SuppressWarnings("unchecked")
    public Map<String, DataItem> asObject() {
        check(Type.OBJECT);
        return (Map<String, DataItem>) value;
    }

    public boolean asBoolean() {
        check(Type.BOOLEAN);
        return (Boolean) value;
    }

    public long asInteger() {
        check(Type.INTEGER);
        return (Long) value;
    }

    public double asFloat() {
        check(Type.FLOAT);
        return (Double) value;
    }

    @NonNull
    public String asString() {
        check(Type.STRING);
        return (String) value;
    }

    @NonNull
    public DataItem get(@NonNull String key) {
        var ret = asObject().get(key);
        return ret != null ? ret : newNull();
    }

    @NonNull
    public DataItem get(int index) {
        var arr = asArray();
        if (index < 0 || index >= arr.size()) return newNull();
        var ret = arr.get(index);
        return ret != null ? ret : newNull();
    }

    public DataItem opt(@NonNull String key, @Nullable DataItem defaultValue) {
        if (!is(Type.OBJECT)) return defaultValue;
        var obj = asObject();
        return obj.getOrDefault(key, defaultValue);
    }

    public DataItem opt(int index, @Nullable DataItem defaultValue) {
        if (!is(Type.ARRAY)) return defaultValue;
        var arr = asArray();
        if (index < 0 || index >= arr.size()) return defaultValue;
        return arr.get(index);
    }

    public String optString(@NonNull String key, @Nullable String defaultValue) {
        var item = opt(key, null);
        if (item != null && item.is(Type.STRING)) return item.asString();
        return defaultValue;
    }

    public String optString(int index, @Nullable String defaultValue) {
        var item = opt(index, null);
        if (item != null && item.is(Type.STRING)) return item.asString();
        return defaultValue;
    }

    public boolean optBoolean(@NonNull String key, boolean defaultValue) {
        var item = opt(key, null);
        if (item != null && item.is(Type.BOOLEAN)) return item.asBoolean();
        return defaultValue;
    }

    public boolean optBoolean(int index, boolean defaultValue) {
        var item = opt(index, null);
        if (item != null && item.is(Type.BOOLEAN)) return item.asBoolean();
        return defaultValue;
    }

    public long optLong(@NonNull String key, long defaultValue) {
        var item = opt(key, null);
        if (item != null && item.is(Type.INTEGER)) return item.asInteger();
        return defaultValue;
    }

    public long optLong(int index, long defaultValue) {
        var item = opt(index, null);
        if (item != null && item.is(Type.INTEGER)) return item.asInteger();
        return defaultValue;
    }

    public double optDouble(@NonNull String key, double defaultValue) {
        var item = opt(key, null);
        if (item != null && item.is(Type.FLOAT)) return item.asFloat();
        return defaultValue;
    }

    public double optDouble(int index, double defaultValue) {
        var item = opt(index, null);
        if (item != null && item.is(Type.FLOAT)) return item.asFloat();
        return defaultValue;
    }

    public void insert(int index, @Nullable DataItem value) {
        check(Type.ARRAY);
        asArray().add(index, value);
    }

    public void append(@Nullable DataItem value) {
        check(Type.ARRAY);
        asArray().add(value);
    }

    public void remove(@NonNull String key) {
        check(Type.OBJECT);
        asObject().remove(key);
    }

    public void remove(int index) {
        check(Type.ARRAY);
        asArray().remove(index);
    }

    public void clear() {
        if (is(Type.OBJECT)) asObject().clear();
        else if (is(Type.ARRAY)) asArray().clear();
        else set();
    }

    public void puts(@NonNull Map<String, DataItem> entries) {
        check(Type.OBJECT);
        asObject().putAll(entries);
    }

    public void puts(@NonNull List<DataItem> items) {
        check(Type.ARRAY);
        asArray().addAll(items);
    }

    public void puts(@NonNull DataItem other) {
        if (other.is(Type.OBJECT)) {
            puts(other.asObject());
        } else if (other.is(Type.ARRAY)) {
            puts(other.asArray());
        }
    }

    public void set(@NonNull String key, @Nullable DataItem value) {
        check(Type.OBJECT);
        asObject().put(key, new DataItem(value));
    }

    public void set(int index, @Nullable DataItem value) {
        check(Type.ARRAY);
        asArray().set(index, new DataItem(value));
    }

    public <E extends Enum<E>> void set(@NonNull String key, @Nullable Enum<E> value) {
        if (value == null) set(key, newNull());
        else set(key, value.name().toLowerCase());
    }

    public void set(@NonNull String key, @Nullable Object value) {
        set(key, from(value));
    }

    public void set(int index, @Nullable Object value) {
        set(index, from(value));
    }

    public void set(@NonNull Type type, @Nullable Object value) {
        boolean valid = false;
        switch (type) {
            case NULL:
                valid = value == null;
                break;
            case BOOLEAN:
                valid = value instanceof Boolean;
                break;
            case INTEGER:
                if (value instanceof Integer)
                    value = ((Integer) value).longValue();
                valid = value instanceof Long;
                break;
            case FLOAT:
                if (value instanceof Float)
                    value = ((Float) value).doubleValue();
                valid = value instanceof Double;
                break;
            case ARRAY:
                if (value instanceof List) {
                    var list = (List<?>) value;
                    valid = true;
                    for (var item : list) {
                        if (!(item instanceof DataItem)) {
                            valid = false;
                            break;
                        }
                    }
                }
                break;
            case OBJECT:
                if (value instanceof Map) {
                    var map = (Map<?, ?>) value;
                    valid = true;
                    for (var entry : map.entrySet()) {
                        if (
                            !(entry.getKey() instanceof String) ||
                            !(entry.getValue() instanceof DataItem)
                        ) {
                            valid = false;
                            break;
                        }
                    }
                }
                break;
            case STRING:
                valid = value instanceof String;
                break;
        }
        if (!valid) throw new IllegalArgumentException(fmt(
            "Invalid value for type %s: %s", type, value
        ));
        this.type = type;
        this.value = value;
    }

    public void set() {
        set(Type.NULL, null);
    }

    public void set(@Nullable Map<String, DataItem> obj) {
        if (obj == null) set();
        else set(Type.OBJECT, new HashMap<>(obj));
    }

    public void set(@Nullable List<DataItem> arr) {
        if (arr == null) set();
        else set(Type.ARRAY, new ArrayList<>(arr));
    }

    public void set(@Nullable DataItem item) {
        if (item == null) set();
        else if (item.is(Type.OBJECT)) set(new HashMap<>(item.asObject()));
        else if (item.is(Type.ARRAY)) set(new ArrayList<>(item.asArray()));
        else set(item.type, item.value);
    }

    public void set(boolean bool) {
        set(Type.BOOLEAN, bool);
    }

    public void set(long integer) {
        set(Type.INTEGER, integer);
    }

    public void set(double floating) {
        set(Type.FLOAT, floating);
    }

    public void set(@Nullable String str) {
        if (str == null) set();
        else set(Type.STRING, str);
    }

    public void set(@Nullable JSONArray arr) throws JSONException {
        if (arr == null) {
            set();
            return;
        }
        set(Type.ARRAY, JsonUtils.arrayToList(arr, DataItem::from));
    }

    public void set(@Nullable JSONObject obj) throws JSONException {
        if (obj == null) {
            set();
            return;
        }
        set(Type.OBJECT, JsonUtils.objectToMap(obj, DataItem::from));
    }

    @NonNull
    public static DataItem from(@Nullable Object val) {
        if (val == null || val == JSONObject.NULL) {
            return newNull();
        } else if (val instanceof Boolean) {
            return newBoolean((boolean) val);
        } else if (val instanceof Integer) {
            return newInteger(((Integer) val).longValue());
        } else if (val instanceof Long) {
            return newInteger((Long) val);
        } else if (val instanceof Float) {
            return newFloat(((Float) val).doubleValue());
        } else if (val instanceof Double) {
            return newFloat((Double) val);
        } else if (val instanceof String) {
            return newString((String) val);
        } else if (val instanceof JSONArray) {
            try {
                return fromJson((JSONArray) val);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        } else if (val instanceof JSONObject) {
            try {
                return fromJson((JSONObject) val);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        } else if (val instanceof Collection) {
            var list = (Collection<?>) val;
            var items = new ArrayList<DataItem>();
            for (var item : list)
                items.add(from(item));
            return newArray(items);
        } else if (val instanceof Map) {
            var map = (Map<?, ?>) val;
            var entries = new HashMap<String, DataItem>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) continue;
                entries.put((String) entry.getKey(), from(entry.getValue()));
            }
            return newObject(entries);
        } else if (val instanceof DataItem) {
            return new DataItem((DataItem) val);
        } else if (val instanceof JSONSerialize) {
            try {
                return from(((JSONSerialize) val).toJson());
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        } else if (val instanceof Enum) {
            return newString(((Enum<?>) val).name());
        } else if (val instanceof CharSequence) {
            return newString(val.toString());
        } else if (val instanceof BigInteger) {
            var bi = (BigInteger) val;
            if (bi.bitLength() > 63) throw new IllegalArgumentException(fmt(
                "BigInteger value too large for integer type: %s", bi
            ));
            return newInteger(bi.longValue());
        } else if (val instanceof BigDecimal) {
            return newFloat(((BigDecimal) val).doubleValue());
        } else if (val instanceof Number) {
            var num = (Number) val;
            return newInteger(((Number) val).longValue());
        } else if (val instanceof UUID) {
            return newString(val.toString());
        } else if (val instanceof Date) {
            return newString(val.toString());
        } else {
            throw new IllegalArgumentException(fmt(
                "Unsupported value type: %s", val.getClass()
            ));
        }
    }

    @NonNull
    public Object toJsonValue() throws JSONException {
        switch (type) {
            case BOOLEAN:
                return asBoolean();
            case INTEGER:
                return asInteger();
            case FLOAT:
                return asFloat();
            case STRING:
                return asString();
            case ARRAY:
                return toJsonArray();
            case OBJECT:
                return toJson();
            default:
                return JSONObject.NULL;
        }
    }

    @NonNull
    public JSONArray toJsonArray() throws JSONException {
        check(Type.ARRAY);
        var arr = new JSONArray();
        for (var item : asArray()) {
            arr.put(item != null ? item.toJsonValue() : JSONObject.NULL);
        }
        return arr;
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        check(Type.OBJECT);
        var obj = new JSONObject();
        for (var entry : asObject().entrySet()) {
            var val = entry.getValue();
            obj.put(entry.getKey(), val != null ? val.toJsonValue() : JSONObject.NULL);
        }
        return obj;
    }

    public int size() {
        if (is(Type.OBJECT)) return asObject().size();
        if (is(Type.ARRAY)) return asArray().size();
        return 0;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public static class Iter {
        private final String key;
        private final int index;
        private final DataItem value;

        private Iter(String key, int index, @NonNull DataItem value) {
            this.key = key;
            this.index = index;
            this.value = value;
        }

        @NonNull
        public String getKey() {
            return key;
        }

        public int getIndex() {
            return index;
        }

        @NonNull
        public DataItem getValue() {
            return value;
        }
    }

    @Override
    public void forEach(@NonNull Consumer<? super Iter> action) {
        if (is(Type.NULL)) return;
        if (is(Type.OBJECT)) {
            int i = 0;
            for (var entry : asObject().entrySet())
                action.accept(new Iter(entry.getKey(), i++, entry.getValue()));
            return;
        }
        if (is(Type.ARRAY)) {
            var list = asArray();
            for (int i = 0; i < list.size(); i++)
                action.accept(new Iter(null, i, list.get(i)));
            return;
        }
        throw new IllegalStateException(fmt(
            "Cannot iterate over type %s", type
        ));
    }

    @NonNull
    @Override
    public Iterator<Iter> iterator() {
        if (is(Type.OBJECT)) {
            var entries = asObject().entrySet().iterator();
            return new Iterator<>() {
                int index = 0;

                @Override
                public boolean hasNext() {
                    return entries.hasNext();
                }

                @Override
                public Iter next() {
                    var entry = entries.next();
                    return new Iter(entry.getKey(), index++, entry.getValue());
                }
            };
        }
        if (is(Type.ARRAY)) {
            var list = asArray();
            return new Iterator<>() {
                int index = 0;

                @Override
                public boolean hasNext() {
                    return index < list.size();
                }

                @Override
                public Iter next() {
                    var item = list.get(index);
                    return new Iter(null, index++, item);
                }
            };
        }
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public Iter next() {
                throw new NoSuchElementException();
            }
        };
    }

    public void forEachArray(@NonNull Consumer<DataItem> action) {
        if (is(Type.NULL)) return;
        asArray().forEach(action);
    }

    public void forEachObject(@NonNull Consumer<Map.Entry<String, DataItem>> action) {
        if (is(Type.NULL)) return;
        asObject().entrySet().forEach(action);
    }

    @NonNull
    @Override
    public String toString() {
        try {
            var json = toJsonValue();
            if (json instanceof JSONObject)
                return ((JSONObject) json).toString(2);
            if (json instanceof JSONArray)
                return ((JSONArray) json).toString(2);
            return String.valueOf(json);
        } catch (Exception ignored) {
            return super.toString();
        }
    }

    @Override
    public int hashCode() {
        if (value == null) return Objects.hashCode(null);
        return value.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof DataItem)) return false;
        var other = (DataItem) obj;
        if (type != other.type) return false;
        if (value == null) return other.value == null;
        return value.equals(other.value);
    }

    @NonNull
    public DataItem lookup(@NonNull String path, @NonNull DataItem def) {
        if (path.isEmpty()) return this;
        var segments = parseLookupPath(path);
        DataItem current = this;
        for (var segment : segments) {
            if (current == null) return def;
            if (current.is(Type.ARRAY)) {
                try {
                    int index = Integer.parseInt(segment);
                    var arr = current.asArray();
                    if (index < 0 || index >= arr.size()) return def;
                    current = arr.get(index);
                } catch (NumberFormatException e) {
                    return def;
                }
            } else if (current.is(Type.OBJECT)) {
                current = current.asObject().get(segment);
            } else {
                return def;
            }
        }
        return current != null ? current : def;
    }

    @NonNull
    public DataItem lookup(@NonNull String path) {
        return lookup(path, newNull());
    }

    @Nullable
    public String lookupString(@NonNull String path, @Nullable String def) {
        var item = lookup(path);
        if (item.is(Type.STRING)) return item.asString();
        return def;
    }

    public long lookupInteger(@NonNull String path, long def) {
        var item = lookup(path);
        if (item.is(Type.INTEGER)) return item.asInteger();
        return def;
    }

    public boolean lookupBoolean(@NonNull String path, boolean def) {
        var item = lookup(path);
        if (item.is(Type.BOOLEAN)) return item.asBoolean();
        return def;
    }

    public double lookupFloat(@NonNull String path, double def) {
        var item = lookup(path);
        if (item.is(Type.FLOAT)) return item.asFloat();
        return def;
    }

    @NonNull
    private static List<String> parseLookupPath(@NonNull String path) {
        var segments = new ArrayList<String>();
        var sb = new StringBuilder();
        int i = 0;
        int len = path.length();
        while (i < len) {
            char c = path.charAt(i);
            if (c == '.' || c == '/') {
                if (sb.length() > 0) {
                    segments.add(sb.toString());
                    sb.setLength(0);
                }
                i++;
            } else if (c == '[') {
                if (sb.length() > 0) {
                    segments.add(sb.toString());
                    sb.setLength(0);
                }
                i++;
                if (i < len && (path.charAt(i) == '\'' || path.charAt(i) == '"')) {
                    char quote = path.charAt(i);
                    i++;
                    while (i < len && path.charAt(i) != quote) {
                        sb.append(path.charAt(i));
                        i++;
                    }
                    if (i < len) i++;
                } else {
                    while (i < len && path.charAt(i) != ']') {
                        sb.append(path.charAt(i));
                        i++;
                    }
                }
                if (i < len && path.charAt(i) == ']') i++;
                segments.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
                i++;
            }
        }
        if (sb.length() > 0)
            segments.add(sb.toString());
        return segments;
    }

    @NonNull
    public static DataItem newNull() {
        return new DataItem();
    }

    @NonNull
    public static DataItem newBoolean(boolean bool) {
        return new DataItem(bool);
    }

    @NonNull
    public static DataItem newInteger(long integer) {
        return new DataItem(integer);
    }

    @NonNull
    public static DataItem newFloat(double floating) {
        return new DataItem(floating);
    }

    @NonNull
    public static DataItem newString(@Nullable String str) {
        return new DataItem(str);
    }

    @NonNull
    public static DataItem newArray(@Nullable List<DataItem> arr) {
        return new DataItem(arr);
    }

    @NonNull
    public static DataItem newArray() {
        return newArray(new ArrayList<>());
    }

    @NonNull
    public static DataItem newObject(@Nullable Map<String, DataItem> obj) {
        return new DataItem(obj);
    }

    @NonNull
    public static DataItem newObject() {
        return newObject(new HashMap<>());
    }

    @NonNull
    public static DataItem fromJson(@Nullable JSONObject obj) throws JSONException {
        return new DataItem(obj);
    }

    @NonNull
    public static DataItem fromJson(@Nullable JSONArray arr) throws JSONException {
        return new DataItem(arr);
    }
}
