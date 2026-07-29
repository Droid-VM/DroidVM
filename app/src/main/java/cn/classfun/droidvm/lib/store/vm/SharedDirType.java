// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum SharedDirType implements StringEnum {
    P9,
    FS;

    @NonNull
    @Override
    public String getString() {
        return name();
    }
}
