package cn.classfun.droidvm.ui.main.disk;

import android.app.Activity;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument;
import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.disk.action.DiskActionDialog;
import cn.classfun.droidvm.ui.disk.info.DiskInfoActivity;
import cn.classfun.droidvm.ui.main.base.list.MainListFragment;

public final class MainDiskFragment extends MainListFragment<DiskConfig, DiskStore, DiskAdapter> {
    private ActivityResultLauncher<String[]> filePicker;
    private DiskActionDialog dialog;

    public MainDiskFragment() {
        super(DiskAdapter.class);
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_main_disk;
    }

    @Override
    public int getTitleResId() {
        return R.string.nav_disk;
    }

    @Override
    public @MenuRes int getCustomMenuResId() {
        return R.menu.menu_main_disk;
    }

    @Override
    public void onFabClick(@NonNull View v) {
        var pop = new MaterialMenu(requireContext(), v);
        pop.inflate(R.menu.menu_disk_add);
        pop.setOnMenuItemClickListener(dialog::onImportItemSelected);
        pop.show();
    }

    @NonNull
    @Override
    protected Class<? extends Activity> getInfoActivity() {
        return DiskInfoActivity.class;
    }

    @Override
    protected int getItemMenuResId(@NonNull DiskConfig config) {
        var fmt = config.getFormat();
        if (!DiskConfig.supportsExtraOperations(fmt))
            return R.menu.menu_disk_actions_simple;
        return R.menu.menu_disk_actions;
    }

    @Override
    protected boolean onMenuClicked(@NonNull DiskConfig config, @NonNull MenuItem item) {
        return dialog.diskMenuOnClick(config, item.getItemId());
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Runnable onPicker = () -> filePicker.launch(new String[]{"*/*"});
        dialog = new DiskActionDialog(requireContext(), this::refreshView, onPicker);
        filePicker = registerForActivityResult(
            new OpenDocument(), uri -> dialog.onFileImported(uri)
        );
    }
}
