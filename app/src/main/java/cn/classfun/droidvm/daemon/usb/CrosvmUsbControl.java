// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.usb;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;
import static cn.classfun.droidvm.lib.utils.StringUtils.streamToString;

import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import cn.classfun.droidvm.lib.natives.NativeProcess;

/**
 * The {@code crosvm usb} sub-commands, run against one VM's control socket.
 *
 * <p>Attaching cannot be done over the control socket ourselves: crosvm opens the usbfs node in
 * its own process and passes the fd across, so the fd has to come from a crosvm process. The
 * bundled CLI is that process, and it prints exactly one line per command.</p>
 */
public final class CrosvmUsbControl {
    private static final String TAG = "CrosvmUsbControl";
    /** A control-socket round trip that has not answered by now is not going to. */
    private static final long TIMEOUT_SECONDS = 15;
    /** How long a killed child gets to be reaped before we stop waiting for it. */
    private static final long TIMEOUT_KILL_SECONDS = 2;
    /** What vm_control prints when the CLI cannot reach the control socket at all. */
    private static final String CONNECT_FAILED = "failed to connect to socket";

    /** One line of {@code crosvm usb list}. */
    public static final class Entry {
        public final int port;
        public final String vid;
        public final String pid;

        Entry(int port, @NonNull String vid, @NonNull String pid) {
            this.port = port;
            this.vid = vid;
            this.pid = pid;
        }
    }

    private static final class Output {
        final String stdout;
        final String stderr;

        Output(@NonNull String stdout, @NonNull String stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private final String crosvmBinary;
    private final String socketPath;

    public CrosvmUsbControl(@NonNull String crosvmBinary, @NonNull String socketPath) {
        this.crosvmBinary = crosvmBinary;
        this.socketPath = socketPath;
    }

    /** Hands [node] to the VMM, which claims every interface on it. Returns the guest port. */
    public int attach(@NonNull String node) throws IOException, UsbControlException {
        var output = exec(crosvmBinary, "usb", "attach", "0:0:0:0", node, socketPath);
        requireOutput(output);
        try {
            return parseAttach(output.stdout);
        } catch (UsbControlException e) {
            throw new UsbControlException(e.token, output.stderr);
        }
    }

    public int detach(int port) throws IOException, UsbControlException {
        var output = exec(crosvmBinary, "usb", "detach", String.valueOf(port), socketPath);
        requireOutput(output);
        try {
            return parseDetach(output.stdout);
        } catch (UsbControlException e) {
            throw new UsbControlException(e.token, output.stderr);
        }
    }

    @NonNull
    public List<Entry> list() throws IOException, UsbControlException {
        var output = exec(crosvmBinary, "usb", "list", socketPath);
        requireOutput(output);
        try {
            return parseList(output.stdout);
        } catch (UsbControlException e) {
            throw new UsbControlException(e.token, output.stderr);
        }
    }

    /**
     * A CLI that died before printing has no refusal token to report, and an empty token would
     * name a failure that never happened; what went wrong is on stderr instead. One such
     * failure is told apart: the CLI never reached the VMM, which is about the socket and not
     * about the device.
     */
    private static void requireOutput(@NonNull Output output) throws IOException {
        if (!output.stdout.trim().isEmpty()) return;
        if (isUnreachable(output.stderr))
            throw new UsbVmmUnreachableException(output.stderr);
        throw new IOException(fmt("crosvm usb printed nothing; stderr: %s", output.stderr));
    }

    /**
     * Whether [stderr] is the CLI saying it could not connect to the control socket -- the VMM
     * not listening yet, or gone -- rather than anything the VMM or the device answered.
     */
    static boolean isUnreachable(@NonNull String stderr) {
        return stderr.contains(CONNECT_FAILED);
    }

    @NonNull
    private Output exec(@NonNull String... argv) throws IOException {
        var builder = new NativeProcess.Builder(argv);
        // Same two lines as VMBackendInstance.prepareProcess: the CLI is the same binary as the
        // VMM and needs the same loader environment. prepareProcess is protected on an abstract
        // class and there is no backend instance to call it on here, so it is copied rather than
        // reused.
        String[] preload = {
            pathJoin(DATA_DIR, "lib", "libsimpledump.so"),
            pathJoin(DATA_DIR, "lib", fmt("libcompat_a%s.so", Build.VERSION.RELEASE)),
        };
        builder.environment("LD_PRELOAD", String.join(":", preload));
        builder.environment("LD_LIBRARY_PATH", pathJoin(DATA_DIR, "usr", "lib"));
        Log.i(TAG, fmt("Executing: %s", String.join(" ", argv)));
        try (var process = builder.start()) {
            // Both pipes have to be drained or the child blocks on a full one -- and both on
            // their own threads: stdout only reaches EOF when the child exits, so reading it
            // here would run past the timeout below instead of enforcing it, which is exactly
            // the case the timeout exists for (a CLI stuck on an unanswered control socket).
            var stdoutHolder = new String[]{""};
            var stderrHolder = new String[]{""};
            var stdoutReader = drain(process.getInputStream(), stdoutHolder, "crosvm-usb-stdout");
            var stderrReader = drain(process.getErrorStream(), stderrHolder, "crosvm-usb-stderr");
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // SIGKILL, not SIGTERM: this one is already not answering. Reap it afterwards,
                // because nothing else ever waits on the pid.
                process.destroyForcibly();
                process.waitFor(TIMEOUT_KILL_SECONDS, TimeUnit.SECONDS);
                throw new IOException(fmt("crosvm usb did not answer in %ds", TIMEOUT_SECONDS));
            }
            stdoutReader.join(TimeUnit.SECONDS.toMillis(1));
            stderrReader.join(TimeUnit.SECONDS.toMillis(1));
            var stderr = stderrHolder[0].trim();
            if (!stderr.isEmpty()) Log.w(TAG, fmt("crosvm usb stderr: %s", stderr));
            return new Output(stdoutHolder[0], stderr);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for crosvm usb", e);
        }
    }

