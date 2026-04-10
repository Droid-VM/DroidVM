package cn.classfun.droidvm.ui.main.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.DroidVMApp;
import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.ForegroundCallback;
import cn.classfun.droidvm.lib.daemon.VMEventHandler;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.ui.main.base.stateful.MainStatefulFragment;
import cn.classfun.droidvm.ui.vm.VMActions;
import cn.classfun.droidvm.ui.vm.console.VMConsoleActivity;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.vm.info.VMInfoActivity;

public final class MainVMFragment
    extends MainStatefulFragment<VMConfig, VMStore, VMAdapter, VMState>
    implements ForegroundCallback {
    private final AtomicBoolean wantOpenConsole = new AtomicBoolean(false);

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
        startActivity(new Intent(requireContext(), VMEditActivity.class));
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
            intent.putExtra(VMEditActivity.EXTRA_VM_ID, config.getId());
            startActivity(intent);
        } else if (id == R.id.menu_vm_delete) {
            confirmDeleteVM(config);
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
                var intent = new Intent(requireContext(), VMConsoleActivity.class);
                intent.putExtra(VMConsoleActivity.EXTRA_VM_ID, vmId.toString());
                intent.putExtra(VMConsoleActivity.EXTRA_VM_NAME, config.getName());
                startActivity(intent);
            }
        });
    }

    @Override
    protected void onActionClicked(VMConfig config, VMState currentState) {
        if (currentState == VMState.STOPPED) {
            VMActions.createAndStart(config, mainHandler, uiContext, wantOpenConsole);
        } else if (currentState == VMState.RUNNING) {
            VMActions.sendCommand("vm_stop", config.getId(), mainHandler, uiContext);
        }
    }

    private void deleteVM(@NonNull VMConfig config) {
        var ctx = requireContext();
        adapter.items.removeById(config.getId());
        adapter.items.save(ctx);
        refreshView();
        DaemonConnection.getInstance().buildRequest("vm_delete")
            .put("vm_id", config.getId().toString())
            .invoke();
    }

    private void confirmDeleteVM(@NonNull VMConfig config) {
        var ctx = requireContext();
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(config.getName())
            .setMessage(R.string.vm_delete_confirm)
            .setPositiveButton(R.string.vm_delete, (d, w) -> deleteVM(config))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
