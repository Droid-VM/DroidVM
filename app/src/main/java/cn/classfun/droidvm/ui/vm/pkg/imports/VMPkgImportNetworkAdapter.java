package cn.classfun.droidvm.ui.vm.pkg.imports;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.ui.main.base.BaseViewHolder;

public final class VMPkgImportNetworkAdapter extends RecyclerView.Adapter<BaseViewHolder> {
    public final List<NetworkConfig> networks = new ArrayList<>();

    @NonNull
    @Override
    public BaseViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        var inf = LayoutInflater.from(parent.getContext());
        return new BaseViewHolder(inf.inflate(R.layout.item_main_list, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder h, int position) {
        var network = networks.get(position);
        var ctx = h.itemView.getContext();
        var bridge = network.getBridgeName();
        h.itemName.setText(network.getName());
        h.itemInfo.setText(bridge == null || bridge.isEmpty() ? network.getUplinkMode().key() : bridge);
        h.itemInfo.setVisibility(VISIBLE);
        h.itemState.setVisibility(GONE);
        h.itemAction.setVisibility(GONE);
        h.itemIcon.setVisibility(VISIBLE);
        h.itemIcon.setImageDrawable(AppCompatResources.getDrawable(ctx, R.drawable.ic_nav_network));
        h.itemCard.setEnabled(false);
        h.itemCard.setClickable(false);
    }

    @Override
    public int getItemCount() {
        return networks.size();
    }
}
