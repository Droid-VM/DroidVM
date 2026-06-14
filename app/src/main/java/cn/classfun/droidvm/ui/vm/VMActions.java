package cn.classfun.droidvm.ui.vm;

import static android.widget.Toast.LENGTH_LONG;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_INITRD;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_KERNEL;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.ui.main.settings.MainSettingsFragment.isAutoConsoleEnabled;
import static cn.classfun.droidvm.ui.main.settings.MainSettingsFragment.isClearLogsBeforeStartEnabled;

import android.content.Context;
import android.content.Intent;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.BootConfig;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.UIContext;
import cn.classfun.droidvm.ui.disk.create.DiskFormat;
import cn.classfun.droidvm.ui.disk.operation.DiskOperationActivity;
import cn.classfun.droidvm.ui.vm.boot.BootMenuDialog;

public final class VMActions {
    private static final String TAG = "VMActions";

    private VMActions() {
    }

    /**
     * Launches a {@link DiskOperationActivity} convert and runs
     * {@code onConverted} when it returns {@code RESULT_OK}. The call site
     * owns an {@code ActivityResultLauncher}; this lets the static start flow
     * chain a pre-start disk convert without holding an Activity itself.
     */
    public interface ConvertLauncher {
        void launch(@NonNull Intent intent, @NonNull Runnable onConverted);
    }

    public static void createAndStart(
        @NonNull VMConfig config,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @NonNull AtomicBoolean wantOpenConsole,
        @Nullable ConvertLauncher convertLauncher
    ) {
        // crosvm can't read compressed qcow2 (the guest gets vda I/O errors
        // and an unreadable partition table, so any root= hangs). Catch it
        // before start and offer to convert; everything else starts normally.
        guardCompressedDisks(config, mainHandler, ui, convertLauncher,
            () -> startAfterGuard(config, mainHandler, ui, wantOpenConsole));
    }

    private static void startAfterGuard(
        @NonNull VMConfig config,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @NonNull AtomicBoolean wantOpenConsole
    ) {
        // manual GUI start of an image-booting VM goes through the
        // GRUB-style entry menu first (auto-start in the daemon does not)
        if (BootMenuDialog.wanted(config) && ui.isAlive()) {
            BootMenuDialog.show(
                ui.getContext(), config,
                (bootEntry, remember, selected, builtinCmdline) -> {
                    var startEntry = remember
                        ? rememberChoice(ui.getContext(), config, bootEntry,
                            selected, builtinCmdline)
                        : bootEntry;
                    doCreateAndStart(config, mainHandler, ui, wantOpenConsole, startEntry);
                },
                () -> { /* cancelled: do not start */ }
            );
            return;
        }
        doCreateAndStart(config, mainHandler, ui, wantOpenConsole, null);
    }

    /**
     * Pre-start check: for a crosvm VM with qcow2 disks, ask the daemon
     * (lbx) which are zlib-compressed and therefore unreadable by crosvm. If
     * any are, prompt to convert (decompress) them, then {@code proceed};
     * otherwise {@code proceed} straight away. A check failure never blocks a
     * start — a real boot would surface the problem. Callbacks arrive on the
     * daemon thread, so UI work is posted to {@code mainHandler}.
     */
    private static void guardCompressedDisks(
        @NonNull VMConfig config,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @Nullable ConvertLauncher convertLauncher,
        @NonNull Runnable proceed
    ) {
        if (convertLauncher == null
            || optEnum(config.item, "backend", VMBackend.DEFAULT) != VMBackend.CROSVM) {
            proceed.run();
            return;
        }
        var qcow2 = qcow2DiskPaths(config);
        if (qcow2.isEmpty()) {
            proceed.run();
            return;
        }
        var images = new JSONArray();
        for (var p : qcow2) images.put(p);
        DaemonConnection.getInstance().buildRequest("disk_compat")
            .put("images", images)
            .onResponse(resp -> {
                var compressed = resp.optJSONArray("compressed");
                if (compressed == null || compressed.length() == 0)
                    mainHandler.post(proceed);
                else
                    mainHandler.post(() ->
                        promptConvert(config, ui, convertLauncher, compressed, proceed));
            })
            .onUnsuccessful(resp -> mainHandler.post(proceed))
            .onError(e -> {
                Log.w(TAG, "disk_compat check failed", e);
                mainHandler.post(proceed);
            })
            .invoke();
    }

