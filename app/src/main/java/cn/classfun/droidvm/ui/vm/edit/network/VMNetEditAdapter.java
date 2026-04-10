package cn.classfun.droidvm.ui.vm.edit.network;

import static cn.classfun.droidvm.lib.utils.NetUtils.generateRandomMac;
import static cn.classfun.droidvm.lib.utils.StringUtils.getEditText;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.ui.widgets.container.CardItemAdapter;

public final class VMNetEditAdapter extends CardItemAdapter<VMNetEditViewHolder> {
    public VMNetEditAdapter(@NonNull Context context) {
        super(context);
    }

    @NonNull
    @Override
    protected VMNetEditViewHolder createViewHolderInstance(@NonNull View view) {
        return new VMNetEditViewHolder(view);
    }

    @Override
    protected int getLayoutRes() {
        return R.layout.item_vm_net_edit;
    }

    @Override
    public void onBindViewHolder(@NonNull VMNetEditViewHolder holder, int position) {
        var net = items.get(position);
        updateSelectButton(holder.btnSelect, net.optString("network_id", ""));
        holder.btnSelect.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            showNetworkPicker(pos, holder.btnSelect);
        });
        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                removeItem(pos);
        });
        var mac = net.optString("mac_address", "");
        if (mac.isEmpty()) {
            mac = generateRandomMac();
            net.set("mac_address", mac);
        }
        holder.etMac.setText(mac);
        holder.etMac.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION)
                    items.get(pos).set("mac_address", getEditText(holder.etMac));
            }
        });
        holder.btnMacRandom.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            var randomMac = generateRandomMac();
            holder.etMac.setText(randomMac);
            items.get(pos).set("mac_address", randomMac);
        });
    }

    private void showNetworkPicker(int position, MaterialButton btn) {
        var netStore = new NetworkStore();
        netStore.load(context);
        var ids = new ArrayList<String>();
        var names = new ArrayList<String>();
        ids.add("");
        names.add(context.getString(R.string.create_vm_network_none));
        netStore.forEach((id, config) -> {
            ids.add(id.toString());
            names.add(config.getName());
        });
        var currentId = items.get(position).optString("network_id", "");
        int checked = Math.max(0, ids.indexOf(currentId));
        DialogInterface.OnClickListener onclick = (dialog, which) -> {
            items.get(position).set("network_id", ids.get(which));
            updateSelectButton(btn, ids.get(which));
            dialog.dismiss();
        };
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.create_vm_network_label)
            .setSingleChoiceItems(names.toArray(new String[0]), checked, onclick)
            .show();
    }

    private void updateSelectButton(MaterialButton btn, String networkId) {
        if (networkId == null || networkId.isEmpty()) {
            btn.setText(R.string.create_vm_network_none);
            return;
        }
        var netStore = new NetworkStore();
        netStore.load(context);
        var net = netStore.findById(networkId);
        btn.setText(net != null ? net.getName() :
            context.getString(R.string.create_vm_network_none));
    }
}
