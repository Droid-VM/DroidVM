// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.Locale;

import cn.classfun.droidvm.R;

/**
 * The four layers of the automatic attach rules, in the order the daemon tries them (plan
 * section 2.4). Each knows its wire key and which fields a rule in it carries. "Keep on host"
 * (a null vm) is a legal target everywhere but the last layer: that one catches whatever the
 * others left, so a null there would be a rule that matches every device and does nothing.
 */
public enum UsbRuleLayer {
    EXACT("exact", R.string.usb_rules_layer_exact, true, true),
    PORT("port", R.string.usb_rules_layer_port, false, true),
    DEVICE("device", R.string.usb_rules_layer_device, true, false),
    ANY("any", R.string.usb_rules_layer_any, false, false);

    /** Key of this layer's list inside the rules object. */
    public final String key;
    @StringRes
    public final int titleRes;
    /** Whether a rule here names a device by its {@code vid:pid[:serial]} id. */
    public final boolean hasId;
    /** Whether a rule here names a physical port path. */
    public final boolean hasPort;

    UsbRuleLayer(@NonNull String key, @StringRes int titleRes, boolean hasId, boolean hasPort) {
        this.key = key;
        this.titleRes = titleRes;
        this.hasId = hasId;
        this.hasPort = hasPort;
    }

    /** 1-based position: the layer number the dry run reports and the page shows. */
    public int number() {
        return ordinal() + 1;
    }

    /** Whether a rule may name no VM at all and keep the device on the host. */
    public boolean allowsHost() {
        return this != ANY;
    }

    /** Whether a rule here needs a device or port picked before a target. */
    public boolean needsSubject() {
        return hasId || hasPort;
    }

    @Nullable
    public static UsbRuleLayer fromKey(@Nullable String key) {
        if (key == null) return null;
        for (var layer : values())
            if (layer.key.equalsIgnoreCase(key)) return layer;
        return null;
    }

    /**
     * The layer a daemon result names, whichever way it does: by wire key, or by 1-based
     * number as a number or a numeric string.
     */
    @Nullable
    public static UsbRuleLayer fromValue(@Nullable Object value) {
        if (value == null) return null;
        if (value instanceof Number) return fromNumber(((Number) value).intValue());
        var text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        var byKey = fromKey(text);
        if (byKey != null) return byKey;
        try {
            return fromNumber(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static UsbRuleLayer fromNumber(int number) {
        var all = values();
        if (number < 1 || number > all.length) return null;
        return all[number - 1];
    }
}
