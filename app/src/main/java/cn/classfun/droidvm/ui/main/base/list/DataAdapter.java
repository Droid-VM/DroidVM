package cn.classfun.droidvm.ui.main.base.list;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataConfig;
import cn.classfun.droidvm.lib.store.base.DataStore;
import cn.classfun.droidvm.ui.main.base.BaseViewHolder;

public abstract class DataAdapter<
    D extends DataConfig,
    S extends DataStore<D>
> extends RecyclerView.Adapter<BaseViewHolder> {
    protected final String TAG = getClass().getSimpleName();
    public final S items;
    protected OnItemClickListener<D> onItemClickListener;
    protected OnItemLongClickListener<D> onItemLongClickListener;
    protected final Handler mainHandler = new Handler(Looper.getMainLooper());
    protected final Class<S> storeClass;

    public DataAdapter(@NonNull Class<S> storeClass) {
        this.storeClass = storeClass;
        this.items = createStore();
    }

    @NonNull
    protected S createStore() {
        try {
            var cons = storeClass.getConstructor();
            return cons.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create store instance", e);
        }
    }

    @NonNull
    @Override
    public final BaseViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        var inf = LayoutInflater.from(parent.getContext());
        View v = inf.inflate(R.layout.item_main_list, parent, false);
        return new BaseViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder h, int position) {
        var d = items.get(position);
        var ctx = h.itemView.getContext();
        var drawable = AppCompatResources.getDrawable(ctx, getIconResId());
        h.itemIcon.setImageDrawable(drawable);
        if (d != null)
            h.itemName.setText(d.getName());
        h.itemCard.setOnClickListener(v -> {
            if (onItemClickListener != null && d != null)
                onItemClickListener.onClick(v, d);
        });
        h.itemCard.setOnLongClickListener(v -> {
            if (onItemLongClickListener != null && d != null)
                return onItemLongClickListener.onLongClick(v, d);
            return false;
        });
    }

    @DrawableRes
    public abstract int getIconResId();

    @SuppressLint("NotifyDataSetChanged")
    public void onItemsUpdated() {
        notifyDataSetChanged();
    }

    @Override
    public final int getItemCount() {
        return items.size();
    }

    public final void setOnItemClickListener(OnItemClickListener<D> l) {
        this.onItemClickListener = l;
    }

    public final void setOnItemLongClickListener(OnItemLongClickListener<D> l) {
        this.onItemLongClickListener = l;
    }

    public interface OnItemClickListener<D extends DataConfig> {
        @SuppressWarnings("unused")
        void onClick(@NonNull View v, @NonNull D data);
    }

    public interface OnItemLongClickListener<D extends DataConfig> {
        @SuppressWarnings("unused")
        boolean onLongClick(@NonNull View v, @NonNull D data);
    }
}
