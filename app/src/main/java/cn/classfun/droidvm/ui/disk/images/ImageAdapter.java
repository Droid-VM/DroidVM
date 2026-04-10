package cn.classfun.droidvm.ui.disk.images;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.function.Consumer;

import cn.classfun.droidvm.R;

public final class ImageAdapter extends RecyclerView.Adapter<ImageViewHolder> {
    private final FlatImage.FlatImages items;
    private final Consumer<FlatImage> onItemClick;
    private FlatImage selected;

    public ImageAdapter(
        @NonNull FlatImage.FlatImages items,
        @Nullable Consumer<FlatImage> onItemClick
    ) {
        this.items = items;
        this.onItemClick = onItemClick;
    }

    @SuppressWarnings("unused")
    public void setSelected(@Nullable FlatImage fi) {
        this.selected = fi;
    }

    @NonNull
    @Override
    public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        var v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_image_card, parent, false);
        return new ImageViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ImageViewHolder h, int position) {
        var fi = items.get(position);
        h.render(fi, fi == selected);
        h.card.setOnClickListener(v -> {
            if (onItemClick != null) onItemClick.accept(fi);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void onChanged() {
        notifyDataSetChanged();
    }
}