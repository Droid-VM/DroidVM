package cn.classfun.droidvm.ui.vm.info;

import static android.widget.Toast.LENGTH_SHORT;

import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;

import java.util.ArrayList;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.lib.ui.IconItemAdapter;
import cn.classfun.droidvm.ui.vm.console.VMConsoleRouter;

public final class ConsoleButton {
    private static final String TAG = "VMInfoActivity";
    private final VMInfoActivity parent;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ConsoleButton(VMInfoActivity parent) {
        this.parent = parent;
    }

    void showConsoleChooser() {
        if (parent.config == null) return;
        DaemonConnection.getInstance().buildRequest("vm_console_list")
            .put("vm_id", parent.vmId.toString())
            .onResponse(resp ->
                mainHandler.post(() -> buildConsoleChooserDialog(resp.optJSONArray("data"))))
            .onUnsuccessful(r ->
                mainHandler.post(() -> buildConsoleChooserDialog(null)))
            .onError(e -> {
                Log.w(TAG, "Failed to list consoles", e);
                mainHandler.post(() -> buildConsoleChooserDialog(null));
            })
            .invoke();
    }

    void openDefaultConsole() {
        if (parent.config == null) return;
        // Shared with the VM-list auto-open-after-start (VMConsoleRouter), so both
        // pick the same view: native -> VNC -> serial (uart, then stdio).
        VMConsoleRouter.openDefault(parent, parent.vmId, parent.config,
            parent.currentState != VMState.STOPPED);
    }

    private void buildConsoleChooserDialog(@Nullable JSONArray streams) {
        if (parent.isFinishing()) return;
        var titles = new ArrayList<String>();
        var names = new ArrayList<String>();
        var icons = new ArrayList<Integer>();
        if (streams != null) for (int i = 0; i < streams.length(); i++) {
            var name = streams.optString(i, "");
            if (name.isEmpty()) continue;
            names.add(name);
            titles.add(parent.getString(R.string.vm_info_console_text_select, name));
            icons.add(R.drawable.ic_serial_port);
        }
        var running = parent.currentState != VMState.STOPPED;
        var cfg = parent.config == null ? DataItem.newObject() : parent.config.item;
        var hasVnc = running && cfg.optBoolean("vnc_enabled", false);
        var hasNative = running && cfg.optBoolean("native_display_enabled", false);
        if (hasNative) {
            names.add("native");
            titles.add(parent.getString(R.string.vm_info_console_native_select));
            icons.add(R.drawable.ic_monitor);
        }
        if (hasVnc) {
            names.add("vnc");
            titles.add(parent.getString(R.string.vm_info_console_vnc_select));
            icons.add(R.drawable.ic_remote_desktop);
            names.add("vnc-ext");
            titles.add(parent.getString(R.string.vm_info_console_vnc_ext_select));
            icons.add(R.drawable.ic_monitor);
        }
        if (titles.isEmpty()) {
            Toast.makeText(parent, R.string.vm_info_console_not_found, LENGTH_SHORT).show();
            return;
        }
        if (names.size() == 1 && !hasVnc && !hasNative) {
            openConsole(names.get(0));
            return;
        }
        var adapter = IconItemAdapter.create(parent, titles, icons);
        DialogInterface.OnClickListener callback = (dialog, which) -> {
            var selected = names.get(which);
            if (selected.equals("native")) {
                openNativeDisplay();
            } else if (selected.equals("vnc")) {
                openVncDisplay();
            } else if (selected.equals("vnc-ext")) {
                openVncExtDisplay();
            } else {
                openConsole(selected);
            }
        };
        new MaterialAlertDialogBuilder(parent)
            .setTitle(R.string.vm_info_console_chooser_title)
            .setAdapter(adapter, callback)
            .show();
    }

    private void openConsole(@NonNull String stream) {
        VMConsoleRouter.openConsole(parent, parent.vmId, parent.config, stream,
            parent.currentState == VMState.STOPPED);
    }

    private void openNativeDisplay() {
        if (parent.config == null) return;
        VMConsoleRouter.openNative(parent, parent.vmId, parent.config);
    }

    private void openVncDisplay() {
        if (parent.config == null) return;
        VMConsoleRouter.openVnc(parent, parent.vmId, parent.config);
    }

    private void openVncExtDisplay() {
        if (parent.config == null) return;
        VMConsoleRouter.openVncExt(parent, parent.vmId, parent.config);
    }
}
