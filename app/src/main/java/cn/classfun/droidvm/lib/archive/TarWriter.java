package cn.classfun.droidvm.lib.archive;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;

import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZInputStream;
import org.tukaani.xz.XZOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class TarWriter implements AutoCloseable {
    private static final int BLOCK = 512;
    private final OutputStream out;
    private boolean closed = false;

    public TarWriter(@NonNull OutputStream out) {
        this.out = out;
    }

    public void entry(
        @NonNull String name,
        byte[] content
    ) throws IOException {
        entry(name, content, 0, content.length);
    }

    public void entry(
        @NonNull String name,
        byte[] content,
        int off,
        int len
    ) throws IOException {
        entry(name, len, os -> os.write(content, off, len));
    }

    public interface ContentWriter {
        void write(@NonNull OutputStream os) throws IOException;
    }

    @SuppressWarnings("OctalInteger")
    public static final class TarMetadata {
        public int mode = 0644;
        public int uid = 0;
        public int gid = 0;
        public long mtime = System.currentTimeMillis() / 1000;
    }

    public void entry(
        @NonNull String name,
        long size,
        @NonNull ContentWriter writer
    ) throws IOException {
        entry(name, size, null, writer);
    }

    public void entry(
        @NonNull String name,
        long size,
        @Nullable TarMetadata metadata,
        @NonNull ContentWriter writer
    ) throws IOException {
        if (closed) throw new IOException("TarWriter closed");
        if (name.length() > 100) {
            entry("./@LongLink", name.getBytes(UTF_8));
            int slash = name.lastIndexOf('/');
            if (slash >= 0 && slash < name.length() - 1)
                name = name.substring(slash + 1);
        }
        writeHeader(name, size, '0', metadata);
        long remaining;
        try (var sink = new OutputStreamLimiter(out, size)) {
            writer.write(sink);
            remaining = sink.getRemaining();
        }
        if (remaining > 0) throw new IOException(fmt(
            "entry %s: short write (%d of %d bytes)",
            name, size - remaining, size
        ));
        int pad = (int) ((BLOCK - (size % BLOCK)) % BLOCK);
        if (pad > 0) {
            out.write(new byte[pad]);
        }
    }

    private void writeHeader(
        @NonNull String name,
        long size,
        char type,
        @Nullable TarMetadata metadata
    ) throws IOException {
        var hdr = new byte[BLOCK];
        var nameBytes = name.getBytes(UTF_8);
        System.arraycopy(nameBytes, 0, hdr, 0, Math.min(nameBytes.length, 100));
        if (metadata == null) metadata = new TarMetadata();
        field(hdr, 100, 8, padOctal(metadata.mode, 7));
        field(hdr, 108, 8, padOctal(metadata.uid, 7));
        field(hdr, 116, 8, padOctal(metadata.gid, 7));
        field(hdr, 124, 12, padOctal(size, 11));
        field(hdr, 136, 12, padOctal(metadata.mtime, 11));
        for (int i = 148; i < 156; i++) hdr[i] = ' ';
        hdr[156] = (byte) type;
        var magic = "ustar";
        System.arraycopy(magic.getBytes(US_ASCII), 0, hdr, 257, 5);
        hdr[262] = 0;
        int sum = 0;
        for (byte b : hdr) sum += b & 0xff;
        field(hdr, 148, 8, Long.toOctalString(sum).concat(" "));
        out.write(hdr);
    }

    private static void field(byte[] hdr, int off, int len, @NonNull String oct) {
        var b = oct.getBytes(US_ASCII);
        int n = Math.min(b.length, len);
        System.arraycopy(b, 0, hdr, off, n);
        for (int i = off + n; i < off + len; i++) hdr[i] = 0;
    }

    @NonNull
    private static String padOctal(long value, int digits) {
        var s = Long.toOctalString(value);
        if (s.length() < digits)
            s = "0".repeat(digits - s.length()).concat(s);
        return s;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        out.write(new byte[BLOCK * 2]);
    }

    @NonNull
    public static OutputStream wrapCompressionOutput(
        @NonNull OutputStream out,
        @NonNull Compression c
    ) throws Exception {
        switch (c) {
            case NONE:
                return out;
            case GZIP:
                return new GZIPOutputStream(out, 64 * 1024);
            case XZ:
                return new XZOutputStream(out, new LZMA2Options());
            case ZSTD:
                return new ZstdOutputStream(out, 3);
            default:
                throw new IllegalArgumentException(fmt("unknown compression: %s", c));
        }
    }

    @NonNull
    public static InputStream wrapCompressionInput(
        @NonNull InputStream out,
        @NonNull Compression c
    ) throws Exception {
        switch (c) {
            case NONE:
                return out;
            case GZIP:
                return new GZIPInputStream(out, 64 * 1024);
            case XZ:
                return new XZInputStream(out);
            case ZSTD:
                return new ZstdInputStream(out);
            default:
                throw new IllegalArgumentException(fmt("unknown compression: %s", c));
        }
    }
}
