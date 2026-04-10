package cn.classfun.droidvm.ui.main.disk;

import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.ui.main.base.BaseViewHolder;
import cn.classfun.droidvm.ui.main.base.list.DataAdapter;

public final class DiskAdapter extends DataAdapter<DiskConfig, DiskStore> {
    private final Map<String, ImageInfo> infoCache = new HashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public DiskAdapter() {
        super(DiskStore.class);
    }

    @Override
    public void onItemsUpdated() {
        super.onItemsUpdated();
        infoCache.clear();
    }

    @Override
    public int getIconResId() {
        return R.drawable.ic_nav_disk;
    }

    @Override
    public void onBindViewHolder(@NonNull BaseViewHolder h, int position) {
        var d = items.get(position);
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
                if (position < items.size() && path.equals(items.get(position).getFullPath())) {
                    try {
                        notifyItemChanged(position);
                    } catch (Exception ignored) {
                    }
                }
            });
        });
    }
}
