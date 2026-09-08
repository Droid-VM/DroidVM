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
 * The lookup table lives in the app's {@code res/xml/qcom.xml}. The daemon reads it straight out
 * of its own APK ({@link QcomChipName#apkResources()}), because {@code createPackageContext} on
 * the daemon's system context needs an attached ActivityThread the daemon does not have; that
 * route stays as a fallback. Falls back to the raw SoC model string (e.g. "SM8650") when the
 * table is unreachable, and to null when even getprop yields nothing.
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
        String soc;
        try {
            soc = QcomChipName.getCurrentSoC();
        } catch (Throwable t) {
            Log.w(TAG, "failed to read the host SoC model", t);
            resolved = true;
            return cached = null;
        }
        if (soc == null || soc.trim().isEmpty()) {
            resolved = true;
            return cached = null;
        }
        soc = soc.trim();
        var name = lookup(soc);
        if (name != null && !name.isEmpty() && !name.equals(soc)) {
            // A marketing name never changes under us, so this one is final.
            resolved = true;
            cached = name;
        } else {
            // The raw model still beats "Gunyah vCPU", but a table that failed to load is worth
            // retrying at the next VM start rather than freezing the fallback for this daemon.
            cached = soc;
        }
        Log.i(TAG, fmt("host SoC name: %s (model %s)", cached, soc));
        return cached;
    }

    /** The marketing name for {@code soc}, or null when no chip table could be read. */
    @Nullable
    private static String lookup(String soc) {
        try {
            var res = QcomChipName.apkResources();
            if (res != null) return new QcomChipName(res).lookupChipName(soc);
        } catch (Throwable t) {
            Log.w(TAG, "chip table from the APK failed", t);
        }
        try {
            var sys = DaemonSystemContext.get();
            if (sys != null) {
                var appCtx = sys.createPackageContext(BuildConfig.APPLICATION_ID, 0);
                return new QcomChipName(appCtx).lookupChipName(soc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "chip table from the package context failed", t);
        }
        return null;
    }
}
