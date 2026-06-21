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
        String typeLabel = ctx.getString(
            config.getBridgeType() == cn.classfun.droidvm.lib.store.network.BridgeType.GVISOR
                ? R.string.network_edit_bridge_type_gvisor
                : R.string.network_edit_bridge_type_linux);
        String modeLabel;
        switch (config.getUplinkMode()) {
            case L2:
                modeLabel = ctx.getString(R.string.network_edit_uplink_l2);
                break;
            case L3:
                modeLabel = ctx.getString(R.string.network_edit_uplink_l3);
                break;
            default:
                modeLabel = ctx.getString(R.string.network_edit_uplink_none);
                break;
        }
        holder.itemInfo.setVisibility(VISIBLE);
        holder.itemInfo.setText(ctx.getString(
            R.string.network_item_subtitle, typeLabel, modeLabel));
        super.onBindViewHolder(holder, position);
    }
}
