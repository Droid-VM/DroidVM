package cn.classfun.droidvm.ui.disk.info.base;

import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.ui.disk.info.DiskInfoActivity;
import cn.classfun.droidvm.ui.disk.info.info.DiskInfoInfoTab;
import cn.classfun.droidvm.ui.disk.info.snapshot.DiskInfoSnapshotTab;
import cn.classfun.droidvm.ui.disk.info.tree.DiskInfoTreeTab;

public enum DiskInfoTab {
    TAB_INFO(R.string.disk_info_tab_info, DiskInfoInfoTab.class),
    TAB_TREE(R.string.disk_info_tab_tree, DiskInfoTreeTab.class),
    TAB_SNAPSHOT(R.string.disk_info_tab_snapshot, DiskInfoSnapshotTab.class);

    private final @StringRes int titleId;
    private final Class<? extends DiskInfoBaseTab> tabClass;

    DiskInfoTab(@StringRes int titleId, Class<? extends DiskInfoBaseTab> tabClass) {
        this.titleId = titleId;
        this.tabClass = tabClass;
    }

    public int getTitleId() {
        return titleId;
    }

    @NonNull
    public DiskInfoBaseTab createTabInstance(
        @NonNull DiskInfoActivity activity,
        @NonNull FrameLayout tabContainer
    ) {
        try {
            var cons = tabClass.getConstructor(DiskInfoActivity.class, FrameLayout.class);
            return cons.newInstance(activity, tabContainer);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tab instance", e);
        }
    }
}
