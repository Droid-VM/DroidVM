// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.gnss;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;

import cn.classfun.droidvm.lib.natives.UnixHelper;
import cn.classfun.droidvm.lib.store.vm.Gnss;
import cn.classfun.droidvm.lib.store.vm.GnssSource;

/**
 * One VM's GNSS receiver on the daemon side: the pipe whose read end crosvm takes as the input of
 * the {@link Gnss#PORT_NAME} virtio-console port, and what the receiver reports.
 *
 * <p>The write end is non-blocking and a write that does not fit is dropped: crosvm drains the
 * pipe continuously (and itself drops what the guest has no reader for), so a full pipe means a
 * crosvm that has stopped, and a receiver's sentences are worthless a second later anyway. The
 * hub's one thread writes every VM's port, and must not hang on one of them.</p>
 */
public final class GnssPort implements Closeable {
    private static final String TAG = "GnssPort";

    final String vmName;
    final GnssSource source;
    final boolean markSimulated;

    private int readFd;
    private ParcelFileDescriptor writer;
    /** elapsedRealtime of the last epoch written, for the hub's gap filling; hub thread only. */
    long lastEpochAt;
    /** UTC of the last receiver fix written, so one fix is not reported twice; hub thread only. */
    long lastFixUtc = Long.MIN_VALUE;
    private boolean broken;

    /** {@code source} must already be resolved: RECEIVER or LOCATION. */
    public GnssPort(@NonNull String vmName, @NonNull GnssSource source, boolean markSimulated)
        throws IOException {
        this.vmName = vmName;
        this.source = source;
        this.markSimulated = markSimulated;
        var fds = UnixHelper.nativePipe();
        if (fds == null || fds.length != 2 || fds[0] < 0 || fds[1] < 0)
            throw new IOException("pipe() failed");
        readFd = fds[0];
        writer = ParcelFileDescriptor.adoptFd(fds[1]);
        try {
            Os.fcntlInt(writer.getFileDescriptor(), OsConstants.F_SETFL, OsConstants.O_NONBLOCK);
        } catch (ErrnoException e) {
            close();
            throw new IOException("O_NONBLOCK", e);
        }
    }

    /** The fd crosvm reads, for the command line and preserveFd; -1 once handed over. */
    public int getReadFd() {
        return readFd;
    }

    /** The daemon's copy of the read end, closed once crosvm has its own. */
    public void closeReadFd() {
        if (readFd >= 0) UnixHelper.nativeCloseFd(readFd);
        readFd = -1;
    }

    /** {@code --serial} for this port: input from the pipe, the guest's writes discarded. */
    @NonNull
    public String serialArg(int num) {
        return fmt("type=sink,hardware=virtio-console,num=%d,name=%s,input=/proc/self/fd/%d",
            num, Gnss.PORT_NAME, readFd);
    }

    /** Writes one epoch; false when it did not go out whole. Hub thread only. */
    boolean write(@NonNull String epoch, long now) {
        var w = writer;
        if (w == null || broken) return false;
        var bytes = epoch.getBytes(StandardCharsets.US_ASCII);
        try {
            int n = Os.write(w.getFileDescriptor(), bytes, 0, bytes.length);
            lastEpochAt = now;
            return n == bytes.length;
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EAGAIN) return false;
            // EPIPE: crosvm is gone. Stop trying; the backend's cleanup unregisters the port.
            broken = true;
            Log.w(TAG, fmt("%s: GNSS port write failed: %s", vmName, e.getMessage()));
            return false;
        } catch (InterruptedIOException e) {
            return false;
        }
    }

    @Override
    public void close() {
        closeReadFd();
        var w = writer;
        writer = null;
        if (w != null) {
            try {
                w.close();
            } catch (IOException ignored) {
            }
        }
    }
}
