package cn.classfun.droidvm.ui.disk.info.snapshot;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import cn.classfun.droidvm.R;

final class DiskSnapshotEntryViewHolder extends RecyclerView.ViewHolder {
    final ImageView ivIcon;
    final TextView tvLine1;
    final TextView tvLine2;

    DiskSnapshotEntryViewHolder(@NonNull View itemView) {
        super(itemView);
        ivIcon = itemView.findViewById(R.id.iv_icon);
        tvLine1 = itemView.findViewById(R.id.tv_line1);
        tvLine2 = itemView.findViewById(R.id.tv_line2);
    }
}
