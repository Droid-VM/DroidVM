// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.data;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;

/**
 * Host audio endpoints, as Android reports them, in a form that survives a reboot.
 *
 * <p>{@link AudioDeviceInfo#getId()} is what AAudio wants ({@code AAudioStreamBuilder_setDeviceId},
 * which is how crosvm's virtio-snd pins a PCM device to one endpoint), but the ids are handed out
 * per boot and change when something is plugged or paired. So a VM config stores the stable
 * {@link #keyOf key} instead -- device type plus routing address -- and the backend
 * {@link #resolve resolves} it to a live id when the VM starts.</p>
 *
 * <p>Usable from the daemon as well as the UI: the lookup only needs a Context that can reach
 * AudioManager, which the daemon's system context can.</p>
 */
public final class HostAudioDevices {
    private static final String TAG = "HostAudioDevices";
    /** AAUDIO_UNSPECIFIED: let the platform route the stream itself. */
    public static final int DEVICE_UNSPECIFIED = 0;

    /**
     * The endpoint that means "whatever the platform would route to", named rather than left
     * blank.
     *
     * <p>An unset field said the same thing until now, and saying it by omission turned out to
     * be worse in every direction: the config held a device with nothing in it, the command line
     * carried an entry with no fields, and the option parser has no use for either. It also
     * cannot be told apart from a field nobody filled in.</p>
     *
     * <p>It is shaped like any other key so nothing has to special-case it -- and it appears in
     * the published device table against id 0, which is `AAUDIO_DEVICE_UNSPECIFIED`, so
     * resolving it produces the platform's own routing by the ordinary path rather than by an
     * exception to it. `DEFAULT` is not an AudioDeviceInfo type name, so a real endpoint can
     * never collide with it.</p>
     */
    public static final String SYSTEM_DEFAULT_KEY = "DEFAULT|system default";

    /** One live host endpoint. */
    public static final class Entry {
        /** Stable descriptor stored in the VM config; see {@link #keyOf}. */
        public final String key;
        /** Human-readable name for the picker. */
        public final String label;
        /** Live AudioDeviceInfo id, valid only for this boot. */
        public final int id;

        Entry(@NonNull String key, @NonNull String label, int id) {
            this.key = key;
            this.label = label;
            this.id = id;
        }
    }

    private HostAudioDevices() {
    }

    /**
     * Live output ({@code input == false}) or input endpoints, deduplicated by key. Empty when
     * AudioManager is unreachable -- callers fall back to "platform default" routing.
     */
    @NonNull
    public static List<Entry> list(@NonNull Context context, boolean input) {
        var out = new ArrayList<Entry>();
        var devices = query(context, input);
        if (devices == null) return out;
        for (var device : devices) {
            // A sink that can't be targeted is noise in the picker (telephony, the guest's own
            // loopback, ...); AAudio can only open real endpoints anyway.
            if (!isSelectable(device, input)) continue;
            var key = keyOf(device);
            boolean dup = false;
            for (var seen : out)
                if (seen.key.equals(key)) dup = true;
            if (dup) continue;
            out.add(new Entry(key, labelOf(context, device), device.getId()));
        }
        return out;
    }

    /**
     * The same endpoints as {@link #list}, as {@code id -> key} pairs and nothing else.
     *
     * <p>Separate from {@code list} because that one builds a label for the picker, and a label
     * needs the app's string resources. The daemon's context has none -- asking it for one throws
     * {@code Resources$NotFoundException} -- and it has no use for a label anyway: it is
     * publishing the endpoints for crosvm to match against, not showing them to anyone.</p>
     */
    @NonNull
    public static List<int[]> idsAndKeys(@NonNull Context context, boolean input,
                                         @NonNull List<String> keysOut) {
        var ids = new ArrayList<int[]>();
        var devices = query(context, input);
        if (devices == null) return ids;
        for (var device : devices) {
            if (!isSelectable(device, input)) continue;
            var key = keyOf(device);
            if (keysOut.contains(key)) continue;
            keysOut.add(key);
            ids.add(new int[] {
                device.getId(), soleValue(device.getSampleRates()),
                soleValue(device.getChannelCounts()), kindOf(device, input)
            });
        }
        return ids;
    }

