package cn.classfun.droidvm.lib.pkg;

import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Volume list embedded in the metadata master ({@code <base>.vmpkg}) right
 * after the manifest. It records the total data size and, for each sub-volume,
 * its logical size and CRC32 so a reader can verify the set is complete and
 * intact before unpacking. Plain JSON -- its length is master-file-size minus
 * the data-region offset, so no framing/magic footer is needed.
 */
public final class VolumeIndex {
    public static final class Entry {
        public final int index;
        public final long size;
        public final long crc32;

        public Entry(int index, long size, long crc32) {
            this.index = index;
            this.size = size;
            this.crc32 = crc32;
        }
    }

    public int version = PackageConstants.VOLUME_VERSION;
    public long dataSize = 0;
    public final List<Entry> volumes = new ArrayList<>();

    @NonNull
    public byte[] toBytes() throws IOException {
        var arr = new JSONArray();
        try {
            for (var v : volumes) {
                var o = new JSONObject();
                o.put("index", v.index);
                o.put("size", v.size);
                o.put("crc32", v.crc32);
                arr.put(o);
            }
            var root = new JSONObject();
            root.put("magic", PackageConstants.VOLUME_MAGIC);
            root.put("version", version);
            root.put("data_size", dataSize);
            root.put("volumes", arr);
            return root.toString().getBytes(UTF_8);
        } catch (Exception e) {
            throw new IOException("failed to build volume index", e);
        }
    }

    @NonNull
    public static VolumeIndex parse(@NonNull byte[] json) throws IOException {
        try {
            var root = new JSONObject(new String(json, UTF_8));
            if (!PackageConstants.VOLUME_MAGIC.equals(root.optString("magic")))
                throw new IOException("missing volume index magic");
            var idx = new VolumeIndex();
            idx.version = root.optInt("version", 0);
            idx.dataSize = root.optLong("data_size", 0);
            var arr = root.optJSONArray("volumes");
            if (arr == null || arr.length() == 0)
                throw new IOException("volume index has no volumes");
            for (int i = 0; i < arr.length(); i++) {
                var o = arr.getJSONObject(i);
                idx.volumes.add(new Entry(
                    o.optInt("index", i + 1),
                    o.optLong("size", 0),
                    o.optLong("crc32", 0)
                ));
            }
            if (idx.version != PackageConstants.VOLUME_VERSION)
                throw new IOException(fmt("unsupported volume version: %d", idx.version));
            return idx;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("failed to parse volume index", e);
        }
    }
}
