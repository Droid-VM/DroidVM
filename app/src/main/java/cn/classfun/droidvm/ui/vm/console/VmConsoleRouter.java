package cn.classfun.droidvm.ui.vm.console;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.UUID;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.ui.vm.display.nativedisplay.display.VMNativeDisplayActivity;
import cn.classfun.droidvm.ui.vm.display.vnc.base.BaseVncActivity;
import cn.classfun.droidvm.ui.vm.display.vnc.display.VMVncDisplayActivity;
import cn.classfun.droidvm.ui.vm.display.vnc.display.VMVncPresentationActivity;

/**
 * Shared "open the VM's default view" routing, so the VM-info screen (a console
 * button) and the VM-list auto-open-after-start pick the <b>same</b> thing:
 * native display, else VNC, else the serial console (uart, then stdio). Keeping
 * this in one place is why both paths agree instead of the list always opening
 * the UART console regardless of the VM's display.
 */
public final class VmConsoleRouter {
    private VmConsoleRouter() {
    }

    /**
     * Open the VM's default view. {@code running} = the VM is up (so the serial
     * console shows the live stream, not saved logs).
     */
    public static void openDefault(@NonNull Context ctx, @NonNull UUID vmId,
                                   @NonNull VMConfig config, boolean running) {
        var item = config.item;
        if (item.optBoolean("native_display_enabled", false)) {
            openNative(ctx, vmId, config);
            return;
        }
        if (item.optBoolean("vnc_enabled", false)) {
            openVnc(ctx, vmId, config);
            return;
        }
        // Serial console: ask the daemon which streams exist, prefer uart then stdio.
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
                    else if (names.contains("stdio")) stream = "stdio";
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

    public static void openNative(@NonNull Context ctx, @NonNull UUID vmId, @NonNull VMConfig config) {
        var item = config.item;
        var intent = new Intent(ctx, VMNativeDisplayActivity.class);
        intent.putExtra(VMNativeDisplayActivity.EXTRA_VM_ID, vmId.toString());
        intent.putExtra(VMNativeDisplayActivity.EXTRA_VM_NAME, config.getName());
        intent.putExtra(VMNativeDisplayActivity.EXTRA_WIDTH, item.optLong("display_width", 1280));
        intent.putExtra(VMNativeDisplayActivity.EXTRA_HEIGHT, item.optLong("display_height", 720));
        ctx.startActivity(intent);
    }

    public static void openVnc(@NonNull Context ctx, @NonNull UUID vmId, @NonNull VMConfig config) {
        var intent = new Intent(ctx, VMVncDisplayActivity.class);
        intent.putExtra(BaseVncActivity.EXTRA_VM_ID, vmId.toString());
        intent.putExtra(BaseVncActivity.EXTRA_VM_NAME, config.getName());
        ctx.startActivity(intent);
    }

    public static void openVncExt(@NonNull Context ctx, @NonNull UUID vmId, @NonNull VMConfig config) {
        var intent = new Intent(ctx, VMVncPresentationActivity.class);
        intent.putExtra(BaseVncActivity.EXTRA_VM_ID, vmId.toString());
        intent.putExtra(BaseVncActivity.EXTRA_VM_NAME, config.getName());
        ctx.startActivity(intent);
    }
}
