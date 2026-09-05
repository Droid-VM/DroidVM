// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.edit.storage;

import static android.app.Activity.RESULT_OK;
import static cn.classfun.droidvm.lib.Constants.PFLASH_MAX_SIZE;
import static cn.classfun.droidvm.lib.utils.FileUtils.checkFileName;
import static cn.classfun.droidvm.lib.utils.FileUtils.checkFilePath;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;

import android.content.Intent;
import android.view.View;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.HashSet;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskBus;
import cn.classfun.droidvm.lib.store.enums.Enums;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.disk.action.DiskActionDialog;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditTab;
import cn.classfun.droidvm.ui.vm.edit.basic.VMEditBasicTab;
import cn.classfun.droidvm.ui.vm.edit.boot.VMEditBootTab;
import cn.classfun.droidvm.ui.vm.edit.storage.dir.VMSharedDirEditAdapter;
import cn.classfun.droidvm.ui.vm.edit.storage.disk.VMDiskEditAdapter;
import cn.classfun.droidvm.ui.widgets.container.CardItemListView;

public final class VMEditStorageTab extends VMEditBaseTab {
    private VMDiskEditAdapter diskAdapter;
    private VMSharedDirEditAdapter sharedDirAdapter;
    private CardItemListView listDisks;
    private CardItemListView listSharedDirs;
    private int pendingBrowsePosition = -1;
    private DiskActionDialog pendingImportDialog;
    private ActivityResultLauncher<Intent> diskActivityLauncher;

    public VMEditStorageTab(VMEditActivity parent, View view) {
        super(parent, view);
    }

    @Override
    public void initView() {
        listDisks = view.findViewById(R.id.list_disks);
        listSharedDirs = view.findViewById(R.id.list_shared_dirs);
    }

    @Override
    public void initValue() {
        var act = new ActivityResultContracts.StartActivityForResult();
        diskActivityLauncher = parent.registerForActivityResult(act, this::activityResult);
        diskAdapter = listDisks.setAdapter(VMDiskEditAdapter.class);
        diskAdapter.setOnImportOrCreateListener(this::diskAdapterOnImportOrCreate);
        // The boot tab points at a disk by its position in this list.
        diskAdapter.setOnItemMovedListener((from, to) -> {
            try {
                var boot = (VMEditBootTab) parent.getTab(VMEditTab.TAB_BOOT);
                if (boot != null) boot.onDiskMoved(from, to);
            } catch (Exception ignored) {
            }
        });
        // no PFLASH while the boot tab's UEFI vars pflash is enabled
        diskAdapter.setUefiVarsEnabledProvider(() -> {
            try {
                var boot = (VMEditBootTab) parent.getTab(VMEditTab.TAB_BOOT);
                return boot == null || boot.isVarsEnabledLive();
            } catch (Exception e) {
                return true;
            }
        });
        sharedDirAdapter = listSharedDirs.setAdapter(VMSharedDirEditAdapter.class);
        sharedDirAdapter.setOnBrowseListener(this::sharedDirAdapterOnBrowse);
    }

    // Refresh disk rows; PFLASH availability may have changed.
    public void refreshDisks() {
        if (diskAdapter != null) diskAdapter.notifyDataSetChanged();
    }

    @Override
    public void onTabShown() {
        // Merges and flattens run in other screens; re-derive which disks are overlay bases so
        // a row's read-only lock reflects the tree as it is now.
        if (diskAdapter != null) diskAdapter.reloadLockedPaths();
    }

    private void diskAdapterOnImportOrCreate(int pos) {
        Runnable onImportPickerUi = () -> parent.runOnUiThread(this::onImportPicker);
        pendingBrowsePosition = pos;
        pendingImportDialog = new DiskActionDialog(
            parent, null, onImportPickerUi, diskActivityLauncher
        );
        pendingImportDialog.showImportDialog();
    }

