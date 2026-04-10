package cn.classfun.droidvm.ui.logs;

import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

public final class LogViewHolder extends RecyclerView.ViewHolder {
    final TextView tvLine;

    LogViewHolder(View v) {
        super(v);
        tvLine = (TextView) v;
    }
}
