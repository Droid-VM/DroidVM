// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.widgets.container;

import android.content.Context;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.ui.SimpleAdapterDataObserver;

public final class CardItemListView extends LinearLayout {
    private final Context context;
    private TextView emptyView;
    private RecyclerView listView;
    private MaterialButton addButton;
    private CardItemAdapter<?> adapter;
    private boolean reorderable = false;
    private boolean dragging = false;

    public CardItemListView(@NonNull Context context) {
        super(context);
        this.context = context;
        init(null);
    }

    public CardItemListView(
        @NonNull Context context,
        @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        this.context = context;
        init(attrs);
    }

    public CardItemListView(
        @NonNull Context context,
        @Nullable AttributeSet attrs,
        int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        this.context = context;
        init(attrs);
    }

    private void init(@Nullable AttributeSet attrs) {
        setOrientation(VERTICAL);
        var inf = LayoutInflater.from(context);
        inf.inflate(R.layout.widget_card_item_list, this, true);
        emptyView = findViewById(R.id.cl_empty);
        listView = findViewById(R.id.cl_list);
        addButton = findViewById(R.id.cl_add);
        listView.setLayoutManager(new LinearLayoutManager(context));
        initAttrs(attrs);
        if (isInEditMode()) return;
        updateEmptyState();
        addButton.setOnClickListener(v -> {
            if (adapter != null)
                adapter.onAddRequested(v);
        });
    }

    private void initAttrs(@Nullable AttributeSet attrs) {
        if (attrs == null) return;
        try (var a = context.obtainStyledAttributes(attrs, R.styleable.CardItemListView)) {
            var hint = a.getString(R.styleable.CardItemListView_android_hint);
            if (hint != null) emptyView.setText(hint);
            var text = a.getString(R.styleable.CardItemListView_android_text);
            if (text != null) addButton.setText(text);
            var icon = a.getDrawable(R.styleable.CardItemListView_android_icon);
            if (icon != null) addButton.setIcon(icon);
            reorderable = a.getBoolean(R.styleable.CardItemListView_reorderable, false);
        }
        if (reorderable) attachReorder();
    }

    /**
     * Long-press a card to lift it, drag up or down to reorder, release to drop. The helper
     * asks the enclosing scroll view not to intercept once a drag starts, so the page stays
     * still under the finger; a single card has nowhere to go and is not liftable.
     */
    private void attachReorder() {
        var callback = new ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(
                @NonNull RecyclerView rv,
                @NonNull RecyclerView.ViewHolder vh,
                @NonNull RecyclerView.ViewHolder target
            ) {
                if (adapter == null) return false;
                int from = vh.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION)
                    return false;
                adapter.moveItem(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return adapter != null && adapter.getItemCount() > 1;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public void onSelectedChanged(@Nullable RecyclerView.ViewHolder vh, int state) {
                super.onSelectedChanged(vh, state);
                if (state == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                    dragging = true;
                    vh.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    vh.itemView.setAlpha(0.85f);
                    vh.itemView.setScaleX(1.02f);
                    vh.itemView.setScaleY(1.02f);
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                vh.itemView.setAlpha(1f);
                vh.itemView.setScaleX(1f);
                vh.itemView.setScaleY(1f);
                if (dragging) {
                    dragging = false;
                    if (adapter != null) adapter.onReorderFinished();
                }
            }
        };
        new ItemTouchHelper(callback).attachToRecyclerView(listView);
    }

    public void setAdapter(@Nullable CardItemAdapter<?> adapter) {
        this.adapter = adapter;
        listView.setAdapter(adapter);
        if (adapter != null) {
            adapter.registerAdapterDataObserver(
                new SimpleAdapterDataObserver(this::updateEmptyState)
            );
        }
        updateEmptyState();
    }

    @NonNull
    public <T extends CardItemAdapter<?>> T setAdapter(@NonNull Class<T> adapter) {
        try {
            var constructor = adapter.getConstructor(Context.class);
            var instance = constructor.newInstance(context);
            setAdapter(instance);
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create adapter instance", e);
        }
    }

    @Nullable
    public DataItem getItems() {
        if (adapter == null) return null;
        return adapter.getItems();
    }

    @SuppressWarnings("unchecked")
    public <V extends RecyclerView.ViewHolder> void setItems(@Nullable DataItem items) {
        if (adapter == null)
            throw new IllegalStateException("Adapter is not set");
        var a = (CardItemAdapter<V>) adapter;
        a.setItems(items);
    }

    private void updateEmptyState() {
        var adapter = listView.getAdapter();
        boolean empty = adapter == null || adapter.getItemCount() == 0;
        emptyView.setVisibility(empty ? VISIBLE : GONE);
    }
}
