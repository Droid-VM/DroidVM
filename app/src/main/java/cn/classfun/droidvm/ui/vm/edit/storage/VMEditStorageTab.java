package cn.classfun.droidvm.ui.vm.edit.storage;

import static android.app.Activity.RESULT_OK;
import static cn.classfun.droidvm.lib.utils.FileUtils.checkFilePath;
import static cn.classfun.droidvm.lib.utils.StringUtils.resolveUriPath;

import android.content.Intent;
import android.view.View;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;

import java.util.HashSet;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.disk.action.DiskActionDialog;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.edit.base.VMEditBaseTab;
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
        diskAdapter.setOnBrowseFileListener(this::diskAdapterOnBrowseFile);
        diskAdapter.setOnImportOrCreateListener(this::diskAdapterOnImportOrCreate);
        sharedDirAdapter = listSharedDirs.setAdapter(VMSharedDirEditAdapter.class);
        sharedDirAdapter.setOnBrowseListener(this::sharedDirAdapterOnBrowse);
    }

    private void diskAdapterOnImportOrCreate(int pos) {
        Runnable onImportPickerUi = () -> parent.runOnUiThread(this::onImportPicker);
        pendingBrowsePosition = pos;
        pendingImportDialog = new DiskActionDialog(
            parent, null, onImportPickerUi, diskActivityLauncher
        );
        pendingImportDialog.showImportDialog();
    }

    private void diskAdapterOnBrowseFile(int pos) {
        pendingBrowsePosition = pos;
        if (parent.currentPicker != null) return;
        parent.currentPicker = uri -> {
            if (uri != null && pendingBrowsePosition >= 0) {
                var path = resolveUriPath(parent, uri);
                diskAdapter.setPathAt(pendingBrowsePosition, path);
            }
            pendingBrowsePosition = -1;
            parent.currentPicker = null;
        };
        parent.filePickerLauncher.launch(new String[]{"*/*"});
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

    @Override
    public void loadConfig(@NonNull VMConfig config) {
        listDisks.setItems(config.item.opt("disks", DataItem.newArray()));
        listSharedDirs.setItems(config.item.opt("shared_dirs", DataItem.newArray()));
    }

    @Override
    public boolean validateInput(@NonNull VMStore store) {
        for (var disk : diskAdapter.getItems())
            if (!checkFilePath(disk.getValue().optString("path", ""), true))
                return false;
        var set = new HashSet<String>();
        for (var dir : sharedDirAdapter.getItems()) {
            var d = dir.getValue();
            if (!checkFilePath(d.optString("path", ""), true))
                return false;
            var tag = d.optString("tag", "");
            if (!checkFilePath(tag, true))
                return false;
            if (set.contains(tag))
                return false;
            set.add(tag);
        }
        return true;
    }

    @Override
    public void saveConfig(@NonNull VMConfig config) {
        config.item.set("disks", listDisks.getItems());
        config.item.set("shared_dirs", listSharedDirs.getItems());
    }
}
