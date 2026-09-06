// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.pkg.imports;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.pkg.NetworkImportPlan;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * What the import screen offers for one packaged network, in the order it prefers them: join a
 * network already here, build the packaged one, or leave it behind. The default lands on the
 * first of these that is actually available.
 */
public enum NetworkImportMode implements StringEnum {
    JOIN(R.string.vmpkg_import_network_join, NetworkImportPlan.Action.JOIN),
    CREATE(R.string.vmpkg_import_network_create, NetworkImportPlan.Action.CREATE),
    SKIP(R.string.vmpkg_import_network_skip, NetworkImportPlan.Action.SKIP);

    @StringRes
    private final int stringId;
    @NonNull
    public final NetworkImportPlan.Action action;

    NetworkImportMode(@StringRes int stringId, @NonNull NetworkImportPlan.Action action) {
        this.stringId = stringId;
        this.action = action;
    }

    @Override
    public int getStringId() {
        return stringId;
    }
}
