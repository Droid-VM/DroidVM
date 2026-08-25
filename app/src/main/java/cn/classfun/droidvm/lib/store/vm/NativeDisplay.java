// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import androidx.annotation.NonNull;

/**
 * Shared naming for the native (crosvm android-display) backend. The daemon (launching crosvm and
 * hosting the native-display binder) and the UI (looking up the display binder / sending input)
 * both derive the service names and socket paths from here, so they agree and different VMs never
 * collide.
 *
 * Everything hangs off one per-VM root, {@link #channelKeyFromId}: the display service of a
 * screen is that root plus the screen's id, and the VM-wide input sockets are that root plus the
 * channel kind. The root is a pure function of the VM's UUID, which is why a crosvm that died and
 * restarted re-registers under exactly the name the app is still waiting on.
 *
 * A display service is per screen, not per VM: two screens exporting natively at once would
 * otherwise want one servicemanager name for two Surfaces, and a service holds two slots (main +
 * cursor), not N.
 *
 * The input sockets split the same way, but only half of them: the keyboard and the relative
 * pointer have no output binding at all -- the guest compositor routes them by focus -- so they
 * stay on the VM root, while the multi-touch and absolute-pointer devices are per screen, because
 * an absolute coordinate only means anything under one output's geometry. A per-screen device's
 * socket is keyed by that screen's display-service name rather than by a second, parallel string:
 * one identity per screen, so the name the guest maps by and the inode crosvm opens cannot drift
 * apart across a reboot.
 */
public final class NativeDisplay {
    /**
     * Input channels. Each maps to one crosvm {@code --input <kind>} virtio-input device and one
     * unix socket, and the UI routes to whichever the current {@code InputMode} selects
     * (multi-touch, relative mouse, or absolute single-touch tablet). Ordinals are the wire
     * channel ids shared with the daemon's InputHandler; append new channels, never renumber.
     *
     * <p>A channel is not by itself a device any more: {@link #isPerScreen} says whether the VM
     * has one of them or one per screen, so the daemon binds a socket per (screen, channel) pair
     * that exists rather than {@link #CHANNEL_COUNT} of them.</p>
     */
    public static final int MULTITOUCH = 0;
    public static final int KEYBOARD = 1;
    public static final int MOUSE = 2;
    public static final int TABLET = 3;
    public static final int CHANNEL_COUNT = 4;

    /**
     * Broadcast the daemon (running as root) sends to hand its INativeDisplayRootService binder to
     * the UI. A live IBinder can't cross the daemon's TCP/JSON-RPC channel, so it travels through
     * system_server as a broadcast extra instead (the same path libsu/Shizuku use), sidestepping the
     * servicemanager-find restriction an untrusted_app hits. The binder + nonce are nested in a
     * Bundle under {@link #EXTRA_BUNDLE} so AMS doesn't strip the binder from the top-level extras.
     */
    public static final String BINDER_BROADCAST_ACTION =
        "cn.classfun.droidvm.action.NATIVE_DISPLAY_BINDER";
    public static final String EXTRA_BUNDLE = "extra.bundle";
    public static final String EXTRA_BINDER = "binder";
    /** Per-attach random token; the UI only accepts a broadcast carrying the nonce it requested. */
    public static final String EXTRA_NONCE = "nonce";

    // Socket filename tags per channel (index == channel constant). "mouse"/"tablet" are our tags;
    // the crosvm device kinds they pair with are "mouse" (relative) and "single-touch" (absolute).
    private static final String[] KINDS = {"multitouch", "keyboard", "mouse", "tablet"};
    private static final String RUN_PATH = pathJoin(DATA_DIR, "run");

    /** Prefix every name built here starts with; {@link #vmIdFromServiceName} reads it back. */
    private static final String NAME_PREFIX = "droidvm_disp_";

    private NativeDisplay() {
    }

    /**
     * The VM's display-channel root: the key the VM-wide input sockets are named after, and the
     * stem every per-screen service name is built on. Stable across boots because the VM's UUID
     * is. Taken as a raw id rather than a config because half its callers only have the id, out
     * of an Intent extra or a socket name being read back.
     */
    @NonNull
    public static String channelKeyFromId(@NonNull String vmId) {
        return fmt("%s%s", NAME_PREFIX, sanitize(vmId));
    }

    /**
     * The servicemanager name crosvm registers for one screen's native display -- the value of
     * that screen's {@code --android-display-service name=}, and what the UI looks up.
     *
     * The screen id rides in the name rather than in a separate field because the name is the
     * whole identity of a channel on both sides of the binder, and because it has to survive a
     * crosvm restart unchanged: it is derived, never allocated. The charset stays the one
     * {@link #sanitize} allows, so the name is legal both as a binder service name and inside a
     * socket filename.
     */
    @NonNull
    public static String serviceName(@NonNull VMConfig config, @NonNull String screenId) {
        return serviceNameFromId(config.getId().toString(), screenId);
    }

    /** Same as {@link #serviceName(VMConfig, String)} but from a raw VM id. */
    @NonNull
    public static String serviceNameFromId(@NonNull String vmId, @NonNull String screenId) {
        return fmt("%s_%s", channelKeyFromId(vmId), sanitize(screenId));
    }

