// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import cn.classfun.droidvm.R;

import cn.classfun.droidvm.lib.store.enums.StringEnum;

/**
 * Kind of serial port hardware the guest sees -- one entry of a VM's "serial_ports" array names
 * one of these.
 *
 * <p>The four PC-style 16550 COM ports are special: crosvm creates all four unconditionally
 * (unconfigured ones are sinks), so they exist as fixed rows that can only change backend, and
 * {@link #isAddable()} is false. The other kinds are standalone devices the user adds.</p>
 */
public enum SerialHardware implements StringEnum {
    /** PC-style 8250/16550 COM port. Always four of them (num 1-4); fixed, not addable. */
    SERIAL(R.string.edit_vm_serial_hw_serial, R.drawable.ic_serial_port, false, 4),
    /**
     * ARM SBSA UART (PL011 subset). The one serial device Windows-on-ARM has an in-box driver
     * for (SerPL011.sys); crosvm wires a single instance.
     */
    SBSA(R.string.edit_vm_serial_hw_sbsa, R.drawable.ic_serial_port, true, 1),
    /** virtio-console port; needs a virtio driver in the guest (hvcN on Linux). */
    VIRTIO_CONSOLE(R.string.edit_vm_serial_hw_virtio_console, R.drawable.ic_serial_port, true, 4);

    private final @StringRes int titleId;
    private final @DrawableRes int iconId;
    private final boolean addable;
    private final int maxPorts;

    SerialHardware(@StringRes int titleId, @DrawableRes int iconId, boolean addable, int maxPorts) {
        this.titleId = titleId;
        this.iconId = iconId;
        this.addable = addable;
        this.maxPorts = maxPorts;
    }

    @Override
    public int getStringId() {
        return titleId;
    }

    @DrawableRes
    public int getIconId() {
        return iconId;
    }

    /** False for the fixed 16550 quartet, which exists whether configured or not. */
    public boolean isAddable() {
        return addable;
    }

    /** Highest port number (1-based) the backend wires for this hardware. */
    public int getMaxPorts() {
        return maxPorts;
    }

    /** The value crosvm's {@code --serial hardware=} option expects. */
    public String getCrosvmName() {
        switch (this) {
            case SBSA: return "sbsa";
            case VIRTIO_CONSOLE: return "virtio-console";
            case SERIAL:
            default: return "serial";
        }
    }
}
