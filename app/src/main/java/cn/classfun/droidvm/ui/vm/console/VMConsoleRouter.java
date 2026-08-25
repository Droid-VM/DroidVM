// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.console;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.UUID;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.DisplayExporter;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMScreenConfig;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.display.VMNativeDisplayActivity;
import cn.classfun.droidvm.ui.vm.display.vnc.base.BaseVncActivity;
import cn.classfun.droidvm.ui.vm.display.vnc.display.VMVncDisplayActivity;
import cn.classfun.droidvm.ui.vm.display.vnc.display.VMVncPresentationActivity;

/**
 * Shared "open the VM's default view" routing, so the VM-info screen (a console
 * button) and the VM-list auto-open-after-start pick the <b>same</b> thing:
 * the first bound screen, else the serial console (uart, then stdio). Keeping
 * this in one place is why both paths agree instead of the list always opening
 * the UART console regardless of the VM's display.
 *
 * <p>Every display view is opened for one screen, named explicitly, because the VM can have two
 * and they can be exported differently. The default picks the first one bound -- the only one a
 * single-screen VM has; the chooser is where a two-screen VM is asked which.</p>
 */
public final class VMConsoleRouter {
    private VMConsoleRouter() {
    }

    /**
     * Open the VM's default view. {@code running} = the VM is up (so the serial
     * console shows the live stream, not saved logs).
     */
    public static void openDefault(@NonNull Context ctx, @NonNull UUID vmId,
                                   @NonNull VMConfig config, boolean running) {
        var bound = VMScreenConfig.boundOf(config.item);
        if (!bound.isEmpty()) {
            var screen = bound.get(0);
            if (screen.getExporter() == DisplayExporter.NATIVE)
                openNative(ctx, vmId, config, screen.id);
            else
                openVnc(ctx, vmId, config, screen.id);
            return;
        }
        // Serial console: ask the daemon which streams exist. Prefer a real guest serial port
        // (the app-console serial streams, or QEMU's legacy "uart") over the process stdio.
        DaemonConnection.getInstance().buildRequest("vm_console_list")
            .put("vm_id", vmId.toString())
            .onResponse(resp -> {
                var data = resp.optJSONArray("data");
                String stream = null;
                if (data != null) {
                    var names = new ArrayList<String>();
                    for (int i = 0; i < data.length(); i++) {
                        var n = data.optString(i, "");
                        if (!n.isEmpty()) names.add(n);
                    }
                    if (names.contains("uart")) stream = "uart";
                    if (stream == null)
                        for (var n : names)
                            if (n.matches("(serial|sbsa|vcon)[0-9]+")) {
                                stream = n;
                                break;
                            }
                    if (stream == null && names.contains("stdio")) stream = "stdio";
                }
                if (stream == null) return;
                final var s = stream;
                new Handler(Looper.getMainLooper()).post(
                    () -> openConsole(ctx, vmId, config, s, !running));
            })
            .onUnsuccessful(r -> {
            })
            .invoke();
    }

    public static void openConsole(@NonNull Context ctx, @NonNull UUID vmId,
                                   @NonNull VMConfig config, @NonNull String stream, boolean logs) {
        var intent = new Intent(ctx, VMConsoleActivity.class);
        intent.putExtra(VMConsoleActivity.EXTRA_VM_ID, vmId.toString());
        intent.putExtra(VMConsoleActivity.EXTRA_VM_NAME, config.getName());
        intent.putExtra(VMConsoleActivity.EXTRA_STREAM, stream);
        intent.putExtra(VMConsoleActivity.EXTRA_LOGS, logs);
        ctx.startActivity(intent);
    }

    /**
     * Opens [screenId]'s native display. The screen id travels in the intent rather than being
     * inferred here, because the display service name is derived from it and a wrong guess is a
     * console that waits forever on a binder nobody registers.
     */
    public static void openNative(@NonNull Context ctx, @NonNull UUID vmId,
                                  @NonNull VMConfig config, @NonNull String screenId) {
        var item = config.item;
        var intent = new Intent(ctx, VMNativeDisplayActivity.class);
        intent.putExtra(VMNativeDisplayActivity.EXTRA_VM_ID, vmId.toString());
        intent.putExtra(VMNativeDisplayActivity.EXTRA_VM_NAME, config.getName());
        intent.putExtra(VMNativeDisplayActivity.EXTRA_SCREEN, screenId);
        intent.putExtra(VMNativeDisplayActivity.EXTRA_INPUT_ENABLED, inputEnabled(item, screenId));
        intent.putExtra(VMNativeDisplayActivity.EXTRA_WIDTH, item.optLong("display_width", 1280));
        intent.putExtra(VMNativeDisplayActivity.EXTRA_HEIGHT, item.optLong("display_height", 720));
        ctx.startActivity(intent);
    }

    /**
     * Whether [screenId] was started with its own absolute input devices.
     *
     * <p>This is what the config says now, which is only the same as what the running VM has if
     * nobody edited it since. The console uses it to explain dead touch input, never to decide
     * where to send events -- that answer comes from the daemon, which knows which sockets it
     * actually bound.</p>
     */
    private static boolean inputEnabled(@NonNull DataItem item, @NonNull String screenId) {
        var screen = VMScreenConfig.find(item, screenId);
        return screen == null || screen.isInputEnabled();
    }

    public static void openVnc(@NonNull Context ctx, @NonNull UUID vmId,
                               @NonNull VMConfig config, @NonNull String screenId) {
        var intent = new Intent(ctx, VMVncDisplayActivity.class);
        intent.putExtra(BaseVncActivity.EXTRA_VM_ID, vmId.toString());
        intent.putExtra(BaseVncActivity.EXTRA_VM_NAME, config.getName());
        intent.putExtra(BaseVncActivity.EXTRA_SCREEN, screenId);
        intent.putExtra(BaseVncActivity.EXTRA_INPUT_ENABLED, inputEnabled(config.item, screenId));
        ctx.startActivity(intent);
    }

    public static void openVncExt(@NonNull Context ctx, @NonNull UUID vmId,
                                  @NonNull VMConfig config, @NonNull String screenId) {
        var intent = new Intent(ctx, VMVncPresentationActivity.class);
        intent.putExtra(BaseVncActivity.EXTRA_VM_ID, vmId.toString());
        intent.putExtra(BaseVncActivity.EXTRA_VM_NAME, config.getName());
        intent.putExtra(BaseVncActivity.EXTRA_SCREEN, screenId);
        ctx.startActivity(intent);
    }
}
