// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The devices this daemon deauthorized, why, and what is owed to one the host shows
 * deauthorized. Pure -- no sysfs, no android -- and every call is made under the manager's lock,
 * the way {@link UsbLeftovers} is.
 *
 * <p>A sink is stateful in a way an attachment is not: an attachment is self-evident, a record
 * plus a guest port, while a deauthorized device looks to a later pass exactly like an idle one.
 * What the record adds is provenance -- which rule did it, to which instance of the device, and
 * whether the user asked directly -- and that is all it adds: {@link #reconcile} is driven by
 * the scan, so a device left behind by a previous daemon run, or by somebody's shell, is decided
 * by the same rules as one this run sank.</p>
 *
 * <p>Nothing here is persisted, which is why the reconcile has to be able to say what to do
 * about a device it has never seen: at daemon start the map is empty and the host may well have
 * deauthorized devices on it.</p>
 */
public final class UsbSinks {
    /** One deauthorized device. */
    public static final class Record {
        /** The layer of the rule that asked for it; null when the user asked directly. */
        @Nullable
        public final UsbRules.Layer layer;
        /** Its index in that layer, or -1 for a manual sink. */
        public final int index;
        /** The management page asked for this one, so no rule pass may give it back. */
        public final boolean manual;
        /**
         * The instance it was done to. {@code authorized} resets to 1 on re-enumeration, so a
         * device replugged into the same socket is authorized again and is not this record's.
         */
        public final int devnum;

        Record(@Nullable UsbRules.Layer layer, int index, boolean manual, int devnum) {
            this.layer = layer;
            this.index = index;
            this.manual = manual;
            this.devnum = devnum;
        }
    }

    /** What is owed to a device the host shows deauthorized. */
    public enum Reconcile {
        /** Nothing: it is spoken for, or it is already recorded as this daemon's doing. */
        LEAVE,
        /** The rules do sink it and no record says so yet: write nothing, remember it. */
        ADOPT,
        /** Nothing sinks it any more: authorize it back. */
        RESTORE,
    }

    private final Map<String, Record> records = new HashMap<>();

    /** Records that [sysfs] is sinked, [by] being the rule that asked or null when the user did. */
    public void put(@NonNull String sysfs, @Nullable UsbRuleEngine.Decision by, boolean manual,
                    int devnum) {
        records.put(sysfs, new Record(by == null ? null : by.layer, by == null ? -1 : by.index,
            manual, devnum));
    }

    @Nullable
    public Record get(@NonNull String sysfs) {
        return records.get(sysfs);
    }

    public boolean has(@NonNull String sysfs) {
        return records.containsKey(sysfs);
    }

    /** Forgets [sysfs]; called on an unplug and after the device is authorized again. */
    public void remove(@NonNull String sysfs) {
        records.remove(sysfs);
    }

    /**
     * What is owed to [sysfs], which a scan just reported deauthorized. [rulesSink] is whether
     * the rules decide a sink for it right now.
     *
     * <p>The user's word outranks the rules in both directions: a pin to sink, or a record the
     * management page made, is left alone, and a pin to host is given back even when a rule
     * would sink the device -- a pin lasts until the device is unplugged, and that is what makes
     * it worth anything.</p>
     */
    @NonNull
    public Reconcile reconcile(@NonNull String sysfs, boolean attached,
                               @NonNull UsbRuleEngine.Pin pin, boolean rulesSink) {
        // A deauthorized device has no interfaces for a VMM to claim, so this cannot happen; the
        // manager says so out loud, and the safe answer here is to touch nothing.
        if (attached) return Reconcile.LEAVE;
        var record = records.get(sysfs);
        if (record != null && record.manual) return Reconcile.LEAVE;
        if (pin == UsbRuleEngine.Pin.SINK) return Reconcile.LEAVE;
        if (pin == UsbRuleEngine.Pin.HOST) return Reconcile.RESTORE;
        if (!rulesSink) return Reconcile.RESTORE;
        return record == null ? Reconcile.ADOPT : Reconcile.LEAVE;
    }
}
