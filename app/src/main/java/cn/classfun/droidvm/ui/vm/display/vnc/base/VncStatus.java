// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.base;

/** Where a VNC connection is: getting there, there, or given up on. */
public enum VncStatus {
    CONNECTING,
    CONNECTED,
    ERROR
}
