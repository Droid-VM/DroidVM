package cn.classfun.droidvm.lib.pkg;

import static cn.classfun.droidvm.lib.utils.BinaryUtils.alignUp;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.alignUpStrict;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;
import java.util.zip.CRC32;

import cn.classfun.droidvm.lib.archive.ZeroInputStream;

/**
 * Reader-side view of a multi-volume vmpkg. Given the metadata master
 * ({@code <base>.vmpkg}), it parses the embedded {@link VolumeIndex}, locates
 * and validates the {@code <base>.001 ..} sub-volumes, then rebuilds the exact
 * single-file byte stream -- {@code [master head][sub-volume data][tail padding]}
 * -- so the unchanged {@link PackageInput} can consume it. Sub-volume CRC32s are
 * verified as their boundaries are crossed.
 */
public final class VolumeSet {
    private final byte[] masterHead;
    private final long dataStart;
    private final VolumeIndex index;
    private final List<File> subVolumes;

    private VolumeSet(
        @NonNull byte[] masterHead,
        long dataStart,
        @NonNull VolumeIndex index,
        @NonNull List<File> subVolumes
    ) {
        this.masterHead = masterHead;
        this.dataStart = dataStart;
        this.index = index;
        this.subVolumes = subVolumes;
    }

    /** True when {@code path} looks like a sub-volume member ({@code ....NNN}). */
    public static boolean isSubVolume(@NonNull String path) {
        return path.matches(fmt(".*\\.\\d{%d}", PackageConstants.VOLUME_SUFFIX_DIGITS));
    }

    /** Maps any picked path to its metadata master: strips a {@code .NNN}
     * sub-volume suffix, or returns the path unchanged for the master itself. */
    @NonNull
    public static String masterOf(@NonNull String path) {
        if (!isSubVolume(path)) return path;
        return path.substring(0, path.length() - PackageConstants.VOLUME_SUFFIX_DIGITS - 1);
    }

    public long dataSize() {
        return index.dataSize;
    }

    public int count() {
        return subVolumes.size();
    }

    /**
     * Parses and validates the whole set from the master path. Throws with a
     * clear message when a sub-volume is missing or a size does not match.
     */
    @NonNull
    public static VolumeSet discover(@NonNull String masterPath) throws IOException {
        var master = new File(masterPath);
        var all = readAllBytes(master);
        if (all.length < PackageConstants.HEADER_SIZE)
            throw new IOException("master too small");
        PackageHeader hdr;
        try {
            hdr = PackageHeader.fromBytes(Arrays.copyOf(all, PackageConstants.HEADER_SIZE));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("invalid master header", e);
        }
        if (hdr.volumeCount <= 0)
            throw new IOException("not a multi-volume package");
        long dataStart = alignUpStrict(alignUp(PackageConstants.HEADER_SIZE) + hdr.manifestSize);
        if (all.length < dataStart)
            throw new IOException("truncated master");
        var indexJson = Arrays.copyOfRange(all, (int) dataStart, all.length);
        var index = VolumeIndex.parse(indexJson);
        if (index.volumes.size() != hdr.volumeCount)
            throw new IOException(fmt(
                "volume count mismatch: header %d, index %d",
                hdr.volumeCount, index.volumes.size()
            ));
        var subs = new ArrayList<File>();
        for (int i = 0; i < index.volumes.size(); i++) {
            var entry = index.volumes.get(i);
            var f = new File(VolumeSplitOutputStream.volumeName(masterPath, i + 1));
            if (!f.isFile())
                throw new IOException(fmt("missing sub-volume %d (%s)", i + 1, f.getName()));
            if (f.length() != entry.size)
                throw new IOException(fmt(
                    "sub-volume %d size mismatch: on-disk %d, expected %d",
                    i + 1, f.length(), entry.size
                ));
            subs.add(f);
        }
        var head = Arrays.copyOf(all, (int) dataStart);
        return new VolumeSet(head, dataStart, index, subs);
    }

    /**
     * Rebuilds the single-file byte stream: master head (header + manifest +
     * padding) ++ concatenated sub-volume data (CRC-checked) ++ trailing
     * alignment zeros. Feed to {@link PackageInput#open(InputStream, long)}.
     */
    @NonNull
    public InputStream openLogicalStream() {
        long dataEnd = dataStart + index.dataSize;
        long tailPad = alignUp(dataEnd) - dataEnd;
        var streams = new Vector<InputStream>();
        streams.add(new ByteArrayInputStream(masterHead));
        streams.add(new SubVolumeConcatStream());
        streams.add(new ZeroInputStream(tailPad));
        return new SequenceInputStream(streams.elements());
    }

    @NonNull
    private static byte[] readAllBytes(@NonNull File f) throws IOException {
        long len = f.length();
        if (len <= 0 || len > (16L << 20))
            throw new IOException(fmt("unexpected master size: %d", len));
        var buf = new byte[(int) len];
        try (var in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) throw new IOException("short read on master");
                off += n;
            }
        }
        return buf;
    }

    private final class SubVolumeConcatStream extends InputStream {
        private int idx = -1;
        private InputStream cur;
        private CRC32 crc;
        private long remaining;

        private boolean advance() throws IOException {
            if (cur != null) {
                verify();
                cur.close();
                cur = null;
            }
            idx++;
            if (idx >= subVolumes.size()) return false;
            cur = new BufferedInputStream(
                new FileInputStream(subVolumes.get(idx)), PackageConstants.BUFFER
            );
            crc = new CRC32();
            remaining = index.volumes.get(idx).size;
            return true;
        }

        private void verify() throws IOException {
            if (remaining != 0)
                throw new IOException(fmt("sub-volume %d truncated", idx + 1));
            if (crc.getValue() != index.volumes.get(idx).crc32)
                throw new IOException(fmt("sub-volume %d checksum mismatch", idx + 1));
        }

        @Override
        public int read() throws IOException {
            var one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(@NonNull byte[] b, int off, int len) throws IOException {
            while (true) {
                if (cur == null || remaining == 0) {
                    if (!advance()) return -1;
                    continue;
                }
                int toRead = (int) Math.min(len, remaining);
                int n = cur.read(b, off, toRead);
                if (n < 0)
                    throw new IOException(fmt("sub-volume %d ended early", idx + 1));
                crc.update(b, off, n);
                remaining -= n;
                return n;
            }
        }

        @Override
        public void close() throws IOException {
            if (cur != null) {
                cur.close();
                cur = null;
            }
        }
    }
}
