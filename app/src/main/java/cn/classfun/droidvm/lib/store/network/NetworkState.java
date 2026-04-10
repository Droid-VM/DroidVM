package cn.classfun.droidvm.lib.store.network;

import androidx.annotation.ColorRes;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.ColorEnum;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum NetworkState implements StringEnum, ColorEnum {
    STOPPED(
        R.string.network_state_stopped,
        android.R.color.darker_gray
    ),
    STARTING(
        R.string.network_state_starting,
        android.R.color.holo_orange_dark
    ),
    RUNNING(
        R.string.network_state_running,
        android.R.color.holo_green_dark
    ),
    STOPPING(
        R.string.network_state_stopping,
        android.R.color.holo_orange_dark
    );

    private final @StringRes int stringId;
    private final @ColorRes int colorId;

    NetworkState(@StringRes int stringId, @ColorRes int colorId) {
        this.stringId = stringId;
        this.colorId = colorId;
    }

    @Override
    @StringRes
    public int getStringId() {
        return stringId;
    }

    @Override
    @ColorRes
    public int getColorId() {
        return colorId;
    }
}
