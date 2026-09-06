// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm;

import static android.widget.Toast.LENGTH_LONG;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_INITRD;
import static cn.classfun.droidvm.lib.Constants.PATH_BUILTIN_KERNEL;
import static cn.classfun.droidvm.lib.store.enums.Enums.optEnum;
import static cn.classfun.droidvm.lib.utils.ImageUtils.hasInternalSnapshots;
import static cn.classfun.droidvm.lib.utils.StringUtils.basename;
import static cn.classfun.droidvm.lib.utils.StringUtils.dirname;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;
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
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.lib.store.vm.BootConfig;
import cn.classfun.droidvm.lib.utils.ImageUtils;
import cn.classfun.droidvm.ui.disk.action.BackingChainLinker;
import cn.classfun.droidvm.ui.disk.tree.DiskTree;
import cn.classfun.droidvm.lib.store.vm.LendMthpMode;
import cn.classfun.droidvm.lib.store.vm.VMBackend;
import cn.classfun.droidvm.lib.hugepage.PoolPreflight;
import cn.classfun.droidvm.ui.hugepage.HugePageActivity;
import cn.classfun.droidvm.ui.main.settings.KernelModuleDialog;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.UIContext;
import cn.classfun.droidvm.ui.disk.create.DiskCompress;
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
        // Pre-start guards, in order: internal snapshots (crosvm refuses the disk), a base
        // image attached writable (writing would corrupt its overlays - any backend), a disk a
        // running VM already holds, compressed clusters (crosvm boots to I/O errors), a host
        // kernel module this configuration needs but nobody loaded, a LEND mode this kernel's
        // resource manager will not accept, and a huge-page reserve too small to back this VM.
        // Each prompts with its fix and chains to the next; everything else starts normally. The
        // shared-disk guard may hand the rest of the chain a session copy of the config, so
        // everything downstream uses what it passes on rather than `config`.
        guardSnapshotDisks(config, mainHandler, ui, convertLauncher,
            () -> guardLockedParents(config, mainHandler, ui,
                () -> guardSharedRunning(config, mainHandler, ui,
                    started -> guardCompressedDisks(started, mainHandler, ui, convertLauncher,
                        () -> guardKernelModules(started, mainHandler, ui,
                            () -> guardLendMthp(started, mainHandler, ui,
                                () -> guardHugePagePool(started, mainHandler, ui,
                                    () -> startAfterGuard(started, mainHandler, ui,
                                        wantOpenConsole))))))));
    }

    /**
     * Pre-start check: will this kernel's Gunyah resource manager take the parcels this VM is
     * configured to lend? {@link LendMthpPreflight} answers that from the config and the running
     * kernel series; on 6.1 the 256 MB parcels of {@code chunked} are refused a few in, and the VM
     * dies at GH_VM_START naming nothing that would lead anyone to the setting.
     *
     * <p>Unlike the module guard there is something to repair from the dialog, and repairing it is
     * the point: the value is almost always inherited rather than chosen -- a VM package exported
     * from a 6.6 phone carries the mode that phone needed -- so the offer is to correct it and
     * keep the correction, in the store as well as in the config this start hands the daemon.</p>
     *
     * <p>Nobody to ask means the start proceeds untouched, as the other guards do. It will fail,
     * the way it does today; silently rewriting a VM's memory configuration for an unattended
     * start is a worse answer than the failure it would avoid.</p>
     */
    private static void guardLendMthp(
        @NonNull VMConfig config,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @NonNull Runnable proceed
    ) {
        var appContext = ui.getContext().getApplicationContext();
        runOnPool(() -> {
            boolean refused;
            try {
                refused = LendMthpPreflight.check(config.item);
            } catch (Exception e) {
                Log.w(TAG, "LEND mode preflight failed", e);
                refused = false;
            }
            if (!refused) {
                mainHandler.post(proceed);
                return;
            }
            mainHandler.post(() -> promptLendMthp(config, appContext, mainHandler, ui, proceed));
        });
    }

    private static void promptLendMthp(
        @NonNull VMConfig config,
        @NonNull Context appContext,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @NonNull Runnable proceed
    ) {
        if (!ui.isAlive()) {
            proceed.run();
            return;
        }
        var ctx = ui.getContext();
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.vm_lend_mthp_refused_title)
            .setMessage(R.string.vm_lend_mthp_refused_message)
            .setPositiveButton(R.string.vm_lend_mthp_refused_fix, (d, w) ->
                runOnPool(() -> {
                    applySingleLendMthp(appContext, config);
                    mainHandler.post(proceed);
                }))
            .setNeutralButton(R.string.vm_lend_mthp_refused_start_anyway, (d, w) -> proceed.run())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Writes single-parcel LEND to this VM, in both places it has to land.
     *
     * <p>The config the chain carries is what the daemon is given by vm_modify, and the store is
     * what the VM list reloads from -- the same pair, and the same reason, as
     * {@link #rememberChoice}: a change made only in memory starts this VM correctly and is gone by
     * the next one. Does file I/O; call it off the main thread.</p>
     */
    private static void applySingleLendMthp(
        @NonNull Context context,
        @NonNull VMConfig config
    ) {
        config.item.set(LendMthpMode.KEY, LendMthpMode.SINGLE);
        try {
            var store = new VMStore();
            if (store.load(context)) {
                var stored = store.findById(config.getId());
                if (stored != null) {
                    stored.item.set(LendMthpMode.KEY, LendMthpMode.SINGLE);
                    store.save(context);
                }
            }
        } catch (Exception e) {
            // The start still gets the corrected config; only the remembering failed.
            Log.w(TAG, "failed to persist the LEND mode correction", e);
        }
    }

    /**
     * Pre-start check: does this VM's configuration reach for a host kernel module nobody has
     * loaded? {@link KernelModulePreflight} answers that from the config itself (pseudo-
     * unprotected RAM, GPU acceleration, a VM too big for the 6.1 Gunyah driver's page list),
     * counting only the modules the Kernel Module page would actually offer on this phone - a
     * module built for another kernel or another SoC is not something to warn about.
     *
     * <p>Unlike the disk guards there is nothing here to repair on the user's behalf: loading a
     * module is a decision of its own, made in the Kernel Module page, which is why the offer is
     * to go there rather than to fix it from this dialog. Starting anyway is a real answer too -
     * a missing module costs the feature that wanted it, and does not corrupt anything - so it
     * is what the countdown settles on for a start nobody is watching.
     */
    private static void guardKernelModules(
        @NonNull VMConfig config,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @NonNull Runnable proceed
    ) {
        var appContext = ui.getContext().getApplicationContext();
        runOnPool(() -> {
            List<KernelModulePreflight.Missing> missing;
            try {
                missing = KernelModulePreflight.check(appContext, config.item);
            } catch (Exception e) {
                Log.w(TAG, "kernel-module preflight failed", e);
                missing = List.of();
            }
            if (missing.isEmpty()) {
                mainHandler.post(proceed);
                return;
            }
            var found = missing;
            mainHandler.post(() -> promptKernelModules(ui, found, proceed));
        });
    }

    private static void promptKernelModules(
        @NonNull UIContext ui,
        @NonNull List<KernelModulePreflight.Missing> missing,
        @NonNull Runnable proceed
    ) {
        if (!ui.isAlive()) {
            // Nobody to ask: the start was requested, so honour it.
            proceed.run();
            return;
        }
        var ctx = ui.getContext();
        var lines = new StringBuilder();
        for (var m : missing)
            lines.append("\n- ").append(m.display).append(": ").append(ctx.getString(m.reason));
        var dialog = new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.vm_kernel_module_title)
            .setMessage(ctx.getString(R.string.vm_kernel_module_message, lines.toString()))
            .setPositiveButton(R.string.vm_kernel_module_start_anyway, (d, w) -> proceed.run())
            .setNeutralButton(R.string.vm_kernel_module_manage, (d, w) -> showKernelModules(ctx))
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        // No response in 5s = "start anyway" (the chosen default), so an
        // unattended start isn't blocked; the countdown shows on that button.
        var startAnyway = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (startAnyway != null)
            startAnyway.setText(ctx.getString(R.string.vm_kernel_module_start_countdown, 5));
        var timer = new CountDownTimer(5000, 1000) {
            @Override
            public void onTick(long ms) {
                if (startAnyway != null)
                    startAnyway.setText(ctx.getString(
                        R.string.vm_kernel_module_start_countdown,
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

    /**
     * Opens the Kernel Module list (the same one Settings shows). This ends the start: loading a
     * module is not instant, and re-deciding from a page the user is still working in would be
     * guesswork - they start the VM again when they are done.
     */
    private static void showKernelModules(@NonNull Context ctx) {
        if (ctx instanceof FragmentActivity) {
            KernelModuleDialog.show(((FragmentActivity) ctx).getSupportFragmentManager());
            return;
        }
        Log.w(TAG, "no fragment host for the kernel module list");
        Toast.makeText(ctx, R.string.vm_kernel_module_manage_unavailable, LENGTH_LONG).show();
    }

    /**
     * Pre-start check: can the huge-page reserve back this VM right now?
     *
     * <p>When it cannot, the memory crosvm hands the hypervisor comes from ordinary movable
     * memory instead of the reserve's isolated folios, and handing that over means migrating it
     * out of CMA first -- which on a tight phone has stalled the whole host for minutes or reset
     * it outright. The pool refills a couple of seconds after a VM exits, so the usual cause is
     * simply starting again too soon, and the usual fix is to wait a moment and retry.
     *
     * <p>Foreground starts ask rather than wait: someone is looking at the screen, and they may
     * well know something we do not (a smaller VM about to be shut down, a deliberate
     * experiment). Background starts wait instead -- see {@code PoolPreflight.waitForPool}.
     */
    private static void guardHugePagePool(
        @NonNull VMConfig config,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @NonNull Runnable proceed
    ) {
        runOnPool(() -> {
            var status = PoolPreflight.check(config.item);
            if (status.isEnough()) {
                mainHandler.post(proceed);
                return;
            }
            mainHandler.post(() -> promptHugePageShort(ui, status, proceed));
        });
    }

    private static void promptHugePageShort(
        @NonNull UIContext ui,
        @NonNull PoolPreflight.Status status,
        @NonNull Runnable proceed
    ) {
        if (!ui.isAlive()) {
            // Nobody to ask: the start was requested, so honour it.
            proceed.run();
            return;
        }
        var ctx = ui.getContext();
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.vm_hugepage_short_title)
            .setMessage(ctx.getString(R.string.vm_hugepage_short_message,
                status.availMb(), status.neededMb(), status.shortMb()))
            .setPositiveButton(R.string.vm_hugepage_short_settings, (d, w) ->
                ctx.startActivity(new Intent(ctx, HugePageActivity.class)))
            .setNeutralButton(R.string.vm_hugepage_short_start_anyway, (d, w) -> proceed.run())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
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
     * Pre-start check: a disk attached writable while registered overlays build on it must not
     * be written - the overlays' copy-on-write base would shift under them - so offer to flip
     * those attachments to read-only (persisted) and start. Backend-independent, unlike the
     * crosvm-specific guards. The registry changes after this VM was configured (an overlay
     * created elsewhere), which is why the disk editor's forced-readonly alone isn't enough.
     */
    private static void guardLockedParents(
        @NonNull VMConfig config,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @NonNull Runnable proceed
    ) {
        if (!ui.isAlive()) {
            proceed.run();
            return;
        }
        var appContext = ui.getContext().getApplicationContext();
        runOnPool(() -> {
            // Reconcile parent links from the images' own headers first, so registries predating
            // the overlay tree (or images rebased outside the app) lock correctly from here on.
            // Cheap and unambiguous at this point: a chain whose members are all present is
            // exactly the case where linking is right, and a broken one can't boot anyway.
            BackingChainLinker.repairAllBlocking(appContext, qcow2DiskPaths(config));
            var lockedPaths = new ArrayList<String>();
            try {
                var diskStore = new DiskStore();
                diskStore.load(appContext);
                for (var path : VmDiskSharing.attachedPaths(config, true)) {
                    var registered = diskStore.findByPath(path);
                    if (registered != null && diskStore.hasChildren(registered.getId()))
                        lockedPaths.add(path);
                }
            } catch (Exception e) {
                Log.w(TAG, "locked-parent check failed", e);
            }
            if (lockedPaths.isEmpty()) {
                mainHandler.post(proceed);
                return;
            }
            mainHandler.post(() -> {
                if (!ui.isAlive()) return;
                var ctx = ui.getContext();
                var files = new StringBuilder();
                for (var p : lockedPaths) files.append("\n- ").append(basename(p));
                new MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.vm_locked_disk_title)
                    .setMessage(ctx.getString(R.string.vm_locked_disk_message, files))
                    .setPositiveButton(R.string.vm_locked_disk_readonly_start, (d, w) ->
                        runOnPool(() -> {
                            applyReadonly(appContext, config, lockedPaths);
                            mainHandler.post(proceed);
                        }))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
        });
    }

    /**
     * Pre-start check: another VM may attach the same disk file, and while that VM is running
     * both would have it open - two writers corrupt the image, and a reader under a writer sees
     * it change underneath. Sharing on its own is fine, so this asks the daemon instead: with
     * every other VM on the disk stopped, the start proceeds writable and untouched; with any of
     * them anything else (starting, running, suspended, stopping, rebooting) the start is
     * offered read-only for those disks.
     *
     * <p>That flip lasts one boot: the copy handed to {@code proceed} is what the daemon is
     * given, and the stored config keeps its writable slots, so the next start decides again
     * from what the user saved.
     */
    private static void guardSharedRunning(
        @NonNull VMConfig config,
        @NonNull Handler mainHandler,
        @NonNull UIContext ui,
        @NonNull Consumer<VMConfig> proceed
    ) {
        var appContext = ui.getContext().getApplicationContext();
        runOnPool(() -> {
            var held = new LinkedHashMap<String, List<String>>();
            try {
                var vmStore = new VMStore();
                if (vmStore.load(appContext)) {
                    var sharers = VmDiskSharing.sharersOf(vmStore, config.getId(),
                        VmDiskSharing.attachedPaths(config, true));
                    if (!sharers.isEmpty()) {
                        var names = new LinkedHashSet<String>();
                        for (var vms : sharers.values()) names.addAll(vms);
                        // Blocking daemon query; a daemon that cannot be reached has no VM
                        // running either, so it reads as "nobody holds these".
                        var running = new HashSet<>(VmRunningQuery.inUseAmong(names));
                        for (var entry : sharers.entrySet()) {
                            var holders = new ArrayList<String>();
                            for (var name : entry.getValue())
                                if (running.contains(name)) holders.add(name);
                            if (!holders.isEmpty()) held.put(entry.getKey(), holders);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "shared-disk check failed", e);
            }
            if (held.isEmpty()) {
                mainHandler.post(() -> proceed.accept(config));
                return;
            }
            mainHandler.post(() -> promptSharedRunning(config, ui, held, proceed));
        });
    }

    private static void promptSharedRunning(
        @NonNull VMConfig config,
        @NonNull UIContext ui,
        @NonNull Map<String, List<String>> held,
        @NonNull Consumer<VMConfig> proceed
    ) {
        Runnable start = () -> proceed.accept(readonlyForSession(config, held.keySet()));
        // Nobody to ask: the start was requested and read-only is the answer that cannot corrupt
        // anything, which is the same one the countdown below settles on.
        if (!ui.isAlive()) {
            start.run();
            return;
        }
        var ctx = ui.getContext();
        var files = new StringBuilder();
        for (var entry : held.entrySet())
            files.append("\n- ").append(basename(entry.getKey()))
                .append(" (").append(String.join(", ", entry.getValue())).append(")");
        var dialog = new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.vm_shared_disk_title)
            .setMessage(ctx.getString(R.string.vm_shared_disk_message, files.toString()))
            .setPositiveButton(R.string.vm_shared_disk_readonly_start, (d, w) -> start.run())
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        // No response in 5s = the read-only start (the only safe answer while the other VM
        // holds the file), so an unattended start isn't blocked; the countdown shows on it.
        var readonlyStart = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (readonlyStart != null)
            readonlyStart.setText(ctx.getString(R.string.vm_shared_disk_readonly_countdown, 5));
        var timer = new CountDownTimer(5000, 1000) {
            @Override
            public void onTick(long ms) {
                if (readonlyStart != null)
                    readonlyStart.setText(ctx.getString(
                        R.string.vm_shared_disk_readonly_countdown,
                        (int) Math.ceil(ms / 1000.0)));
            }

            @Override
            public void onFinish() {
                dialog.dismiss();
                start.run();
            }
        };
        // Any interaction (a button tap dismisses the dialog) stops the countdown.
        dialog.setOnDismissListener(d -> timer.cancel());
        timer.start();
    }

    /**
     * A copy of {@code config} with {@code paths} attached read-only, for this start only. The
     * daemon is handed the copy (vm_create/vm_modify take whatever config the chain carries), so
     * neither the VM store nor the config the UI holds records the flip.
     */
    @NonNull
    private static VMConfig readonlyForSession(
        @NonNull VMConfig config,
        @NonNull Set<String> paths
    ) {
        var pathList = new ArrayList<>(paths);
        try {
            var session = new VMConfig();
            // Through JSON: DataItem's copy constructor shares the nested items, and setting
            // read-only on those would write straight back into the caller's config.
            session.item.set(config.toJson());
            setReadonlyOnDisks(session, pathList);
            return session;
        } catch (Exception e) {
            // Starting writable is what this guard exists to prevent, so flip the live config
            // instead and accept that the editor shows read-only until it reloads; nothing is
            // saved either way.
            Log.w(TAG, "session config copy failed; flipping the live config instead", e);
            setReadonlyOnDisks(config, pathList);
            return config;
        }
    }

    /** Flip the given attachments to read-only on the live config and the persisted VM store. */
    private static void applyReadonly(
        @NonNull Context context,
        @NonNull VMConfig config,
        @NonNull List<String> paths
    ) {
        setReadonlyOnDisks(config, paths);
        try {
            var store = new VMStore();
            if (store.load(store, context)) {
                var stored = store.findById(config.getId());
                if (stored != null) {
                    setReadonlyOnDisks(stored, paths);
                    store.save(context);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist read-only flip", e);
        }
    }

    private static void setReadonlyOnDisks(@NonNull VMConfig config, @NonNull List<String> paths) {
        var disks = config.item.opt("disks", null);
        if (disks == null || !disks.is(DataItem.Type.ARRAY)) return;
        for (var disk : disks.asArray()) {
            if (paths.contains(disk.optString("path", "")))
                disk.set("readonly", true);
        }
    }

    /**
     * Pre-start check: crosvm refuses to open a qcow2 with internal snapshots for writing (it
     * has no snapshot support, and writing would corrupt them), so a writable disk carrying
     * snapshots means the VM cannot start at all. Offer to flatten it - the same convert the
     * compression guard uses, which keeps the active state and drops the snapshots - and say so
     * plainly, since that is destructive in a way the compression convert is not. There is no
     * "start anyway": crosvm would just fail to open the disk. Read-only disks are skipped;
     * crosvm accepts snapshots there.
     */
    private static void guardSnapshotDisks(
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
        var qcow2 = qcow2DiskPaths(config, true);
        if (qcow2.isEmpty()) {
            proceed.run();
            return;
        }
        runOnPool(() -> {
            var snapshotted = new JSONArray();
            for (var p : qcow2)
                if (hasInternalSnapshots(p)) snapshotted.put(p);
            if (snapshotted.length() == 0)
                mainHandler.post(proceed);
            else
                mainHandler.post(() ->
                    promptFlattenSnapshots(ui, convertLauncher, snapshotted, proceed));
        });
    }

    private static void promptFlattenSnapshots(
        @NonNull UIContext ui,
        @NonNull ConvertLauncher convertLauncher,
        @NonNull JSONArray snapshotted,
        @NonNull Runnable proceed
    ) {
        if (!ui.isAlive()) return;
        var ctx = ui.getContext();
        var files = new StringBuilder();
        for (int i = 0; i < snapshotted.length(); i++) {
            var p = snapshotted.optString(i, "");
            if (!p.isEmpty()) files.append("\n- ").append(basename(p));
        }
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.vm_snapshot_disk_title)
            .setMessage(ctx.getString(R.string.vm_snapshot_disk_message, files.toString()))
            .setPositiveButton(R.string.vm_snapshot_disk_flatten,
                (d, w) -> convertNext(ctx, convertLauncher, snapshotted, 0, proceed))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Pre-start check: for a crosvm VM with qcow2 disks, detect each disk's compression via
     * qemu-img ({@link DiskCompress#detect}; ImageUtils runs it through the root run-context,
     * so daemon-owned paths read fine) and prompt to convert any whose compression isn't in
     * {@link DiskCompress#CROSVM_SUPPORTED} - the convert rewrites uncompressed, which is
     * always supported - then {@code proceed}; otherwise {@code proceed} straight away. A
     * detection failure reads as uncompressed, so a check hiccup never blocks a start (a real
     * boot would surface the problem anyway).
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
        runOnPool(() -> {
            // Walk each disk's whole backing chain: crosvm reads the base images too, so a
            // compressed cluster anywhere in the chain gives the guest the same I/O errors.
            // The convert preserves an overlay's backing (appendConvert carries it), so
            // decompressing a chain member never flattens it.
            var unsupported = new JSONArray();
            var seen = new HashSet<String>();
            for (var top : qcow2)
                for (var p : backingChainOf(top))
                    if (seen.add(p) && !DiskCompress.detect(p).isCrosvmSupported())
                        unsupported.put(p);
            if (unsupported.length() == 0)
                mainHandler.post(proceed);
            else
                mainHandler.post(() ->
                    promptConvert(config, ui, convertLauncher, unsupported, proceed));
        });
    }

    /** {@code path} plus every backing file under it (header walk, cycle- and depth-guarded). */
    @NonNull
    private static List<String> backingChainOf(@NonNull String path) {
        var out = new ArrayList<String>();
        var seen = new HashSet<String>();
        var current = path;
        for (int i = 0; i < DiskTree.MAX_DEPTH && seen.add(current); i++) {
            out.add(current);
            try {
                var info = ImageUtils.getImageInfo(current);
                var backing = info.optString("full-backing-filename",
                    info.optString("backing-filename", ""));
                if (backing.isEmpty()) break;
                if (!backing.startsWith("/"))
                    backing = pathJoin(dirname(current), backing);
                current = backing;
            } catch (Exception e) {
                break; // unreadable member - the boot itself will surface it
            }
        }
        return out;
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
            if (!p.isEmpty()) files.append("\n- ").append(basename(p));
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
        return qcow2DiskPaths(config, false);
    }

    /**
     * Absolute paths of the VM's qcow2 disks; with {@code writableOnly}, skips the ones attached
     * read-only ({@code ro=true}), which crosvm opens under rules of their own - notably it
     * accepts internal snapshots there, since reading never touches them.
     */
    @NonNull
    private static List<String> qcow2DiskPaths(@NonNull VMConfig config, boolean writableOnly) {
        var out = new ArrayList<String>();
        var disks = config.item.opt("disks", null);
        if (disks != null && disks.is(DataItem.Type.ARRAY)) {
            for (var disk : disks.asArray()) {
                var path = disk.optString("path", "");
                if (path.isEmpty() || DiskFormat.fromFilename(path) != DiskFormat.QCOW2)
                    continue;
                if (writableOnly && disk.optBoolean("readonly", false))
                    continue;
                out.add(path);
            }
        }
        return out;
    }

    /**
     * Persists a "remember this choice" boot-menu selection to the on-disk
     * VM store -- the source of truth the list reloads on resume, which is
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
        // built-in now boots as a plain manual config -- no one-shot override
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
        // migrated / decoupled NICs may have a static lease enabled but no
        // offset yet; assign a conflict-free one (and persist it) before the
        // config is pushed to the daemon, which never allocates offsets itself
        NicLeaseAllocator.resolveAndPersist(config, ui.getContext());
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
