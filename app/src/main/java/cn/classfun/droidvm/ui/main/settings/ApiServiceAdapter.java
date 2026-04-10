package cn.classfun.droidvm.ui.main.settings;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.api.ApiManager;
import cn.classfun.droidvm.lib.api.ApiServiceInfo;

final class ApiServiceAdapter extends RecyclerView.Adapter<ApiServiceViewHolder> {
    private final List<ApiServiceInfo> services;
    private final ApiManager apiManager;

    ApiServiceAdapter(
        @NonNull List<ApiServiceInfo> services,
        @NonNull ApiManager apiManager
    ) {
        this.services = services;
        this.apiManager = apiManager;
    }

    @NonNull
    @Override
    public ApiServiceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        var view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_api_service, parent, false);
        return new ApiServiceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ApiServiceViewHolder holder, int position) {
        var service = services.get(position);
        holder.tvName.setText(service.getName());
        holder.tvUrl.setText(apiManager.getApiRawUrl(service.getId()));
        holder.swEnabled.setOnCheckedChangeListener(null);
        holder.swEnabled.setChecked(apiManager.isServiceEnabled(service.getId()));
        holder.swEnabled.setOnCheckedChangeListener((btn, checked) ->
            apiManager.setServiceEnabled(service.getId(), checked));
        holder.itemView.setOnClickListener(v -> holder.swEnabled.toggle());
    }

    @Override
    public int getItemCount() {
        return services.size();
    }
}
