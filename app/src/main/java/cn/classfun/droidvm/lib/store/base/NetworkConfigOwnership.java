// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.store.base;

import java.io.File;

/** Network counterpart of the shared persistent VM ownership gate. */
public final class NetworkConfigOwnership {
    public static final String FILE_NAME = "networks.config-owner";
    private NetworkConfigOwnership() {}
    public static void legacyWrite(File directory, VmConfigOwnership.Write action) throws Exception {
        VmConfigOwnership.legacyWrite(directory, FILE_NAME, action);
    }
    public static void requireLegacy(File directory) throws Exception {
        VmConfigOwnership.requireLegacy(directory, FILE_NAME);
    }
}
