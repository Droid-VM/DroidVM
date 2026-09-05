// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.data.QcomChipName;
import cn.classfun.droidvm.lib.data.QcomGunyahSupports;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum LendMthpMode implements StringEnum {
    DISABLED(R.string.create_vm_prepare_lend_mthp_disabled),
    SINGLE(R.string.create_vm_prepare_lend_mthp_single),
    CHUNKED(R.string.create_vm_prepare_lend_mthp_chunked);

    public static final String KEY = "prepare_lend_mthp";
    public static final LendMthpMode DEFAULT = CHUNKED;
    private static final String TAG = "LendMthpMode";

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

    /**
     * Device-aware default used by every new-VM path. Keep the priority identical to the
     * capability table: a more specific supported mode later in the list wins. In particular,
     * Snapdragon 8 Gen 3 advertises only {@code mthp_single} and must never default to chunked
     * preallocation.
     */
    @NonNull
    public static LendMthpMode defaultForDevice(@NonNull Context context) {
        var mode = DEFAULT;
        try {
            var socModel = QcomChipName.getCurrentSoC();
            var gunyah = new QcomGunyahSupports(context);
            if (gunyah.isCapacitySupported(socModel, "no_mthp"))
                mode = DISABLED;
            if (gunyah.isCapacitySupported(socModel, "mthp_chunked"))
                mode = CHUNKED;
            if (gunyah.isCapacitySupported(socModel, "mthp_single"))
                mode = SINGLE;
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve device MTHP default", e);
        }
        return mode;
    }
}
