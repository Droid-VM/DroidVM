package cn.classfun.droidvm.ui.vm.info;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.store.enums.Enums.applyText;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
import static cn.classfun.droidvm.ui.vm.VMActions.sendCommand;
import static cn.classfun.droidvm.ui.vm.VMActions.sendControlCommand;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.DroidVMApp;
import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.ForegroundCallback;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.ProtectedVM;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.UIContext;
import cn.classfun.droidvm.ui.vm.VMActions;
import cn.classfun.droidvm.ui.vm.edit.VMEditActivity;
import cn.classfun.droidvm.ui.widgets.row.TextRowWidget;

public final class VMInfoActivity extends AppCompatActivity implements ForegroundCallback {
    private static final String TAG = "VMInfoActivity";
    private final UIContext ui = UIContext.fromActivity(this);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean wantOpenConsole = new AtomicBoolean(false);
    private final ConsoleButton toolConsole = new ConsoleButton(this);
    private VMState oldState = VMState.STOPPED;
    private CollapsingToolbarLayout collapsingToolbar;
    private TextView tvState;
    private TextView tvStateDetail;
    private MaterialButton btnStartStop;
    private MaterialButton btnRestart;
    private MaterialButton btnSuspendResume;
    private MaterialButton btnConsole;
    private MaterialButton btnEdit;
    private MaterialButton btnDelete;
    private MaterialButton btnPowerBtn;
    private MaterialButton btnSleepBtn;
    private MaterialToolbar toolbar;
    private TextRowWidget rowCpu;
    private TextRowWidget rowMemory;
    private TextRowWidget rowKernel;
    private TextRowWidget rowProtected;
    private TextRowWidget rowDisks;
    private TextRowWidget rowOptions;
    private TextRowWidget rowCreated;
    public VMState currentState = VMState.STOPPED;
    public UUID vmId;
    public VMConfig config;
    public VMStore store;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vm_info);
        collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        tvState = findViewById(R.id.tv_state);
        tvStateDetail = findViewById(R.id.tv_state_detail);
        btnStartStop = findViewById(R.id.btn_start_stop);
        btnRestart = findViewById(R.id.btn_restart);
        btnSuspendResume = findViewById(R.id.btn_suspend_resume);
        btnConsole = findViewById(R.id.btn_console);
        btnEdit = findViewById(R.id.btn_edit);
        btnDelete = findViewById(R.id.btn_delete);
        btnPowerBtn = findViewById(R.id.btn_powerbtn);
        btnSleepBtn = findViewById(R.id.btn_sleepbtn);
        rowCpu = findViewById(R.id.row_cpu);
        rowMemory = findViewById(R.id.row_memory);
        rowKernel = findViewById(R.id.row_kernel);
        rowProtected = findViewById(R.id.row_protected);
        rowDisks = findViewById(R.id.row_disks);
        rowOptions = findViewById(R.id.row_options);
        rowCreated = findViewById(R.id.row_created);
        toolbar = findViewById(R.id.toolbar);
        initialize();
    }

    private void initialize() {
        var id = getIntent().getStringExtra("target_id");
        if (id == null) {
            finish();
            return;
        }
        vmId = UUID.fromString(id);
        store = new VMStore();
        runOnPool(() -> store.load(this));
        toolbar.setNavigationOnClickListener(v -> finish());
        btnStartStop.setOnClickListener(v -> onStartStopClicked());
        btnRestart.setOnClickListener(v -> doRestart());
        btnSuspendResume.setOnClickListener(v -> onSuspendResumeClicked());
        btnConsole.setOnClickListener(v -> toolConsole.showConsoleChooser());
        btnEdit.setOnClickListener(v -> openEdit());
        btnDelete.setOnClickListener(v -> doDelete());
        btnPowerBtn.setOnClickListener(v ->
            sendControlCommand("powerbtn", vmId, mainHandler, ui));
        btnSleepBtn.setOnClickListener(v ->
            sendControlCommand("sleepbtn", vmId, mainHandler, ui));
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadConfig();
        syncState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        var app = (DroidVMApp) getApplication();
        var handler = app.getVMEventHandler();
        if (handler != null) handler.addForegroundCallback(TAG, this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        var app = (DroidVMApp) getApplication();
        var handler = app.getVMEventHandler();
        if (handler != null) handler.removeForegroundCallback(TAG);
    }

    @Override
    @SuppressWarnings("unused")
    public void onVMStateChanged(UUID id, VMState state) {
        if (vmId.equals(id)) mainHandler.post(() -> {
            currentState = state;
            updateStateUI();
        });
    }

    @Override
    @SuppressWarnings("unused")
    public void onVMExited(UUID id, String vmName, int exitCode, JSONObject data) {
        if (!vmId.equals(id)) return;
        mainHandler.post(() -> {
            if (isFinishing()) return;
            currentState = VMState.STOPPED;
            updateStateUI();
            if (exitCode == 0) {
                Toast.makeText(
                    this,
                    getString(R.string.vm_exited_success, vmName),
                    LENGTH_SHORT
                ).show();
            } else {
                Toast.makeText(this,
                    getString(R.string.vm_exited_error, vmName, exitCode),
                    LENGTH_LONG
                ).show();
            }
        });
    }

    private void reloadConfig() {
        runOnPool(() -> {
            store.load(this);
            config = store.findById(vmId);
            runOnUiThread(config == null ? this::finish : this::populateInfo);
        });
    }

    private void populateInfo() {
        var item = config.item;
        collapsingToolbar.setTitle(config.getName());
        rowCpu.setValue(getString(R.string.vm_info_cpu_value, item.optLong("cpu_count", 0)));
        rowMemory.setValue(getString(R.string.vm_info_memory_value, item.optLong("memory_mb", 0)));
        rowKernel.setValue(basename(item.optString("kernel", "")));
        var protectedVm = optEnum(item, "protected_vm", ProtectedVM.PROTECTED_WITHOUT_FIRMWARE);
        rowProtected.setValue(protectedVm.getDisplayString(this));
        var disks = item.opt("disks", DataItem.newArray());
        int diskCount = disks.size();
        if (diskCount > 0) {
            rowDisks.setValue(getResources().getQuantityString(
                R.plurals.vm_info_disks_value, diskCount, diskCount
            ));
        } else {
            rowDisks.setValue(R.string.vm_info_disks_none);
        }
        var opts = new ArrayList<String>();
        if (item.optBoolean("balloon", false)) opts.add("Balloon");
        if (item.optBoolean("sandbox", false)) opts.add("Sandbox");
        rowOptions.setValue(opts.isEmpty() ? "-" : String.join(", ", opts));
        var df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        rowCreated.setValue(df.format(new Date(item.optLong("created_at", 0))));
    }

    private void syncState() {
        DaemonConnection.OnResponse res = resp -> {
            var arr = resp.optJSONArray("data");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                var vm = arr.optJSONObject(i);
                if (vm == null) continue;
                if (vmId.equals(UUID.fromString(vm.optString("id", "")))) {
                    var s = VMState.valueOf(vm.optString("state", "stopped").toUpperCase());
                    mainHandler.post(() -> {
                        currentState = s;
                        updateStateUI();
                    });
                    return;
                }
            }
            mainHandler.post(() -> {
                currentState = VMState.STOPPED;
                updateStateUI();
            });
        };
        DaemonConnection.getInstance().buildRequest("vm_list")
            .onResponse(res)
            .onUnsuccessful(r -> {
            })
            .onError(e -> Log.w(TAG, "Failed to sync state", e))
            .invoke();
    }

    private void updateStateUI() {
        if (currentState != oldState) {
            if (currentState == VMState.RUNNING &&
                wantOpenConsole.compareAndSet(true, false))
                toolConsole.openDefaultConsole();
            oldState = currentState;
        }
        applyText(tvState, currentState);
        var isStopped = currentState == VMState.STOPPED;
        var isRunning = currentState == VMState.RUNNING;
        var isSuspended = currentState == VMState.SUSPENDED;
        var isActive = isRunning || isSuspended;
        if (isActive) {
            btnStartStop.setText(R.string.vm_info_stop);
            btnStartStop.setIconResource(R.drawable.ic_vm_stop);
            btnStartStop.setEnabled(true);
        } else {
            btnStartStop.setText(R.string.vm_info_start);
            btnStartStop.setIconResource(R.drawable.ic_vm_start);
            btnStartStop.setEnabled(isStopped);
        }
        if (isSuspended) {
            btnSuspendResume.setText(R.string.vm_info_resume);
            btnSuspendResume.setIconResource(R.drawable.ic_resume);
            btnSuspendResume.setEnabled(true);
        } else {
            btnSuspendResume.setText(R.string.vm_info_suspend);
            btnSuspendResume.setIconResource(R.drawable.ic_pause);
            btnSuspendResume.setEnabled(isRunning);
        }
        btnRestart.setEnabled(isRunning);
        if (isStopped) {
            btnConsole.setText(R.string.vm_info_logs);
            btnConsole.setIconResource(R.drawable.ic_logs);
        } else {
            btnConsole.setText(R.string.vm_info_console);
            btnConsole.setIconResource(R.drawable.ic_monitor);
        }
        btnEdit.setEnabled(isStopped);
        btnDelete.setEnabled(isStopped);
        btnPowerBtn.setEnabled(isActive);
        btnSleepBtn.setEnabled(isActive);
        if (isRunning) {
            tvStateDetail.setText(R.string.vm_info_state_active);
        } else if (isSuspended) {
            tvStateDetail.setText(R.string.vm_info_state_suspended);
        } else {
            tvStateDetail.setText("");
        }
    }

    private void onStartStopClicked() {
        var isActive = currentState == VMState.RUNNING
            || currentState == VMState.SUSPENDED;
        if (isActive) {
            doStop();
        } else {
            doStart();
        }
    }

    private void onSuspendResumeClicked() {
        if (currentState == VMState.SUSPENDED) {
            sendCommand("vm_resume", vmId, mainHandler, ui);
        } else if (currentState == VMState.RUNNING) {
            sendCommand("vm_suspend", vmId, mainHandler, ui);
        }
    }

    private void doStart() {
        if (config == null) return;
        VMActions.createAndStart(config, mainHandler, ui, wantOpenConsole);
    }

    private void doStop() {
        sendCommand("vm_stop", vmId, mainHandler, ui);
    }

    private void doRestart() {
        DialogInterface.OnClickListener cb = (d, w) ->
            sendCommand("vm_stop", vmId, mainHandler, ui,
                () -> mainHandler.postDelayed(this::doStart, 1500));
        new MaterialAlertDialogBuilder(this)
            .setTitle(config.getName())
            .setMessage(R.string.vm_info_restart_confirm)
            .setPositiveButton(R.string.vm_info_restart, cb)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void openEdit() {
        var intent = new Intent(this, VMEditActivity.class);
        intent.putExtra(VMEditActivity.EXTRA_VM_ID, vmId.toString());
        startActivity(intent);
    }

    private void doDelete() {
        DialogInterface.OnClickListener cb = (d, w) -> {
            store.removeById(vmId);
            runOnPool(() -> store.save(this));
            Toast.makeText(
                this,
                R.string.vm_info_delete_success,
                LENGTH_SHORT
            ).show();
            setResult(RESULT_OK);
            finish();
        };
        new MaterialAlertDialogBuilder(this)
            .setTitle(config.getName())
            .setMessage(R.string.vm_delete_confirm)
            .setPositiveButton(R.string.vm_delete, cb)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