    /**
     * The one value in a capability list, or 0 when there is more than one.
     *
     * <p>These lists say what an endpoint will accept, not what it runs at, and the two are only
     * the same thing when there is a single entry. Picking a favourite out of several would be a
     * guess, and a guessed hint is worse than none: the guest treats a hint as the format to
     * default to, and would then default to something the platform converts. An empty list means
     * the platform declined to say, which is the same answer.</p>
     */
    private static int soleValue(int[] values) {
        return values != null && values.length == 1 ? values[0] : 0;
    }

    /**
     * What kind of thing the endpoint is, in the numbering the guest driver uses: 1 speaker,
     * 2 headphones, 3 headset, 4 line out, 5 digital, 6 microphone, 7 telephony.
     *
     * <p>This is what decides the name and icon Windows shows beside the endpoint, so an
     * approximate answer is still much better than none.</p>
     */
    public static int kindOf(@NonNull AudioDeviceInfo device, boolean input) {
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_TELEPHONY:
                return 7;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return 2;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return 3;
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_AUX_LINE:
                return 4;
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return 5;
            default:
                // Everything left is a built-in transducer of one kind or the other, and which
                // one is decided by the direction rather than by the type.
                return input ? 6 : 1;
        }
    }

    /**
     * Live AudioDeviceInfo id for a stored key, or {@link #DEVICE_UNSPECIFIED} when the key is
     * empty (follow the platform) or names a device that is not currently present.
     */
    public static int resolve(@NonNull Context context, boolean input, @Nullable String key) {
        // "" is what older configs stored for the same thing.
        if (key == null || key.isEmpty() || SYSTEM_DEFAULT_KEY.equals(key)) {
            return DEVICE_UNSPECIFIED;
        }
        var devices = query(context, input);
        if (devices == null) return DEVICE_UNSPECIFIED;
        for (var device : devices)
            if (keyOf(device).equals(key)) return device.getId();
        Log.w(TAG, fmt("host audio device %s is not present; falling back to default routing", key));
        return DEVICE_UNSPECIFIED;
    }

    /**
     * Separates the endpoint's name from what the stream is for.
     *
     * <p>An address already contains most of the punctuation worth choosing: a Bluetooth address
     * is a MAC with colons, a USB one looks like {@code card=1;device=0}. So the separator has to
     * be something none of them use.</p>
     */
    public static final char ATTR_SEPARATOR = '#';

    /** The part of a stored key that names the endpoint, without what it is to be used for. */
    @NonNull
    public static String deviceOf(@NonNull String key) {
        int at = key.indexOf(ATTR_SEPARATOR);
        return at < 0 ? key : key.substring(0, at);
    }

    /** The {@code attr=value,...} part of a stored key, or "" when it carries none. */
    @NonNull
    public static String attrsOf(@NonNull String key) {
        int at = key.indexOf(ATTR_SEPARATOR);
        return at < 0 ? "" : key.substring(at + 1);
    }

    /** One attribute out of the {@code attr=value,...} part, or "" when it is not set. */
    @NonNull
    public static String attrOf(@NonNull String key, @NonNull String name) {
        for (var pair : attrsOf(key).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equals(name)) {
                return pair.substring(eq + 1).trim();
            }
        }
        return "";
    }

    /** Rebuilds a key from an endpoint and its attributes; empty attributes are left out. */
    @NonNull
    public static String withAttrs(@NonNull String device, @NonNull List<String> names,
                                   @NonNull List<String> values) {
        var parts = new ArrayList<String>();
        for (int i = 0; i < names.size() && i < values.size(); i++) {
            if (!values.get(i).isEmpty()) {
                parts.add(fmt("%s=%s", names.get(i), values.get(i)));
            }
        }
        return parts.isEmpty() ? device : device + ATTR_SEPARATOR + String.join(",", parts);
    }

    /**
     * Stable descriptor for one endpoint: {@code "<TYPE>|<address>"}. The type name (not its
     * numeric constant) keeps the config readable, and the address separates one paired headset
     * or USB card from another of the same type.
     */
    @NonNull
    public static String keyOf(@NonNull AudioDeviceInfo device) {
        var address = "";
        try {
            address = device.getAddress();
        } catch (Throwable ignored) {
            // getAddress() is @SystemApi-adjacent on some builds; the type alone still works
        }
        return fmt("%s|%s", typeName(device.getType()), address == null ? "" : address);
    }

    /** Localized type name, with the product name or address appended when it disambiguates. */
    @NonNull
    public static String labelOf(@NonNull Context context, @NonNull AudioDeviceInfo device) {
        var name = typeLabel(context, device.getType());
        var product = String.valueOf(device.getProductName()).trim();
        var address = "";
        try {
            address = device.getAddress();
        } catch (Throwable ignored) {
        }
        if (address != null && !address.isEmpty())
            return fmt("%s (%s)", name, address);
        if (!product.isEmpty() && !product.equalsIgnoreCase(name))
            return fmt("%s (%s)", name, product);
        return name;
    }

    @Nullable
    private static AudioDeviceInfo[] query(@NonNull Context context, boolean input) {
        try {
            var am = context.getSystemService(AudioManager.class);
            if (am == null) {
                Log.w(TAG, "AudioManager unavailable");
                return null;
            }
            return am.getDevices(input
                ? AudioManager.GET_DEVICES_INPUTS
                : AudioManager.GET_DEVICES_OUTPUTS);
        } catch (Throwable t) {
            Log.w(TAG, "failed to enumerate host audio devices", t);
            return null;
        }
    }

    /**
     * The echo reference: the output mix, fed back so a capture path can subtract it. Android
     * exposes it in the device list but only opens it for system callers, so an endpoint pinned
     * to it appears in the guest and never produces a sample -- measured: PREPARE comes back
     * VIRTIO_SND_S_IO_ERR, "Failed to open stream".
     *
     * <p>By number because there is no public constant for it -- the SDK's list goes 25, 26, 27,
     * 29 -- and a name that does not exist cannot be compiled against.</p>
     */
    private static final int TYPE_ECHO_REFERENCE = 28;

    /** Endpoints that make no sense as a VM's speaker or microphone. */
    private static boolean isSelectable(@NonNull AudioDeviceInfo device, boolean input) {
        switch (device.getType()) {
            case TYPE_ECHO_REFERENCE:
            case AudioDeviceInfo.TYPE_TELEPHONY:
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX:
            case AudioDeviceInfo.TYPE_FM:
            case AudioDeviceInfo.TYPE_FM_TUNER:
            case AudioDeviceInfo.TYPE_TV_TUNER:
                return false;
            default:
                return input ? device.isSource() : device.isSink();
        }
    }

    /** Stable, config-visible name for an AudioDeviceInfo type constant. */
    @NonNull
    private static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "BUILTIN_EARPIECE";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "BUILTIN_SPEAKER";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE: return "BUILTIN_SPEAKER_SAFE";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "WIRED_HEADSET";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "WIRED_HEADPHONES";
            case AudioDeviceInfo.TYPE_LINE_ANALOG: return "LINE_ANALOG";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL: return "LINE_DIGITAL";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "BLUETOOTH_SCO";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "BLUETOOTH_A2DP";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "BLE_HEADSET";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return "BLE_SPEAKER";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            case AudioDeviceInfo.TYPE_HDMI_ARC: return "HDMI_ARC";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB_DEVICE";
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: return "USB_ACCESSORY";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB_HEADSET";
            case AudioDeviceInfo.TYPE_DOCK: return "DOCK";
            case AudioDeviceInfo.TYPE_AUX_LINE: return "AUX_LINE";
            case AudioDeviceInfo.TYPE_IP: return "IP";
            case AudioDeviceInfo.TYPE_BUS: return "BUS";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "BUILTIN_MIC";
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX: return "REMOTE_SUBMIX";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "TELEPHONY";
            case AudioDeviceInfo.TYPE_FM: return "FM";
            case AudioDeviceInfo.TYPE_FM_TUNER: return "FM_TUNER";
            case AudioDeviceInfo.TYPE_TV_TUNER: return "TV_TUNER";
            default: return fmt("TYPE_%d", type);
        }
    }

    /** Localized name for the picker; unlisted types fall back to the stable name. */
    @NonNull
    private static String typeLabel(@NonNull Context context, int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return context.getString(R.string.audio_device_builtin_earpiece);
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE:
                return context.getString(R.string.audio_device_builtin_speaker);
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return context.getString(R.string.audio_device_wired_headset);
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return context.getString(R.string.audio_device_wired_headphones);
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return context.getString(R.string.audio_device_bluetooth_sco);
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return context.getString(R.string.audio_device_bluetooth);
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return context.getString(R.string.audio_device_usb);
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
                return context.getString(R.string.audio_device_hdmi);
            case AudioDeviceInfo.TYPE_DOCK:
            case AudioDeviceInfo.TYPE_AUX_LINE:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                return context.getString(R.string.audio_device_line);
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return context.getString(R.string.audio_device_builtin_mic);
            default:
                return typeName(type);
        }
    }
}
