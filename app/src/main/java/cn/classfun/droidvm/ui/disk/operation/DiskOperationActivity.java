package cn.classfun.droidvm.ui.disk.operation;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.FileUtils.findExecute;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.SIGHUP;
import static cn.classfun.droidvm.lib.utils.ProcessUtils.shellKillProcess;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.StringUtils.dirname;
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
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import org.json.JSONObject;

import java.util.UUID;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.store.disk.DiskConfig;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalSessionClient;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalViewClient;

public final class DiskOperationActivity extends AppCompatActivity {
    private static final String TAG = "DiskOperationActivity";
    public static final String EXTRA_DISK_ID = "disk_id";
    public static final String EXTRA_TASK_JSON = "task_json";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TerminalView terminalView;
    private ProgressBar progressSpinner;
    private ImageView ivStatus;
    private TextView tvFilename;
    private TextView tvStatus;
    private MaterialButton btnCancel;
    private MaterialToolbar toolbar;
    private TerminalSession session;
    private boolean finished = false;
    private String outputPath = null;
    private String taskAction = null;
    private DiskStore diskStore = null;
    private DiskConfig diskConfig = null;

    private final TerminalSessionClient sessionClient = new SimpleTerminalSessionClient() {
        @Override
        public void onTextChanged(@NonNull TerminalSession s) {
            mainHandler.post(() -> {
                if (terminalView != null)
                    terminalView.onScreenUpdated();
            });
        }

        @Override
        public void onSessionFinished(@NonNull TerminalSession s) {
            mainHandler.post(() -> onProcessFinished());
        }
    };

    private final TerminalViewClient viewClient = new SimpleTerminalViewClient() {
    };

    @NonNull
    public static Intent createIntent(
        @NonNull Context context,
        @NonNull UUID diskId,
        @NonNull JSONObject obj
    ) {
        var intent = new Intent(context, DiskOperationActivity.class);
        intent.putExtra(EXTRA_DISK_ID, diskId.toString());
        intent.putExtra(EXTRA_TASK_JSON, obj.toString());
        return intent;
    }

    public static void startOptimize(
        @NonNull Context context,
        @NonNull UUID diskId
    ) {
        try {
            var obj = new JSONObject();
            obj.put("action", "convert");
            var intent = createIntent(context, diskId, obj);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start optimize activity", e);
        }
    }

    public static void startConvert(
        @NonNull Context context,
        @NonNull UUID diskId,
        @NonNull String format,
        @NonNull String output
    ) {
        try {
            var obj = new JSONObject();
            obj.put("action", "convert");
            obj.put("format", format);
            obj.put("output", output);
            var intent = createIntent(context, diskId, obj);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start convert activity", e);
        }
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_disk_operation);
        toolbar = findViewById(R.id.toolbar);
        progressSpinner = findViewById(R.id.progress_spinner);
        ivStatus = findViewById(R.id.iv_status);
        tvFilename = findViewById(R.id.tv_filename);
        tvStatus = findViewById(R.id.tv_status);
        btnCancel = findViewById(R.id.btn_cancel);
        terminalView = findViewById(R.id.terminal_view);
        terminalView.setTerminalViewClient(viewClient);
        btnCancel.setOnClickListener(v -> confirmCancel());
        initialize();
    }

    private void initialize() {
        toolbar.setTitle(R.string.disk_operation_title);
        toolbar.setNavigationOnClickListener(v -> confirmFinish());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmFinish();
            }
        });
        var intent = getIntent();
        var diskIdStr = intent.getStringExtra(EXTRA_DISK_ID);
        var taskJsonStr = intent.getStringExtra(EXTRA_TASK_JSON);
        if (diskIdStr == null || taskJsonStr == null) {
            Log.e(TAG, "Missing intent extras");
            finish();
            return;
        }
        var diskId = UUID.fromString(diskIdStr);
        diskStore = new DiskStore();
        diskStore.load(this);
        diskConfig = diskStore.findById(diskId);
        if (diskConfig == null) {
            Log.e(TAG, fmt("Disk not found: %s", diskId));
            finish();
            return;
        }
        var diskPath = diskConfig.getFullPath();
        tvFilename.setText(diskConfig.getName());
        tvStatus.setText(R.string.disk_operation_running);
        runOnPool(() -> {
            final String cmd;
            try {
                var task = new JSONObject(taskJsonStr);
                var gen = new ImageCommandGenerate(diskStore);
                cmd = gen.buildCommand(task, diskPath);
                taskAction = task.optString("action", "");
                outputPath = gen.getOutputPath();
            } catch (Exception e) {
                Log.e(TAG, "Failed to build command from task JSON", e);
                runOnUiThread(() -> showFailed(getString(R.string.disk_operation_bad_task)));
                return;
            }
            Log.i(TAG, fmt("Running: %s", cmd));
            runOnUiThread(() -> startTerminalSession(cmd));
        });
    }

    private void startTerminalSession(String cmd) {
        var shell = findExecute("su", "/system/bin/su");
        var cwd = getFilesDir().getAbsolutePath();
        var args = new String[]{"su", "-c", cmd};
        var env = new String[]{
            "TERM=xterm-256color",
            "PATH=/system/bin",
            fmt("HOME=%s", cwd),
        };
        session = new TerminalSession(shell, cwd, args, env, null, sessionClient);
        float density = getResources().getDisplayMetrics().density;
        terminalView.setTextSize((int) (10 * density));
        terminalView.attachSession(session);
    }

    private void onProcessFinished() {
        if (finished) return;
        finished = true;
        int exitCode = session == null ? -1 : session.getExitStatus();
        if (exitCode == 0 && outputPath != null) {
            if (taskAction.equals("clone")) {
                var cloned = new DiskConfig();
                if (outputPath.contains("/")) {
                    cloned.setName(basename(outputPath));
                    cloned.item.set("folder", dirname(outputPath));
                } else {
                    cloned.setName(outputPath);
                }
                diskStore.add(cloned);
            } else {
                if (outputPath.contains("/")) {
                    diskConfig.setName(basename(outputPath));
                    diskConfig.item.set("folder", dirname(outputPath));
                } else {
                    diskConfig.setName(outputPath);
                }
                diskStore.update(diskConfig);
            }
            diskStore.save(this);
        }
        progressSpinner.setVisibility(GONE);
        ivStatus.setVisibility(VISIBLE);
        btnCancel.setVisibility(GONE);
        if (exitCode == 0) {
            ivStatus.setImageResource(R.drawable.ic_large_success);
            tvStatus.setText(R.string.disk_operation_success);
        } else {
            ivStatus.setImageResource(R.drawable.ic_large_error);
            tvStatus.setText(getString(R.string.disk_operation_failed, exitCode));
        }
    }

    private void showFailed(String message) {
        finished = true;
        progressSpinner.setVisibility(GONE);
        ivStatus.setVisibility(VISIBLE);
        ivStatus.setImageResource(R.drawable.ic_close);
        tvStatus.setText(message);
        btnCancel.setVisibility(GONE);
    }

    private void confirmCancel() {
        if (finished) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disk_operation_cancel_title)
            .setMessage(R.string.disk_operation_cancel_message)
            .setPositiveButton(android.R.string.ok, (d, w) -> sendSigint())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmFinish() {
        if (finished) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disk_operation_cancel_title)
            .setMessage(R.string.disk_operation_cancel_message)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                sendSigint();
                finish();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void sendSigint() {
        if (session != null && !finished && session.isRunning()) {
            Log.i(TAG, "Sending SIGINT to process");
            shellKillProcess(session.getPid(), SIGHUP);
        }
    }
}
