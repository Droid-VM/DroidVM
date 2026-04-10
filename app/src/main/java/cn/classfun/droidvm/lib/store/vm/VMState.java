package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.ColorRes;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.ColorEnum;
import cn.classfun.droidvm.lib.store.enums.StringEnum;

public enum VMState implements StringEnum, ColorEnum {
    STOPPED(
        R.string.vm_state_stopped,
        android.R.color.darker_gray
    ),
    STARTING(
        R.string.vm_state_starting,
        android.R.color.holo_orange_dark
    ),
    RUNNING(
        R.string.vm_state_running,
        android.R.color.holo_green_dark
    ),
    SUSPENDED(
        R.string.vm_state_suspended,
        android.R.color.holo_blue_dark
    ),
    STOPPING(
        R.string.vm_state_stopping,
        android.R.color.holo_orange_dark
    );

    private final @StringRes int stringId;
    private final @ColorRes int colorId;

    VMState(@StringRes int stringId, @ColorRes int colorId) {
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
