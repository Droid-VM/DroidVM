package cn.classfun.droidvm.ui.widgets.container;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import cn.classfun.droidvm.lib.store.base.DataItem;

public abstract class CardItemAdapter<
    V extends RecyclerView.ViewHolder
    > extends RecyclerView.Adapter<V> {
    protected final Handler mainHandler = new Handler(Looper.getMainLooper());
    protected final DataItem items = DataItem.newArray();
    protected final Context context;

    public CardItemAdapter(@NonNull Context context) {
        this.context = context;
    }

    @SuppressLint("NotifyDataSetChanged")
    public final void setItems(@Nullable DataItem items) {
        this.items.clear();
        if (items != null)
            this.items.puts(items);
        notifyDataSetChanged();
    }

    @NonNull
    public final DataItem getItems() {
        return new DataItem(items);
    }

    public final void createItem() {
        items.append(DataItem.newObject());
        notifyItemInserted(items.size() - 1);
    }

    public final void removeItem(int position) {
        if (position < 0 || position >= items.size()) return;
        items.remove(position);
        notifyItemRemoved(position);
        notifyItemRangeChanged(position, items.size() - position);
    }

    @Override
    public final int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public V onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        var inf = LayoutInflater.from(context);
        var view = inf.inflate(getLayoutRes(), parent, false);
        return createViewHolderInstance(view);
    }

    @NonNull
    protected abstract V createViewHolderInstance(@NonNull View view);

    @LayoutRes
    protected abstract int getLayoutRes();
}