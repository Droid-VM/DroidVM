package cn.classfun.droidvm.lib.store.enums;

import android.content.Context;
import android.graphics.Color;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.R;

public interface ColorEnum {
    @ColorRes
    default int getColorId() {
        return R.color.nullptr;
    }

    @Nullable
    @SuppressWarnings("SameReturnValue")
    default Color getColor() {
        return null;
    }

    @NonNull
    default Color getDisplayColor(Context ctx) {
        var colorId = getColorId();
        if (colorId == R.color.nullptr) {
            var str = getColor();
            if (str != null)
                return str;
            colorId = R.color.black;
        }
        return Color.valueOf(ctx.getColor(colorId));
    }

}
