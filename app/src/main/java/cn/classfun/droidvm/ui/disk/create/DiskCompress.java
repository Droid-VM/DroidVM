// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.create;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;
import cn.classfun.droidvm.lib.utils.ImageUtils;

public enum DiskCompress implements StringEnum {
    NONE(R.string.disk_create_compress_disabled),
    DEFLATE(R.string.disk_create_compress_deflate),
    ZSTD(R.string.disk_create_compress_zstd);

    /**
     * qcow2 compression the crosvm backend can read. Currently only uncompressed images boot;
     * when crosvm grows zlib (and later zstd) support, extend this set and every consumer -
     * the post-import optimize skip and the pre-start convert prompt - follows.
     */
    public static final Set<DiskCompress> CROSVM_SUPPORTED =
        Collections.unmodifiableSet(EnumSet.of(NONE));

    private final @StringRes int stringId;

    DiskCompress(int stringId) {
        this.stringId = stringId;
    }

    @Override
    @StringRes
    public int getStringId() {
        return stringId;
    }

    public boolean isCrosvmSupported() {
        return CROSVM_SUPPORTED.contains(this);
    }

    /** The wire value used in disk-operation tasks and preferences: none / deflate / zstd. */
    @NonNull
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @Nullable
    public static DiskCompress fromValue(@Nullable String value) {
        if (value == null) return null;
        for (var c : values()) {
            if (c.value().equals(value)) return c;
        }
        return null;
    }

    /** Maps qemu's compression naming ({@code none}/{@code zlib}/{@code zstd}). */
    @NonNull
    public static DiskCompress fromQemuType(@Nullable String type) {
        if ("zstd".equals(type)) return ZSTD;
        if ("zlib".equals(type)) return DEFLATE;
        return NONE;
    }

    /**
     * The image's effective compression, via qemu-img ({@link ImageUtils#detectCompression}):
     * NONE unless it really stores compressed clusters. Blocking - call off the main thread.
     */
    @NonNull
    public static DiskCompress detect(@NonNull String path) {
        return fromQemuType(ImageUtils.detectCompression(path));
    }
}
