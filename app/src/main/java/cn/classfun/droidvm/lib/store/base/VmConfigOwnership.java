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
    private VmConfigOwnership() {}
    public interface Write { void run() throws Exception; }

    public static synchronized void legacyWrite(File directory, Write action) throws Exception {
        var path = new File(directory, FILE_NAME).toPath();
        try (var channel = FileChannel.open(path,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid VM ownership gate");
            try (var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("VM configuration ownership is changing");
                if (channel.size() != 0) throw new IOException("VM configuration belongs to the native service");
                action.run();
            }
        }
    }

    /** Startup check before --force can stop a native daemon, and again after
     * taking the shared daemon Instance lock. Does not create a root-owned gate. */
    public static synchronized void requireLegacy(File directory) throws Exception {
        if (Files.notExists(new File(directory, FILE_NAME).toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        legacyWrite(directory, () -> {});
    }
}
