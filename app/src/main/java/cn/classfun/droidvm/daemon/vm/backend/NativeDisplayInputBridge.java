// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import static cn.classfun.droidvm.lib.utils.FileUtils.deleteFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.classfun.droidvm.lib.natives.UnixHelper;
import cn.classfun.droidvm.lib.network.FDSocket;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;

/**
 * Owns the native-display input sockets for one crosvm instance. crosvm's --input
 * <kind>[path=...] connects to a unix socket whose inode must already exist (crosvm is the
 * *client*), so the daemon is the only process that can both bind the socket before crosvm starts
 * and stay alive to feed it. This bridge pre-binds + accepts the crosvm-facing sockets; evdev from
 * the UI arrives via {@link #writeNativeInput(String, int, byte[])} - called either on the daemon's
 * native-display broker binder thread (touch hot path) or from the vm_input IPC handler - and is
 * written straight to the matching crosvm peer. {@link CrosvmBackendInstance} drives the lifecycle:
 * {@link #startListening(String, List)} from start() and {@link #release()} from cleanup().
 *
 * <p>The socket set is not one per channel: the keyboard and the relative pointer are VM-wide,
 * while multi-touch and the absolute pointer exist once per screen that has them, so a slot is a
 * (screen, channel) pair and the screens that get one are decided by the config. Everything here
 * is therefore keyed rather than indexed -- a flat channel-indexed array cannot say which screen a
 * write is for, and silently picking one would put touches on the wrong output.</p>
 */
final class NativeDisplayInputBridge {
    private static final String TAG = "NativeDisplayInput";

    /**
     * One crosvm-facing input socket: the inode we bound, the accepted crosvm connection, and the
     * lock that keeps a write and a reconnect off each other. crosvm connects to OUR socket at
     * startup (we are the only listener), so writing UI-forwarded evdev to {@link #peer} is what
     * actually reaches the guest.
     */
    private static final class Slot {
        final String key;
        final String path;
        final int serverFd;
        final Object lock = new Object();
        /** Volatile: accept threads, write threads and release() all touch it without one lock. */
        volatile FDSocket peer;

        Slot(@NonNull String key, @NonNull String path, int serverFd) {
            this.key = key;
            this.path = path;
            this.serverFd = serverFd;
        }
    }

    /**
     * Live slots by {@link #slotKey}. Replaced wholesale on start and cleared on release, so a
     * reader either sees the whole set or none of it. Null until the first start.
     */
    private volatile Map<String, Slot> slots = null;
    private volatile boolean inputClosed = false;

    /**
     * Identity of one socket: the channel, plus the screen for the channels that have one per
     * screen. VM-wide channels collapse onto the empty screen so the same key comes out whatever
     * screen the console that sent the bytes happens to be showing.
     */
    @NonNull
    private static String slotKey(@NonNull String screenId, int channel) {
        return fmt("%s/%d", NativeDisplay.isPerScreen(channel) ? screenId : "", channel);
    }

    /**
     * Pre-creates the input sockets as listening unix sockets. crosvm connects to these paths at
     * startup, so a listener must exist before {@link CrosvmBackendInstance#start()} execs the
     * crosvm process. nativeUnixListen unlinks any stale inode and re-binds, so a leftover socket
     * file from a crashed run is replaced rather than blocking us. Returns true iff every slot
     * ended up with a live listener.
     *
     * <p>[absoluteScreens] is the screens that get their own multi-touch + absolute pointer -- the
     * same list {@link CrosvmBackendInstance} emits {@code --input} devices for, so the sockets and
     * the devices cannot diverge. A screen left out of it has no sockets and no devices; input
     * aimed at it is refused rather than landing on some other screen's geometry.</p>
     */
    boolean startListening(@NonNull String vmId, @NonNull List<String> absoluteScreens) {
        if (!UnixHelper.isLoaded()) {
            Log.w(TAG, "UnixHelper not loaded; cannot pre-bind native-display input sockets");
            return false;
        }
        inputClosed = false;
        var built = new LinkedHashMap<String, Slot>();
        boolean allListening = true;
        for (int ch = 0; ch < NativeDisplay.CHANNEL_COUNT; ch++) {
            for (var screenId : screensFor(ch, absoluteScreens)) {
                var path = NativeDisplay.inputSocketPath(vmId, screenId, ch);
                var fd = UnixHelper.nativeUnixListen(path);
                if (fd < 0) {
                    Log.w(TAG, fmt("Failed to pre-listen on input socket: %s", path));
                    allListening = false;
                    continue;
                }
                Log.i(TAG, fmt("Pre-listening on input socket: %s (fd=%d)", path, fd));
                var slot = new Slot(slotKey(screenId, ch), path, fd);
                built.put(slot.key, slot);
                // Accept crosvm's connection in the background. crosvm is the client and connects
                // at its own startup, so a peer may not arrive until after start() execs it.
                startInputAcceptThread(slot);
            }
        }
        slots = built;
        return allListening;
    }

