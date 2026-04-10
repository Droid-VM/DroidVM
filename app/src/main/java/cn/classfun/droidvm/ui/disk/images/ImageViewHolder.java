package cn.classfun.droidvm.ui.disk.images;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import cn.classfun.droidvm.R;

public final class ImageViewHolder extends RecyclerView.ViewHolder {
    final MaterialCardView card;
    final TextView tvFilename;
    final TextView tvRepo;
    final TextView tvSize;
    final TextView tvDate;

    ImageViewHolder(@NonNull View v) {
        super(v);
        card = (MaterialCardView) v;
        tvFilename = v.findViewById(R.id.tv_filename);
        tvRepo = v.findViewById(R.id.tv_repo);
        tvSize = v.findViewById(R.id.tv_size);
        tvDate = v.findViewById(R.id.tv_date);
    }

    void render(@NonNull FlatImage fi, boolean selected) {
        tvFilename.setText(fi.displayFilename);
        tvRepo.setText(fi.displayRepo);
        tvSize.setText(fi.displaySize);
        tvDate.setText(fi.displayDate);
        card.setChecked(selected);
    }
}