    private static void promptConvert(
        @NonNull VMConfig config,
        @NonNull UIContext ui,
        @NonNull ConvertLauncher convertLauncher,
        @NonNull JSONArray compressed,
        @NonNull Runnable proceed
    ) {
        if (!ui.isAlive()) return;
        var ctx = ui.getContext();
        var files = new StringBuilder();
        for (int i = 0; i < compressed.length(); i++) {
            var p = compressed.optString(i, "");
            if (!p.isEmpty()) files.append("\n• ").append(basename(p));
        }
        var dialog = new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.vm_compressed_disk_title)
            .setMessage(ctx.getString(R.string.vm_compressed_disk_message, files.toString()))
            .setPositiveButton(R.string.vm_compressed_disk_convert,
                (d, w) -> convertNext(ctx, convertLauncher, compressed, 0, proceed))
            .setNeutralButton(R.string.vm_compressed_disk_start_anyway, (d, w) -> proceed.run())
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        // No response in 5s = "start anyway" (the chosen default), so an
        // unattended start isn't blocked; the countdown shows on that button.
        var startAnyway = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (startAnyway != null)
            startAnyway.setText(ctx.getString(R.string.vm_compressed_disk_start_countdown, 5));
        var timer = new CountDownTimer(5000, 1000) {
            @Override
            public void onTick(long ms) {
                if (startAnyway != null)
                    startAnyway.setText(ctx.getString(
                        R.string.vm_compressed_disk_start_countdown,
                        (int) Math.ceil(ms / 1000.0)));
            }

            @Override
            public void onFinish() {
                dialog.dismiss();
                proceed.run();
            }
        };
        // Any interaction (a button tap dismisses the dialog) stops the countdown.
        dialog.setOnDismissListener(d -> timer.cancel());
        timer.start();
    }

    /** Convert each compressed disk in turn, then {@code proceed} to start. */
    private static void convertNext(
        @NonNull Context ctx,
        @NonNull ConvertLauncher convertLauncher,
        @NonNull JSONArray compressed,
        int i,
        @NonNull Runnable proceed
    ) {
        if (i >= compressed.length()) {
            proceed.run();
            return;
        }
        var path = compressed.optString(i, "");
        if (path.isEmpty()) {
            convertNext(ctx, convertLauncher, compressed, i + 1, proceed);
            return;
        }
        var intent = DiskOperationActivity.optimizeForResultIntent(ctx, path, basename(path));
        convertLauncher.launch(intent,
            () -> convertNext(ctx, convertLauncher, compressed, i + 1, proceed));
    }

    /** Absolute paths of the VM's qcow2 disks (the ones crosvm reads as blocks). */
    @NonNull
    private static List<String> qcow2DiskPaths(@NonNull VMConfig config) {
        var out = new ArrayList<String>();
        var disks = config.item.opt("disks", null);
        if (disks != null && disks.is(DataItem.Type.ARRAY)) {
            for (var disk : disks.asArray()) {
                var path = disk.optString("path", "");
                if (!path.isEmpty() && DiskFormat.fromFilename(path) == DiskFormat.QCOW2)
                    out.add(path);
            }
        }
        return out;
    }

    /**
     * Persists a "remember this choice" boot-menu selection to the on-disk
     * VM store — the source of truth the list reloads on resume, which is
     * why a one-shot start that only mutated the in-memory config never
     * stuck. The same change is mirrored onto {@code config} so the daemon
     * gets it via vm_modify. The built-in kernel is baked into a plain
     * manual source (so it persists without a one-shot override and the
     * menu stops appearing for that VM); image entries are pinned, with a
     * null selection clearing the pin to follow the bootloader default.
     *
     * @return the boot_entry to start with (null once the built-in choice
     *         has been baked into the config; the original key otherwise)
     */
    @Nullable
    private static String rememberChoice(
        @NonNull Context context,
        @NonNull VMConfig config,
        @Nullable String bootEntry,
        @Nullable BootConfig.ImageEntry selected,
        @Nullable String builtinCmdline
    ) {
        boolean builtin = BootConfig.BUILTIN_ENTRY_KEY.equals(bootEntry);
        applyChoice(BootConfig.of(config), builtin, selected, builtinCmdline);
        var store = new VMStore();
        if (store.load(context)) {
            var stored = store.findById(config.getId());
            if (stored != null) {
                applyChoice(BootConfig.of(stored), builtin, selected, builtinCmdline);
                store.save(context);
            }
        }
        // built-in now boots as a plain manual config — no one-shot override
        return builtin ? null : bootEntry;
    }

    private static void applyChoice(
        @NonNull BootConfig boot,
        boolean builtin,
        @Nullable BootConfig.ImageEntry selected,
        @Nullable String builtinCmdline
    ) {
        if (!builtin) {
            boot.setImageEntry(selected);
            return;
        }
        boot.setLinuxSource(BootConfig.LinuxSource.MANUAL);
        boot.setKernel(PATH_BUILTIN_KERNEL);
        boot.setInitrd(PATH_BUILTIN_INITRD);
        boot.setCmdline(builtinCmdline != null && !builtinCmdline.isEmpty()
            ? builtinCmdline : BootConfig.DEFAULT_MANUAL_CMDLINE);
    }

    private static void doCreateAndStart(
        @NonNull VMConfig config,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @NonNull AtomicBoolean wantOpenConsole,
        @Nullable String bootEntry
    ) {
        var conn = DaemonConnection.getInstance();
        var createReq = conn.buildRequest("vm_exists");
        createReq.put("vm_id", config.getId().toString());
        DaemonConnection.OnError err = e ->
            showDaemonError(mainHandler, ui, e);
        DaemonConnection.OnUnsuccessful f = resp ->
            showError(mainHandler, ui, resp.optString("message", "Unknown error"));
        DaemonConnection.OnResponse onStart = resp -> {
            if (isAutoConsoleEnabled(ui.getContext()))
                wantOpenConsole.set(true);
        };
        DaemonConnection.OnResponse onCreateModify = resp -> conn
            .buildRequest("vm_start")
            .copy(resp, "vm_id")
            .put("clear_logs_before_start", isClearLogsBeforeStartEnabled(ui.getContext()))
            .put("boot_entry", bootEntry == null ? "" : bootEntry)
            .onResponse(onStart)
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
        DaemonConnection.OnResponse onExists = resp -> {
            var exists = resp.optBoolean("exists", false);
            conn.buildRequest(exists ? "vm_modify" : "vm_create")
                .put("config", config)
                .onResponse(onCreateModify)
                .onUnsuccessful(f)
                .onError(err)
                .invoke();
        };
        conn.buildRequest("vm_exists")
            .put("vm_id", config.getId())
            .onResponse(onExists)
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
    }

    public static void sendCommand(
        @NonNull String command,
        @NonNull UUID vmId,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui
    ) {
        sendCommand(command, vmId, mainHandler, ui, null);
    }

    public static void sendCommand(
        @NonNull String command,
        @NonNull UUID vmId,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @Nullable Runnable onSuccess
    ) {
        DaemonConnection.OnError err = e ->
            showDaemonError(mainHandler, ui, e);
        DaemonConnection.OnUnsuccessful f = resp ->
            showError(mainHandler, ui, resp.optString("message", "Unknown error"));
        DaemonConnection.getInstance().buildRequest(command)
            .put("vm_id", vmId.toString())
            .onResponse(resp -> {
                if (onSuccess != null) onSuccess.run();
            })
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
    }

    public static void sendControlCommand(
        @NonNull String cmd,
        @NonNull UUID vmId,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui
    ) {
        DaemonConnection.OnError err = e ->
            showDaemonError(mainHandler, ui, e);
        DaemonConnection.OnUnsuccessful f = resp ->
            showError(mainHandler, ui, resp.optString("message", "Unknown error"));
        DaemonConnection.getInstance().buildRequest("vm_control")
            .put("vm_id", vmId.toString())
            .put("cmd", cmd)
            .onUnsuccessful(f)
            .onError(err)
            .invoke();
    }

    private static void showError(@NonNull Handler handler, UIContext ui, String msg) {
        handler.post(() -> {
            if (ui.isAlive())
                Toast.makeText(ui.getContext(), msg, LENGTH_LONG).show();
        });
    }

    private static void showDaemonError(@NonNull Handler handler, UIContext ui, Exception e) {
        Log.e(TAG, "Daemon request failed", e);
        handler.post(() -> {
            if (ui.isAlive())
                Toast.makeText(ui.getContext(), R.string.vm_daemon_error, LENGTH_LONG).show();
        });
    }
}
