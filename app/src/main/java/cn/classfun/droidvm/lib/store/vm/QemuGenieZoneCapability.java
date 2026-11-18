// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import static cn.classfun.droidvm.lib.utils.AssetUtils.getPrebuiltBinaryPath;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.RunUtils.runListQuiet;

import androidx.annotation.NonNull;

public final class QemuGenieZoneCapability {
    public static final String DEVICE = "/dev/gzvm";
    public static final String ACCELERATOR = "gzvm";

    private QemuGenieZoneCapability() {
    }

    public static boolean acceleratorIsAvailable(@NonNull String acceleratorHelp) {
        for (var line : acceleratorHelp.toLowerCase().split("\\R")) {
            var token = line.trim();
            if (token.equals(ACCELERATOR)
                || token.startsWith(ACCELERATOR + " ")
                || token.startsWith(ACCELERATOR + "\t")) return true;
        }
        return false;
    }

    public static boolean preflight(boolean deviceExists, boolean acceleratorExists) {
        return deviceExists && acceleratorExists;
    }

    public static boolean isAvailable() {
        if (!shellCheckExists(DEVICE)) return false;
        var binary = getPrebuiltBinaryPath("qemu-system-aarch64");
        if (!shellCheckExists(binary)) return false;
        try {
            var result = runListQuiet(binary, "-accel", "help");
            var help = result.getOutString() + "\n" + result.getErrString();
            return result.isSuccess() && preflight(true, acceleratorIsAvailable(help));
        } catch (Throwable t) {
            return false;
        }
    }
}
