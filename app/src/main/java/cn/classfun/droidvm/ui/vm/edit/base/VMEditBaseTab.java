package cn.classfun.droidvm.ui.vm.edit.base;

import android.view.View;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;

public abstract class VMEditBaseTab {
    protected final VMEditActivity parent;
    protected final View view;

    public VMEditBaseTab(VMEditActivity parent, View view) {
        this.parent = parent;
        this.view = view;
    }

    public abstract void initView();

    public abstract void initValue();

    public abstract void loadConfig(@NonNull VMConfig config);

    public abstract boolean validateInput(@NonNull VMStore store);

    public abstract void saveConfig(@NonNull VMConfig config);
}
