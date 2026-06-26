package cn.classfun.droidvm.ui.vm.display.nativedisplay.input;

import static cn.classfun.droidvm.lib.store.vm.NativeDisplay.CHANNEL_COUNT;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.display.INativeDisplayRootService;

/**
 * {@link InputForwarder.InputSink} that hands pre-encoded evdev straight to the daemon via its
 * binder ({@link INativeDisplayRootService#writeInput}), which writes it to the crosvm input socket
 * it owns - bypassing the slow vm_input JSON-RPC round-trip. An untrusted_app can neither
 * {@code connectto} that su-domain socket nor receive its fd over binder, but it can hand the bytes
 * across binder. Falls back to {@code fallback} on any failure so the feature degrades to the
 * original IPC path instead of dropping input.
 */
public final class DirectInputSink implements InputForwarder.InputSink {
    private static final String TAG = "DirectInputSink";

    private final String vmId;
    private final INativeDisplayRootService rootService;
    private final InputForwarder.InputSink fallback;
    private volatile boolean closed = false;
    // Whether the most recent write() went out the daemon-binder path (vs. the IPC fallback). For
    // latency diagnostics only; the worker thread is single-threaded so a plain volatile is enough.
    private volatile boolean lastWriteDirect = false;

    /**
     * @param vmId        the VM id, used by the daemon to find the running VM's input channel.
     * @param rootService daemon broker binder that writes the bytes; null disables the direct path.
     * @param fallback    sink used when the direct path is unavailable or errors.
     */
    public DirectInputSink(@NonNull String vmId, @Nullable INativeDisplayRootService rootService,
                           @Nullable InputForwarder.InputSink fallback) {
        this.vmId = vmId;
        this.rootService = rootService;
        this.fallback = fallback;
    }

    @Override
    public boolean write(int channel, @NonNull byte[] data) {
        if (closed || channel < 0 || channel >= CHANNEL_COUNT) return false;
        if (rootService != null) {
            try {
                if (rootService.writeInput(vmId, channel, data)) {
                    lastWriteDirect = true;
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, fmt("channel %d root forward failed: %s - falling back",
                    channel, e.getMessage()));
            }
        }
        lastWriteDirect = false;
        return fallback != null && fallback.write(channel, data);
    }

    /** True iff the most recent {@link #write} went out the daemon-binder path. Diagnostics only. */
    public boolean wasLastWriteDirect() {
        return lastWriteDirect;
    }

    /** Stops using the direct path; subsequent writes go through the fallback. */
    public void close() {
        closed = true;
    }
}
