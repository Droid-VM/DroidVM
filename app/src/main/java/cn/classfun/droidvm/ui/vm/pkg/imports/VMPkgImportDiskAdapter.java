package cn.classfun.droidvm.ui.vm.pkg.imports;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.pkg.DiskEntry;
import cn.classfun.droidvm.ui.main.base.BaseViewHolder;

public final class VMPkgImportDiskAdapter extends RecyclerView.Adapter<BaseViewHolder> {
    public final List<DiskEntry> disks = new ArrayList<>();

    @NonNull
    @Override
    public BaseViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        var inf = LayoutInflater.from(parent.getContext());
        return new BaseViewHolder(inf.inflate(R.layout.item_main_list, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder h, int position) {
        var disk = disks.get(position);
        var ctx = h.itemView.getContext();
        h.itemName.setText(disk.name);
        h.itemInfo.setText(formatSize(disk.size));
        h.itemInfo.setVisibility(VISIBLE);
        h.itemState.setVisibility(GONE);
        h.itemAction.setVisibility(GONE);
        h.itemIcon.setVisibility(VISIBLE);
        h.itemIcon.setImageDrawable(AppCompatResources.getDrawable(ctx, R.drawable.ic_nav_disk));
        h.itemCard.setEnabled(false);
        h.itemCard.setClickable(false);
    }

    @Override
    public int getItemCount() {
        return disks.size();
    }
}
