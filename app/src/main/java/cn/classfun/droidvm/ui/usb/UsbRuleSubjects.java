// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.usb;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import cn.classfun.droidvm.R;

/**
 * What a rule in a given layer can be about, offered from what is plugged in right now.
 *
 * <p>Shared by the two places that add a rule -- the global rules page and the xHCI card in the
 * VM editor -- so the labels and the folding read the same in both. A device offers only the
 * part its layer stores: picking a device for a port rule stores the port and nothing else, and
 * the entry says so by naming the port first.</p>
 */
public final class UsbRuleSubjects {
    /** A row of the picker: what it says, and the id and port it stands for ("" for neither). */
    public static final class Subject {
        public final String label;
        public final String id;
        public final String port;

        Subject(@NonNull String label, @NonNull String id, @NonNull String port) {
            this.label = label;
            this.id = id;
            this.port = port;
        }
    }

    private UsbRuleSubjects() {
    }

    /**
     * One row per plugged-in device for the exact and device layers (the device layer folds two
     * identical serial-less devices into one, as the rule would), one row per occupied port for
     * the port layer, and nothing at all for the catch-all layer, which is about no device in
     * particular. Hubs never arrive: the daemon leaves them out of the list.
     */
    @NonNull
    public static List<Subject> of(@NonNull Context context, @NonNull UsbRuleLayer layer,
                                   @NonNull List<UsbHostDeviceInfo> devices) {
        var out = new LinkedHashMap<String, Subject>();
        for (var device : devices) {
            var name = device.displayName(context);
            switch (layer) {
                case EXACT:
                    out.putIfAbsent(fmt("%s@%s", device.id, device.port), new Subject(
                        context.getString(R.string.usb_rules_pick_device_exact_fmt,
                            name, device.id, device.port),
                        device.id, device.port));
                    break;
                case PORT:
                    out.putIfAbsent(device.port, new Subject(
                        context.getString(R.string.usb_rules_pick_device_port_fmt,
                            device.port, name),
                        "", device.port));
                    break;
                case DEVICE:
                    out.putIfAbsent(device.id, new Subject(
                        context.getString(R.string.usb_rules_pick_device_device_fmt,
                            name, device.id),
                        device.id, ""));
                    break;
                default:
                    break;
            }
        }
        return new ArrayList<>(out.values());
    }

    /** The labels of {@code subjects}, ready for a dialog's item list. */
    @NonNull
    public static String[] labelsOf(@NonNull List<Subject> subjects) {
        var labels = new String[subjects.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = subjects.get(i).label;
        return labels;
    }
}
