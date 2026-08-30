// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.Nullable;

import cn.classfun.droidvm.BuildConfig;
import cn.classfun.droidvm.daemon.display.DaemonSystemContext;
import cn.classfun.droidvm.lib.data.QcomChipName;

/**
 * Resolves the host SoC marketing name (e.g. "Qualcomm Snapdragon 8 Elite") inside the daemon so
 * VM backends can forward it to guest firmware ({@code crosvm --smbios processor-version=...} ->
 * FDT /chosen -> EDK2 SMBIOS Type 4), letting Windows show the real CPU name instead of the
 * firmware's built-in "Gunyah vCPU".
 *
 * The lookup table lives in the app's {@code res/xml/qcom.xml}; the daemon reaches it through a
 * package context created from its system context. Falls back to the raw SoC model string
 * (e.g. "SM8750P") when resources are unavailable, and to null when even getprop yields nothing.
 */
public final class HostSocName {
    private static final String TAG = "HostSocName";
    private static String cached;
    private static boolean resolved;

    private HostSocName() {
    }

    @Nullable
    public static synchronized String get() {
        if (resolved) return cached;
        resolved = true;
        try {
            var soc = QcomChipName.getCurrentSoC();
            if (soc == null || soc.trim().isEmpty()) return cached = null;
            soc = soc.trim();
            cached = soc;
            var sys = DaemonSystemContext.get();
            if (sys != null) {
                var appCtx = sys.createPackageContext(BuildConfig.APPLICATION_ID, 0);
                var name = new QcomChipName(appCtx).lookupChipName(soc);
                if (name != null && !name.trim().isEmpty())
                    cached = name.trim();
            }
            Log.i(TAG, fmt("host SoC name: %s", cached));
        } catch (Throwable t) {
            Log.w(TAG, "failed to resolve host SoC name", t);
        }
        return cached;
    }
}
