package cn.classfun.droidvm.ui.main.base;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.UIContext;

public abstract class MainBaseFragment extends Fragment implements MenuProvider {
    protected final String TAG = getClass().getSimpleName();
    protected MainFragmentEnum fragmentId;
    protected final Handler mainHandler = new Handler(Looper.getMainLooper());
    protected final UIContext uiContext = UIContext.fromFragment(this);

    protected abstract @LayoutRes int getLayoutResId();

    public abstract @StringRes int getTitleResId();

    protected abstract @MenuRes int getCustomMenuResId();

    public void onFabClick(@NonNull View v) {
    }

    public boolean isShowFab() {
        return false;
    }

    public final boolean isDefaultHide() {
        return fragmentId != MainFragmentEnum.DEFAULT;
    }

    protected void refreshView() {
    }

    @Nullable
    @Override
    public final View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(getLayoutResId(), container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        var act = requireActivity();
        if (!isHidden())
            act.addMenuProvider(this, getViewLifecycleOwner());
        refreshView();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshView();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) refreshView();
        var activity = getActivity();
        if (activity == null) return;
        if (hidden) {
            activity.removeMenuProvider(this);
        } else {
            activity.addMenuProvider(this, getViewLifecycleOwner());
        }
    }

    @Override
    public final void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(getCustomMenuResId(), menu);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        var id = item.getItemId();
        if (id == R.id.menu_refresh) {
            refreshView();
            return true;
        }
        return false;
    }
}
