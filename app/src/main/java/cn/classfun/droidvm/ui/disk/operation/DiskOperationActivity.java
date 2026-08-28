// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.disk.operation;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.FileUtils.findExecute;
import static cn.classfun.droidvm.lib.utils.ImageUtils.hasBackingFile;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import cn.classfun.droidvm.lib.utils.RunUtils;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalSessionClient;
import cn.classfun.droidvm.lib.ui.termux.TerminalFonts;
import cn.classfun.droidvm.lib.ui.termux.SimpleTerminalViewClient;
import cn.classfun.droidvm.ui.disk.create.DiskCompress;
import cn.classfun.droidvm.ui.disk.action.DiskDependencyUpdater;
import cn.classfun.droidvm.ui.main.settings.MainSettingsFragment;

public final class DiskOperationActivity extends AppCompatActivity {
    private static final String TAG = "DiskOperationActivity";
    public static final String EXTRA_DISK_ID = "disk_id";
    public static final String EXTRA_TASK_JSON = "task_json";
    /** Path-mode (no registered DiskConfig): operate on this file directly. */
    public static final String EXTRA_DISK_PATH = "disk_path";
    public static final String EXTRA_DISK_NAME = "disk_name";
    /** On success, {@code setResult(RESULT_OK)} and finish so a launcher can chain. */
    public static final String EXTRA_AUTOFINISH = "autofinish";
    /** Explicit in-app activity to launch only after this disk operation succeeds. */
    public static final String EXTRA_SUCCESS_INTENT = "success_intent";
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
    private boolean postProcessing = false;
    private boolean autoFinish = false;
    private Intent successIntent = null;
    private String outputPath = null;
    private String taskAction = null;
    private DiskStore diskStore = null;
    private DiskConfig diskConfig = null;

    private final TerminalSessionClient sessionClient = new SimpleTerminalSessionClient(this) {
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

    /**
     * Intent that optimizes (and thereby decompresses) the qcow2 at
     * {@code path} in place and returns {@code RESULT_OK} on success -- for
     * launching via an {@code ActivityResultLauncher} so the caller can chain
     * (e.g. start the VM once a crosvm-unreadable compressed image is fixed).
     * Works without a registered {@link DiskConfig}, since a VM disk may point
     * at an unregistered file.
     */
    @NonNull
    public static Intent optimizeForResultIntent(
        @NonNull Context context,
        @NonNull String path,
        @NonNull String name
    ) {
        var intent = new Intent(context, DiskOperationActivity.class);
        try {
            var obj = new JSONObject();
            obj.put("action", "convert"); // no compress -> rewrites uncompressed
            intent.putExtra(EXTRA_TASK_JSON, obj.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to build optimize task", e);
        }
        intent.putExtra(EXTRA_DISK_PATH, path);
        intent.putExtra(EXTRA_DISK_NAME, name);
        intent.putExtra(EXTRA_AUTOFINISH, true);
        return intent;
    }

    /**
     * Post-import hook: optimize only when the imported image's compression isn't in
     * {@link DiskCompress#CROSVM_SUPPORTED} (an image crosvm already boots needs no rewrite).
     * The compression check runs off the main thread; {@code done} always runs on the main
     * thread - after launching the optimize, after skipping it, or on prompt cancel.
     */
    public static void startOptimizeAfterImport(
        @NonNull android.app.Activity activity,
        @NonNull UUID diskId,
        @NonNull String path,
        @NonNull Runnable done
    ) {
        startOptimizeAfterImportImpl(activity, diskId, path, null, done, done);
    }

    /**
     * Result-aware variant for callers that must continue only after optimization succeeds.
     * {@code onSkipped} runs when the image already needs no rewrite; an optimization that is
     * required is launched through {@code launcher} and reports its outcome there. Cancelling the
     * compression prompt or failing to launch calls {@code onCancelled}.
     */
    public static void startOptimizeAfterImportForResult(
        @NonNull android.app.Activity activity,
        @NonNull UUID diskId,
        @NonNull String path,
        @NonNull ActivityResultLauncher<Intent> launcher,
        @NonNull Runnable onSkipped,
        @NonNull Runnable onCancelled
    ) {
        startOptimizeAfterImportImpl(
            activity, diskId, path, launcher, onSkipped, onCancelled);
    }

    private static void startOptimizeAfterImportImpl(
        @NonNull android.app.Activity activity,
        @NonNull UUID diskId,
        @NonNull String path,
        @Nullable ActivityResultLauncher<Intent> launcher,
        @NonNull Runnable onSkipped,
        @NonNull Runnable onCancelled
    ) {
        runOnPool(() -> {
            var supported = DiskCompress.detect(path).isCrosvmSupported();
            // An imported overlay is skipped outright: it is mostly a header (nothing worth
            // rewriting), and its chain gets checked by the pre-start guard instead.
            var isOverlay = hasBackingFile(path);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) {
                    onCancelled.run();
                    return;
                }
                if (supported || isOverlay) {
                    onSkipped.run();
                    return;
                }
                OptimizeCompression.resolve(activity, onCancelled, compress -> {
                    try {
                        var obj = new JSONObject();
                        obj.put("action", "convert");
                        obj.put("compress", compress.value());
                        var intent = createIntent(activity, diskId, obj);
                        if (launcher == null) {
                            activity.startActivity(intent);
                            onSkipped.run();
                        } else {
                            intent.putExtra(EXTRA_AUTOFINISH, true);
                            launcher.launch(intent);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to start optimize activity", e);
                        onCancelled.run();
                    }
                });
            });
        });
    }

