package cn.classfun.droidvm.ui.hugepage;

import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellReadFile;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.StringUtils.pathJoin;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;

/**
 * Shared huge-page data layer for the status ({@link HugePageActivity}) and
 * process ({@link HugePageProcessActivity}) screens.
 *
 * <p>Both screens run a per-second refresh loop, so this class caches the three
 * expensive sources with short TTLs so a refresh no longer re-probes root sysfs,
 * blocks on the daemon, or re-scans {@code /proc} every single tick:
 * <ul>
 *   <li><b>capabilities</b> — one batched root probe of the module's sysfs knobs,
 *       resolved at most once per {@link #CAPS_TTL_MS} (a static property of the
 *       loaded {@code .ko}, not something to re-derive every tick);</li>
 *   <li><b>VM names</b> — the daemon {@code vm_list} pid&rarr;name map, fetched at
 *       most once per {@link #VM_TTL_MS} and reused (last-known) on a slow daemon
 *       so labels never blank to raw pids;</li>
 *   <li><b>pool_target</b> — the read-only v6 grow target, read once and cached.</li>
 * </ul>
 * Each screen owns its own instance (separate caches); everything here is
 * context-free (pure data), so it never holds an Activity reference.
 */
final class HugePageModel {
    static final String SYSFS_BASE = "/sys/module/gh_hugepage_reserve";
    static final String SYSFS_PARAMS = pathJoin(SYSFS_BASE, "parameters");
    static final String SYSFS_OWNERS = pathJoin(SYSFS_PARAMS, "vm_owners");
    /** A THP huge page is 2 MiB, i.e. 2048 KiB. */
    static final long KB_PER_PAGE = 2048;
    static final long PAGE_BYTES = KB_PER_PAGE * 1024;

    /** meminfo/smaps values are "<n> kB"; strip everything but the digits once. */
    private static final Pattern NON_DIGITS = Pattern.compile("[^0-9]");

    private static final long CAPS_TTL_MS = 3000;
    private static final long VM_TTL_MS = 4000;
    private static final long DAEMON_TIMEOUT_S = 2;

    /* ================================================================== */
    /*  Module capabilities                                               */
    /* ================================================================== */

    /** Immutable snapshot of what the currently-loaded module supports. */
    static final class Caps {
        /** {@code /sys/module/gh_hugepage_reserve} exists (module is loaded). */
        final boolean loaded;
        /** served_summary present: per-VM KO attribution API (v7). */
        final boolean koAttribution;
        /** pool_want knob present (v7 resizable target). */
        final boolean hasPoolWant;
        /** acquire knob present (v7 async grow). */
        final boolean hasAcquire;
        /** manual_refill knob present (v6 refill). */
        final boolean hasManualRefill;

        Caps(boolean loaded, boolean koAttribution, boolean hasPoolWant,
             boolean hasAcquire, boolean hasManualRefill) {
            this.loaded = loaded;
            this.koAttribution = koAttribution;
            this.hasPoolWant = hasPoolWant;
            this.hasAcquire = hasAcquire;
            this.hasManualRefill = hasManualRefill;
        }
    }

    @Nullable
    private volatile Caps capsCache;
    private volatile long capsAtMs = Long.MIN_VALUE;

    /**
     * Module capabilities, cached for {@link #CAPS_TTL_MS}. Resolving this from a
     * cache (instead of re-probing every refresh) is what stops a single flaky
     * root {@code test -e} from flapping the whole view between KO and scan mode.
     * Pass {@code force = true} right after an action that changes the module
     * (load / unload / soft-disable) to re-probe immediately.
     */
    @NonNull
    Caps caps(boolean force) {
        long now = SystemClock.elapsedRealtime();
        var c = capsCache;
        if (!force && c != null && now - capsAtMs < CAPS_TTL_MS) return c;
        c = probeCaps();
        capsCache = c;
        capsAtMs = now;
        return c;
    }

    /**
     * One batched root round-trip that tests every knob at once (5 {@code test -e}
     * in a single {@code run}, instead of one exec per knob). If it comes back
     * "not loaded" we retry once, since a lone root {@code test -e} can flake
     * under load and we don't want to spuriously report the module gone.
     */
    @NonNull
    private Caps probeCaps() {
        var caps = probeCapsOnce();
        if (!caps.loaded) caps = probeCapsOnce();   // absorb a flaky probe
        return caps;
    }