    /** The screens [channel] needs a socket for: every listed one, or just the VM itself. */
    @NonNull
    private static List<String> screensFor(int channel, @NonNull List<String> absoluteScreens) {
        return NativeDisplay.isPerScreen(channel) ? absoluteScreens : List.of("");
    }

    /**
     * Accepts crosvm's connection on one slot and keeps the live peer on it. Loops so a crosvm
     * restart (new connection on the same socket) replaces the dead peer; ends when
     * {@link #release()} closes the server fd.
     */
    private void startInputAcceptThread(@NonNull Slot slot) {
        var t = new Thread(() -> {
            while (!inputClosed) {
                int peerFd = UnixHelper.nativeUnixAccept(slot.serverFd);
                if (peerFd < 0) {
                    if (inputClosed) break;
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                var peer = new FDSocket(peerFd);
                synchronized (slot.lock) {
                    if (inputClosed) {
                        peer.close();
                        break;
                    }
                    var old = slot.peer;
                    slot.peer = peer;
                    if (old != null) old.close();
                }
                Log.i(TAG, fmt("crosvm input connected: %s", slot.path));
            }
        }, fmt("CrosvmInputAccept-%s", slot.key));
        t.setDaemon(true);
        t.start();
    }

    /**
     * Writes pre-encoded evdev bytes (8-byte records) to the crosvm connection for [channel] on
     * [screenId]. Called from the daemon IPC thread or the broker binder thread on behalf of the
     * UI. Returns false if the VM has no such device (an absolute channel on a screen whose input
     * is switched off), no crosvm peer is connected yet, or the write fails -- the caller reports
     * that as "not delivered" rather than pretending it landed somewhere.
     */
    boolean writeNativeInput(@NonNull String screenId, int channel, @NonNull byte[] data) {
        if (channel < 0 || channel >= NativeDisplay.CHANNEL_COUNT || data.length == 0) return false;
        var slot = findSlot(screenId, channel);
        if (slot == null) return false;
        synchronized (slot.lock) {
            var peer = slot.peer;
            if (peer == null || !peer.isOpen()) return false;
            try {
                var os = peer.getOutputStream();
                os.write(data);
                os.flush();
                return true;
            } catch (IOException e) {
                Log.w(TAG, fmt("input write to %s failed: %s", slot.path, e.getMessage()));
                slot.peer = null;
                peer.close();
                return false;
            }
        }
    }

    @Nullable
    private Slot findSlot(@NonNull String screenId, int channel) {
        // Snapshot the map once: release() nulls the field concurrently.
        var live = slots;
        return live == null ? null : live.get(slotKey(screenId, channel));
    }

    /** Closes the input server fds we opened and unlinks the inodes we own. */
    void release() {
        inputClosed = true; // stop accept loops; closing the server fd below unblocks nativeUnixAccept
        var live = slots;
        slots = null;
        if (live == null) return;
        for (var slot : live.values()) {
            synchronized (slot.lock) {
                if (slot.peer != null) {
                    slot.peer.close();
                    slot.peer = null;
                }
            }
            UnixHelper.nativeCloseFd(slot.serverFd);
            deleteFile(slot.path);
        }
    }
}
