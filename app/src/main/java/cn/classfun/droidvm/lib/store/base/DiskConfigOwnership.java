// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.store.base;

import java.io.File;

/** Persistent disk-registry ownership; file operations need their own admission. */
public final class DiskConfigOwnership {
    public static final String FILE_NAME = "disks.config-owner";
    private DiskConfigOwnership() {}
    public static void legacyWrite(File directory, VmConfigOwnership.Write action) throws Exception {
        VmConfigOwnership.legacyWrite(directory, FILE_NAME, action);
    }
    public static void requireLegacy(File directory) throws Exception {
        VmConfigOwnership.requireLegacy(directory, FILE_NAME);
    }
}