    @NonNull
    private Caps probeCapsOnce() {
        String raw;
        try {
            raw = run(
                "for f in base served_summary pool_want acquire manual_refill; do "
                    + "if [ \"$f\" = base ]; then t=%1$s; else t=%2$s/$f; fi; "
                    + "[ -e \"$t\" ] && echo 1 || echo 0; done",
                SYSFS_BASE, SYSFS_PARAMS
            ).getOutString();
        } catch (Exception e) {
            raw = "";
        }
        var f = raw.split("\n");
        boolean loaded = flag(f, 0);
        boolean served = flag(f, 1);
        return new Caps(loaded, loaded && served, flag(f, 2), flag(f, 3), flag(f, 4));
    }

    private static boolean flag(@NonNull String[] lines, int i) {
        return i < lines.length && "1".equals(lines[i].trim());
    }

    /* ================================================================== */
    /*  VM name resolution (daemon vm_list)                               */
    /* ================================================================== */

    @NonNull
    private volatile Map<Integer, String> vmNames = new LinkedHashMap<>();
    private volatile long vmNamesAtMs = Long.MIN_VALUE;
    private volatile boolean vmNamesInit = false;

    /**
     * Best-effort pid&rarr;VM-name map from the daemon, cached for
     * {@link #VM_TTL_MS}. Within the TTL the cached map is returned without
     * contacting the daemon, so the refresh loop no longer blocks up to 2s every
     * tick. On a fetch that times out the last known map is kept (labels stay
     * stable instead of blanking to raw pids). Pass {@code force = true} to
     * bypass the cache (e.g. right after killing a process).
     */
    @NonNull
    Map<Integer, String> vmNames(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && vmNamesInit && now - vmNamesAtMs < VM_TTL_MS) return vmNames;
        var fresh = fetchVmMap();
        if (fresh != null) {            // null == timed out / error: keep last known
            vmNames = fresh;
            vmNamesAtMs = now;
            vmNamesInit = true;
        }
        return vmNames;
    }

    /**
     * One blocking daemon {@code vm_list} round-trip (bounded to
     * {@link #DAEMON_TIMEOUT_S}). Returns {@code null} on timeout/error so the
     * caller can distinguish "no answer" from a genuine empty VM list.
     *
     * <p>The map is published through an {@link AtomicReference} set inside the
     * callback, so the caller either sees a fully-built map or {@code null} —
     * never a half-populated one, even when the response lands right at the
     * timeout boundary.
     */
    @Nullable
    private Map<Integer, String> fetchVmMap() {
        var result = new AtomicReference<Map<Integer, String>>(null);
        var latch = new CountDownLatch(1);
        DaemonConnection.getInstance().buildRequest("vm_list")
            .onResponse(resp -> {
                try {
                    var map = new LinkedHashMap<Integer, String>();
                    var arr = resp.optJSONArray("data");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            var obj = arr.optJSONObject(i);
                            if (obj == null) continue;
                            int pid = obj.optInt("pid", -1);
                            var name = obj.optString("name", null);
                            if (pid > 0 && name != null) map.put(pid, name);
                        }
                    }
                    result.set(map);
                } finally {
                    latch.countDown();
                }
            })
            .onError(e -> latch.countDown())
            .invoke();
        try {
            //noinspection ResultOfMethodCallIgnored
            latch.await(DAEMON_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return result.get();
    }

    /* ================================================================== */
    /*  /proc scanning                                                    */
    /* ================================================================== */

    /** Live /proc state for a pid: run state, THP occupancy, liveness. */
    static final class ProcInfo {
        final char state;
        /** AnonHugePages + ShmemPmdMapped in KiB, or -1 if unknown. */
        final long thpKb;
        final boolean alive;

        ProcInfo(char state, long thpKb, boolean alive) {
            this.state = state;
            this.thpKb = thpKb;
            this.alive = alive;
        }
    }

    /**
     * Batch-read live /proc state (R/S/D + liveness) and THP occupancy
     * (AnonHugePages + ShmemPmdMapped) for the given pids in a single root call.
     */
    @NonNull
    Map<Integer, ProcInfo> readProcInfo(@NonNull Collection<Integer> pids) {
        var map = new LinkedHashMap<Integer, ProcInfo>();
        if (pids.isEmpty()) return map;
        var pidList = new StringBuilder();
        for (var pid : pids) {
            if (pidList.length() > 0) pidList.append(' ');
            pidList.append(pid);
        }
        String raw;
        try {
            raw = run(
                "for p in %s; do echo \"@@@ $p\"; "
                    + "grep -E '^State:' /proc/$p/status 2>/dev/null; "
                    + "grep -E '^AnonHugePages:|^ShmemPmdMapped:' "
                    + "/proc/$p/smaps_rollup 2>/dev/null; "
                    + "done",
                pidList.toString()
            ).getOutString();
        } catch (Exception e) {
            return map;
        }
        int curPid = -1;
        char state = '?';
        long thp = -1;
        boolean alive = false;
        for (var line : raw.split("\n")) {
            if (line.startsWith("@@@ ")) {
                if (curPid > 0) map.put(curPid, new ProcInfo(state, thp, alive));
                try {
                    curPid = Integer.parseInt(line.substring(4).trim());
                } catch (NumberFormatException e) {
                    curPid = -1;
                }
                state = '?';
                thp = -1;
                alive = false;
            } else if (line.startsWith("State:")) {
                var v = line.substring(6).trim();
                if (!v.isEmpty()) state = v.charAt(0);
                alive = true;   // status readable -> process exists
            } else if (line.startsWith("AnonHugePages:")
                || line.startsWith("ShmemPmdMapped:")) {
                var digits = NON_DIGITS.matcher(line).replaceAll("");
                if (!digits.isEmpty()) {
                    try {
                        thp = (thp < 0 ? 0 : thp) + Long.parseLong(digits);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (curPid > 0) map.put(curPid, new ProcInfo(state, thp, alive));
        return map;
    }

    /**
     * Per-VM THP occupancy in <b>pages</b> for the given pids, as {pid, pages}
     * rows, dropping any VM with under one full huge page. Used by the status
     * screen's scan-fallback bar; a thin wrapper over {@link #readProcInfo}.
     */
    @NonNull
    List<long[]> scanThpPages(@NonNull Collection<Integer> pids) {
        var result = new ArrayList<long[]>();
        var procInfo = readProcInfo(pids);
        for (var pid : pids) {
            var info = procInfo.get(pid);
            if (info == null || info.thpKb <= 0) continue;
            long pages = info.thpKb / KB_PER_PAGE;
            if (pages > 0) result.add(new long[]{pid, pages});
        }
        return result;
    }

    /* ================================================================== */
    /*  Pool stats                                                        */
    /* ================================================================== */

    private volatile long poolTargetCache = -1;
    private volatile long poolTargetAtMs = Long.MIN_VALUE;

    /**
     * v6 grow target from the read-only {@code pool_target} param, cached with a
     * short TTL (it is static until the module is reloaded / the app restarts).
     * Returns -1 if unavailable. This is what stops the v6 refresh loop from
     * re-{@code cat}-ing the same static file every tick.
     */
    private long poolTarget() {
        long now = SystemClock.elapsedRealtime();
        if (poolTargetAtMs != Long.MIN_VALUE && now - poolTargetAtMs < CAPS_TTL_MS) {
            return poolTargetCache;
        }
        long v = -1;
        try {
            var t = shellReadFile(pathJoin(SYSFS_PARAMS, "pool_target")).trim();
            if (!t.isEmpty()) v = Long.parseLong(t);
        } catch (Exception ignored) {
        }
        poolTargetCache = v;
        poolTargetAtMs = now;
        return v;
    }

    /**
     * Read the pool counters from {@code refill_stat}.
     * Returns {@code {total, avail, want, acquiring, served}} in pages, or
     * {@code null} if the module isn't loaded / stats are unreadable. {@code want}
     * falls back to v6's cached {@code pool_target}, then to capacity.
     */
    @Nullable
    long[] readPoolPages() {
        try {
            if (!shellCheckExists(SYSFS_BASE)) return null;
            var raw = shellReadFile(pathJoin(SYSFS_PARAMS, "refill_stat"));
            long total = -1, avail = 0, want = -1, acquiring = 0, served = 0;
            for (var line : raw.split("\n")) {
                line = line.trim();
                if (line.startsWith("pool_total=")) {
                    total = Long.parseLong(line.substring(11).trim());
                } else if (line.startsWith("pool_avail=")) {
                    avail = Long.parseLong(line.substring(11).trim());
                } else if (line.startsWith("pool_want=")) {
                    want = Long.parseLong(line.substring(10).trim());
                } else if (line.startsWith("acquire_active=")) {
                    acquiring = Long.parseLong(line.substring(15).trim());
                } else if (line.startsWith("served=")) {
                    served = Long.parseLong(line.substring(7).trim());
                }
            }
            if (total < 0) return null;
            if (want < 0) want = poolTarget();   // v6: separate read-only knob
            if (want < 0) want = total;
            return new long[]{total, avail, want, acquiring, served};
        } catch (Exception e) {
            return null;
        }
    }
}
