package cn.classfun.droidvm.ui.disk.info.base;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;

import cn.classfun.droidvm.ui.disk.info.DiskInfoActivity;

public abstract class DiskInfoBaseTab {
    protected final DiskInfoActivity activity;
    protected final NestedScrollView scrollView;
    protected final View view;

    public DiskInfoBaseTab(
        @NonNull DiskInfoActivity activity,
        @NonNull FrameLayout tabContainer
    ) {
        this.activity = activity;
        this.scrollView = new NestedScrollView(activity);
        scrollView.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        scrollView.setFillViewport(true);
        var inf = LayoutInflater.from(activity);
        this.view = inf.inflate(getLayoutResId(), scrollView, false);
        scrollView.addView(view);
        tabContainer.addView(scrollView);
    }

    protected abstract @LayoutRes int getLayoutResId();

    public boolean isShow() {
        return true;
    }

    @NonNull
    public NestedScrollView getScrollView() {
        return scrollView;
    }

    public abstract void onCreateView();

    public abstract void onConfigLoaded();

    public abstract void onDataLoaded();

    public void onTabSelected() {}

    public void onTabDeselected() {}
}
