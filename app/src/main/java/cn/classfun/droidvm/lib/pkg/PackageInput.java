package cn.classfun.droidvm.lib.pkg;

import static cn.classfun.droidvm.lib.archive.TarWriter.wrapCompressionInput;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.alignUp;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.alignUpStrict;
import static cn.classfun.droidvm.lib.utils.BinaryUtils.readZeroPadding;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;

import cn.classfun.droidvm.lib.archive.Compression;
import cn.classfun.droidvm.lib.archive.LimitedInputStream;

public final class PackageInput implements AutoCloseable {
    @NonNull
    private final InputStream source;
    @NonNull
    private final LimitedInputStream rawData;
    @NonNull
    public final InputStream data;
    @NonNull
    public final PackageManifest manifest;
    @NonNull
    private final PackageHeader header;

    public PackageInput(
        @NonNull InputStream source,
        @NonNull LimitedInputStream rawData,
        @NonNull InputStream data,
        @NonNull PackageManifest manifest,
        @NonNull PackageHeader header
    ) {
        this.source = source;
        this.rawData = rawData;
        this.data = data;
        this.manifest = manifest;
        this.header = header;
    }

    public void validateDataConsumed() throws IOException {
        if (rawData.getRemaining() != 0)
            throw new IOException("vmpkg data_size is larger than tarball data");
        long dataEnd = alignUpStrict(alignUp(PackageConstants.HEADER_SIZE) + header.manifestSize);
        dataEnd += header.dataSize;
        readZeroPadding(source, alignUp(dataEnd) - dataEnd);
        if (source.read() >= 0) throw new IOException("unexpected bytes after vmpkg package");
    }

    @Override
    public void close() throws IOException {
        data.close();
    }

    @NonNull
    public static PackageInput open(@NonNull InputStream raw) throws Exception {
        var pkgHeader = PackageHeader.fromStream(raw);
        var manifest = PackageManifest.fromStream(raw, pkgHeader);
        if (pkgHeader.manifestVersion != manifest.manifestVersion)
            throw new IOException("vmpkg manifest_version mismatch");
        if (pkgHeader.appVersionCode != manifest.appVersionCode)
            throw new IOException("vmpkg app_version_code mismatch");
        if (pkgHeader.compression != manifest.compression.type)
            throw new IOException("vmpkg compression mismatch");
        long pos = alignUp(PackageConstants.HEADER_SIZE) + pkgHeader.manifestSize;
        readZeroPadding(raw, alignUpStrict(pos) - pos);
        var stream = new LimitedInputStream(raw, pkgHeader.dataSize);
        var compression = Compression.fromType(pkgHeader.compression);
        if (compression == null) throw new IllegalArgumentException("invalid compression");
        var compStream = wrapCompressionInput(stream, compression);
        return new PackageInput(raw, stream, compStream, manifest, pkgHeader);
    }
}