    private void sharedDirAdapterOnBrowse(int pos) {
        if (parent.currentPicker != null) return;
        parent.currentPicker = uri -> {
            if (uri != null) {
                var path = resolveUriPath(parent, uri);
                sharedDirAdapter.setPathAt(pos, path);
            }
            parent.currentPicker = null;
        };
        parent.folderPickerLauncher.launch(null);
    }

    private void onImportPicker() {
        parent.currentPicker = uri -> {
            if (pendingImportDialog != null) {
                var config = pendingImportDialog.onFileImported(uri);
                pendingImportDialog = null;
                if (config != null && pendingBrowsePosition >= 0)
                    diskAdapter.setPathAt(pendingBrowsePosition, config.getFullPath());
            }
            pendingBrowsePosition = -1;
        };
        parent.filePickerLauncher.launch(new String[]{"*/*"});
    }

    private void activityResult(@NonNull ActivityResult result) {
        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
            var path = result.getData().getStringExtra("result_disk_path");
            if (path != null && pendingBrowsePosition >= 0)
                diskAdapter.setPathAt(pendingBrowsePosition, path);
        }
        pendingBrowsePosition = -1;
    }

    /** Live disk entries as edited right now (not yet saved to config). */
    @Nullable
    public DataItem getCurrentDisks() {
        return listDisks.getItems();
    }

    @Override
    public void loadConfig(@NonNull VMConfig config) {
        // The rows stand in for this VM's own saved slots: those must not count as "another
        // VM attaches this disk" when deriving forced read-only.
        diskAdapter.setEditingVm(parent.editMode ? config.getId() : null, config.getName());
        listDisks.setItems(config.item.opt("disks", DataItem.newArray()));
        listSharedDirs.setItems(config.item.opt("shared_dirs", DataItem.newArray()));
    }

    @Override
    public boolean validateInput(@NonNull VMStore store) {
        var crosvm = currentBackend() == VMBackend.CROSVM;
        for (var disk : diskAdapter.getItems()) {
            var d = disk.getValue();
            var path = d.optString("path", "");
            if (!checkFilePath(path, true))
                return showValidateFailed(R.string.edit_vm_target_disk_path_invalid);
            // crosvm-only pflash size limit
            if (crosvm && Enums.optEnum(d, "bus", DiskBus.VIRTIO) == DiskBus.PFLASH
                && new File(path).length() > PFLASH_MAX_SIZE)
                return showValidateFailed(parent.getString(
                    R.string.edit_vm_uefi_vars_too_large, PFLASH_MAX_SIZE / (1024 * 1024)));
        }
        var set = new HashSet<String>();
        for (var dir : sharedDirAdapter.getItems()) {
            var d = dir.getValue();
            if (!checkFilePath(d.optString("path", ""), true))
                return showValidateFailed(R.string.edit_vm_shared_directory_path_invalid);
            var tag = d.optString("tag", "");
            if (!checkFileName(tag))
                return showValidateFailed(R.string.edit_vm_shared_dir_tag_invalid);
            if (set.contains(tag))
                return showValidateFailed(R.string.edit_vm_shared_dir_tag_duplicated);
            set.add(tag);
        }
        return true;
    }

    /** The backend as currently selected in the basic tab (before save). */
    @NonNull
    private VMBackend currentBackend() {
        try {
            var basic = (VMEditBasicTab) parent.getTab(VMEditTab.TAB_BASIC);
            if (basic != null) return basic.getCurrentBackend();
        } catch (Exception ignored) {
        }
        return VMBackend.DEFAULT;
    }

    @Override
    public void saveConfig(@NonNull VMConfig config) {
        // Effective read-only (forced by overlays/sharing, or chosen) for every row, including
        // ones never bound since their disk's situation last changed.
        diskAdapter.commitReadonly();
        config.item.set("disks", listDisks.getItems());
        config.item.set("shared_dirs", listSharedDirs.getItems());
    }
}
