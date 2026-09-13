// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.pm.ServiceInfo;
import android.util.Log;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.daemon.display.DaemonSystemContext;
import cn.classfun.droidvm.lib.peripheral.PeripheralForegroundService;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.lib.store.vm.VpuConfig;

/**
 * Keeps {@link PeripheralForegroundService} in step with what this daemon is running.
 *
 * <p>Driven from the daemon rather than the app process, even though the service lives in the app.
 * The daemon is the only party that knows when a VM actually starts -- a VM can be started over
 * IPC with no UI open at all -- and it is the only one allowed to raise the service at that
 * moment: an app calling {@code startForegroundService} from the background is refused, while
 * {@code ActiveServices} exempts a root caller by app id ({@code ROOT_UID} yields
 * {@code REASON_SYSTEM_UID}). The service still runs in the app process under the app's uid,
 * which is the uid whose capability the guest needs, so who asked for it does not change what it
 * grants.</p>
 *
 * <p>What that exemption does <em>not</em> buy is the ordinary {@code Context} call: the daemon's
 * Context names the package {@code android} and its ActivityThread has no process record, so
 * {@code startServiceLocked} throws before any of the uid-0 exemptions are reached (defect D11).
 * {@link PeripheralForegroundService#apply} is where that is dealt with; this class only decides
 * the mask.</p>
 *
 * <p>Nothing here names a kind of peripheral: the mask comes from
 * {@code PeripheralType.getForegroundServiceType}, and whether a row is a device this VM
 * actually attaches comes from {@code PeripheralType.isAttachedTo}, the predicate the crosvm
 * backend branches on.</p>
 *
 * <p>It decides one more thing beside the mask: whether the phone's display is held on while a
 * camera VM runs ({@link #keepsScreenOn}, the {@code camera_keep_screen_on} switch). That is here
 * because it is the same question asked of the same VMs at the same moment -- a foreground
 * service makes the uid foreground, and a screen that sleeps takes it straight back to
 * {@code TOP_SLEEPING}, where AppOps stops resolving the camera op and the guest's session dies
 * mid-capture (defect D76). The bit is handed to the service with the mask and the service holds
 * the lock, because the service is the thing whose lifetime already is "a VM that needs this is
 * running".</p>
 *
 * <p>An apply that is refused is not remembered ({@link #appliedAfter}): the mask is the
 * short-circuit for "nothing changed", so recording a refusal as done would turn one transient
 * failure at STARTING into a VM that runs its whole life without the capability.</p>
 *
 * <p>Ordering, which is the part that has to be right: {@code VMInstance.start} calls
 * {@code setState(STARTING)} on the caller's thread and only then creates the worker thread that
 * resolves the boot plan and spawns crosvm, so the request to raise the service is issued before
 * the process exists -- not after it, and not from the worker. What is asynchronous is the last
 * step alone: {@code startForegroundService} returns immediately and the service reaches
 * {@code startForeground} a moment later on the app's main thread. That gap is bounded by the
 * app process starting, and it is spent against a much longer one -- the guest driver does not
 * open a camera until it has probed, which is seconds into the boot -- so the capability is in
 * place well before anything asks for it. Nothing here waits for the service: a start that
 * blocked on the app process would be a VM that does not boot when the app is not installed
 * properly.</p>
 */
final class PeripheralForegroundControl {
    private static final String TAG = "PeripheralFgsControl";

    /** Last mask handed to the service, so an unchanged state is not re-applied on every event. */
    private static int applied = 0;

    /** The same for the display hold, which the service carries beside the mask. */
    private static boolean appliedKeepScreenOn = false;

    private PeripheralForegroundControl() {
    }

    /**
     * The service types one VM in {@code state} with {@code item}'s peripherals needs.
     *
     * <p>The whole rule, in one pure function so it can be tested: a VM that is not STOPPED is a
     * VM whose devices exist. STARTING counts -- the guest driver probes seconds after the
     * process is spawned, and the capability has to already be there when it does; so does
     * STOPPING and REBOOTING, because the device is not gone until the process is.</p>
     *
     * <p>Only <em>effective</em> peripherals count -- the rows the backend will really attach.
     * That is not a similar question to the one {@code buildPeripheralCommand} asks, it is the
     * same one, so it is asked through the same predicate ({@code PeripheralType.isAttachedTo}):
     * a device the host cannot serve is not attached, and neither is a virtio-media one on a VM
     * whose VPU switch is off. A camera listed on such a VM never opens, so raising a camera
     * foreground service for it would put a privacy indicator on the user's screen for a camera
     * nothing is using.</p>
     */
    static int typesFor(@NonNull VMState state, @NonNull DataItem item) {
        if (state == VMState.STOPPED) return 0;
        int mask = 0;
        for (var peripheral : VMPeripheralConfig.listOf(item)) {
            var type = peripheral.getType();
            if (!type.isAttachedTo(item)) continue;
            mask |= type.getForegroundServiceType();
        }
        return mask;
    }

