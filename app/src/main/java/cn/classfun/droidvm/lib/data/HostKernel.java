// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.data;

import static cn.classfun.droidvm.lib.utils.RunUtils.runList;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * Which kernel this phone is running, to the major.minor that behaviour actually turns on.
 *
 * <p>The GKI series is the unit several things here are decided by, because it is the unit the
 * vendor branches are cut on: a Gunyah resource manager, a CMA redirect, a driver's page-list
 * allocation all behave one way on 6.1 and another on 6.6. The patch level below it never matters
 * to any of them, so it is dropped rather than compared.</p>
 *
 * <p>Matched as a whole token and not by prefix. A {@code startsWith("6.1")} says yes to a 6.12
 * kernel, which is a different series with the opposite behaviour in at least one of the places
 * this is asked -- the same trap {@code KernelModuleManager} documents for its KMI directories.</p>
 *
 * <p>Runs {@code uname}, so not on the main thread. Cached for the life of the process; the kernel
 * does not change under a running app.</p>
 */
public final class HostKernel {
    private static final Pattern MAJOR_MINOR = Pattern.compile("^(\\d+\\.\\d+)");
    /** The 6.1 GKI. Named because several rules key off it and a bare "6.1" reads as nothing. */
    public static final String GKI_6_1 = "6.1";

    @Nullable
    private static volatile String cached;

    private HostKernel() {
    }

    /**
     * The running kernel's {@code major.minor}, or null when {@code uname} could not be read.
     *
     * <p>Null is "we do not know", and every caller has to treat it as such rather than as "not
     * that version": the rules built on this are about a kernel that cannot do something, and
     * guessing wrong in that direction turns a warning into a VM that does not start.</p>
     */
    @Nullable
    public static String majorMinor() {
        var known = cached;
        if (known != null) return known;
        String release;
        try {
            release = runList("uname", "-r").getOutString().trim();
        } catch (Exception e) {
            return null;
        }
        var parsed = majorMinorOf(release);
        cached = parsed;
        return parsed;
    }

    /** {@link #majorMinor} for a {@code uname -r} string already in hand. Pure. */
    @Nullable
    public static String majorMinorOf(@Nullable String unameRelease) {
        if (unameRelease == null) return null;
        var m = MAJOR_MINOR.matcher(unameRelease.trim());
        return m.find() ? m.group(1) : null;
    }
}
