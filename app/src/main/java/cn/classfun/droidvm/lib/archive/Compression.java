package cn.classfun.droidvm.lib.archive;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum Compression implements StringEnum {
    NONE(
        /* ext     = */ "bin",
        /* mime    = */ "application/octet-stream",
        /* id      = */ "none",
        /* type    = */ 0,
        /* display = */ R.string.vmpkg_export_compression_none,
        /* desc    = */ R.string.nullptr
    ),
    GZIP(
        /* ext     = */ "gz",
        /* mime    = */ "application/gzip",
        /* id      = */ "gzip",
        /* type    = */ 1,
        /* display = */ R.string.vmpkg_export_compression_gzip,
        /* desc    = */ R.string.vmpkg_export_compression_gzip_desc
    ),
    XZ(
        /* ext     = */ "xz",
        /* mime    = */ "application/x-xz",
        /* id      = */ "xz",
        /* type    = */ 2,
        /* display = */ R.string.vmpkg_export_compression_xz,
        /* desc    = */ R.string.vmpkg_export_compression_xz_desc
    ),
    ZSTD(
        /* ext     = */ "zst",
        /* mime    = */ "application/zstd",
        /* id      = */ "zstd",
        /* type    = */ 3,
        /* display = */ R.string.vmpkg_export_compression_zstd,
        /* desc    = */ R.string.vmpkg_export_compression_zstd_desc
    );

    @NonNull
    public final String extSuffix;
    @NonNull
    public final String mime;
    @NonNull
    public final String id;
    public final int type;
    @StringRes
    public final int display;
    @StringRes
    public final int desc;

    Compression(
        @NonNull String extSuffix,
        @NonNull String mime,
        @NonNull String id,
        int type,
        @StringRes int display,
        @StringRes int desc
    ) {
        this.extSuffix = extSuffix;
        this.mime = mime;
        this.id = id;
        this.type = type;
        this.display = display;
        this.desc = desc;
    }

    @Override
    public String getString() {
        return id;
    }

    @NonNull
    @Override
    public String getDisplayString(@NonNull Context ctx) {
        return ctx.getString(display);
    }

    @Nullable
    public static Compression fromType(int type) {
        for (var c : values()) if (c.type == type) return c;
        return null;
    }
}
