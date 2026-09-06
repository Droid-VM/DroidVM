// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
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
        appendItem(DataItem.newObject());
    }

    /** Appends one prepared item, for adapters whose rows are not blank to begin with. */
    protected final void appendItem(@NonNull DataItem item) {
        items.append(item);
        notifyItemInserted(items.size() - 1);
    }

    /**
     * The list's + button was pressed, with {@code anchor} the button itself. Appends one blank
     * item; adapters whose rows need something decided first (a type, a target) override this
     * and call {@link #appendItem} once they have it.
     */
    public void onAddRequested(@NonNull View anchor) {
        createItem();
    }

    /**
     * Drag reorder step: the item at {@code from} now sits at {@code to}. Rows are looked up
     * by binding position at event time, so nothing else needs rebinding until the drop.
     */
    public final void moveItem(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= items.size() || to >= items.size())
            return;
        var list = items.asArray();
        var moved = list.remove(from);
        list.add(to, moved);
        notifyItemMoved(from, to);
        onItemMoved(from, to);
    }

    /** Called after {@link #moveItem}; adapters whose neighbours index into the list hook it. */
    protected void onItemMoved(int from, int to) {
    }

    /** The drag ended: positions shifted for a whole range, so rebind everything. */
    @SuppressLint("NotifyDataSetChanged")
    public void onReorderFinished() {
        notifyDataSetChanged();
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