    /** Reads [stream] to EOF into [holder] on a daemon thread; join it to see what it got. */
    @NonNull
    private static Thread drain(@NonNull InputStream stream, @NonNull String[] holder,
                                @NonNull String name) {
        var thread = new Thread(() -> {
            try {
                holder[0] = streamToString(stream);
            } catch (IOException e) {
                Log.w(TAG, fmt("Failed to read %s", name), e);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    static int parseAttach(@NonNull String stdout) throws UsbControlException {
        return parseOkPort(stdout);
    }

    static int parseDetach(@NonNull String stdout) throws UsbControlException {
        return parseOkPort(stdout);
    }

    /** {@code ok <port>} on success; every refusal is a single word, and becomes the token. */
    private static int parseOkPort(@NonNull String stdout) throws UsbControlException {
        for (var raw : stdout.split("\n")) {
            var line = raw.trim();
            if (line.isEmpty()) continue;
            var parts = line.split("\\s+");
            if (parts.length < 2 || !parts[0].equals("ok"))
                throw new UsbControlException(parts[0]);
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new UsbControlException(line);
            }
        }
        throw new UsbControlException(stdout.trim());
    }

    @NonNull
    static List<Entry> parseList(@NonNull String stdout) throws UsbControlException {
        for (var raw : stdout.split("\n")) {
            var line = raw.trim();
            if (line.isEmpty()) continue;
            var parts = line.split("\\s+");
            if (!parts[0].equals("devices"))
                throw new UsbControlException(parts[0]);
            // Everything after the keyword is port/vid/pid, repeated; a trailing partial group
            // means the line is not the list it claims to be, and half of it must not pass as one.
            if ((parts.length - 1) % 3 != 0)
                throw new UsbControlException(line);
            var entries = new ArrayList<Entry>();
            for (int i = 1; i + 2 < parts.length; i += 3) {
                try {
                    entries.add(new Entry(Integer.parseInt(parts[i]), parts[i + 1], parts[i + 2]));
                } catch (NumberFormatException e) {
                    throw new UsbControlException(line);
                }
            }
            return entries;
        }
        throw new UsbControlException(stdout.trim());
    }
}
