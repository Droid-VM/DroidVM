// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

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
 * {@code ActiveServices} exempts a root caller by app id, and the background-start check seeds
 * itself from that same verdict. The service still runs in the app process under the app's uid,
 * which is the uid whose capability the guest needs, so who asked for it does not change what it
 * grants.</p>
 *
 * <p>Nothing here names a kind of peripheral: the mask comes from
 * {@code PeripheralType.getForegroundServiceType}, and whether a row is a device this VM
 * actually attaches comes from {@code PeripheralType.needsVpu} against the VM's own switch.</p>
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
     * <p>Only <em>effective</em> peripherals count -- the rows the backend will really attach,
     * which is the same question {@code buildPeripheralCommand} asks. A device the host cannot
     * serve is not attached, and neither is a virtio-media one on a VM whose VPU switch is off:
     * a camera listed on such a VM never opens, so raising a camera foreground service for it
     * would put a privacy indicator on the user's screen for a camera nothing is using.</p>
     */
    static int typesFor(@NonNull VMState state, @NonNull DataItem item) {
        if (state == VMState.STOPPED) return 0;
        boolean media = VpuConfig.mediaDevicesAttached(item);
        int mask = 0;
        for (var peripheral : VMPeripheralConfig.listOf(item)) {
            var type = peripheral.getType();
            if (!type.isAvailable()) continue;
            if (type.needsVpu() && !media) continue;
            mask |= type.getForegroundServiceType();
        }
        return mask;
    }

    /** Recomputes from every instance in {@code store} and starts, re-types or stops the service. */
    static synchronized void refresh(@NonNull VMInstanceStore store) {
        int wanted = 0;
        try {
            var mask = new int[1];
            store.forEach((id, instance) ->
                mask[0] |= typesFor(instance.getState(), instance.item));
            wanted = mask[0];
        } catch (Exception e) {
            Log.w(TAG, "could not work out which peripherals are running", e);
            return;
        }
        if (wanted == applied) return;
        var context = DaemonSystemContext.get();
        if (context == null) {
            // Without a Context there is no way to reach the service. Leave `applied` alone so a
            // later call retries rather than believing it has already done this.
            Log.w(TAG, "no system context; peripheral foreground service not updated");
            return;
        }
        Log.i(TAG, fmt("peripheral foreground service types 0x%s -> 0x%s",
            Integer.toHexString(applied), Integer.toHexString(wanted)));
        PeripheralForegroundService.apply(context, wanted);
        applied = wanted;
    }
}
