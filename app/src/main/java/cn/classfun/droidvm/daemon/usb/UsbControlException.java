// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import androidx.annotation.NonNull;

/**
 * The crosvm usb CLI answered with a refusal rather than {@code ok <port>}.
 *
 * <p>Checked, because every refusal is a normal outcome the caller has to decide about: which one
 * it was matters -- {@code no_such_port} on a detach means the VMM already dropped the device,
 * while {@code no_available_port} on an attach means the guest's controller is full.</p>
 */
public final class UsbControlException extends Exception {
    /** The single word crosvm printed, e.g. {@code no_available_port}. */
    public final String token;
    /** Whatever the child wrote to stderr, or {@code ""}; empty for the pure parsers. */
    public final String stderr;

    public UsbControlException(@NonNull String token) {
        this(token, "");
    }

    public UsbControlException(@NonNull String token, @NonNull String stderr) {
        super(token);
        this.token = token;
        this.stderr = stderr;
    }
}