    /**
     * The VM id a name built here belongs to, or an empty string when the name is not ours.
     *
     * The daemon uses this to decide whether a display binder is worth waiting for, so it has to
     * be the exact inverse of the two builders above -- which is why it lives next to them
     * instead of being a prefix-strip written out again at the call site.
     */
    @NonNull
    public static String vmIdFromServiceName(@NonNull String serviceName) {
        if (!serviceName.startsWith(NAME_PREFIX)) return "";
        var rest = serviceName.substring(NAME_PREFIX.length());
        for (var screenId : VMScreenConfig.IDS) {
            var suffix = fmt("_%s", sanitize(screenId));
            if (rest.endsWith(suffix))
                return rest.substring(0, rest.length() - suffix.length());
        }
        return rest;
    }

    /**
     * The socket path crosvm connects to for [key]'s [channel], where key is what
     * {@link #inputSocketKey} returns for that channel. Must match across all callers -- the
     * daemon binds the inode and crosvm's {@code --input ...[path=]} connects to it, so a
     * disagreement is a VM that will not start.
     */
    @NonNull
    public static String inputSocketPath(@NonNull String key, int channel) {
        return pathJoin(RUN_PATH, fmt("%s_input_%s.sock", sanitize(key), KINDS[channel]));
    }

    /**
     * Whether [channel]'s device belongs to one screen rather than to the whole VM.
     *
     * <p>The two absolute devices are: their coordinates are read against one output's geometry,
     * so a VM with two screens needs two of each and the guest has to be told by hand which is
     * which. The keyboard and the relative pointer are not: they have no output binding, and the
     * guest compositor sends them wherever focus is.</p>
     */
    public static boolean isPerScreen(int channel) {
        return channel == MULTITOUCH || channel == TABLET;
    }

    /**
     * The {@link #inputSocketPath} key for [channel] on [screenId]: the VM root for the devices
     * the whole VM shares, that screen's display-service name for the two absolute ones.
     *
     * <p>Reusing the service name rather than minting a second per-screen string is the point:
     * the screen has one identity, and the socket, the binder name and the evdev name the guest
     * maps by all ride it, so none of them can survive a rename the others did not.</p>
     */
    @NonNull
    public static String inputSocketKey(@NonNull String vmId, @NonNull String screenId,
                                        int channel) {
        return isPerScreen(channel) ? serviceNameFromId(vmId, screenId) : channelKeyFromId(vmId);
    }

    /**
     * The socket path for [channel] on [screenId] of [vmId]. [screenId] is ignored for the
     * VM-wide channels, so passing the empty string for them is exact rather than a placeholder.
     *
     * <p>The daemon binds these and crosvm's {@code --input ...[path=]} connects to them, and the
     * two sides agree because they call this one function rather than each composing the key and
     * the path themselves.</p>
     */
    @NonNull
    public static String inputSocketPath(@NonNull String vmId, @NonNull String screenId,
                                         int channel) {
        return inputSocketPath(inputSocketKey(vmId, screenId, channel), channel);
    }

    /**
     * The evdev name crosvm gives [screenId]'s multi-touch device -- what the guest sees as the
     * touchscreen's name, and the whole of its identity there.
     *
     * <p>Neither evdev nor HID has a field for "I belong to output N", so every guest OS maps a
     * touchscreen to an output by the device's <em>name</em>: kwin stores it by name,
     * {@code xinput map-to-output} takes it by name, Windows' Tablet PC setup remembers the one
     * it was pointed at. That makes the name the only lever there is, and it has to be a pure
     * function of the screen and never change -- rename it and the user's mapping silently stops
     * matching anything, with no error to notice.</p>
     *
     * <p>Its absolute-pointer sibling is named by {@link #tabletDeviceName} for the same reason
     * and on the same terms.</p>
     */
    @NonNull
    public static String touchDeviceName(@NonNull String screenId) {
        return fmt("DroidVM Touch (%s)", screenId);
    }

    /**
     * The evdev name crosvm gives [screenId]'s absolute-pointer (tablet) device.
     *
     * <p>Everything {@link #touchDeviceName} says applies here unchanged -- an absolute pointer is
     * as much a per-output device as a touchscreen, and the guest maps it to an output by name in
     * exactly the same places. It used to have no name at all, because crosvm's
     * {@code absolute-mouse} option had no {@code name} field and its option enum rejects unknown
     * keys, so the device fell back to crosvm's generated "Crosvm Virtio Absolute Mouse &lt;idx&gt;"
     * -- an index that counts emission order and therefore moves when another screen's input is
     * switched off, which is the one thing a mapping key must never do. crosvm takes the field
     * now, so the tablet is pinnable on the same terms as the touchscreen.</p>
     */
    @NonNull
    public static String tabletDeviceName(@NonNull String screenId) {
        return fmt("DroidVM Tablet (%s)", screenId);
    }

    /** Keep socket/service names to a filesystem- and binder-safe charset. */
    @NonNull
    public static String sanitize(@NonNull String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append((Character.isLetterOrDigit(c) || c == '_' || c == '-') ? c : '_');
        }
        return sb.toString();
    }
}
