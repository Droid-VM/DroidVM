// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;

/**
 * Shared naming for the native (crosvm android-display) backend. The daemon (launching crosvm and
 * hosting the native-display binder) and the UI (looking up the display binder / sending input)
 * both derive the service names and socket paths from here, so they agree and different VMs never
 * collide.
 *
 * The names the outside world sees hang off one per-VM root, {@link #channelKeyFromId}: a screen's
 * display service is that root plus the screen's id. The root is a pure function of the VM's UUID,
 * which is why a crosvm that died and restarted re-registers under exactly the name the app is
 * still waiting on.
 *
 * A display service is per screen, not per VM: two screens exporting natively at once would
 * otherwise want one servicemanager name for two Surfaces, and a service holds two slots (main +
 * cursor), not N.
 *
 * The input sockets split the same way, but only half of them: the keyboard and the relative
 * pointer have no output binding at all -- the guest compositor routes them by focus -- so they
 * stay on the VM root, while the multi-touch and absolute-pointer devices are per screen, because
 * an absolute coordinate only means anything under one output's geometry.
 *
 * The socket <em>filenames</em> are the one set of names here that is not identity-bearing, and
 * they are deliberately terse. A unix socket address holds 107 bytes of path plus a NUL, and this
 * app's run directory already spends 35 of them ({@code /data/data/cn.classfun.droidvm/run/}); the
 * old {@code droidvm_disp_<uuid>_input_multitouch.sock} came to 106 -- one byte of headroom -- so
 * the moment the screen id joined it the per-screen names hit 115 and 111 and crosvm refused the
 * whole command line with "path must be shorter than SUN_LEN". Nothing outside this process pair
 * ever reads these names: the daemon binds the inode and crosvm is handed the path on the command
 * line it is started with, so unlike the service name and the evdev names, which the guest and the
 * user's saved mappings key on across reboots, a socket filename can be abbreviated freely.
 * {@link #inputSocketPath} therefore builds {@code dvmin_<uuid>[_<screen>]_<channel>.sock} out of
 * two-to-three-letter tags, worst case 90 bytes, and {@link #requireBindablePath} makes the
 * remaining margin a wall rather than a hope -- see its note on what bind(2) does otherwise.
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

    // Socket filename tags per channel (index == channel constant). "ms"/"tab" are our tags; the
    // crosvm device kinds they pair with are "mouse" (relative) and "absolute-mouse". Short
    // because the whole path has to fit sun_path (see the class note); still self-describing,
    // because the next person reading `ls run/` deserves to know which inode is the touchscreen.
    private static final String[] CHANNEL_TAGS = {"mt", "kbd", "ms", "tab"};

    /**
     * Socket filename tag per screen, positionally paired with {@link VMScreenConfig#IDS}. A
     * screen id is a word ("simplefb"); a socket name has a couple of bytes to spend on it. The
     * static check below is the wall a third screen walks into: adding an id without adding its
     * tag fails at class load rather than minting a name that overflows or collides.
     */
    private static final String[] SCREEN_TAGS = {"g0", "sfb"};

    static {
        if (SCREEN_TAGS.length != VMScreenConfig.IDS.length)
            throw new IllegalStateException("every screen id needs an input-socket tag");
    }

    private static final String RUN_PATH = pathJoin(DATA_DIR, "run");

    /** Prefix every name built here starts with; {@link #vmIdFromServiceName} reads it back. */
    private static final String NAME_PREFIX = "droidvm_disp_";

    /** Prefix of the input socket filenames; short, and not a name anything looks up by. */
    private static final String SOCKET_PREFIX = "dvmin_";

    /**
     * Bytes of {@code sockaddr_un.sun_path} on Linux -- 108, of which the last must be the NUL,
     * so 107 is the longest bindable path. Not a tunable: it is the kernel's array size.
     */
    public static final int SUN_PATH_SIZE = 108;

    /** The longest path {@link #requireBindablePath} will pass: {@link #SUN_PATH_SIZE} less NUL. */
    public static final int MAX_UNIX_PATH = SUN_PATH_SIZE - 1;

    private NativeDisplay() {
    }

    /**
     * The VM's display-channel root: the stem every per-screen service name is built on, and the
     * name a pre-screens build registered on its own. Stable across boots because the VM's UUID
     * is. Taken as a raw id rather than a config because half its callers only have the id, out
     * of an Intent extra or a service name being read back.
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
     * The socket filename tag for [screenId], or the sanitized id itself if the screen is one
     * {@link #SCREEN_TAGS} has never heard of -- a long name the length check will catch, which is
     * the failure worth having over a short one that might collide with another screen's tag.
     */
    @NonNull
    private static String screenTag(@NonNull String screenId) {
        for (int i = 0; i < VMScreenConfig.IDS.length; i++)
            if (VMScreenConfig.IDS[i].equals(screenId)) return SCREEN_TAGS[i];
        return sanitize(screenId);
    }

    /**
     * The socket path for [channel] on [screenId] of [vmId]: {@code dvmin_<uuid>_<sc>_<ch>.sock},
     * with the screen tag left out entirely for the VM-wide channels -- so passing the empty
     * screen id for them is exact rather than a placeholder, and no screen a console could name
     * reaches the keyboard's or the relative pointer's inode.
     *
     * <p>The daemon binds these and crosvm's {@code --input ...[path=]} connects to them, and the
     * two sides agree because they call this one function rather than each composing the name and
     * the directory themselves.</p>
     *
     * <p>Terse on purpose, and safe to be terse: unlike the service name and the evdev names, this
     * string is born and dies inside one VM start -- see the class note for what the long form
     * cost. Worst case here is 90 bytes of the 107 a unix socket address holds; the margin is
     * asserted in the tests and enforced by {@link #requireBindablePath} at the bind.</p>
     */
    @NonNull
    public static String inputSocketPath(@NonNull String vmId, @NonNull String screenId,
                                         int channel) {
        var screen = isPerScreen(channel) ? fmt("_%s", screenTag(screenId)) : "";
        return pathJoin(RUN_PATH, fmt("%s%s%s_%s.sock",
            SOCKET_PREFIX, sanitize(vmId), screen, CHANNEL_TAGS[channel]));
    }

    /**
     * Returns [path] if a unix socket can actually be bound to it, and throws naming the path and
     * its length if not.
     *
     * <p>This exists because the two ends disagree about what to do with an over-long path, and
     * both answers are bad. crosvm refuses the command line outright ("path must be shorter than
     * SUN_LEN") and the VM never starts. bind(2) as this daemon reaches it does the opposite: the
     * path is copied into a 108-byte {@code sun_path} and <em>silently truncated</em>, so the
     * daemon binds some other inode, logs a successful pre-listen, and waits forever for a crosvm
     * that was told the untruncated name -- run/ on the test phone still held two of those stubs,
     * {@code ..._simplefb_input_multito} and {@code ..._simplefb_input_tablet.}, as the only trace
     * that anything had gone wrong. So the length is checked here, before the syscall, and a name
     * that grows past the limit hits a wall with the number in the message instead of a mystery.</p>
     *
     * <p>Measured in bytes, not chars: the kernel copies bytes, and {@link #sanitize} keeps the
     * two equal only as long as every name it is fed is ASCII.</p>
     */
    @NonNull
    public static String requireBindablePath(@NonNull String path) {
        int len = path.getBytes(StandardCharsets.UTF_8).length;
        if (len > MAX_UNIX_PATH)
            throw new IllegalArgumentException(fmt(
                "unix socket path is %d bytes, over the %d sun_path allows: %s",
                len, MAX_UNIX_PATH, path));
        return path;
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
