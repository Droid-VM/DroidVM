package cn.classfun.droidvm.lib.store.enums;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;

public interface StringEnum {
    @StringRes
    default int getStringId() {
        return R.string.nullptr;
    }

    @Nullable
    default String getString() {
        return null;
    }

    @NonNull
    default String getDisplayString(Context ctx) {
        var stringId = getStringId();
        if (stringId != R.string.nullptr)
            return ctx.getString(stringId);
        var str = getString();
        if (str != null)
            return str;
        return toString();
    }

    default boolean isDisplay() {
        return true;
    }
}
