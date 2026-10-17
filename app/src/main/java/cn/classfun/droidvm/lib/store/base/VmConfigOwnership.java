// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.store.base;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Persistent, one-way native ownership; shares the C++ gate inode and POSIX lock range.
 * Never delete or truncate the gate, even after daemon death. Synchronization also
 * prevents another Java channel close from releasing this process's POSIX lock. */
public final class VmConfigOwnership {
    public static final String FILE_NAME = "vms.config-owner";
    private static java.nio.file.Path activeGate;
    private VmConfigOwnership() {}
    public interface Write { void run() throws Exception; }

    public static synchronized void legacyWrite(File directory, Write action) throws Exception {
        legacyWrite(directory, FILE_NAME, action);
    }

    static synchronized void legacyWrite(File directory, String fileName, Write action) throws Exception {
        var path = new File(directory.getCanonicalFile(), fileName).toPath();
        // A legacy read/modify/save transaction may invoke DataStore.save under
        // this same lock. Never open/close a second channel: POSIX would drop it.
        if (activeGate != null) {
            if (!activeGate.equals(path)) throw new IOException("Nested ownership transaction uses another gate");
            action.run();
            return;
        }
        try (var channel = FileChannel.open(path,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid configuration ownership gate");
            try (var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Configuration ownership is changing");
                if (channel.size() != 0) throw new IOException("Configuration belongs to the native service: " + fileName);
                activeGate = path;
                try { action.run(); }
                finally { activeGate = null; }
            }
        }
    }

    /** Read-only: the native service recorded ownership (a non-empty gate). Never creates it. */
    public static boolean claimed(File directory) {
        var gate = new File(directory, FILE_NAME);
        return Files.isRegularFile(gate.toPath(), LinkOption.NOFOLLOW_LINKS) && gate.length() > 0;
    }

    /** Startup check before --force can stop a native daemon, and again after
     * taking the shared daemon Instance lock. Does not create a root-owned gate. */
    public static synchronized void requireLegacy(File directory) throws Exception {
        requireLegacy(directory, FILE_NAME);
    }

    static synchronized void requireLegacy(File directory, String fileName) throws Exception {
        if (Files.notExists(new File(directory, fileName).toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        legacyWrite(directory, fileName, () -> {});
    }
}
