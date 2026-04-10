package cn.classfun.droidvm.ui.main.settings;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

import cn.classfun.droidvm.R;

final class ApiServiceViewHolder extends RecyclerView.ViewHolder {
    final TextView tvName;
    final TextView tvUrl;
    final MaterialSwitch swEnabled;

    ApiServiceViewHolder(@NonNull View itemView) {
        super(itemView);
        tvName = itemView.findViewById(R.id.tv_service_name);
        tvUrl = itemView.findViewById(R.id.tv_service_url);
        swEnabled = itemView.findViewById(R.id.sw_service_enabled);
    }
}
