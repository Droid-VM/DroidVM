package cn.classfun.droidvm.ui.agent;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.FileUtils.findExecute;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.shellKillProcess;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

import cn.classfun.droidvm.DroidVMApp;
import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.ForegroundCallback;
import cn.classfun.droidvm.lib.daemon.VMEventHandler;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalSessionClient;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalViewClient;
import cn.classfun.droidvm.ui.agent.base.AgentVM;
import cn.classfun.droidvm.ui.agent.base.BaseAction;

public final class AgentOperationActivity extends AppCompatActivity
    implements DaemonConnection.EventListener, ForegroundCallback {
    private static final String TAG = "AgentOperationActivity";
    public static final String EXTRA_AGENT_VM_JSON = "agent_vm_json";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ProgressBar progressSpinner;
    private ImageView ivStatus;
    private TextView tvTitle;
    private TextView tvStatus;
    private TerminalView terminalView;
    private TerminalSession terminalSession;
    private MaterialButton btnCancel;
    private MaterialToolbar toolbar;
    private boolean finished = false;
    private String vmId = null;
    private AgentVM agentVM = null;
    private BaseAction action = null;

    private final SimpleTerminalSessionClient sessionClient = new SimpleTerminalSessionClient() {
        @Override
        public void onTextChanged(@NonNull TerminalSession s) {
            mainHandler.post(() -> {
                if (terminalView != null) terminalView.onScreenUpdated();
            });
        }
    };

    private final SimpleTerminalViewClient viewClient = new SimpleTerminalViewClient() {
    };

    @NonNull
    public static Intent createIntent(
        @NonNull Context context,
        @NonNull AgentVM agentVM
    ) {
        var intent = new Intent(context, AgentOperationActivity.class);
        try {
            intent.putExtra(EXTRA_AGENT_VM_JSON, agentVM.toJson().toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize AgentVM", e);
        }
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_operation);
        toolbar = findViewById(R.id.toolbar);
        progressSpinner = findViewById(R.id.progress_spinner);
        ivStatus = findViewById(R.id.iv_status);
        tvTitle = findViewById(R.id.tv_title);
        tvStatus = findViewById(R.id.tv_status);
        terminalView = findViewById(R.id.terminal_view);
        btnCancel = findViewById(R.id.btn_cancel);
        btnCancel.setOnClickListener(v -> confirmCancel());
        initialize();
    }

    private void initialize() {
        toolbar.setTitle(R.string.agent_operation_title);
        toolbar.setNavigationOnClickListener(v -> confirmFinish());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmFinish();
            }
        });
        var intent = getIntent();
        var agentVmJson = intent.getStringExtra(EXTRA_AGENT_VM_JSON);
        if (agentVmJson == null) {
            Log.e(TAG, "Missing agent_vm_json extra");
            finish();
            return;
        }
        try {
            var diskStore = new DiskStore();
            diskStore.load(this);
            agentVM = new AgentVM(diskStore, new JSONObject(agentVmJson));
            action = BaseAction.createAction(agentVM);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse AgentVM", e);
            finish();
            return;
        }
        terminalView.setTerminalViewClient(viewClient);
        initTerminal();
        tvTitle.setText(R.string.agent_operation_title);
        tvStatus.setText(R.string.agent_operation_preparing);
        appendLog(getString(R.string.agent_operation_log_preparing));
        runOnPool(this::startAgent);
    }

    private void initTerminal() {
        var shell = findExecute("sh");
        var cwd = getFilesDir().getAbsolutePath();
        var args = new String[]{"sh", "-c", "while true; do sleep 86400; done"};
        var env = new String[]{
            "TERM=xterm-256color",
            "PATH=/system/bin",
            fmt("HOME=%s", cwd),
        };
        terminalSession = new TerminalSession(shell, cwd, args, env, null, sessionClient);
        float density = getResources().getDisplayMetrics().density;
        terminalView.setTextSize((int) (4 * density));
        terminalView.attachSession(terminalSession);
    }

    private void appendLog(@NonNull String text) {
        if (terminalSession == null) return;
        var emulator = terminalSession.getEmulator();
        if (emulator == null) return;
        if (text.contains("\n") && !text.contains("\r"))
            text = text.replace("\n", "\r\n");
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
        terminalView.onScreenUpdated();
    }

    private void startAgent() {
        try {
            agentVM.prepareVars();
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare agent", e);
            runOnUiThread(() -> showFailed(getString(R.string.agent_operation_prepare_failed)));
            return;
        }
        runOnUiThread(() -> {
            tvStatus.setText(R.string.agent_operation_creating_vm);
            appendLog(getString(R.string.agent_operation_log_creating_vm));
        });
        var vmConfig = agentVM.buildVM();
        var conn = DaemonConnection.getInstance();
        try {
            registerEventListeners();
            var createReq = new JSONObject();
            createReq.put("command", "vm_create");
            createReq.put("config", vmConfig.toJson());
            var createResp = conn.request(createReq);
            if (!createResp.optBoolean("success", false)) {
                var msg = createResp.optString("message", "unknown error");
                throw new RuntimeException(fmt("vm_create failed: %s", msg));
            }
            vmId = createResp.optString("vm_id", "");
            if (vmId.isEmpty()) throw new RuntimeException("vm_create returned empty vm_id");
            runOnUiThread(() -> {
                tvStatus.setText(R.string.agent_operation_starting_vm);
                appendLog(getString(R.string.agent_operation_log_starting_vm));
            });
            var startReq = new JSONObject();
            startReq.put("command", "vm_start");
            startReq.put("vm_id", vmId);
            var startResp = conn.request(startReq);
            if (!startResp.optBoolean("success", false)) {
                var msg = startResp.optString("message", "unknown error");
                throw new RuntimeException(fmt("vm_start failed: %s", msg));
            }
            runOnUiThread(() -> {
                tvStatus.setText(R.string.agent_operation_running);
                appendLog(getString(R.string.agent_operation_log_running));
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to create/start agent VM", e);
            runOnUiThread(() -> showFailed(
                getString(R.string.agent_operation_start_failed, e.getMessage())
            ));
            cleanupVM();
        }
    }

    private void registerEventListeners() {
        DaemonConnection.getInstance().addListener(this);
        var app = (DroidVMApp) getApplication();
        app.getVMEventHandler().addForegroundCallback(TAG, this);
    }

    private void unregisterEventListeners() {
        DaemonConnection.getInstance().removeListener(this);
        var app = (DroidVMApp) getApplication();
        app.getVMEventHandler().removeForegroundCallback(TAG);
    }

    @Override
    public void onDaemonEvent(@NonNull JSONObject msg) {
        var type = msg.optString("type", "");
        if (!type.equals("event")) return;
        var data = msg.optJSONObject("data");
        if (data == null) return;
        var eventVmId = data.optString("vm_id", "");
        if (!eventVmId.equals(vmId)) return;
        var event = data.optString("event", "");
        if (event.equals("output")) {
            var text = data.optString("data", "");
            var stream = data.optString("stream", "");
            if (!text.isEmpty() && (stream.equals("stdio") || stream.equals("uart")))
                mainHandler.post(() -> appendLog(text));
        } else if (event.equals("exited")) {
            int exitCode = data.optInt("exit_code", -1);
            mainHandler.post(() -> onVMFinished(exitCode));
        }
    }

    @Override
    public void onDaemonConnected() {
    }

    @Override
    public void onDaemonDisconnected() {
        if (!finished) {
            mainHandler.post(() -> showFailed(getString(R.string.agent_operation_daemon_disconnected)));
        }
    }

    private void onVMFinished(int exitCode) {
        if (finished) return;
        finished = true;
        appendLog(fmt(
            "\n--- %s (exit code: %d) ---\n",
            getString(R.string.agent_operation_vm_exited), exitCode
        ));
        runOnPool(() -> {
            String resultMessage = null;
            boolean success = false;
            try {
                if (action != null) {
                    action.checkResult();
                    success = true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Agent result check failed", e);
                resultMessage = e.getMessage();
            }
            killTerminalSession();
            cleanupVM();
            final boolean finalSuccess = success;
            final String finalMsg = resultMessage;
            runOnUiThread(() -> {
                progressSpinner.setVisibility(GONE);
                ivStatus.setVisibility(VISIBLE);
                btnCancel.setText(android.R.string.ok);
                btnCancel.setOnClickListener(v -> finish());
                if (finalSuccess) {
                    ivStatus.setImageResource(R.drawable.ic_large_success);
                    tvStatus.setText(R.string.agent_operation_success);
                    appendLog(getString(R.string.agent_operation_log_success));
                } else {
                    ivStatus.setImageResource(R.drawable.ic_large_error);
                    if (finalMsg != null) {
                        tvStatus.setText(getString(R.string.agent_operation_failed_detail, finalMsg));
                    } else {
                        tvStatus.setText(getString(R.string.agent_operation_failed, exitCode));
                    }
                    appendLog(getString(R.string.agent_operation_log_failed));
                }
            });
        });
    }

    private void killTerminalSession() {
        if (terminalSession != null) {
            try {
                shellKillProcess(terminalSession.getPid());
            } catch (Exception ignored) {
            }
            terminalSession = null;
        }
    }

    private void cleanupVM() {
        unregisterEventListeners();
        if (vmId != null && !vmId.isEmpty()) {
            try {
                var conn = DaemonConnection.getInstance();
                var destroyReq = new JSONObject();
                destroyReq.put("command", "vm_delete");
                destroyReq.put("vm_id", vmId);
                conn.request(destroyReq);
                Log.i(TAG, fmt("Temporary VM %s destroyed", vmId));
            } catch (Exception e) {
                Log.w(TAG, fmt("Failed to destroy temporary VM %s", vmId), e);
            }
        }
        if (agentVM != null) {
            try {
                agentVM.cleanupVars();
            } catch (Exception e) {
                Log.w(TAG, "Failed to cleanup vars", e);
            }
        }
    }

    private void showFailed(@NonNull String message) {
        finished = true;
        progressSpinner.setVisibility(GONE);
        ivStatus.setVisibility(VISIBLE);
        ivStatus.setImageResource(R.drawable.ic_large_error);
        tvStatus.setText(message);
        btnCancel.setText(android.R.string.ok);
        btnCancel.setOnClickListener(v -> finish());
    }

    private void confirmCancel() {
        if (finished) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.agent_operation_cancel_title)
            .setMessage(R.string.agent_operation_cancel_message)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                finished = true;
                runOnPool(() -> {
                    killTerminalSession();
                    cleanupVM();
                });
                finish();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmFinish() {
        if (finished) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.agent_operation_cancel_title)
            .setMessage(R.string.agent_operation_cancel_message)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                finished = true;
                runOnPool(() -> {
                    killTerminalSession();
                    cleanupVM();
                });
                finish();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!finished) {
            runOnPool(() -> {
                killTerminalSession();
                cleanupVM();
            });
        }
    }
}

