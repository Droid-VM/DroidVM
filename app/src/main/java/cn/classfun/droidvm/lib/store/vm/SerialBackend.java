// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;

import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * Where a serial port's bytes go on the host -- one per "serial_ports" entry.
 *
 * <p>Most map 1:1 onto a crosvm {@code --serial type=}. The exception is {@code APP_CONSOLE}:
 * crosvm's {@code file} type pointed at a pipe pair the daemon keeps, which is what shows up
 * as a text console in the app.</p>
 */
public enum SerialBackend implements StringEnum {
    /** Bytes are discarded; input is never delivered. crosvm {@code type=sink}. */
    SINK(R.string.edit_vm_serial_backend_sink, false, true),
    /** Wired to a daemon pipe pair and shown as an interactive text console in the app. */
    APP_CONSOLE(R.string.edit_vm_serial_backend_app_console, false, true),
    /** Output appended to a file on the host; no input. crosvm {@code type=file}. */
    FILE(R.string.edit_vm_serial_backend_file, true, true),
    /** Output datagrams to an existing unix socket; no input. crosvm {@code type=unix}. */
    UNIX(R.string.edit_vm_serial_backend_unix, true, true),
    /** Bidirectional unix stream socket (crosvm connects). crosvm {@code type=unix-stream}. */
    UNIX_STREAM(R.string.edit_vm_serial_backend_unix_stream, true, true),
    /**
     * crosvm-opened pty ({@code type=pty}); the path field, when set, becomes a symlink to the
     * slave so consumers find it at a stable name.
     */
    PTY(R.string.edit_vm_serial_backend_pty, true, true),
    /** crosvm's stdout (ends up in the daemon log). crosvm {@code type=stdout}. */
    STDOUT(R.string.edit_vm_serial_backend_stdout, false, true),
    /** Host syslog. crosvm {@code type=syslog}. */
    SYSLOG(R.string.edit_vm_serial_backend_syslog, false, true),
    /**
     * USB gadget CDC-ACM port towards an external host: the daemon grafts an acm function
     * onto the gadget and crosvm opens the resulting ttyGSn ({@code type=dev}). Binding
     * re-enumerates USB for a moment, and the port lives on whichever USB connection is
     * currently active.
     */
    USB_ACM(R.string.edit_vm_serial_backend_usb_acm, false, true);

    private final @StringRes int titleId;
    private final boolean usesPath;
    private final boolean available;

    SerialBackend(@StringRes int titleId, boolean usesPath, boolean available) {
        this.titleId = titleId;
        this.usesPath = usesPath;
        this.available = available;
    }

    @Override
    public int getStringId() {
        return titleId;
    }

    /** True when the row shows the path field (mandatory except for {@link #PTY}). */
    public boolean usesPath() {
        return usesPath;
    }

    /** False when nothing on the host can serve this backend yet; the daemon degrades to sink. */
    public boolean isAvailable() {
        return available;
    }
}