    public static void startConvert(
        @NonNull Context context,
        @NonNull UUID diskId,
        @NonNull String format,
        @NonNull String output,
        @NonNull String compress
    ) {
        try {
            var obj = new JSONObject();
            obj.put("action", "convert");
            obj.put("format", format);
            obj.put("output", output);
            if (!compress.equals("none"))
                obj.put("compress", compress);
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
        autoFinish = intent.getBooleanExtra(EXTRA_AUTOFINISH, false);
        successIntent = intent.getParcelableExtra(EXTRA_SUCCESS_INTENT, Intent.class);
        if (taskJsonStr == null) {
            Log.e(TAG, "Missing task JSON");
            finish();
            return;
        }
        diskStore = new DiskStore();
        diskStore.load(this);
        final String diskPath;
        final String diskName;
        if (diskIdStr != null) {
            diskConfig = diskStore.findById(UUID.fromString(diskIdStr));
            if (diskConfig == null) {
                Log.e(TAG, fmt("Disk not found: %s", diskIdStr));
                finish();
                return;
            }
            diskPath = diskConfig.getFullPath();
            diskName = diskConfig.getName();
        } else {
            // Path mode: operate on the file directly (no DiskStore entry).
            diskPath = intent.getStringExtra(EXTRA_DISK_PATH);
            if (diskPath == null) {
                Log.e(TAG, "Missing disk id and path");
                finish();
                return;
            }
            var name = intent.getStringExtra(EXTRA_DISK_NAME);
            diskName = name != null ? name : basename(diskPath);
        }
        tvFilename.setText(diskName);
        tvStatus.setText(R.string.disk_operation_running);
        runOnPool(() -> {
            final String cmd;
            try {
                var task = new JSONObject(taskJsonStr);
                var gen = new ImageCommandGenerate(diskStore);
                gen.setCpuAffinity(
                    MainSettingsFragment.getQemuImgCpuAffinity(getApplicationContext()));
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

    /**
     * After a successful {@code qemu-img commit}: the base's logical content now equals the
     * overlay's, so re-pointing the overlay's children and VM attachments at the base is a
     * lossless swap. Order matters only in that the overlay is deleted LAST - until then both
     * "points at overlay" and "points at base" are valid views, so a failure at any step leaves
     * a consistent, recoverable state.
     */
    private boolean finishCommit() {
        try {
            var store = new DiskStore();
            if (!store.load(this)) {
                Log.e(TAG, "commit finished but disk registry could not be loaded; keeping overlay");
                return false;
            }
            var overlay = store.findById(diskConfig.getId());
            if (overlay == null) return false;
            var parent = store.parentOf(overlay);
            if (parent == null) {
                Log.w(TAG, "commit finished but overlay has no registered parent");
                return false;
            }
            var overlayPath = overlay.getFullPath();
            var parentPath = parent.getFullPath();
            var parentFormat = detectFormat(parentPath);
            // Children of the committed overlay re-base onto the (now content-identical)
            // parent: header-only rewrite, then the registry link. A partial failure is still
            // consistent: already-moved children point at the parent and the overlay remains for
            // children that were not moved.
            for (var child : store.childrenOf(overlay.getId())) {
                var result = RunUtils.runList(
                    findQemuImg(), "rebase", "-u",
                    "-b", parentPath, "-F", parentFormat, child.getFullPath());
                if (!result.isSuccess()) {
                    result.printLog(TAG);
                    if (!store.save(this))
                        Log.e(TAG, "Failed to persist completed child rebases");
                    Log.e(TAG, "Keeping committed overlay after child rebase failure: "
                        + child.getFullPath());
                    return false;
                }
                child.setParentId(parent.getId());
            }
            // Persist child links before changing attachments or deleting the overlay file.
            if (!store.save(this)) {
                Log.e(TAG, "Keeping committed overlay: failed to save child links");
                return false;
            }
            // Only slots pointing directly at the merged overlay move to its parent. Slots
            // pointing at child overlays stay exactly where they are.
            if (!DiskDependencyUpdater.redirectVmDisks(
                this, java.util.Set.of(overlayPath), parentPath)) {
                Log.e(TAG, "Keeping committed overlay: failed to save VM attachments");
                return false;
            }
            store.removeById(overlay.getId());
            if (!store.save(this)) {
                Log.e(TAG, "Keeping committed overlay: failed to remove registry entry");
                return false;
            }
            var removed = RunUtils.runList("rm", "-f", overlayPath);
            if (!removed.isSuccess()) {
                removed.printLog(TAG);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "commit follow-up failed", e);
            return false;
        }
    }

    /**
     * After a successful flatten the replacement image uses the same path and contains the whole
     * backing-chain view. Child backing headers and every VM slot therefore remain valid and must
     * not move; only this image's parent registry link is cleared.
     */
    private boolean finishFlatten() {
        try {
            var store = new DiskStore();
            if (!store.load(this)) {
                Log.e(TAG, "flatten finished but disk registry could not be loaded");
                return false;
            }
            var overlay = store.findById(diskConfig.getId());
            if (overlay == null) return false;
            overlay.setParentId(null);
            if (!store.save(this)) {
                Log.e(TAG, "flatten finished but standalone registry link could not be saved");
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "flatten follow-up failed", e);
            return false;
        }
    }

    @NonNull
    private static String detectFormat(@NonNull String path) {
        try {
            var f = cn.classfun.droidvm.lib.utils.ImageUtils.getImageInfo(path)
                .optString("format", "");
            if (!f.isEmpty()) return f;
        } catch (Exception ignored) {
        }
        return "qcow2";
    }

    @NonNull
    private static String findQemuImg() {
        return cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath("qemu-img");
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
        TerminalFonts.apply(terminalView);
        terminalView.attachSession(session);
    }

    private void onProcessFinished() {
        if (finished) return;
        finished = true;
        int exitCode = session == null ? -1 : session.getExitStatus();
        // Overlay-tree success includes its persisted relationship follow-up. Keep the progress
        // UI up until that finishes; never report success while VMStore/DiskStore is still stale.
        if (exitCode == 0 && diskConfig != null && "commit".equals(taskAction)) {
            startTreePostProcessing(this::finishCommit);
            return;
        } else if (exitCode == 0 && diskConfig != null && "flatten".equals(taskAction)) {
            startTreePostProcessing(this::finishFlatten);
            return;
        }
        // Path mode (no registered DiskConfig) is an in-place op, so there is
        // nothing to persist -- skip the store update. commit/flatten did their own registry
        // work above (their outputPath equals the disk path; nothing to rename either).
        if (exitCode == 0 && outputPath != null && diskConfig != null
            && !"commit".equals(taskAction) && !"flatten".equals(taskAction)) {
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
        if (exitCode == 0 && successIntent != null) {
            startActivity(successIntent);
            finish();
            return;
        }
        // Chained convert (e.g. pre-start decompress): hand control back to the
        // launcher, which starts the VM. No success screen -- the start is the
        // feedback.
        if (exitCode == 0 && autoFinish) {
            setResult(RESULT_OK);
            finish();
            return;
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

    private void startTreePostProcessing(@NonNull java.util.function.BooleanSupplier operation) {
        postProcessing = true;
        btnCancel.setVisibility(GONE);
        tvStatus.setText(R.string.disk_operation_finalizing);
        runOnPool(() -> {
            boolean success = operation.getAsBoolean();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                postProcessing = false;
                progressSpinner.setVisibility(GONE);
                ivStatus.setVisibility(VISIBLE);
                if (success) {
                    ivStatus.setImageResource(R.drawable.ic_large_success);
                    tvStatus.setText(R.string.disk_operation_success);
                } else {
                    ivStatus.setImageResource(R.drawable.ic_large_error);
                    tvStatus.setText(R.string.disk_operation_dependency_failed);
                }
            });
        });
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
        if (postProcessing) return;
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