    /**
     * Whether one VM in {@code state} with {@code item}'s peripherals wants the display held on.
     *
     * <p>Two things, and the first is {@link #typesFor}'s own answer rather than a second walk of
     * the peripheral list: a VM keeps the screen awake exactly when it is raising a <em>camera</em>
     * service, which already means a live state, an available type and the VPU switch on. That
     * makes the pair impossible to get out of step -- there is no VM that holds the display and
     * has no camera, and none that opens a camera and lets the display go.</p>
     *
     * <p>The second is the user's own answer, {@code camera_keep_screen_on}, default on. It is
     * read here rather than in {@code VpuConfig} because it is only half a decision on its own:
     * what it modifies is a rule about rows, and the rows are walked here.</p>
     *
     * <p>Why the camera type and not every foreground type: the failure this fixes is specific to
     * a {@code foreground}-mode AppOp being resolved against a procstate that a sleeping screen
     * demotes (defect D76, {@code logs/vpu_wp/B15-build.md} section 5.2). Microphone will have
     * the same shape when it gets a type, and can join by name here; sound and USB have no AppOp
     * to lose.</p>
     */
    static boolean keepsScreenOn(@NonNull VMState state, @NonNull DataItem item) {
        int types = typesFor(state, item);
        return (types & ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0
            && VpuConfig.isCameraKeepScreenOn(item);
    }

    /** Recomputes from every instance in {@code store} and starts, re-types or stops the service. */
    static synchronized void refresh(@NonNull VMInstanceStore store) {
        int wanted = 0;
        boolean wantedKeepScreenOn = false;
        try {
            var mask = new int[1];
            var keep = new boolean[1];
            store.forEach((id, instance) -> {
                mask[0] |= typesFor(instance.getState(), instance.item);
                // A union like the mask, and for the same reason: one VM that asked for the
                // display is enough, and the hold drops only when the last of them has stopped.
                keep[0] |= keepsScreenOn(instance.getState(), instance.item);
            });
            wanted = mask[0];
            wantedKeepScreenOn = keep[0];
        } catch (Exception e) {
            Log.w(TAG, "could not work out which peripherals are running", e);
            return;
        }
        if (wanted == applied && wantedKeepScreenOn == appliedKeepScreenOn) return;
        var context = DaemonSystemContext.get();
        if (context == null) {
            // Without a Context there is no way to reach the service. Leave `applied` alone so a
            // later call retries rather than believing it has already done this.
            Log.w(TAG, "no system context; peripheral foreground service not updated");
            return;
        }
        Log.i(TAG, fmt("peripheral foreground service types 0x%s -> 0x%s, screen held %s -> %s",
            Integer.toHexString(applied), Integer.toHexString(wanted),
            appliedKeepScreenOn, wantedKeepScreenOn));
        boolean ok = PeripheralForegroundService.apply(context, wanted, wantedKeepScreenOn);
        if (!ok)
            Log.w(TAG, fmt("peripheral foreground service types 0x%s refused; will retry on the "
                + "next change", Integer.toHexString(wanted)));
        applied = appliedAfter(wanted, ok);
        appliedKeepScreenOn = keepScreenOnAfter(wantedKeepScreenOn, ok);
    }

    /**
     * What {@code applied} becomes after handing {@code wanted} to the service.
     *
     * <p>The whole point of the field is the short-circuit above: an unchanged mask is not
     * re-applied on every state change. So a refused request must not be recorded as done, or a
     * VM whose STARTING transition was refused keeps the same mask through RUNNING and never
     * asks again -- its camera has no capability for the life of the VM, from one transient
     * refusal. Forgetting it (0) is both the truth, since nothing was raised, and what makes the
     * next transition ask again, even when the mask it wants is the same one.</p>
     */
    static int appliedAfter(int wanted, boolean ok) {
        return ok ? wanted : 0;
    }

    /**
     * The same rule for the display hold, and it has to be the same rule: the two are one request
     * on one intent, so a refusal loses both and the next transition must ask for both again.
     * Recording the hold as applied after a refused start would leave a camera VM whose STARTING
     * transition was refused running to the end of its life with the screen free to sleep -- the
     * D76 failure, arrived at through the retry path rather than through the switch.
     */
    static boolean keepScreenOnAfter(boolean wanted, boolean ok) {
        return ok && wanted;
    }
}
