// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;

/**
 * The USB classes a class rule can name, as two hex digits the way bDeviceClass and
 * bInterfaceClass give them, and what they are called. A class rule speaks for every device with
 * an interface of its class (or of the class as a whole): HID, the keyboards and mice, is the one
 * the console offers; the rules page offers the rest too.
 */
public final class UsbClasses {
    /** HID: keyboards, mice, game controllers. */
    public static final String HID = "03";

    private static final String[] CODES = {
        HID, "08", "01", "0e", "02", "0a", "07", "06", "0b", "e0", "ff",
    };
    @StringRes
    private static final int[] NAMES = {
        R.string.usb_class_hid, R.string.usb_class_storage, R.string.usb_class_audio,
        R.string.usb_class_video, R.string.usb_class_comm, R.string.usb_class_cdc_data,
        R.string.usb_class_printer, R.string.usb_class_image, R.string.usb_class_smart_card,
        R.string.usb_class_wireless, R.string.usb_class_vendor,
    };

    private UsbClasses() {
    }

    /** "HID (03)", or the bare code for a class this has no name for. */
    @NonNull
    public static String label(@NonNull Context context, @Nullable String code) {
        if (code == null || code.isEmpty()) return context.getString(R.string.usb_rules_value_absent);
        var lower = code.toLowerCase(Locale.ROOT);
        for (int i = 0; i < CODES.length; i++)
            if (CODES[i].equals(lower))
                return context.getString(R.string.usb_class_fmt, context.getString(NAMES[i]), lower);
        return context.getString(R.string.usb_class_unnamed_fmt, lower);
    }

    /** Asks which class, [current] checked; [onPicked] hears the code. */
    public static void pick(@NonNull Context context, @Nullable String current,
                            @NonNull Consumer<String> onPicked) {
        var labels = new String[CODES.length];
        int checked = -1;
        for (int i = 0; i < CODES.length; i++) {
            labels[i] = label(context, CODES[i]);
            if (CODES[i].equalsIgnoreCase(current == null ? "" : current)) checked = i;
        }
        new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.usb_rules_layer_class)
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                dialog.dismiss();
                onPicked.accept(CODES[which]);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
