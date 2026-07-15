package cn.classfun.droidvm.lib.pkg;

import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.getInt64LE;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.getUInt16LE;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.readFully;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;

import cn.classfun.droidvm.lib.archive.Compression;

public final class PackageHeader {
    public int manifestVersion = 0;
    public int appVersionCode = 0;
    public int manifestSize = 0;
    public int compression = 0;
    // 0 = single self-contained file; > 0 = split package whose data lives in
    // that many <base>.NNN sub-volumes (this file is the metadata master).
    public int volumeCount = 0;
    public long dataSize = 0;

    public PackageHeader() {
    }

    public void parseFromData(@NonNull byte[] hdr) throws IOException {
        if (hdr.length < PackageConstants.HEADER_SIZE)
            throw new IOException("incomplete package header");
        if (!checkMagic(hdr))
            throw new IOException("invalid package header");
        manifestVersion = getUInt16LE(hdr, 6);
        appVersionCode = getUInt16LE(hdr, 8);
        manifestSize = getUInt16LE(hdr, 10);
        compression = getUInt16LE(hdr, 12);
        volumeCount = getUInt16LE(hdr, 14);
        dataSize = getInt64LE(hdr, 16);
    }

    public void validate() throws IOException {
        if (manifestVersion != PackageConstants.MANIFEST_VERSION)
            throw new IOException(fmt("unsupported vmpkg manifest version: %d", manifestVersion));
        if (Compression.fromType(compression) == null)
            throw new IOException(fmt("unsupported vmpkg compression: %d", compression));
        if (manifestSize <= 0) throw new IOException("empty vmpkg manifest");
        if (dataSize < 0) throw new IOException("vmpkg data size is too large");
    }

    @NonNull
    public static PackageHeader fromBytes(byte[] hdr) throws IOException {
        var header = new PackageHeader();
        header.parseFromData(hdr);
        header.validate();
        return header;
    }

    @NonNull
    public static PackageHeader fromStream(InputStream in) throws IOException {
        var hdr = new byte[PackageConstants.HEADER_SIZE];
        readFully(in, hdr);
        return fromBytes(hdr);
    }

    public static boolean checkMagic(@NonNull byte[] hdr) {
        if (hdr.length < PackageConstants.MAGIC.length() + 1) return false;
        var magic = PackageConstants.MAGIC.getBytes(UTF_8);
        for (int i = 0; i < magic.length; i++)
            if (hdr[i] != magic[i]) return false;
        return hdr[magic.length] == 0;
    }
}
