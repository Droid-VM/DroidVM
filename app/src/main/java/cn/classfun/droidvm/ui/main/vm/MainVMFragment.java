// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.main.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.DroidVMApp;
import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.ForegroundCallback;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.MenuDialogBuilder;
import cn.classfun.droidvm.ui.disk.lxc.CreateLinuxVmActivity;
import cn.classfun.droidvm.ui.main.base.stateful.MainStatefulFragment;
import cn.classfun.droidvm.ui.vm.VMActions;
import cn.classfun.droidvm.ui.vm.VMDeletion;
import cn.classfun.droidvm.ui.vm.console.VMConsoleRouter;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.info.VMInfoActivity;
import cn.classfun.droidvm.ui.vm.pkg.exports.VMPkgExportActivity;

public final class MainVMFragment
    extends MainStatefulFragment<VMConfig, VMStore, VMAdapter, VMState>
    implements ForegroundCallback {
    private final AtomicBoolean wantOpenConsole = new AtomicBoolean(false);
    // Pre-start convert (decompress a crosvm-unreadable qcow2): run this once
    // the convert Activity returns RESULT_OK.
    @Nullable
    private Runnable pendingAfterConvert;
    private final ActivityResultLauncher<Intent> convertResultLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            var cb = pendingAfterConvert;
            pendingAfterConvert = null;
            if (result.getResultCode() == Activity.RESULT_OK && cb != null) cb.run();
        });
    private final VMActions.ConvertLauncher convertLauncher = (intent, onConverted) -> {
        pendingAfterConvert = onConverted;
        convertResultLauncher.launch(intent);
    };

    public MainVMFragment() {
        super(VMAdapter.class);
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_main_vm;
    }

    @Override
    public int getTitleResId() {
        return R.string.nav_vm;
    }

    @Override
    protected @MenuRes int getCustomMenuResId() {
        return R.menu.menu_main_vm;
    }

    @Override
    public void onFabClick(@NonNull View v) {
        MenuDialogBuilder.showSimple(
            requireContext(),
            R.string.vm_create_mode_title,
            R.menu.menu_vm_create,
            item -> {
                var context = requireContext();
                var id = item.getItemId();
                if (id == R.id.menu_vm_create_linux) {
                    startActivity(new Intent(context, CreateLinuxVmActivity.class));
                } else if (id == R.id.menu_vm_create_windows) {
                    showWindowsVmUnavailableDialog();
                } else if (id == R.id.menu_vm_create_customize) {
                    startActivity(new Intent(context, VMEditActivity.class));
                } else {
                    return false;
                }
                return true;
            }
        );
    }

    private void showWindowsVmUnavailableDialog() {
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.windows_vm_unavailable_title)
            .setMessage(R.string.windows_vm_unavailable_message)
            .setPositiveButton(R.string.windows_vm_open_script, (dialog, which) ->
                startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/Droid-VM/win11-arm64-image-builder/blob/master/windows_build.ps1")
                )))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @NonNull
    @Override
    protected String getListCallName() {
        return "vm_list";
    }

    @NonNull
    @Override
    protected Class<? extends Activity> getInfoActivity() {
        return VMInfoActivity.class;
    }

    @Override
    protected int getItemMenuResId(@NonNull VMConfig config) {
        return R.menu.menu_vm_actions;
    }

    @Override
    protected boolean onMenuClicked(@NonNull VMConfig config, @NonNull MenuItem item) {
        var ctx = requireContext();
        var id = item.getItemId();
        if (id == R.id.menu_vm_edit) {
            var intent = new Intent(ctx, VMEditActivity.class);
            intent.putExtra(VMEditActivity.EXTRA_VM_ID, config.getId().toString());
            startActivity(intent);
        } else if (id == R.id.menu_vm_delete) {
            confirmDeleteVM(config);
        } else if (id == R.id.menu_vm_export) {
            startActivity(VMPkgExportActivity.createIntent(ctx, config.getId()));
        }
        return true;
    }

    @Override
    public void onStart() {
        super.onStart();
        var app = (DroidVMApp) requireActivity().getApplication();
        var handler = app.getVMEventHandler();
        if (handler != null) handler.addForegroundCallback(TAG, this);
    }

    @Override
    public void onStop() {
        super.onStop();
        var app = (DroidVMApp) requireActivity().getApplication();
        var handler = app.getVMEventHandler();
        if (handler != null) handler.removeForegroundCallback(TAG);
    }

    @Override
    @SuppressWarnings("unused")
    public void onVMStateChanged(UUID vmId, VMState state) {
        Log.d(TAG, fmt("VM %s changed state to %s", vmId, state));
        mainHandler.post(() -> {
            if (!isAdded()) return;
            if (adapter != null)
                adapter.updateState(vmId, state);
            if (state == VMState.RUNNING && wantOpenConsole.compareAndSet(true, false)) {
                var config = adapter.items.findById(vmId);
                if (config == null) return;
                // Same routing as the VM-info console button (native -> VNC ->
                // serial), so a VNC/native VM auto-opens its display, not always UART.
                VMConsoleRouter.openDefault(requireContext(), vmId, config, true);
            }
        });
    }

    @Override
    protected void onActionClicked(VMConfig config, VMState currentState) {
        if (currentState == VMState.STOPPED) {
            VMActions.createAndStart(config, mainHandler, uiContext, wantOpenConsole, convertLauncher);
        } else if (currentState == VMState.RUNNING) {
            VMActions.sendCommand("vm_stop", config.getId(), mainHandler, uiContext);
        }
    }

    private void deleteVM(@NonNull VMConfig config, boolean deleteDisks) {
        var ctx = requireContext();
        adapter.items.removeById(config.getId());
        boolean saved = adapter.items.save(ctx);
        refreshView();
        VMDeletion.releaseDaemonAndMaybeDeleteDisks(ctx, config, deleteDisks, saved);
    }

    private void confirmDeleteVM(@NonNull VMConfig config) {
        VMDeletion.confirm(requireContext(), config,
            deleteDisks -> deleteVM(config, deleteDisks));
    }
}
