package cn.classfun.droidvm.ui.vm.edit.portforward;

import static java.util.Objects.requireNonNull;

import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.widgets.container.CardItemListView;

public final class VMEditPortForwardTab extends VMEditBaseTab {
    private CardItemListView listPf;

    public VMEditPortForwardTab(VMEditActivity parent, View view) {
        super(parent, view);
    }

    @Override
    public void initView() {
        listPf = view.findViewById(R.id.list_port_forwards);
    }

    @Override
    public void initValue() {
        listPf.setAdapter(PortForwardEditAdapter.class);
    }

    @Override
    public void loadConfig(@NonNull VMConfig config) {
        listPf.setItems(config.item.opt("port_forwards", DataItem.newArray()));
    }

    @Override
    public boolean validateInput(@NonNull VMStore store) {
        if (!rulesValid(requireNonNull(listPf.getItems()))) {
            Toast.makeText(parent, R.string.edit_vm_pf_invalid_port, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    /** Mirrors the backend VMPortForwarder.parseRules checks (enabled rules only). */
    public static boolean rulesValid(@NonNull DataItem rules) {
        for (var iter : rules) {
            var r = iter.getValue();
            if (!r.optBoolean("enabled", true)) continue;
            long hostPort = r.optLong("host_port", 0);
            if (hostPort <= 0 || hostPort > 65535) return false;
            long guestPort = r.optLong("guest_port", 0);
            if (guestPort > 65535) return false; // 0 is valid: backend falls back to host_port
        }
        return true;
    }

    @Override
    public void saveConfig(@NonNull VMConfig config) {
        config.item.set("port_forwards", listPf.getItems());
    }
}
