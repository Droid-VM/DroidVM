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

    /** What is still owed to a device something has just decided to sink. */
    public enum Owed {
        /** Hide it: the host still has the device, whatever this map remembers. */
        HIDE,
        /** Hidden already and by nothing this run recorded, so {@link #reconcile} adopts it. */
        ADOPT,
        /**
         * Hidden already, by this run, as this very instance: the sink is done and writes
         * nothing.
         */
        DONE,
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
     * Forgets every record. The master switch going off gives back everything this daemon hid,
     * so there is nothing left for a record to be the provenance of -- and a device a write
     * could not reach is better read off the host's own {@code authorized} flag, which is what
     * the next pass does anyway.
     */
    public void clear() {
        records.clear();
    }

    /**
     * What is owed to [sysfs], which something has just decided to sink. [authorized] is what
     * the host says about the device this moment, and it has to be exactly that: read now, from
     * the device, never carried in from a scan. [devnum] names the instance being sunk, from
     * that same read.
     *
     * <p>Writing {@code authorized} creates and removes no {@code /dev/bus/usb} node, so nothing
     * tells an inotify watch on that tree to look again and a cached flag stays whatever the
     * last plug event left there. A pass that read the flag from such a cache skipped the sink
     * of a device this daemon had itself un-sunk -- rule deleted, device given back and the
     * record dropped with it, then the same rule set again -- because the cache said hidden and
     * the missing record said not ours, and both halves were wrong at once. A record says who
     * hid a device; only the host says whether it is hidden.</p>
     */
    @NonNull
    public Owed owedBySink(@NonNull String sysfs, int devnum, boolean authorized) {
        // Authorized and recorded at once means the record is about an instance that is gone, or
        // about a write that never landed. Either way the host still has a device to hide.
        if (authorized) return Owed.HIDE;
        var record = records.get(sysfs);
        // Hidden by nobody this run recorded: the reconcile adopts it, and announces nothing
        // about something that happened before this daemon was there to announce it.
        if (record == null) return Owed.ADOPT;
        // Hidden and ours, as this very instance: the sink is idempotent per instance and writes
        // nothing -- which is what lets a pass walk over what the fast lane already did. A record
        // about another devnum is the provenance of a unit that has left the socket, so it says
        // nothing about the device hidden there now; that one this run claims.
        return record.devnum == devnum ? Owed.DONE : Owed.HIDE;
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
