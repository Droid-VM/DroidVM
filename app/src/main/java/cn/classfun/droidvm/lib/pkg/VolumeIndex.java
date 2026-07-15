package cn.classfun.droidvm.lib.pkg;

import static cn.classfun.droidvm.lib.utils.JsonUtils.arrayToList;
import static cn.classfun.droidvm.lib.utils.JsonUtils.listToJSONArray;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.JSONSerialize;

/**
 * Volume list embedded in the metadata master ({@code <base>.vmpkg}) right
 * after the manifest. It records the total data size and, for each sub-volume,
 * its logical size and CRC32 so a reader can verify the set is complete and
 * intact before unpacking. Plain JSON -- its length is master-file-size minus
 * the data-region offset, so no framing/magic footer is needed.
 */
public final class VolumeIndex implements JSONSerialize {
    public static final class Entry implements JSONSerialize {
        public final int index;
        public final long size;
        public final long crc32;

        public Entry(int index, long size, long crc32) {
            this.index = index;
            this.size = size;
            this.crc32 = crc32;
        }

        public Entry(@NonNull JSONObject o) {
            index = o.optInt("index");
            size = o.optLong("size");
            crc32 = o.optLong("crc32");
        }

        @NonNull
        @Override
        public JSONObject toJson() throws JSONException {
            var o = new JSONObject();
            o.put("index", index);
            o.put("size", size);
            o.put("crc32", crc32);
            return o;
        }

        @NonNull
        public static Entry from(Object o) throws JSONException {
            if (o instanceof JSONObject)
                return new Entry((JSONObject) o);
            throw new JSONException("object is not json");
        }
    }

    public int version = PackageConstants.VOLUME_VERSION;
    public long dataSize = 0;
    public final List<Entry> volumes = new ArrayList<>();

    public VolumeIndex() {
    }

    public VolumeIndex(@NonNull JSONObject o) throws IOException, JSONException {
        if (!PackageConstants.VOLUME_MAGIC.equals(o.optString("magic")))
            throw new IOException("missing volume index magic");
        version = o.optInt("version");
        if (version != PackageConstants.VOLUME_VERSION)
            throw new IOException(fmt("unsupported volume version: %d", version));
        dataSize = o.optLong("data_size");
        volumes.addAll(arrayToList(o, "volumes", Entry::from));
        if (volumes.isEmpty())
            throw new IOException("volume index has no volumes");
    }

    public VolumeIndex(@NonNull String json) throws IOException, JSONException {
        this(new JSONObject(json));
    }

    @NonNull
    @Override
    public JSONObject toJson() throws JSONException {
        var o = new JSONObject();
        o.put("magic", PackageConstants.VOLUME_MAGIC);
        o.put("version", version);
        o.put("data_size", dataSize);
        o.put("volumes", listToJSONArray(volumes));
        return o;
    }
}
