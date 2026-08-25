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
 * Everything hangs off one per-VM root, {@link #channelKey}: the display service of a screen is
 * that root plus the screen's id, and the VM's input sockets are that root plus the channel kind.
 * The root is a pure function of the VM's UUID, which is why a crosvm that died and restarted
 * re-registers under exactly the name the app is still waiting on.
 *
 * A display service is per screen, not per VM: two screens exporting natively at once would
 * otherwise want one servicemanager name for two Surfaces, and a service holds two slots (main +
 * cursor), not N. The input sockets stay per VM because the input devices are: the AIDL's
 * writeInput(vmId, channel, data) has no screen field, and giving each screen its own absolute
 * devices is a separate step with its own guest-side cost (a manual touch-to-output mapping in
 * every guest OS).
 */
public final class NativeDisplay {
    /**
     * Input channels. Each maps to one crosvm {@code --input <kind>} virtio-input device and one
     * unix socket; the daemon binds one listener per channel by looping {@link #CHANNEL_COUNT}, so
     * the guest sees all of them and the UI routes to whichever the current {@code InputMode}
     * selects (multi-touch, relative mouse, or absolute single-touch tablet). Ordinals are the wire
     * channel ids shared with the daemon's InputHandler; append new channels, never renumber.
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
     * The VM's display-channel root: the vmKey its input sockets are named after, and the stem
     * every per-screen service name is built on. Stable across boots because the VM's UUID is.
     */
    @NonNull
    public static String channelKey(@NonNull VMConfig config) {
        return channelKeyFromId(config.getId().toString());
    }

    /** Same as {@link #channelKey(VMConfig)} but from a raw VM id (e.g. an Intent extra). */
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
     * The socket path crosvm connects to for [vmKey]'s [channel], where vmKey is the VM's
     * {@link #channelKey}. Must match across all callers -- the daemon binds the inode and
     * crosvm's {@code --input ...[path=]} connects to it, so a disagreement is a VM that will
     * not start.
     */
    @NonNull
    public static String inputSocketPath(@NonNull String vmKey, int channel) {
        return pathJoin(RUN_PATH, fmt("%s_input_%s.sock", sanitize(vmKey), KINDS[channel]));
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
