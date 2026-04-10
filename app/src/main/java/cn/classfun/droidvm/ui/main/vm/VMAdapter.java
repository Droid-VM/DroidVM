package cn.classfun.droidvm.ui.main.vm;

import static android.view.View.VISIBLE;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.main.base.BaseViewHolder;
import cn.classfun.droidvm.ui.main.base.stateful.StatefulAdapter;

public final class VMAdapter extends StatefulAdapter<VMConfig, VMStore, VMState> {
    public VMAdapter() {
        super(VMStore.class, VMState.class);
    }

    @Override
    public int getIconResId() {
        return R.drawable.ic_nav_vm;
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder holder, int position) {
        var ctx = holder.itemView.getContext();
        var config = items.get(position);
        holder.itemInfo.setVisibility(VISIBLE);
        holder.itemInfo.setText(ctx.getString(
            R.string.vm_item_info,
            config.item.optLong("cpu_count", 0),
            config.item.optLong("memory_mb", 0)
        ));
        super.onBindViewHolder(holder, position);
    }
}
