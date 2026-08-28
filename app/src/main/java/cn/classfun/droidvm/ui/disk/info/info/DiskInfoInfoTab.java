// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.info.info;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.size.SizeUtils.formatSize;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.util.ArrayList;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.ui.disk.action.DiskActionDialog;
import cn.classfun.droidvm.ui.disk.info.DiskInfoActivity;
import cn.classfun.droidvm.ui.disk.info.base.DiskInfoBaseTab;
import cn.classfun.droidvm.ui.disk.tree.DiskTreeDialog;
import cn.classfun.droidvm.ui.widgets.container.CollapsibleContainer;
import cn.classfun.droidvm.ui.widgets.row.TextRowWidget;

public final class DiskInfoInfoTab extends DiskInfoBaseTab {
    private static final String TAG = "DiskInfoInfoTab";
    private CollapsibleContainer sectionDetails;
    private CollapsibleContainer sectionRaw;
    private TextView tvSummary;
    private TextView tvPath;
    private TextView tvRawOutput;
    private TextRowWidget rowFilename;
    private TextRowWidget rowFolder;
    private TextRowWidget rowFormat;
    private TextRowWidget rowVirtualSize;
    private TextRowWidget rowActualSize;
    private TextRowWidget rowClusterSize;
    private TextRowWidget rowBackingFile;
    private TextRowWidget rowEncryption;
    private TextRowWidget rowCompression;
    private TextRowWidget rowDirtyFlag;
    private LinearLayout actionsContainer;
    private int actionsMenuResId;
    private DiskActionDialog dialog;

    public DiskInfoInfoTab(
        @NonNull DiskInfoActivity activity,
        @NonNull FrameLayout tabContainer
    ) {
        super(activity, tabContainer);
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.partial_disk_info_content;
    }

    @Override
    public void onCreateView() {
        tvSummary = view.findViewById(R.id.tv_summary);
        tvPath = view.findViewById(R.id.tv_path);
        rowFilename = view.findViewById(R.id.row_filename);
        rowFolder = view.findViewById(R.id.row_folder);
        rowFormat = view.findViewById(R.id.row_format);
        rowVirtualSize = view.findViewById(R.id.row_virtual_size);
        rowActualSize = view.findViewById(R.id.row_actual_size);
        sectionDetails = view.findViewById(R.id.section_details);
        rowClusterSize = view.findViewById(R.id.row_cluster_size);
        rowBackingFile = view.findViewById(R.id.row_backing_file);
        rowEncryption = view.findViewById(R.id.row_encryption);
        rowCompression = view.findViewById(R.id.row_compression);
        rowDirtyFlag = view.findViewById(R.id.row_dirty_flag);
        sectionRaw = view.findViewById(R.id.section_raw);
        tvRawOutput = view.findViewById(R.id.tv_raw_output);
        actionsContainer = view.findViewById(R.id.disk_actions_container);
        initialize();
    }

    private void initialize() {
        dialog = new DiskActionDialog(activity, this::onDiskUpdated, null);
        populateActions(activity.config);
        bindCopy(rowFilename);
        bindCopy(rowFolder);
        bindCopy(rowFormat);
        bindCopy(rowVirtualSize);
        bindCopy(rowActualSize);
        bindCopy(rowClusterSize);
        bindCopy(rowBackingFile);
        bindCopy(rowEncryption);
        bindCopy(rowCompression);
        bindCopy(rowDirtyFlag);
    }

