package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import static cn.classfun.droidvm.lib.store.vm.NativeDisplay.KEYBOARD;
import static cn.classfun.droidvm.lib.store.vm.NativeDisplay.MULTITOUCH;
import static cn.classfun.droidvm.lib.store.vm.NativeDisplay.CHANNEL_COUNT;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.OutputStream;

/**
 * {@link InputForwarder.InputSink} that ships pre-encoded evdev straight to the daemon over a unix
 * socket ({@link cn.classfun.droidvm.lib.store.vm.NativeDisplay#uiInputSocketPath}), bypassing the
 * JSON-RPC round-trip used by the vm_input IPC command. One persistent {@link LocalSocket} per
 * channel; writes are synchronized per channel so concurrent touch/keyboard writes can't interleave
 * evdev records. Falls back to {@code fallback} on any connect/write failure so the feature degrades
 * gracefully to the original IPC path instead of dropping input.
 */
public final class DirectInputSink implements InputForwarder.InputSink {
    private static final String TAG = "DirectInputSink";

    private final String[] paths;
    private final InputForwarder.InputSink fallback;
    private final LocalSocket[] sockets = new LocalSocket[CHANNEL_COUNT];
    private final OutputStream[] streams = new OutputStream[CHANNEL_COUNT];
    private final Object[] locks = new Object[CHANNEL_COUNT];
    private final boolean[] directOk = new boolean[CHANNEL_COUNT];
    private volatile boolean closed = false;

    /**
     * @param vmKey     the per-VM service name / vmKey, used to derive the UI input socket paths.
     * @param fallback  sink used for any channel that can't be opened or that errors mid-write.
     */
    public DirectInputSink(@NonNull String vmKey, @Nullable InputForwarder.InputSink fallback) {
        this.paths = new String[CHANNEL_COUNT];
        for (int ch = 0; ch < CHANNEL_COUNT; ch++) {
            paths[ch] = cn.classfun.droidvm.lib.store.vm.NativeDisplay.uiInputSocketPath(vmKey, ch);
            locks[ch] = new Object();
        }
        this.fallback = fallback;
    }

    @Override
    public boolean write(int channel, @NonNull byte[] data) {
        if (closed || channel < 0 || channel >= CHANNEL_COUNT) return false;
        synchronized (locks[channel]) {
            if (directOk[channel]) {
                try {
                    var os = streams[channel];
                    if (os != null) {
                        os.write(data);
                        os.flush();
                        return true;
                    }
                } catch (IOException e) {
                    Log.w(TAG, fmt("channel %d direct write failed: %s — falling back",
                        channel, e.getMessage()));
                    directOk[channel] = false;
                    closeChannel(channel);
                }
            }
            // (Re)try opening the direct socket lazily; on failure route via the fallback sink.
            if (!directOk[channel] && !closed) {
                if (openChannel(channel)) {
                    try {
                        streams[channel].write(data);
                        streams[channel].flush();
                        return true;
                    } catch (IOException e) {
                        Log.w(TAG, fmt("channel %d direct write after open failed: %s",
                            channel, e.getMessage()));
                        directOk[channel] = false;
                        closeChannel(channel);
                    }
                }
            }
        }
        return fallback != null && fallback.write(channel, data);
    }

    private boolean openChannel(int channel) {
        try {
            var s = new LocalSocket();
            s.connect(new LocalSocketAddress(paths[channel],
                LocalSocketAddress.Namespace.FILESYSTEM));
            sockets[channel] = s;
            streams[channel] = s.getOutputStream();
            directOk[channel] = true;
            Log.i(TAG, fmt("channel %d direct sink connected: %s", channel, paths[channel]));
            return true;
        } catch (IOException e) {
            Log.d(TAG, fmt("channel %d direct sink unavailable (%s) — using fallback",
                channel, e.getMessage()));
            closeChannel(channel);
            return false;
        }
    }

    private void closeChannel(int channel) {
        try { if (sockets[channel] != null) sockets[channel].close(); }
        catch (IOException ignored) {}
        sockets[channel] = null;
        streams[channel] = null;
    }

    /** Releases all direct sockets; subsequent writes go through the fallback. */
    public void close() {
        closed = true;
        for (int ch = 0; ch < CHANNEL_COUNT; ch++) {
            synchronized (locks[ch]) {
                directOk[ch] = false;
                closeChannel(ch);
            }
        }
    }
}