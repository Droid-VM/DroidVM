// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import cn.classfun.droidvm.R;

/**
 * What a rule's card calls the device it is about, resolved against what is plugged in right
 * now. Display only: a rule matches on its id or its port, never on a name, so a device that
 * comes back under another name is the same rule.
 *
 * <p>Shared rather than written per call site because the three ways a name can be missing --
 * the rule is about no device in particular, the device is not plugged in, the descriptor
 * carries no name -- read as three different sentences, and two pages saying them differently
 * would be two answers to one question.</p>
 */
public final class UsbDeviceNames {
    private UsbDeviceNames() {
    }

    /** The card's title: the generation the device came up at, then what it is. */
    @NonNull
    public static String cardTitle(@NonNull Context context, @NonNull UsbRuleLayer layer,
                                   @Nullable String id, @Nullable String port,
                                   @NonNull List<UsbHostDeviceInfo> devices) {
        var device = deviceFor(layer, id, port, devices);
        return titled(context, device == null ? "" : device.speed,
            forRule(context, layer, id, port, devices));
    }

    /** The same title for a device in hand rather than for a rule about one. */
    @NonNull
    public static String cardTitle(@NonNull Context context, @NonNull UsbHostDeviceInfo device) {
        return titled(context, device.speed, nameOf(context, device));
    }

    /** The name to show for a rule about [id] / at [port]. */
    @NonNull
    public static String forRule(@NonNull Context context, @NonNull UsbRuleLayer layer,
                                 @Nullable String id, @Nullable String port,
                                 @NonNull List<UsbHostDeviceInfo> devices) {
        if (layer == UsbRuleLayer.ANY) return context.getString(R.string.usb_rules_any_device);
        var device = deviceFor(layer, id, port, devices);
        if (device == null) return context.getString(R.string.usb_rules_device_absent);
        return nameOf(context, device);
    }

    /** What a plugged-in device is called, said as "unknown" rather than as its id. */
    @NonNull
    private static String nameOf(@NonNull Context context, @NonNull UsbHostDeviceInfo device) {
        if (device.product.isEmpty() && device.manufacturer.isEmpty())
            return context.getString(R.string.usb_rules_device_unnamed);
        return device.displayName(context);
    }

    /** "USB 3.0 <name>", or the name alone at a rate this build has no generation for. */
    @NonNull
    private static String titled(@NonNull Context context, @NonNull String speed,
                                 @NonNull String name) {
        var generation = UsbSpeed.generationOf(speed);
        return generation.isEmpty()
            ? context.getString(R.string.usb_rules_card_title_plain_fmt, name)
            : context.getString(R.string.usb_rules_card_title_fmt, generation, name);
    }

    /**
     * The plugged-in device a rule speaks about, or null when none is.
     *
     * <p>A port rule is about whatever is in that port right now, so it is looked up by port and
     * its card is renamed by a replug; the other two layers name a device and are looked up by
     * id.</p>
     */
    @Nullable
    public static UsbHostDeviceInfo deviceFor(@NonNull UsbRuleLayer layer, @Nullable String id,
                                              @Nullable String port,
                                              @NonNull List<UsbHostDeviceInfo> devices) {
        if (layer == UsbRuleLayer.PORT) {
            if (port == null || port.isEmpty()) return null;
            for (var device : devices)
                if (device.port.equals(port)) return device;
            return null;
        }
        if (id == null || id.isEmpty()) return null;
        for (var device : devices)
            if (device.id.equals(id)) return device;
        return null;
    }
}