    /**
     * Build from the list long-press menu, folding create/merge/flatten into the existing branch
     * manager because the tree dialog owns those relationship-changing operations here.
     */
    private void populateActions(@Nullable DiskConfig config) {
        if (config == null) return;
        int menuResId = DiskActionDialog.getMenuResId(config);
        if (menuResId == actionsMenuResId && actionsContainer.getChildCount() > 0) return;
        actionsMenuResId = menuResId;
        actionsContainer.removeAllViews();

        var menuHost = new PopupMenu(activity, actionsContainer);
        menuHost.getMenuInflater().inflate(menuResId, menuHost.getMenu());
        var actions = new ArrayList<MenuItem>();
        boolean branchesAdded = false;
        for (int i = 0; i < menuHost.getMenu().size(); i++) {
            var item = menuHost.getMenu().getItem(i);
            if (!item.isVisible()) continue;
            if (isBranchAction(item.getItemId())) {
                if (!branchesAdded) {
                    actions.add(null); // null is the synthetic "Manage overlays" action.
                    branchesAdded = true;
                }
            } else {
                actions.add(item);
            }
        }

        LinearLayout row = null;
        int visibleCount = 0;
        for (var item : actions) {
            if ((visibleCount & 1) == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                actionsContainer.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            var button = (MaterialButton) LayoutInflater.from(activity)
                .inflate(R.layout.item_disk_info_action, row, false);
            if (item == null) {
                button.setText(R.string.disk_manage_branches);
                button.setIconResource(R.drawable.ic_file_multiple);
                button.setOnClickListener(v -> DiskTreeDialog.show(
                    activity, activity.config.getFullPath(), null));
            } else {
                button.setText(item.getTitle());
                button.setIcon(item.getIcon());
                int actionId = item.getItemId();
                button.setOnClickListener(v ->
                    dialog.diskMenuOnClick(activity.config, actionId));
            }
            row.addView(button);
            visibleCount++;
        }
        if ((visibleCount & 1) != 0 && row != null) {
            var spacer = new Space(activity);
            row.addView(spacer, new LinearLayout.LayoutParams(
                0, 0, 1));
        }
    }

    private static boolean isBranchAction(int id) {
        return id == R.id.menu_disk_create_increment
            || id == R.id.menu_disk_merge
            || id == R.id.menu_disk_flatten;
    }

    private void onDiskUpdated() {
        try {
            var store = new DiskStore();
            store.load(activity);
            if (store.findById(activity.config.getId()) == null)
                activity.runOnUiThread(activity::finish);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load disk info", e);
        }
    }

    private void bindCopy(@NonNull TextRowWidget tr) {
        tr.setOnClickListener(v -> showCopyDialog(tr.getTitle(), tr.getValue()));
    }

    @Override
    public void onConfigLoaded() {
        var config = activity.config;
        if (config == null) return;
        populateActions(config);
        var name = config.getName();
        var folder = config.item.optString("folder", "");
        var format = config.getFormat();
        var fullPath = config.getFullPath();
        tvSummary.setText(format.name());
        tvPath.setText(fullPath);
        rowFilename.setValue(name);
        rowFolder.setValue(folder.isEmpty() ? "-" : folder);
        rowFormat.setValue(format.name());
        rowVirtualSize.setValue(R.string.disk_info_loading);
        rowActualSize.setValue(R.string.disk_info_loading);
    }

    @Override
    public void onDataLoaded() {
        populateImageInfo(activity.imageInfo);
        populateRawOutput(activity.rawText);
    }

    private void populateImageInfo(@Nullable JSONObject info) {
        if (info == null) {
            rowVirtualSize.setValue(R.string.disk_size_unknown);
            rowActualSize.setValue(R.string.disk_size_unknown);
            return;
        }
        long virtualSize = info.optLong("virtual-size", -1);
        if (virtualSize >= 0) {
            tvSummary.setText(formatSize(virtualSize));
            rowVirtualSize.setValue(formatSize(virtualSize));
        } else {
            rowVirtualSize.setValue(R.string.disk_size_unknown);
        }
        long actualSize = info.optLong("actual-size", -1);
        if (actualSize >= 0) {
            rowActualSize.setValue(formatSize(actualSize));
        } else {
            rowActualSize.setValue(R.string.disk_size_unknown);
        }
        var format = info.optString("format", "");
        if (!format.isEmpty()) {
            rowFormat.setValue(format);
        }
        boolean hasDetails = false;
        long clusterSize = info.optLong("cluster-size", -1);
        if (clusterSize > 0) {
            rowClusterSize.setValue(formatSize(clusterSize));
            rowClusterSize.setVisibility(VISIBLE);
            hasDetails = true;
        } else {
            rowClusterSize.setVisibility(GONE);
        }
        var backingFile = info.optString("backing-filename", "");
        if (!backingFile.isEmpty()) {
            rowBackingFile.setValue(backingFile);
            var backingFmt = info.optString("backing-filename-format", "");
            if (!backingFmt.isEmpty()) {
                rowBackingFile.setSubtitle(backingFmt);
            }
            rowBackingFile.setVisibility(VISIBLE);
            hasDetails = true;
        } else {
            rowBackingFile.setVisibility(GONE);
        }
        var encrypted = info.optBoolean("encrypted", false);
        if (encrypted) {
            rowEncryption.setValue(R.string.disk_info_encrypted_yes);
            rowEncryption.setVisibility(VISIBLE);
            hasDetails = true;
        } else {
            rowEncryption.setVisibility(GONE);
        }
        var formatSpecific = info.optJSONObject("format-specific");
        if (formatSpecific != null) {
            var fmtData = formatSpecific.optJSONObject("data");
            if (fmtData != null) {
                var compressType = fmtData.optString("compression-type", "");
                if (!compressType.isEmpty()) {
                    rowCompression.setValue(compressType);
                    rowCompression.setVisibility(VISIBLE);
                    hasDetails = true;
                } else {
                    rowCompression.setVisibility(GONE);
                }
                var dirty = fmtData.optBoolean("corrupt", false);
                if (dirty) {
                    rowDirtyFlag.setValue(R.string.disk_info_dirty_yes);
                    rowDirtyFlag.setVisibility(VISIBLE);
                    hasDetails = true;
                } else {
                    var lazyRefcounts = fmtData.optBoolean("lazy-refcounts", false);
                    if (lazyRefcounts) {
                        rowDirtyFlag.setValue(R.string.disk_info_lazy_refcounts);
                        rowDirtyFlag.setVisibility(VISIBLE);
                        hasDetails = true;
                    } else {
                        rowDirtyFlag.setVisibility(GONE);
                    }
                }
            } else {
                rowCompression.setVisibility(GONE);
                rowDirtyFlag.setVisibility(GONE);
            }
        } else {
            rowCompression.setVisibility(GONE);
            rowDirtyFlag.setVisibility(GONE);
        }
        sectionDetails.setVisibility(hasDetails ? VISIBLE : GONE);
    }

    private void populateRawOutput(@Nullable String raw) {
        if (raw != null && !raw.isEmpty()) {
            tvRawOutput.setText(raw);
            sectionRaw.setVisibility(VISIBLE);
        } else {
            tvRawOutput.setText(R.string.disk_info_load_failed);
            sectionRaw.setVisibility(VISIBLE);
        }
    }

    private void showCopyDialog(
        @NonNull String title,
        @NonNull String content
    ) {
        DialogInterface.OnClickListener copy = (d, w) -> {
            var cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("value", content));
        };
        new MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(content)
            .setPositiveButton(R.string.disk_info_copy, copy)
            .setNegativeButton(android.R.string.cancel, (d, w) -> d.dismiss())
            .show();
    }
}
