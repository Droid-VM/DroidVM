// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.nativedisplay.display;

import android.crosvm.ICrosvmAndroidDisplayService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The guest cursor's position, straight from virtio-gpu.
 *
 * crosvm's Android display backend already writes every cursor move to a pipe -- see
 * set_android_surface_position() in crosvm_android_display_client.cpp, which does nothing but
 * write(fd, {x, y}). Until now nothing ever called setCursorStream, so that fd stayed -1 and the
 * backend logged "cursor position stream is not attached; dropping position updates" once and
 * threw every update away. This class is the missing other end.
 *
 * WHY THIS AND NOT THE APP'S OWN TOUCH COORDINATES
 *
 * The app knows where the user touched, which is not where the guest's pointer is:
 *   - in relative-pointer mode the app sends DELTAS, so it has no absolute position at all;
 *   - the guest warps the pointer on its own (games grabbing it, dialogs centring it);
 *   - while zoomed there is a scale factor between the two, so any app-side dead reckoning drifts.
 * These x/y come from the guest's own cursor plane, which is the only authoritative source.
 *
 * Values are little-endian u32 pairs in GUEST framebuffer coordinates.
 */
final class CursorPositionStream implements AutoCloseable {

    /** Delivered on the main thread. */
    interface Listener {
        void onCursorMoved(int guestX, int guestY);
    }

    private static final String TAG = "CursorPositionStream";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private ParcelFileDescriptor readSide;
    private Thread reader;
    private volatile boolean closed;

    private CursorPositionStream(@NonNull Listener listener) {
        this.listener = listener;
    }

    /**
     * Creates the pipe, hands the WRITE end to crosvm and starts reading the other end.
     * Returns null if the service refuses it, in which case the caller simply has no cursor
     * position -- everything else keeps working.
     */
    static CursorPositionStream attach(@NonNull ICrosvmAndroidDisplayService service,
                                       @NonNull Listener listener) {
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Log.w(TAG, "could not create cursor pipe", e);
            return null;
        }
        var self = new CursorPositionStream(listener);
        self.readSide = pipe[0];
        try {
            // The binder call dups the fd, so our copy must be closed either way -- crosvm keeps
            // its own. Leaving it open here would mean the reader never sees EOF when crosvm exits.
            service.setCursorStream(pipe[1]);
        } catch (Exception e) {
            Log.w(TAG, "setCursorStream failed; cursor position unavailable", e);
            closeQuietly(pipe[0]);
            closeQuietly(pipe[1]);
            return null;
        } finally {
            closeQuietly(pipe[1]);
        }

        self.reader = new Thread(self::readLoop, "CursorPositionStream");
        self.reader.setDaemon(true);
        self.reader.start();
        Log.i(TAG, "cursor position stream attached");
        return self;
    }

    private void readLoop() {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readSide)) {
            var din = new DataInputStream(in);
            byte[] buf = new byte[8];
            while (!closed) {
                // readFully, not read: the writer emits 8-byte records and a short read would
                // desynchronise the stream permanently, turning every later position into noise.
                din.readFully(buf);
                int x = le32(buf, 0);
                int y = le32(buf, 4);
                mainHandler.post(() -> {
                    if (!closed) {
                        listener.onCursorMoved(x, y);
                    }
                });
            }
        } catch (IOException e) {
            if (!closed) {
                // Normal at VM shutdown: crosvm exits and the write end closes.
                Log.i(TAG, "cursor position stream ended: " + e.getMessage());
            }
        }
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF)
             | ((b[off + 1] & 0xFF) << 8)
             | ((b[off + 2] & 0xFF) << 16)
             | ((b[off + 3] & 0xFF) << 24);
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(readSide);   // unblocks readFully
        readSide = null;
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) return;
        try {
            pfd.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the fd is going away either way.
        }
    }
}
