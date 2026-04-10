package cn.classfun.droidvm.ui.widgets.container;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
                adapter.createItem();
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
        }
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
