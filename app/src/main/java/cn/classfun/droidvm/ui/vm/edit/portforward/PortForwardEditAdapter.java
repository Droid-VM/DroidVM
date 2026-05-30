package cn.classfun.droidvm.ui.vm.edit.portforward;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.enums.Enums;
import cn.classfun.droidvm.lib.store.network.NetworkStore;
import cn.classfun.droidvm.lib.store.vm.PortProtocol;
import cn.classfun.droidvm.lib.ui.SimpleTextWatcher;
import cn.classfun.droidvm.ui.widgets.container.CardItemAdapter;

public final class PortForwardEditAdapter extends CardItemAdapter<PortForwardEditViewHolder> {
    public PortForwardEditAdapter(@NonNull Context context) {
        super(context);
    }

    @NonNull
    @Override
    protected PortForwardEditViewHolder createViewHolderInstance(@NonNull View view) {
        return new PortForwardEditViewHolder(view);
    }

    @Override
    protected int getLayoutRes() {
        return R.layout.item_port_forward_edit;
    }

    @Override
    public void onBindViewHolder(@NonNull PortForwardEditViewHolder holder, int position) {
        var item = items.get(position);
        holder.unbindWatchers();

        holder.btnProtocol.configure(
            PortProtocol.class, Enums.optEnum(item, "protocol", PortProtocol.TCP));
        holder.btnProtocol.setOnValueChangedListener((oldVal, newVal) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                items.get(pos).set("protocol", newVal);
        });

        long hostPort = item.optLong("host_port", 0);
        holder.etHostPort.setText(hostPort > 0 ? String.valueOf(hostPort) : "");
        holder.hostPortWatcher = SimpleTextWatcher.simpleAfterTextWatcher(s -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                setPort(items.get(pos), "host_port", s.toString());
        });
        holder.etHostPort.addTextChangedListener(holder.hostPortWatcher);

        // Guest port (empty = same as host port, backend falls back)
        long guestPort = item.optLong("guest_port", 0);
        holder.etGuestPort.setText(guestPort > 0 ? String.valueOf(guestPort) : "");
        holder.guestPortWatcher = SimpleTextWatcher.simpleAfterTextWatcher(s -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                setPort(items.get(pos), "guest_port", s.toString());
        });
        holder.etGuestPort.addTextChangedListener(holder.guestPortWatcher);

        holder.switchEnabled.setOnCheckedChangeListener(null);
        holder.switchEnabled.setChecked(item.optBoolean("enabled", true));
        holder.switchEnabled.setOnCheckedChangeListener((btn, checked) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                items.get(pos).set("enabled", checked);
        });

        holder.etHostIp.setText(item.optString("host_ip", ""));
        holder.hostIpWatcher = SimpleTextWatcher.simpleAfterTextWatcher(s -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                items.get(pos).set("host_ip", s.toString().trim());
        });
        holder.etHostIp.addTextChangedListener(holder.hostIpWatcher);

        holder.etGuestIp.setText(item.optString("guest_ip", ""));
        holder.guestIpWatcher = SimpleTextWatcher.simpleAfterTextWatcher(s -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                items.get(pos).set("guest_ip", s.toString().trim());
        });
        holder.etGuestIp.addTextChangedListener(holder.guestIpWatcher);

        updateNetworkButton(holder.btnNetwork, item.optString("network_id", ""));
        holder.btnNetwork.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                showNetworkPicker(pos, holder.btnNetwork);
        });

        // Advanced section expand state: expanded by default when advanced values exist (data-driven, safe to reuse)
        boolean hasAdvanced = !item.optString("host_ip", "").isEmpty()
            || !item.optString("guest_ip", "").isEmpty()
            || !item.optString("network_id", "").isEmpty();
        applyAdvancedState(holder, hasAdvanced);
        holder.btnAdvanced.setOnClickListener(v -> {
            boolean show = holder.layoutAdvanced.getVisibility() != View.VISIBLE;
            applyAdvancedState(holder, show);
        });

        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION)
                removeItem(pos);
        });
    }

    private void applyAdvancedState(@NonNull PortForwardEditViewHolder holder, boolean expanded) {
        holder.layoutAdvanced.setVisibility(expanded ? View.VISIBLE : View.GONE);
        holder.btnAdvanced.setIconResource(
            expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
    }

    /** Writes the port as an integer; empty/invalid becomes 0 (backend: host_port=0 is skipped, guest_port=0 falls back to host_port). */
    private void setPort(@NonNull DataItem item, @NonNull String key, @NonNull String text) {
        text = text.trim();
        if (text.isEmpty()) {
            item.set(key, 0L);
            return;
        }
        try {
            item.set(key, (long) Integer.parseInt(text));
        } catch (NumberFormatException e) {
            item.set(key, 0L);
        }
    }

    private void showNetworkPicker(int position, @NonNull MaterialButton btn) {
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
            updateNetworkButton(btn, ids.get(which));
            dialog.dismiss();
        };
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.edit_vm_pf_network)
            .setSingleChoiceItems(names.toArray(new String[0]), checked, onclick)
            .show();
    }

    private void updateNetworkButton(@NonNull MaterialButton btn, String networkId) {
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
