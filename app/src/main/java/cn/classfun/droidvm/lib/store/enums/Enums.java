package cn.classfun.droidvm.lib.store.enums;

import android.widget.TextView;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import cn.classfun.droidvm.lib.store.base.DataItem;

public final class Enums {
    private Enums() {
    }

    public static <E extends Enum<E> & StringEnum & ColorEnum> void applyText(
        @NonNull TextView tv, @NonNull E val
    ) {
        var ctx = tv.getContext();
        tv.setText(val.getStringId());
        tv.setTextColor(val.getDisplayColor(ctx).toArgb());
    }

    public static <E extends Enum<E>> E optEnum(
        @NonNull JSONObject jo,
        @NonNull String key,
        @NonNull E def
    ) {
        var str = jo.optString(key, def.name());
        try {
            return Enum.valueOf(
                def.getDeclaringClass(),
                str.toUpperCase()
            );
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    public static <E extends Enum<E>> E optEnum(
        @NonNull DataItem item,
        @NonNull String key,
        @NonNull E def
    ) {
        var str = item.optString(key, def.name());
        try {
            return Enum.valueOf(
                def.getDeclaringClass(),
                str.toUpperCase()
            );
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
