package cn.classfun.droidvm.ui.main.base.list;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataConfig;
import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.main.base.MainBaseFragment;

public abstract class MainListFragment<
    D extends DataConfig,
    S extends DataStore<D>,
    A extends DataAdapter<D, S>
> extends MainBaseFragment {
    protected A adapter;
    protected RecyclerView rvList;
    protected LinearLayout layoutEmpty;
    protected final Class<A> adapterClass;
    private float lastTouchX = 0;

    public MainListFragment(@NonNull Class<A> adapterClass) {
        super();
        this.adapterClass = adapterClass;
    }

    @Override
    public boolean isShowFab() {
        return true;
    }

    @NonNull
    protected abstract Class<? extends Activity> getInfoActivity();

    @MenuRes
    protected abstract int getItemMenuResId(@NonNull D config);

    protected abstract boolean onMenuClicked(@NonNull D config, @NonNull MenuItem item);

    @SuppressWarnings("unused")
    private void onItemClick(@NonNull View v, @NonNull D config) {
        var intent = new Intent(requireContext(), getInfoActivity());
        intent.putExtra("target_id", config.getId().toString());
        startActivity(intent);
    }

    @SuppressWarnings("SameReturnValue")
    private boolean onItemLongClick(@NonNull View v, @NonNull D config) {
        var pop = new MaterialMenu(requireContext(), v);
        pop.inflate(getItemMenuResId(config));
        pop.setOnMenuItemClickListener(item -> onMenuClicked(config, item));
        pop.showAtTouch(lastTouchX, 0);
        return true;
    }

    @NonNull
    private A createAdapter() {
        try {
            var cons = adapterClass.getConstructor();
            return cons.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create adapter instance", e);
        }
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        var activity = requireActivity();
        rvList = view.findViewById(R.id.rv_list);
        layoutEmpty = view.findViewById(R.id.layout_empty);
        adapter = createAdapter();
        adapter.setOnItemClickListener(this::onItemClick);
        adapter.setOnItemLongClickListener(this::onItemLongClick);
        rvList.setLayoutManager(new LinearLayoutManager(activity));
        rvList.setAdapter(adapter);
        rvList.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN)
                    lastTouchX = e.getRawX();
                return false;
            }
        });
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshView();
    }

    @Override
    @SuppressLint("NotifyDataSetChanged")
    protected final void refreshView() {
        if (adapter == null || adapter.items == null) return;
        runOnPool(() -> {
            S tempStore = adapter.createStore();
            tempStore.load(requireContext());
            requireActivity().runOnUiThread(() -> {
                adapter.items.replace(tempStore);
                adapter.onItemsUpdated();
                boolean empty = adapter.items.isEmpty();
                rvList.setVisibility(empty ? GONE : VISIBLE);
                layoutEmpty.setVisibility(empty ? VISIBLE : GONE);
                onRefreshDone();
            });
        });
    }

    protected void onRefreshDone() {

    }
}
