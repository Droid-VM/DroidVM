// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm;

import android.util.Log;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.daemon.display.DaemonSystemContext;
import cn.classfun.droidvm.lib.peripheral.PeripheralForegroundService;
import cn.classfun.droidvm.lib.store.vm.VMPeripheralConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;

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
 * {@code PeripheralType.getForegroundServiceType}.</p>
 */
final class PeripheralForegroundControl {
    private static final String TAG = "PeripheralFgsControl";

    /** Last mask handed to the service, so an unchanged state is not re-applied on every event. */
    private static int applied = 0;

    private PeripheralForegroundControl() {
    }

    /** Recomputes from every instance in {@code store} and starts, re-types or stops the service. */
    static synchronized void refresh(@NonNull VMInstanceStore store) {
        int wanted = 0;
        try {
            var mask = new int[1];
            store.forEach((id, instance) -> {
                if (instance.getState() == VMState.STOPPED) return;
                for (var peripheral : VMPeripheralConfig.listOf(instance.item)) {
                    var type = peripheral.getType();
                    // A device the host cannot serve is not attached, so it needs nothing.
                    if (!type.isAvailable()) continue;
                    mask[0] |= type.getForegroundServiceType();
                }
            });
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
        Log.i(TAG, "peripheral foreground service types 0x" + Integer.toHexString(applied)
            + " -> 0x" + Integer.toHexString(wanted));
        PeripheralForegroundService.apply(context, wanted);
        applied = wanted;
    }
}
