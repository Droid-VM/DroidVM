// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.pkg.exports;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.StringUtils.dirname;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.pkg.DiskRef;
import cn.classfun.droidvm.ui.main.base.BaseViewHolder;

public final class VMPkgExportDiskAdapter extends RecyclerView.Adapter<BaseViewHolder> {
    public final List<DiskRef> disks = new ArrayList<>();
    public final Set<Integer> selected = new HashSet<>();
    // How many backing images each disk brings with it, and the disks whose chain could not be
    // read at all - both keyed by VM disk slot, both filled in once the background walk lands.
    private final Map<Integer, Integer> backingCounts = new HashMap<>();
    private final Map<Integer, String> chainErrors = new HashMap<>();
    private boolean enabled = true;
    private Runnable onSelectionChanged;

    @SuppressLint("NotifyDataSetChanged")
    public void setChains(
        @NonNull Map<Integer, Integer> counts,
        @NonNull Map<Integer, String> errors
    ) {
        backingCounts.clear();
        backingCounts.putAll(counts);
        chainErrors.clear();
        chainErrors.putAll(errors);
        notifyDataSetChanged();
    }

    public void setOnSelectionChanged(@NonNull Runnable onSelectionChanged) {
        this.onSelectionChanged = onSelectionChanged;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public BaseViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        var inf = LayoutInflater.from(parent.getContext());
        var v = inf.inflate(R.layout.item_main_list, parent, false);
        return new BaseViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder h, int position) {
        var disk = disks.get(position);
        var ctx = h.itemView.getContext();
        var isSel = selected.contains(disk.index);
        h.itemName.setText(basename(disk.path));
        h.itemInfo.setText(dirname(disk.path));
        h.itemInfo.setVisibility(VISIBLE);
        bindChainState(h, disk.index);
        h.itemAction.setVisibility(GONE);
        h.itemIcon.setVisibility(VISIBLE);
        var icon = disk.isCDROM() ? R.drawable.ic_cdrom : R.drawable.ic_nav_disk;
        h.itemIcon.setImageDrawable(AppCompatResources.getDrawable(ctx, icon));
        var bg = MaterialColors.getColor(h.itemCard, isSel
            ? com.google.android.material.R.attr.colorSurfaceVariant
            : com.google.android.material.R.attr.colorSurface);
        h.itemCard.setCardBackgroundColor(bg);
        h.itemCard.setEnabled(enabled);
        h.itemCard.setAlpha(enabled ? 1f : 0.64f);
        h.itemCard.setOnClickListener(enabled ? v -> toggleSelection(disk.index) : null);
    }

    /** Say what else rides along with this disk: its backing images, or that they are missing. */
    private void bindChainState(@NonNull BaseViewHolder h, int index) {
        var ctx = h.itemView.getContext();
        var broken = chainErrors.get(index);
        if (broken != null) {
            h.itemState.setVisibility(VISIBLE);
            h.itemState.setText(R.string.vmpkg_export_disk_chain_broken);
            h.itemState.setTextColor(MaterialColors.getColor(
                h.itemState, androidx.appcompat.R.attr.colorError
            ));
            return;
        }
        var count = backingCounts.get(index);
        if (count == null || count <= 0) {
            h.itemState.setVisibility(GONE);
            return;
        }
        h.itemState.setVisibility(VISIBLE);
        h.itemState.setText(ctx.getString(R.string.vmpkg_export_disk_backing_count, count));
        h.itemState.setTextColor(MaterialColors.getColor(
            h.itemState, android.R.attr.textColorSecondary
        ));
    }

    @SuppressLint("NotifyDataSetChanged")
    private void toggleSelection(int index) {
        if (selected.contains(index)) {
            selected.remove(index);
        } else {
            selected.add(index);
        }
        if (onSelectionChanged != null) onSelectionChanged.run();
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return disks.size();
    }
}
