package cn.classfun.droidvm.ui.main.network;

import static android.view.View.VISIBLE;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.network.NetworkConfig;
import cn.classfun.droidvm.lib.store.network.NetworkState;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.ui.main.base.BaseViewHolder;
import cn.classfun.droidvm.ui.main.base.stateful.StatefulAdapter;

public final class NetworkAdapter extends StatefulAdapter<NetworkConfig, NetworkStore, NetworkState> {
    public NetworkAdapter() {
        super(NetworkStore.class, NetworkState.class);
    }

    @Override
    public int getIconResId() {
        return R.drawable.ic_switch;
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder holder, int position) {
        var ctx = holder.itemView.getContext();
        var config = items.get(position);
        var addrCount = config.item.get("ipv4_addresses").size()
            + config.item.get("ipv6_addresses").size();
        holder.itemInfo.setVisibility(VISIBLE);
        holder.itemInfo.setText(ctx.getResources().getQuantityString(
            R.plurals.network_item_info, addrCount,
            config.item.optString("bridge_name", ""), addrCount
        ));
        super.onBindViewHolder(holder, position);
    }
}
