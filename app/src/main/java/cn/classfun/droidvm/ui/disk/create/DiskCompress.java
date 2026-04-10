package cn.classfun.droidvm.ui.disk.create;

import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum DiskCompress implements StringEnum {
    NONE(R.string.disk_create_compress_disabled),
    DEFLATE(R.string.disk_create_compress_deflate),
    ZSTD(R.string.disk_create_compress_zstd);

    private final @StringRes int stringId;

    DiskCompress(int stringId) {
        this.stringId = stringId;
    }

    @Override
    @StringRes
    public int getStringId() {
        return stringId;
    }
}
