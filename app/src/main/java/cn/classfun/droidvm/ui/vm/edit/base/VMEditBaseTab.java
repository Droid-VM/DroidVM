package cn.classfun.droidvm.ui.vm.edit.base;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.google.android.material.snackbar.Snackbar;

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

    /** Called when this tab becomes the visible one. */
    public void onTabShown() {
    }

    protected final boolean showValidateFailed(@StringRes int message) {
        return showValidateFailed(parent.getString(message));
    }

    protected final boolean showValidateFailed(@NonNull CharSequence message) {
        Snackbar.make(parent, view, message, Snackbar.LENGTH_LONG).show();
        return false;
    }

    public abstract void loadConfig(@NonNull VMConfig config);

    public abstract boolean validateInput(@NonNull VMStore store);

    public abstract void saveConfig(@NonNull VMConfig config);
}
