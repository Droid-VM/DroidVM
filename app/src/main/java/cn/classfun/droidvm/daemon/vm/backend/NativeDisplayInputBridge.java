package cn.classfun.droidvm.daemon.vm.backend;

import static cn.classfun.droidvm.lib.utils.FileUtils.deleteFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;

import cn.classfun.droidvm.lib.natives.UnixHelper;
import cn.classfun.droidvm.lib.network.FDSocket;
import cn.classfun.droidvm.lib.store.vm.NativeDisplay;

/**
 * Owns the per-VM native-display input sockets for one crosvm instance. crosvm's --input
 * <kind>[path=...] connects to a unix socket whose inode must already exist (crosvm is the
 * *client*), so the daemon is the only process that can both bind the socket before crosvm starts
 * and stay alive to feed it. This bridge pre-binds + accepts the crosvm-facing sockets; evdev from
 * the UI arrives via {@link #writeNativeInput(int, byte[])} - called either on the daemon's
 * native-display broker binder thread (touch hot path) or from the vm_input IPC handler - and is
 * written straight to the matching crosvm peer. {@link CrosvmBackendInstance} drives the lifecycle:
 * {@link #startListening(String)} from start() and {@link #release()} from cleanup().
 */
final class NativeDisplayInputBridge {
    private static final String TAG = "NativeDisplayInput";

    /** Server fds for the per-VM native-display input sockets, kept while crosvm is running. */
    private volatile int[] inputServerFds = null;
    /** Paths of the input sockets we listened on, so {@link #release()} can unlink them. */
    private volatile String[] inputSocketPaths = null;
    /**
     * The crosvm-side connection accepted on each input channel. crosvm connects to OUR socket at
     * startup (we are the only listener), so writing UI-forwarded evdev here is what actually
     * reaches the guest. Indexed by NativeDisplay channel constants. Volatile because the accept
     * threads, the write threads, and release() all touch it without a single shared lock.
     */
    private volatile FDSocket[] inputPeers = null;
    /** Per-channel write lock; also guards swapping {@link #inputPeers} on reconnect. */
    private volatile Object[] inputWriteLocks = null;
    private volatile boolean inputClosed = false;

    /**
     * Pre-creates the per-VM native-display input sockets as listening unix sockets. crosvm
     * connects to these paths at startup, so a listener must exist before
     * {@link CrosvmBackendInstance#start()} execs the crosvm process. nativeUnixListen unlinks any
     * stale inode and re-binds, so a leftover socket file from a crashed run is replaced rather than
     * blocking us. Returns true iff every channel ended up with a live listener. Server fds we open
     * are tracked for release in {@link #release()}.
     */
    boolean startListening(@NonNull String serviceName) {
        if (!UnixHelper.isLoaded()) {
            Log.w(TAG, "UnixHelper not loaded; cannot pre-bind native-display input sockets");
            return false;
        }
        var paths = new String[NativeDisplay.CHANNEL_COUNT];
        var fds = new int[NativeDisplay.CHANNEL_COUNT];
        inputClosed = false;
        inputPeers = new FDSocket[NativeDisplay.CHANNEL_COUNT];
        inputWriteLocks = new Object[NativeDisplay.CHANNEL_COUNT];
        boolean allListening = true;
        for (int ch = 0; ch < NativeDisplay.CHANNEL_COUNT; ch++) {
            inputWriteLocks[ch] = new Object();
            var path = NativeDisplay.inputSocketPath(serviceName, ch);
            paths[ch] = path;
            var fd = UnixHelper.nativeUnixListen(path);
            if (fd < 0) {
                Log.w(TAG, fmt("Failed to pre-listen on input socket: %s", path));
                allListening = false;
                fds[ch] = -1;
            } else {
                Log.i(TAG, fmt("Pre-listening on input socket: %s (fd=%d)", path, fd));
                fds[ch] = fd;
                // Accept crosvm's connection in the background. crosvm is the client and connects
                // at its own startup, so a peer may not arrive until after start() execs it.
                startInputAcceptThread(ch, fd);
            }
        }
        inputSocketPaths = paths;
        inputServerFds = fds;
        return allListening;
    }

    /**
     * Accepts crosvm's connection on one input channel and keeps the live peer in
     * {@link #inputPeers}. Loops so a crosvm restart (new connection on the same socket) replaces
     * the dead peer; ends when {@link #release()} closes the server fd.
     */
    private void startInputAcceptThread(int channel, int serverFd) {
        var t = new Thread(() -> {
            while (!inputClosed) {
                int peerFd = UnixHelper.nativeUnixAccept(serverFd);
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
                // Snapshot the lock/peer arrays: release() can null them concurrently.
                var locks = inputWriteLocks;
                if (locks == null) {
                    peer.close();
                    break;
                }
                synchronized (locks[channel]) {
                    var peers = inputPeers;
                    if (peers == null) {
                        peer.close();
                        break;
                    }
                    var old = peers[channel];
                    peers[channel] = peer;
                    if (old != null) old.close();
                }
                Log.i(TAG, fmt("crosvm input connected: channel %d", channel));
            }
        }, fmt("CrosvmInputAccept-%d", channel));
        t.setDaemon(true);
        t.start();
    }

    /**
     * Writes pre-encoded evdev bytes (8-byte records) to the crosvm connection for [channel].
     * Called from the daemon IPC thread on behalf of the UI. Returns false if no crosvm peer is
     * connected yet or the write fails.
     */
    boolean writeNativeInput(int channel, @NonNull byte[] data) {
        if (channel < 0 || channel >= NativeDisplay.CHANNEL_COUNT || data.length == 0) return false;
        // Snapshot the arrays once: release() nulls these fields concurrently, so dereferencing the
        // live field after the guard could NPE. The lock object itself stays valid for the session.
        var locks = inputWriteLocks;
        if (locks == null) return false;
        var lock = locks[channel];
        if (lock == null) return false;
        synchronized (lock) {
            var peers = inputPeers;
            if (peers == null) return false;
            var peer = peers[channel];
            if (peer == null || !peer.isOpen()) return false;
            try {
                var os = peer.getOutputStream();
                os.write(data);
                os.flush();
                return true;
            } catch (IOException e) {
                Log.w(TAG, fmt("input write channel %d failed: %s", channel, e.getMessage()));
                peers[channel] = null;
                peer.close();
                return false;
            }
        }
    }

    /**
     * Closes the input server fds we opened and unlinks only the inodes we own. Channels that
     * fell through to the UI's listener (fd == -1) are left untouched so we don't yank a socket
     * the UI still holds.
     */
    void release() {
        inputClosed = true; // stop accept loops; closing the server fd below unblocks nativeUnixAccept
        if (inputPeers != null) {
            for (int ch = 0; ch < inputPeers.length; ch++) {
                if (inputWriteLocks != null && inputWriteLocks[ch] != null) {
                    synchronized (inputWriteLocks[ch]) {
                        if (inputPeers[ch] != null) {
                            inputPeers[ch].close();
                            inputPeers[ch] = null;
                        }
                    }
                }
            }
            inputPeers = null;
        }
        inputWriteLocks = null;
        if (inputServerFds == null) {
            inputSocketPaths = null;
            return;
        }
        for (int ch = 0; ch < inputServerFds.length; ch++) {
            int fd = inputServerFds[ch];
            if (fd < 0) continue;
            UnixHelper.nativeCloseFd(fd);
            if (inputSocketPaths != null && inputSocketPaths[ch] != null)
                deleteFile(inputSocketPaths[ch]);
        }
        inputServerFds = null;
        inputSocketPaths = null;
    }
}
