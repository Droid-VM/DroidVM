// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.main.disk;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;
import cn.classfun.droidvm.ui.disk.tree.DiskTree;
import cn.classfun.droidvm.ui.disk.tree.DiskTreeCollapse;
import cn.classfun.droidvm.ui.main.base.BaseViewHolder;
import cn.classfun.droidvm.ui.main.base.list.DataAdapter;

public final class DiskAdapter extends DataAdapter<DiskConfig, DiskStore> {
    private static final int INDENT_DP = 16;
    private static final int MAX_INDENT_STEPS = 4;

    private final Map<String, ImageInfo> infoCache = new HashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // The overlay forest flattened for display: rows follow tree order, collapsed subtrees are
    // hidden. Rebuilt from the store on every refresh; collapse choices persist via
    // DiskTreeCollapse (injected context - the reflective adapter construction has none).
    private final List<DiskTree.Node> flat = new ArrayList<>();
    private final Set<UUID> collapsed = new HashSet<>();
    @Nullable
    private Context appContext;

    public DiskAdapter() {
        super(DiskStore.class);
    }

    /** Called once by the fragment; enables collapse persistence. */
    public void attachContext(@NonNull Context context) {
        appContext = context.getApplicationContext();
        collapsed.clear();
        collapsed.addAll(DiskTreeCollapse.load(appContext));
        rebuildFlat();
    }

    @Override
    public void onItemsUpdated() {
        infoCache.clear();
        rebuildFlat();
        super.onItemsUpdated();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void rebuildFlat() {
        flat.clear();
        flat.addAll(DiskTree.flatten(DiskTree.buildForest(items), collapsed));
    }

    @NonNull
    @Override
    protected DiskConfig itemAt(int position) {
        return flat.get(position).config;
    }

    @Override
    public int getItemCount() {
        return flat.size();
    }

    @Override
    public int getIconResId(@NonNull DiskConfig disk) {
        if (disk.getFormat() == DiskFormat.ISO)
            return R.drawable.ic_cdrom;
        return R.drawable.ic_nav_disk;
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder h, int position) {
        var node = flat.get(position);
        var d = node.config;
        h.itemCenter.setVisibility(VISIBLE);
        h.itemCenter.setText(d.item.optString("folder", ""));
        final Context ctx = h.itemView.getContext();
        final String unknown = ctx.getString(R.string.disk_size_unknown);
        String actualSize = unknown, virtualSize = unknown;
        var path = d.getFullPath();
        var cached = infoCache.get(path);
        if (cached != null) {
            if (cached.virtualSize >= 0)
                virtualSize = formatSize(cached.virtualSize);
            if (cached.actualSize >= 0)
                actualSize = formatSize(cached.actualSize);
        } else {
            loadImageInfoAsync(path, position);
        }
        h.itemInfo.setVisibility(VISIBLE);
        h.itemInfo.setText(ctx.getString(
            R.string.disk_meta,
            virtualSize, actualSize,
            d.getFormat().name()
        ));
        super.onBindViewHolder(h, position);
        bindTreeChrome(h, node, ctx);
    }

    // Overlay-tree adornments: indent by depth, padlock start-drawable on a locked (has-children)
    // name, chevron on the action button toggling collapse, "+N" state badge while collapsed,
    // warning badge on a broken parent link.
    private void bindTreeChrome(
        @NonNull BaseViewHolder h, @NonNull DiskTree.Node node, @NonNull Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int steps = Math.min(node.depth, MAX_INDENT_STEPS);
        h.itemView.setPaddingRelative(
            Math.round((8 + steps * INDENT_DP) * density),
            h.itemView.getPaddingTop(),
            Math.round(8 * density),
            h.itemView.getPaddingBottom());

        h.itemName.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, node.hasChildren() ? R.drawable.ic_lock : 0, 0);

        boolean isCollapsed = collapsed.contains(node.id());
        if (node.hasChildren()) {
            h.itemAction.setVisibility(VISIBLE);
            h.itemAction.setImageResource(R.drawable.ic_expand_more);
            h.itemAction.setRotation(isCollapsed ? -90 : 0);
            h.itemAction.setOnClickListener(v -> toggleCollapse(node.id()));
        } else {
            h.itemAction.setVisibility(GONE);
            h.itemAction.setOnClickListener(null);
        }

        if (node.brokenParent) {
            h.itemState.setVisibility(VISIBLE);
            h.itemState.setText(R.string.disk_tree_broken_parent);
        } else if (isCollapsed && node.hasChildren()) {
            h.itemState.setVisibility(VISIBLE);
            h.itemState.setText(fmt("+%d", node.countDescendants()));
        } else {
            h.itemState.setVisibility(GONE);
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void toggleCollapse(@NonNull UUID id) {
        if (!collapsed.remove(id)) collapsed.add(id);
        if (appContext != null) DiskTreeCollapse.save(appContext, collapsed);
        rebuildFlat();
        notifyDataSetChanged();
    }

    private void loadImageInfoAsync(String path, int position) {
        executor.submit(() -> {
            long virtualSize = -1, actualSize = -1;
            try {
                var info = ImageUtils.getImageInfo(path);
                virtualSize = info.optLong("virtual-size", -1);
                actualSize = info.optLong("actual-size", -1);
            } catch (Exception e) {
                Log.w(TAG, fmt("Failed to get image info for %s", path), e);
            }
            var result = new ImageInfo(virtualSize, actualSize);
            mainHandler.post(() -> {
                infoCache.put(path, result);
                if (position < flat.size()
                    && path.equals(flat.get(position).config.getFullPath())) {
                    try {
                        notifyItemChanged(position);
                    } catch (Exception ignored) {
                    }
                }
            });
        });
    }
}
