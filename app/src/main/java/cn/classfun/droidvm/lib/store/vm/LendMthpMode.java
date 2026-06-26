package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum LendMthpMode implements StringEnum {
    DISABLED(R.string.create_vm_prepare_lend_mthp_disabled),
    SINGLE(R.string.create_vm_prepare_lend_mthp_single),
    CHUNKED(R.string.create_vm_prepare_lend_mthp_chunked);

    public static final String KEY = "prepare_lend_mthp";
    public static final LendMthpMode DEFAULT = CHUNKED;

    private final @StringRes int stringId;

    LendMthpMode(@StringRes int stringId) {
        this.stringId = stringId;
    }

    @Override
    @StringRes
    public int getStringId() {
        return stringId;
    }

    // Resolves the mode from config, migrating the legacy boolean value
    // (true -> chunked, false -> disabled) written by older versions.
    @NonNull
    public static LendMthpMode fromItem(@NonNull DataItem item) {
        var raw = item.opt(KEY, null);
        if (raw != null && raw.is(DataItem.Type.BOOLEAN))
            return raw.asBoolean() ? CHUNKED : DISABLED;
        return optEnum(item, KEY, DEFAULT);
    }
}
