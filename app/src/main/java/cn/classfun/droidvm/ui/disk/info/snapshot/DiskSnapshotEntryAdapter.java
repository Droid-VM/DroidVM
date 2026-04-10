package cn.classfun.droidvm.ui.disk.info.snapshot;

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

final class DiskSnapshotEntryAdapter extends RecyclerView.Adapter<DiskSnapshotEntryViewHolder> {

    interface OnItemClickListener {
        @SuppressWarnings("unused")
        void onItemClick(@NonNull View view, @NonNull Entry entry);
    }

    static final class Entry {
        final String snapshotName;
        final String line1;
        final String line2;

        Entry(@NonNull String snapshotName, @NonNull String line1, @NonNull String line2) {
            this.snapshotName = snapshotName;
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
    public DiskSnapshotEntryViewHolder onCreateViewHolder(
        @NonNull ViewGroup parent, int viewType
    ) {
        var view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_disk_entry, parent, false);
        return new DiskSnapshotEntryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(
        @NonNull DiskSnapshotEntryViewHolder holder,
        int position
    ) {
        var entry = items.get(position);
        holder.ivIcon.setImageResource(R.drawable.ic_floppy);
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
