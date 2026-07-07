package cn.classfun.droidvm.ui.vm.pkg.imports;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum NetworkImportMode implements StringEnum {
    SKIP(R.string.vmpkg_import_network_skip, "skip"),
    EXISTING(R.string.vmpkg_import_network_existing, "existing"),
    AUTO(R.string.vmpkg_import_network_auto, "auto");

    @StringRes
    private final int stringId;
    @NonNull
    final String id;

    NetworkImportMode(@StringRes int stringId, @NonNull String id) {
        this.stringId = stringId;
        this.id = id;
    }

    @Override
    public int getStringId() {
        return stringId;
    }
}
