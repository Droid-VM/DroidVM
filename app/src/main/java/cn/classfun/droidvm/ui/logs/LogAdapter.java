package cn.classfun.droidvm.ui.logs;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;

public final class LogAdapter extends RecyclerView.Adapter<LogViewHolder> {
    private final List<String> lines = new ArrayList<>();

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        var inf = LayoutInflater.from(parent.getContext());
        var view = inf.inflate(R.layout.item_log_line, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        holder.tvLine.setText(lines.get(position));
    }

    @Override
    public int getItemCount() {
        return lines.size();
    }

    public void appendLines(@NonNull List<String> newLines, int maxLines) {
        if (newLines.isEmpty()) return;
        int removeCount;
        int total = lines.size() + newLines.size();
        if (total > maxLines) {
            removeCount = total - maxLines;
            if (removeCount > lines.size()) removeCount = lines.size();
            lines.subList(0, removeCount).clear();
            notifyItemRangeRemoved(0, removeCount);
        }
        int insertStart = lines.size();
        lines.addAll(newLines);
        notifyItemRangeInserted(insertStart, newLines.size());
    }

    public void clear() {
        int size = lines.size();
        lines.clear();
        notifyItemRangeRemoved(0, size);
    }

    @NonNull
    public List<String> getLines() {
        return lines;
    }
}

