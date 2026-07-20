package cn.classfun.droidvm.lib.pkg;

import cn.classfun.droidvm.lib.archive.Compression;

public final class PackageConstants {
    public static final String EXTENSION = "vmpkg";
    public static final String MIME = "application/vnd.droidvm.vmpkg";
    public static final String MAGIC = "VMPKG";
    public static final int HEADER_SIZE = 24;
    public static final int BUFFER = 64 * 1024;
    public static final int MANIFEST_VERSION = 1;
    public static final String MANIFEST_NAME = "manifest.json";
    public static final Compression DEFAULT_COMPRESSION = Compression.ZSTD;

    // Multi-volume split. The picked file <base> stays as a small metadata
    // master (header + manifest + volume index); the compressed data stream is
    // sliced across <base>.001, <base>.002, ... sub-volumes. volume_size <= 0
    // keeps the legacy single self-contained file (volumeCount == 0).
    public static final long DEFAULT_VOLUME_SIZE = 500L * 1024 * 1024;
    public static final long MIN_VOLUME_SIZE = 16L * 1024 * 1024;
    public static final String VOLUME_MAGIC = "VMPV";
    public static final int VOLUME_VERSION = 1;
    public static final int VOLUME_MAX_COUNT = 999;
    public static final int VOLUME_SUFFIX_DIGITS = 3;

    private PackageConstants() {
    }
}
