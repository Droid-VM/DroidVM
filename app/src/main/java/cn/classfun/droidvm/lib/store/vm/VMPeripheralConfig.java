// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.data.HostAudioDevices;
import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.enums.Enums;

/**
 * Wrapper over one entry of a VM config's "peripherals" array -- one entry, one guest device.
 *
 * <p>Host endpoints are stored as the stable descriptor from {@code HostAudioDevices}
 * ({@code "<TYPE>|<address>"}) rather than the numeric AudioDeviceInfo id, because those ids are
 * handed out per boot: the same number means a different endpoint, or none, after a reboot or a
 * pairing. The label alongside it is only so a row can still name a device that is currently
 * unplugged. Resolution to a live id happens in the daemon at VM start.</p>
 */
public final class VMPeripheralConfig {
    public final DataItem item;

    public VMPeripheralConfig(@NonNull DataItem item) {
        this.item = item;
    }

    @NonNull
    public PeripheralType getType() {
        return Enums.optEnum(item, "type", PeripheralType.VIRTIO_SOUND);
    }

    public void setType(@NonNull PeripheralType type) {
        item.set("type", type);
    }

    /**
     * Creates the sound card offered by default for a new VM: one playback and one capture
     * endpoint, both left to Android's current system routing.
     */
    @NonNull
    public static VMPeripheralConfig createDefaultVirtioSound() {
        var config = new VMPeripheralConfig(DataItem.newObject());
        config.setType(PeripheralType.VIRTIO_SOUND);
        var speaker = config.addEndpoint();
        speaker.setMode(SoundMode.SPEAKER);
        speaker.setHostDevice(HostAudioDevices.SYSTEM_DEFAULT_KEY, "");
        var microphone = config.addEndpoint();
        microphone.setMode(SoundMode.MICROPHONE);
        microphone.setHostDevice(HostAudioDevices.SYSTEM_DEFAULT_KEY, "");
        return config;
    }

    // ---- virtio-snd ----

    /**
     * One host endpoint on the card: a direction, and the host device it is pinned to.
     *
     * <p>A card can carry several. What is shared between them lives on the card -- the buffer
     * depth and what to do about an underrun are properties of the device's queues, not of any
     * one endpoint -- and what distinguishes them lives here.</p>
     */
    public static final class Endpoint {
        public final DataItem item;

        Endpoint(@NonNull DataItem item) {
            this.item = item;
        }

        @NonNull
        public SoundMode getMode() {
            return Enums.optEnum(item, "mode", SoundMode.SPEAKER);
        }

        public void setMode(@NonNull SoundMode mode) {
            item.set("mode", mode);
        }

        /** Stable host-device descriptor; see {@code HostAudioDevices.keyOf}. */
        @NonNull
        public String getHostDevice() {
            var v = item.opt("host_device", (DataItem) null);
            return v == null ? "" : v.asString();
        }

        @NonNull
        public String getHostLabel() {
            var v = item.opt("host_label", (DataItem) null);
            return v == null ? "" : v.asString();
        }

        public void setHostDevice(@NonNull String key, @NonNull String label) {
            item.set("host_device", key);
            item.set("host_label", label);
        }
    }

    /** The card's endpoints, in the order they are shown and numbered. */
    @NonNull
    public List<Endpoint> getEndpoints() {
        var out = new ArrayList<Endpoint>();
        var list = item.opt("endpoints", (DataItem) null);
        if (list == null) return out;
        for (int i = 0; i < list.size(); i++) {
            out.add(new Endpoint(list.opt(i, DataItem.newObject())));
        }
        return out;
    }

    /**
     * Appends an endpoint and returns it.
     *
     * <p>The list is re-read after it is created rather than kept from the local variable:
     * {@code set} stores a copy, so appending to the value that was handed to it would build a
     * list nothing else can see -- which is what silently swallowed the first endpoint of every
     * new card.</p>
     */
    @NonNull
    public Endpoint addEndpoint() {
        var list = item.opt("endpoints", (DataItem) null);
        if (list == null || !list.is(DataItem.Type.ARRAY)) {
            item.set("endpoints", DataItem.newArray());
            list = item.opt("endpoints", (DataItem) null);
        }
        var endpoint = DataItem.newObject();
        list.append(endpoint);
        return new Endpoint(endpoint);
    }

    public void removeEndpoint(int index) {
        var list = item.opt("endpoints", (DataItem) null);
        if (list == null || index < 0 || index >= list.size()) return;
        list.remove(index);
    }

    @NonNull
    public SoundBuffer getBuffer() {
        return Enums.optEnum(item, "buffer", SoundBuffer.NORMAL);
    }

    public void setBuffer(@NonNull SoundBuffer buffer) {
        item.set("buffer", buffer);
    }

    @NonNull
    public SoundUnderrun getUnderrun() {
        return Enums.optEnum(item, "underrun", SoundUnderrun.SILENCE);
    }

    public void setUnderrun(@NonNull SoundUnderrun underrun) {
        item.set("underrun", underrun);
    }

    // ---- host endpoints ----

    /**
     * Stable host-device descriptor for the single endpoint of a one-direction device, or ""
     * for "whatever the host would route to anyway".
     */
    @NonNull
    public String getHostDevice() {
        return str("host_device");
    }

    @NonNull
    public String getHostLabel() {
        return str("host_label");
    }

    public void setHostDevice(@NonNull String key, @NonNull String label) {
        item.set("host_device", key);
        item.set("host_label", label);
    }

    /** Output endpoint of a device that carries both directions (Intel HDA). */
    @NonNull
    public String getHostOutDevice() {
        return str("host_out_device");
    }

    @NonNull
    public String getHostOutLabel() {
        return str("host_out_label");
    }

    public void setHostOutDevice(@NonNull String key, @NonNull String label) {
        item.set("host_out_device", key);
        item.set("host_out_label", label);
    }

    /** Input endpoint of a device that carries both directions (Intel HDA). */
    @NonNull
    public String getHostInDevice() {
        return str("host_in_device");
    }

    @NonNull
    public String getHostInLabel() {
        return str("host_in_label");
    }

    public void setHostInDevice(@NonNull String key, @NonNull String label) {
        item.set("host_in_device", key);
        item.set("host_in_label", label);
    }

    @NonNull
    private String str(@NonNull String key) {
        var v = item.optString(key, "");
        return v == null ? "" : v;
    }

    /** Wraps every entry of {@code config}'s "peripherals" array, in order. */
    @NonNull
    public static List<VMPeripheralConfig> listOf(@NonNull DataItem config) {
        var out = new ArrayList<VMPeripheralConfig>();
        var arr = config.opt("peripherals", null);
        if (arr == null || !arr.is(DataItem.Type.ARRAY)) return out;
        for (var iter : arr)
            out.add(new VMPeripheralConfig(iter.getValue()));
        return out;
    }
}
