package cn.classfun.droidvm.ui.disk.info.tree;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.ui.disk.info.DiskInfoActivity;
import cn.classfun.droidvm.ui.disk.info.base.DiskInfoBaseTab;

public final class DiskInfoTreeTab extends DiskInfoBaseTab {
    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private DiskTreeEntryAdapter adapter;

    public DiskInfoTreeTab(
        @NonNull DiskInfoActivity activity,
        @NonNull FrameLayout tabContainer
    ) {
        super(activity, tabContainer);
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.partial_disk_tree_content;
    }

    @Override
    public boolean isShow() {
        var fmt = activity.config.getFormat();
        return DiskConfig.supportsBackingImage(fmt);
    }

    @Override
    public void onCreateView() {
        recyclerView = view.findViewById(R.id.rv_tree);
        tvEmpty = view.findViewById(R.id.tv_empty);
        adapter = new DiskTreeEntryAdapter();
        adapter.setOnItemClickListener(this::onTreeItemClick);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setAdapter(adapter);
    }

    @Override
    public void onConfigLoaded() {
    }

    @Override
    public void onDataLoaded() {
        var entries = new ArrayList<DiskTreeEntryAdapter.Entry>();
        var backingChain = activity.backingChain;
        var imageInfo = activity.imageInfo;
        if (backingChain != null && backingChain.length() > 0) {
            int last = backingChain.length() - 1;
            for (int i = 0; i <= last; i++) {
                var img = backingChain.optJSONObject(i);
                if (img == null) continue;
                var filename = img.optString("filename", "");
                var imgFormat = img.optString("format", "");
                long vSize = img.optLong("virtual-size", -1);
                long aSize = img.optLong("actual-size", -1);
                String label;
                if (i == 0)
                    label = activity.getString(R.string.disk_info_tree_current);
                else if (i == last)
                    label = activity.getString(R.string.disk_info_tree_root);
                else
                    label = null;
                var sb = new StringBuilder();
                sb.append(imgFormat);
                if (vSize >= 0) sb.append(" - ").append(formatSize(vSize));
                if (aSize >= 0) {
                    sb.append(" - ").append(activity.getString(R.string.disk_info_actual_size));
                    sb.append(": ").append(formatSize(aSize));
                }
                entries.add(buildEntry(i, filename, label, sb.toString()));
            }
        } else if (imageInfo != null) {
            var filename = imageInfo.optString("filename", "");
            var imgFormat = imageInfo.optString("format", "");
            long vSize = imageInfo.optLong("virtual-size", -1);
            var detail = new StringBuilder();
            detail.append(imgFormat);
            if (vSize >= 0) detail.append(" - ").append(formatSize(vSize));
            entries.add(buildEntry(0, filename,
                activity.getString(R.string.disk_info_tree_current), detail.toString()));
        }
        adapter.setItems(entries);
        if (entries.isEmpty()) {
            recyclerView.setVisibility(GONE);
            tvEmpty.setText(R.string.disk_info_tree_no_chain);
            tvEmpty.setVisibility(VISIBLE);
        } else {
            recyclerView.setVisibility(VISIBLE);
            tvEmpty.setVisibility(GONE);
        }
    }

    @NonNull
    private DiskTreeEntryAdapter.Entry buildEntry(
        int index, String filename, @Nullable String label, String detail
    ) {
        int iconResId = index == 0 ? R.drawable.ic_nav_disk : R.drawable.ic_source_branch;
        String line1 = label != null ? fmt("%s (%s)", filename, label) : filename;
        return new DiskTreeEntryAdapter.Entry(iconResId, filename, line1, detail);
    }

    @SuppressWarnings("unused")
    private void onTreeItemClick(
        @NonNull View v,
        @NonNull DiskTreeEntryAdapter.Entry entry
    ) {
        var path = entry.filename;
        if (path.isEmpty()) return;
        var currentPath = activity.config.getFullPath();
        if (path.equals(currentPath)) return;
        var store = new DiskStore();
        store.load(activity);
        var found = store.findByPath(path);
        if (found == null) {
            Toast.makeText(activity,
                R.string.disk_info_tree_not_in_store,
                Toast.LENGTH_SHORT
            ).show();
            return;
        }
        var intent = new Intent(activity, DiskInfoActivity.class);
        intent.putExtra("target_id", found.getId().toString());
        activity.startActivity(intent);
    }
}
