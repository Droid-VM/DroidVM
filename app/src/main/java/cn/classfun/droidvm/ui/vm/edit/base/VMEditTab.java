package cn.classfun.droidvm.ui.vm.edit.base;

import static java.util.Objects.requireNonNull;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.view.View;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.enums.StringEnum;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.basic.VMEditBasicTab;
import cn.classfun.droidvm.ui.vm.edit.boot.VMEditBootTab;
import cn.classfun.droidvm.ui.vm.edit.graphics.VMEditGraphicsTab;
import cn.classfun.droidvm.ui.vm.edit.network.VMEditNetworkTab;
import cn.classfun.droidvm.ui.vm.edit.storage.VMEditStorageTab;

public enum VMEditTab implements StringEnum {
    TAB_BASIC(
        R.string.create_vm_tab_basic,
        R.id.tab_content_basic,
        VMEditBasicTab.class
    ),
    TAB_BOOT(
        R.string.create_vm_tab_boot,
        R.id.tab_content_boot,
        VMEditBootTab.class
    ),
    TAB_STORAGE(
        R.string.create_vm_tab_storage,
        R.id.tab_content_storage,
        VMEditStorageTab.class
    ),
    TAB_NETWORK(
        R.string.create_vm_tab_network,
        R.id.tab_content_network,
        VMEditNetworkTab.class
    ),
    TAB_GRAPHICS(
        R.string.create_vm_tab_graphics,
        R.id.tab_content_graphics,
        VMEditGraphicsTab.class
    );

    public static final VMEditTab DEFAULT = TAB_BASIC;

    private final @StringRes int titleId;
    private final @IdRes int layoutId;
    private final Class<? extends VMEditBaseTab> tabClass;

    VMEditTab(@StringRes int titleId, @IdRes int layoutId, Class<? extends VMEditBaseTab> tabClass) {
        this.titleId = titleId;
        this.layoutId = layoutId;
        this.tabClass = tabClass;
    }

    public int getTitleId() {
        return titleId;
    }

    public int getLayoutId() {
        return layoutId;
    }

    public Class<? extends VMEditBaseTab> getTabClass() {
        return tabClass;
    }

    @NonNull
    public VMEditBaseTab createTabInstance(@NonNull VMEditActivity activity) {
        try {
            View view = requireNonNull(activity.findViewById(getLayoutId()));
            var cons = tabClass.getConstructor(VMEditActivity.class, View.class);
            return cons.newInstance(activity, view);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tab instance", e);
        }
    }

    @NonNull
    public static VMEditTab fromTabClass(@NonNull Class<? extends VMEditBaseTab> tabClass) {
        for (var tab : VMEditTab.values())
            if (tab.getTabClass().equals(tabClass))
                return tab;
        throw new IllegalArgumentException(fmt(
            "No matching tab found for class: %s",
            tabClass.getName()
        ));
    }

    public static VMEditTab fromIndex(int index) {
        var tabs = values();
        if (index < 0 || index >= tabs.length)
            throw new IndexOutOfBoundsException(fmt("Invalid tab index: %d", index));
        return tabs[index];
    }
}
