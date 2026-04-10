package cn.classfun.droidvm.ui.disk.info.tree;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;

final class DiskTreeEntryAdapter extends RecyclerView.Adapter<DiskTreeEntryViewHolder> {

    interface OnItemClickListener {
        @SuppressWarnings("unused")
        void onItemClick(@NonNull View view, @NonNull Entry entry);
    }

    static final class Entry {
        final int iconResId;
        final String filename;
        final String line1;
        final String line2;

        Entry(int iconResId, @NonNull String filename, @NonNull String line1, @NonNull String line2) {
            this.iconResId = iconResId;
            this.filename = filename;
            this.line1 = line1;
            this.line2 = line2;
        }
    }

    private final List<Entry> items = new ArrayList<>();
    private @Nullable OnItemClickListener clickListener;

    void setOnItemClickListener(@Nullable OnItemClickListener listener) {
        this.clickListener = listener;
    }

    @NonNull
    @Override
    public DiskTreeEntryViewHolder onCreateViewHolder(
        @NonNull ViewGroup parent, int viewType
    ) {
        var inf = LayoutInflater.from(parent.getContext());
        var view = inf.inflate(R.layout.item_disk_entry, parent, false);
        return new DiskTreeEntryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(
        @NonNull DiskTreeEntryViewHolder holder,
        int position
    ) {
        var entry = items.get(position);
        holder.ivIcon.setImageResource(entry.iconResId);
        holder.tvLine1.setText(entry.line1);
        holder.tvLine2.setText(entry.line2);
        holder.tvLine2.setVisibility(entry.line2.isEmpty() ? GONE : VISIBLE);
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION)
                    clickListener.onItemClick(v, items.get(pos));
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @SuppressLint("NotifyDataSetChanged")
    void setItems(@NonNull List<Entry> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }
}
