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

    private PackageConstants() {
    }
}
