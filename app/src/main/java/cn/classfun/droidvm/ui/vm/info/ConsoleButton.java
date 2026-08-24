// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.info;

import static android.widget.Toast.LENGTH_SHORT;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMState;
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

    /** One chooser row. The stdio row is special: it carries the stdout/stderr side buttons. */
    private static final class Row {
        final String title;
        final String name;
        final int icon;
        final boolean stdio;

        Row(String title, String name, int icon, boolean stdio) {
            this.title = title;
            this.name = name;
            this.icon = icon;
            this.stdio = stdio;
        }
    }

    /**
     * The chooser's list. Two row shapes: the plain icon+label row, and the stdio row whose
     * trailing stdout/stderr buttons open the split views the row itself folds together.
     */
    private static final class ChooserAdapter extends BaseAdapter {
        private final Context context;
        private final List<Row> rows;
        private final Consumer<String> onSubStream;

        ChooserAdapter(Context context, List<Row> rows, Consumer<String> onSubStream) {
            this.context = context;
            this.rows = rows;
            this.onSubStream = onSubStream;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).stdio ? 1 : 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            var row = rows.get(position);
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(
                    row.stdio ? R.layout.item_console_stdio : R.layout.item_icon_text,
                    parent, false);
            }
            ImageView iv = convertView.findViewById(R.id.iv_icon);
            TextView tv = convertView.findViewById(R.id.tv_label);
            iv.setImageResource(row.icon);
            tv.setText(row.title);
            if (row.stdio) {
                convertView.<View>findViewById(R.id.btn_stdout)
                    .setOnClickListener(v -> onSubStream.accept("stdout"));
                convertView.<View>findViewById(R.id.btn_stderr)
                    .setOnClickListener(v -> onSubStream.accept("stderr"));
            }
            return convertView;
        }
    }

    /**
     * Pretty title for a text-console stream. Names minted by the serial-port config
     * (serialN/sbsaN/vconN) are shown the way the editor names the port; anything else --
     * QEMU's legacy "uart", stdio -- shows its raw name, as before.
     */
    @NonNull
    private String streamTitle(@NonNull String name) {
        var pretty = name;
        if (name.matches("serial[0-9]+"))
            pretty = fmt("%s %s",
                parent.getString(R.string.edit_vm_serial_hw_serial), name.substring(6));
        else if (name.matches("sbsa[0-9]+"))
            pretty = fmt("%s %s",
                parent.getString(R.string.edit_vm_serial_hw_sbsa), name.substring(4));
        else if (name.matches("vcon[0-9]+"))
            pretty = fmt("%s %s",
                parent.getString(R.string.edit_vm_serial_hw_virtio_console), name.substring(4));
        return parent.getString(R.string.vm_info_console_text_select, pretty);
    }

    private void buildConsoleChooserDialog(@Nullable JSONArray streams) {
        if (parent.isFinishing()) return;
        var rows = new ArrayList<Row>();
        var sawStd = false;
        if (streams != null) for (int i = 0; i < streams.length(); i++) {
            var name = streams.optString(i, "");
            if (name.isEmpty()) continue;
            // stdout/stderr fold into the stdio row's side buttons instead of rows of their own.
            if (name.equals("stdout") || name.equals("stderr")) {
                sawStd = true;
                continue;
            }
            if (name.equals("stdio")) {
                rows.add(new Row(streamTitle(name), name, R.drawable.ic_serial_port, true));
                continue;
            }
            rows.add(new Row(streamTitle(name), name, R.drawable.ic_serial_port, false));
        }
        var running = parent.currentState != VMState.STOPPED;
        var cfg = parent.config == null ? DataItem.newObject() : parent.config.item;
        var hasVnc = running && cfg.optBoolean("vnc_enabled", false);
        var hasNative = running && cfg.optBoolean("native_display_enabled", false);
        if (hasNative) {
            rows.add(new Row(parent.getString(R.string.vm_info_console_native_select),
                "native", R.drawable.ic_monitor, false));
        }
        if (hasVnc) {
            rows.add(new Row(parent.getString(R.string.vm_info_console_vnc_select),
                "vnc", R.drawable.ic_remote_desktop, false));
            rows.add(new Row(parent.getString(R.string.vm_info_console_vnc_ext_select),
                "vnc-ext", R.drawable.ic_monitor, false));
        }
        if (rows.isEmpty() && !sawStd) {
            Toast.makeText(parent, R.string.vm_info_console_not_found, LENGTH_SHORT).show();
            return;
        }
        if (rows.size() == 1 && !rows.get(0).stdio && !hasVnc && !hasNative) {
            openConsole(rows.get(0).name);
            return;
        }
        var dialogHolder = new AlertDialog[1];
        Consumer<String> onSubStream = stream -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            openConsole(stream);
        };
        var adapter = new ChooserAdapter(parent, rows, onSubStream);
        DialogInterface.OnClickListener callback = (dialog, which) -> {
            var selected = rows.get(which).name;
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
        dialogHolder[0] = new MaterialAlertDialogBuilder(parent)
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
