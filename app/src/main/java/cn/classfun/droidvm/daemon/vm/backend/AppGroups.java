// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import static cn.classfun.droidvm.lib.Constants.DATA_DIR;
import static cn.classfun.droidvm.lib.utils.FileUtils.readFile;
import static cn.classfun.droidvm.lib.utils.FileUtils.writeFile;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.BuildConfig;

/**
 * The app's supplementary groups, for a device the VMM runs as the app instead of as root.
 *
 * <p>Why they are needed at all: measured on device, a process that drops to the app's uid but
 * carries no supplementary groups cannot even {@code stat()} a directory under
 * {@code /storage/emulated/0} -- traversing the MediaProvider FUSE mount needs AID_EVERYBODY
 * (9997), which every app process carries and no permission grants. The permission-derived
 * groups (ext_data_rw and friends) turned out <em>not</em> to be the ones that gate it. Rather
 * than hardcode a number whose meaning was inferred from one device, take the app's own list:
 * whatever the platform decided the app is, the file server should look the same.
 *
 * <p>Which is why this reads the running app process rather than asking PackageManager.
 * {@code getPackageGids()} knows only the permission-derived half; the framework-assigned half
 * (the everybody gid, the cache gid, the shared gid) is added by Zygote at spawn time and exists
 * assembled only in {@code /proc/<pid>/status}. The daemon runs as root, so it can read it.
 *
 * <p>And why it is cached to disk: a VM can be started over the daemon's IPC long after the UI
 * process has gone away, and there would then be nothing to read. A stale list is not a hazard
 * here -- these groups change only when the user changes a permission, and the failure mode of a
 * stale one is a shared directory that cannot see its files, not one that sees too much.
 */
public final class AppGroups {
    private static final String TAG = "AppGroups";
    private static final String CACHE_PATH = pathJoin(DATA_DIR, "run", "app-gids");

    @Nullable
    private static volatile int[] cached;

    private AppGroups() {
    }

    /**
     * The app's supplementary groups, or {@code null} if they cannot be determined.
     *
     * <p>A {@code null} is not a reason to fall back to root: a caller that asked for the app's
     * identity and cannot be given it should say so and stop, or the switch that requested the
     * drop would silently mean its opposite.
     */
    @Nullable
    public static int[] resolve(int appUid) {
        var hit = cached;
        if (hit != null) return hit;
        synchronized (AppGroups.class) {
            if (cached != null) return cached;
            var live = readFromAppProcess(appUid);
            if (live != null) {
                cached = live;
                persist(live);
                Log.i(TAG, fmt("app groups from the running app process: %s", join(live)));
                return live;
            }
            var stored = readCache();
            if (stored != null) {
                cached = stored;
                Log.i(TAG, fmt("app groups from cache (app not running): %s", join(stored)));
                return stored;
            }
        }
        Log.w(TAG, "app groups unknown: the app is not running and nothing was cached");
        return null;
    }

    /** Formats the list the way crosvm's `supp_gids=` key expects. */
    public static String join(int[] gids) {
        var sb = new StringBuilder();
        for (int i = 0; i < gids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(gids[i]);
        }
        return sb.toString();
    }

    @Nullable
    private static int[] readFromAppProcess(int appUid) {
        var proc = new File("/proc").listFiles();
        if (proc == null) return null;
        for (var entry : proc) {
            var name = entry.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            try {
                // Both checks matter: the daemon itself runs the app's code out of the app's
                // CLASSPATH, so a name match alone would happily find a process running as root.
                if (android.system.Os.stat(entry.getPath()).st_uid != appUid) continue;
                var cmdline = readFile(new File(entry, "cmdline"));
                int nul = cmdline.indexOf('\0');
                if (nul >= 0) cmdline = cmdline.substring(0, nul);
                if (!BuildConfig.APPLICATION_ID.equals(cmdline)) continue;
                var gids = parseGroups(readFile(new File(entry, "status")));
                if (gids != null) return gids;
            } catch (Throwable ignored) {
                // A pid that went away between listing and reading is ordinary, not an error.
            }
        }
        return null;
    }

    /** Pulls the {@code Groups:} line out of {@code /proc/<pid>/status}. */
    @Nullable
    private static int[] parseGroups(String status) {
        for (var line : status.split("\n")) {
            if (!line.startsWith("Groups:")) continue;
            var out = new ArrayList<Integer>();
            for (var field : line.substring("Groups:".length()).trim().split("\\s+")) {
                if (field.isEmpty()) continue;
                try {
                    out.add(Integer.parseInt(field));
                } catch (NumberFormatException ignored) {
                }
            }
            return toArray(out);
        }
        return null;
    }

    private static int[] toArray(List<Integer> list) {
        var out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private static void persist(int[] gids) {
        try {
            writeFile(CACHE_PATH, join(gids));
        } catch (Throwable t) {
            Log.w(TAG, "could not cache the app groups", t);
        }
    }

    @Nullable
    private static int[] readCache() {
        try {
            var text = readFile(CACHE_PATH).trim();
            if (text.isEmpty()) return null;
            var out = new ArrayList<Integer>();
            for (var field : text.split(",")) {
                field = field.trim();
                if (!field.isEmpty()) out.add(Integer.parseInt(field));
            }
            return out.isEmpty() ? null : toArray(out);
        } catch (Throwable t) {
            return null;
        }
    }
}
