package cn.classfun.droidvm.ui.vm.edit.network;

import static java.util.Objects.requireNonNull;

import android.view.View;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.widgets.container.CardItemListView;

public final class VMEditNetworkTab extends VMEditBaseTab {
    private CardItemListView listNets;

    public VMEditNetworkTab(VMEditActivity parent, View view) {
        super(parent, view);
    }

    @Override
    public void initView() {
        listNets = view.findViewById(R.id.list_nets);
    }

    @Override
    public void initValue() {
        listNets.setAdapter(VMNetEditAdapter.class);
    }

    @Override
    public void loadConfig(@NonNull VMConfig config) {
        listNets.setItems(config.item.opt("networks", DataItem.newArray()));
    }

    @Override
    public boolean validateInput(@NonNull VMStore store) {
        var nets = requireNonNull(listNets.getItems());
        for (var net : nets)
            if (net.getValue().optString("network_id", "").isEmpty())
                return false;
        return true;
    }

    @Override
    public void saveConfig(@NonNull VMConfig config) {
        config.item.set("networks", listNets.getItems());
    }
}
