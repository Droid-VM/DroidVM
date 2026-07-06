package cn.classfun.droidvm.ui.hugepage;

import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellReadFile;
import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.run.RunResult;
import cn.classfun.droidvm.lib.utils.Try;

/**
 * The single huge-page facade both screens ({@link HugePageActivity},
 * {@link HugePageProcessActivity}) talk to.
 *
 * <p>It hides the {@code gh_hugepage_reserve} module's <b>v6/v7 differences</b>:
 * callers ask for a high-level operation and never branch on version or poke raw
 * sysfs. Version is never decided ahead of time -- each mutation is a
 * <b>degradation ladder</b> that just tries the best knob and falls back
 * (e.g. the migrating {@code acquire} knob -> {@code manual_refill};
 * {@code pool_want=0} soft-disable -> {@code rmmod}). The internal {@link Try}
 * ladder is collapsed to a small {@link Result} ({@code OK / UNSUPPORTED /
 * FAILED}) for the GUI -- that is the "return an error when it can't run" contract.
 *
 * <p>Reads are cached with short TTLs so the per-second refresh loop doesn't
 * block on the daemon or re-read a static knob every tick. Each screen owns its
 * own instance (separate caches); everything here is context-free (pure data) and
 * never holds an Activity reference. Mutations do shell I/O -- call them off the UI
 * thread.
 */
final class HugePageModel {
    static final String SYSFS_BASE = "/sys/module/gh_hugepage_reserve";
    static final String SYSFS_PARAMS = pathJoin(SYSFS_BASE, "parameters");
    private static final String SYSFS_OWNERS = pathJoin(SYSFS_PARAMS, "vm_owners");
    private static final String MAGISK_BASE = "/data/adb/modules/gh-hugepage-reserve";
    private static final String MODULE_PROP = pathJoin(MAGISK_BASE, "module.prop");
    private static final String DISABLE_FILE = pathJoin(MAGISK_BASE, "disable");
    private static final String SETTINGS_PROP = pathJoin(MAGISK_BASE, "settings.prop");
    private static final String KO_PATH = pathJoin(MAGISK_BASE, "gh_hugepage_reserve.ko");
    /** A THP huge page is 2 MiB = 2048 KiB. */
    private static final long KB_PER_PAGE = 2048;
    /** Default target if settings.prop has none (pages): 1024 x 2 MB = 2 GB. */
    private static final long DEFAULT_WANT_PAGES = 1024;

    /** meminfo/smaps values are "<n> kB"; strip everything but the digits once. */
    private static final Pattern NON_DIGITS = Pattern.compile("[^0-9]");

    private static final long STAT_TTL_MS = 3000;
    private static final long VM_TTL_MS = 4000;
    private static final long DAEMON_TIMEOUT_S = 2;

    /* ================================================================== */
    /*  Public result / data types                                        */
    /* ================================================================== */

    /** Outcome of a mutation. {@code UNSUPPORTED} = the module/version can't do it. */
    enum Outcome { OK, UNSUPPORTED, FAILED }

    /** Which way a usage list was sourced: module KO attribution, or a THP scan. */
    enum Source { KO, SCAN }

    /**
     * Collapsed result of a high-level mutation: the overall outcome, which
     * concrete implementation won (or last failed), and a human reason on failure.
     */
    static final class Result {
        @NonNull final Outcome outcome;
        /** Which rung ran (e.g. "acquire", "manual_refill", "soft", "BARE"), or null. */
        @Nullable final String impl;
        /** Failure reason, or null on success. */
        @Nullable final String detail;
        /**
         * True when a <b>fallback</b> rung won: a preferred implementation was
         * tried first and failed. This replaces the old ladder's "the trace has
         * more than one element" degradation signal - {@link #impl} says which
         * fallback, {@code degraded} says that it is one.
         */
        final boolean degraded;

        private Result(@NonNull Outcome outcome, @Nullable String impl,
                       @Nullable String detail, boolean degraded) {
            this.outcome = outcome;
            this.impl = impl;
            this.detail = detail;
            this.degraded = degraded;
        }

        boolean ok() {
            return outcome == Outcome.OK;
        }

        static Result ok(@Nullable String impl) {
            return new Result(Outcome.OK, impl, null, false);
        }

        static Result ok(@Nullable String impl, boolean degraded) {
            return new Result(Outcome.OK, impl, null, degraded);
        }

        static Result failed(@Nullable String impl, @Nullable String detail) {
            return new Result(Outcome.FAILED, impl, detail, false);
        }

        static Result unsupported(@Nullable String detail) {
            return new Result(Outcome.UNSUPPORTED, null, detail, false);
        }
    }

    /** A per-VM usage list plus which source produced it. */
    static final class Usage {
        @NonNull final List<UsageEntry> entries;
        @NonNull final Source source;

        Usage(@NonNull List<UsageEntry> entries, @NonNull Source source) {
            this.entries = entries;
            this.source = source;
        }
    }

    /** One attributed process: pid, name, huge pages, and live /proc state. */
    static final class UsageEntry {
        final int pid;
        /** Owner comm (KO) or VM name (scan). */
        @NonNull final String comm;
        /** Huge pages attributed: reconciled served count (KO) or THP occupancy (scan). */
        final long pages;
        /** /proc run state (R/S/D/...), or '?' if unknown. */
        final char state;
        final boolean alive;

        UsageEntry(int pid, @NonNull String comm, long pages, char state, boolean alive) {
            this.pid = pid;
            this.comm = comm;
            this.pages = pages;
            this.state = state;
            this.alive = alive;
        }
    }

    /**
     * Version-unified snapshot of module + pool state. {@code targetIdeal} is the
     * one "target" concept across versions: v7's {@code pool_want}, else v6's
     * read-only {@code pool_target}, else current capacity. {@code deficit} is what
     * is still missing toward it ({@code targetIdeal - free - lent}); the acquire
     * buttons are usable exactly when {@code loaded && deficit > 0}.
     */
    static final class Snapshot {
        final boolean installed;      // Magisk module files present
        final boolean loaded;         // insmod'd (sysfs node exists)
        final boolean statsOk;        // refill_stat was readable (false on a transient failure)
        final boolean bootEnabled;    // will load next boot (no Magisk disable file)
        final long targetIdeal;       // unified target (want): pool_want ?? pool_target ?? built
        final long built;             // pool_total (capacity assembled)
        final long free;              // pool_avail (in the pool now)
        final long lent;              // served (out to VMs); 0 if the module can't report it (v6)
        final long deficit;           // max(0, targetIdeal - free - lent)
        final boolean acquiring;      // an acquire worker is running
        final int acquireMode;        // which mode (1/2/3), or -1 if the module can't report it
        final boolean hasPoolWant;    // pool_want knob reported (v7 runtime-resizable)
        final boolean softDisabled;   // v7 pool_want <= 1 (reserve released, module stays loaded)
        // Raw display strings from refill_stat (pass-through, "-" when absent).
        @NonNull final String state;
        @NonNull final String totalServed;
        @NonNull final String totalRefilled;
        @NonNull final String activeVms;
        @NonNull final String acquireStopReason;   // why the last acquire stopped ("-" if unreported)

        private Snapshot(boolean installed, boolean loaded, boolean statsOk, boolean bootEnabled,
                         long targetIdeal, long built, long free, long lent, long deficit,
                         boolean acquiring, int acquireMode, boolean hasPoolWant,
                         boolean softDisabled, @NonNull String state, @NonNull String totalServed,
                         @NonNull String totalRefilled, @NonNull String activeVms,
                         @NonNull String acquireStopReason) {
            this.installed = installed;
            this.loaded = loaded;
            this.statsOk = statsOk;
            this.bootEnabled = bootEnabled;
            this.targetIdeal = targetIdeal;
            this.built = built;
            this.free = free;
            this.lent = lent;
            this.deficit = deficit;
            this.acquiring = acquiring;
            this.acquireMode = acquireMode;
            this.hasPoolWant = hasPoolWant;
            this.softDisabled = softDisabled;
            this.state = state;
            this.totalServed = totalServed;
            this.totalRefilled = totalRefilled;
            this.activeVms = activeVms;
            this.acquireStopReason = acquireStopReason;
        }
    }

    /* ================================================================== */
    /*  Public high-level API                                             */
    /* ================================================================== */

    /**
     * Version-unified module + pool snapshot. Never throws; when the module isn't
     * loaded the pool fields are zero and {@code loaded == false}.
     */
    @NonNull
    Snapshot state() {
        boolean installed = existsSticky(MODULE_PROP);
        boolean bootEnabled = !shellCheckExists(DISABLE_FILE);
        boolean loaded = existsSticky(SYSFS_BASE);
        if (!loaded) {
            return new Snapshot(installed, false, false, bootEnabled,
                0, 0, 0, 0, 0, false, -1, false, false, "-", "-", "-", "-", "-");
        }
        var s = parseProp(safeRead(pathJoin(SYSFS_PARAMS, "refill_stat")));
        boolean statsOk = !s.isEmpty();   // empty == the read failed transiently
        boolean hasWant = s.containsKey("pool_want");
        long built = getLong(s, "pool_total", 0);
        long free = getLong(s, "pool_avail", 0);
        long lent = getLong(s, "served", 0);
        long rawWant = getLong(s, "pool_want", -1);
        long want = rawWant;
        if (want < 0) want = poolTarget();   // v6: separate read-only knob
        if (want < 0) want = built;           // last resort: current capacity
        long deficit = Math.max(0, want - free - lent);
        boolean acquiring = "1".equals(s.get("acquire_active"));
        int mode = (int) getLong(s, "acquire_mode", -1);
        boolean softDisabled = hasWant && rawWant <= 1;
        return new Snapshot(installed, true, statsOk, bootEnabled,
            want, built, free, lent, deficit, acquiring, mode, hasWant, softDisabled,
            s.getOrDefault("state", "-"), s.getOrDefault("total_served", "-"),
            s.getOrDefault("total_refilled", "-"), s.getOrDefault("active_vms", "-"),
            s.getOrDefault("acquire_stop_reason", "-"));
    }

    /**
     * Whether an acquire worker is currently running. Reads only {@code refill_stat}
     * (no module / boot-file probing), so it is cheap enough for the tight poll
     * loops where a full {@link #state()} every 500 ms would be wasteful.
     */
    boolean acquiring() {
        return "1".equals(parseProp(safeRead(
            pathJoin(SYSFS_PARAMS, "refill_stat"))).get("acquire_active"));
    }

    /**
     * Per-VM huge-page usage. Prefers KO attribution (the module's reconciled
     * {@code served_summary} + {@code vm_owners}); degrades to a THP scan of the
     * daemon's running VMs. Pass a {@code pref} to pin a source (the UI lets the
     * user choose), or {@code null} to walk the ladder.
     */
    @NonNull
    Usage usage(@Nullable Source pref) {
        var log = new ArrayList<Try<Source, List<UsageEntry>>>();
        if (rung(pref, log, Source.KO, this::usageFromKo)) return usageOf(log);
        rung(pref, log, Source.SCAN, this::usageFromScan);
        return usageOf(log);
    }

    /** Whether KO attribution is currently available (the {@code served_summary} knob exists). */
    boolean koAvailable() {
        return existsSticky(pathJoin(SYSFS_PARAMS, "served_summary"));
    }

    /**
     * Save a new pool target of {@code pages}. Persists it to settings.prop (for
     * the next boot) and applies it to the running pool if the live {@code pool_want}
     * knob exists; on v6 (read-only {@code pool_target}) only the persist takes
     * effect. Does <b>not</b> fire an acquire -- the caller drives that from the
     * resulting {@link Snapshot#deficit}.
     */
    @NonNull
    Result saveSize(long pages) {
        var persisted = writeSettings(pages);
        if (!persisted.ok()) return Result.failed("settings", persisted.error);
        // Best-effort live apply; the impl reports whether it actually took effect
        // on the running pool ("pool_want") or only persisted for next boot
        // ("settings" - v6's read-only target, or a rejected write).
        var live = writeKnob("pool_want", Long.toString(pages));
        // Degraded when the live write didn't land (v6 read-only target, or a
        // rejected write): only the next-boot persist took effect.
        return Result.ok(live.ok() ? "pool_want" : "settings", !live.ok());
    }

    /**
     * Bring the running pool up (true) or down (false), choosing the deepest action
     * the module supports:
     * <ul>
     *   <li><b>enable</b>: not loaded -> insmod (targeting the saved size); loaded ->
     *       restore the target by writing the saved {@code pool_want} (v6 loaded is
     *       already active, so this is a no-op there);</li>
     *   <li><b>disable</b>: try {@code pool_want=0} (soft -- frees the reserve but
     *       keeps per-VM tracking); if that write can't happen (v6 has no such knob)
     *       fall back to {@code rmmod}.</li>
     * </ul>
     */
    @NonNull
    Result setRuntimeEnabled(boolean on) {
        if (on) {
            if (!existsSticky(SYSFS_BASE)) {
                return collapse(loadLadder(savedWantPages()));   // insmod
            }
            // Loaded: restore the target. Succeeds on v7; on v6 there's no pool_want
            // and a loaded module is already active, so treat a failed write as "already enabled".
            var t = writeKnob("pool_want", Long.toString(savedWantPages()));
            return t.ok() ? Result.ok("soft-enable") : Result.ok("already-active");
        }
        // Disable. Pick soft-vs-rmmod by *capability* (does the pool_want knob
        // exist?), NOT by write success: a v7 soft-disable can also fail
        // transiently (the module refuses a resize mid-acquire, or a root hiccup),
        // and escalating that to rmmod would destroy the per-VM tracking that
        // soft-disable exists to preserve. So v7 only ever soft-disables (a failed
        // write is reported as FAILED to retry); only v6 (no knob) falls to rmmod.
        if (existsSticky(pathJoin(SYSFS_PARAMS, "pool_want"))) {
            var soft = writeKnob("pool_want", "0");
            return soft.ok() ? Result.ok("soft") : Result.failed("soft", soft.error);
        }
        return collapse(unloadLadder());
    }

    /**
     * Enable (true) or disable (false) the module for the <b>next boot</b> via the
     * Magisk {@code disable} marker file. Independent of the running module.
     */
    @NonNull
    Result setBootEnabled(boolean on) {
        var r = on
            ? runList("rm", "-f", DISABLE_FILE)
            : run("touch %s", DISABLE_FILE);
        return r.isSuccess()
            ? Result.ok(on ? "enable-boot" : "disable-boot")
            : Result.failed("disable-file", reason(r, "disable"));
    }

    /**
     * Fill the pool toward its target with acquire algorithm {@code mode}
     * (1 = original scan, 2 = migrate + system reclaim, 3 = migrate + per-block
     * evict). Prefers the migrating {@code acquire} knob; on any failure (v6 has no
     * such knob, or -ENOSYS where this kernel can't run that mode) degrades to
     * {@code manual_refill}. Fire-and-return -- poll {@link #state()} for completion.
     */
    @NonNull
    Result acquire(int mode) {
        // Drop the whole VM page cache + reclaimable slab first so more 2 MB windows
        // are already free (or trivially assemblable) before the sweep - the module's
        // own acquire_drop_slab covers slab only, not the page cache. Best-effort: a
        // failed drop doesn't block the acquire.
        run("echo 3 > /proc/sys/vm/drop_caches");
        // Walk the requested mode down to the gentlest migrating mode: the module
        // returns -ENOSYS for a mode whose symbols didn't resolve (e.g. v3 on a
        // kernel lacking the folio/reclaim symbols, or v2 with no alloc_contig_range),
        // so try mode -> ... -> 1 before giving up on migration and falling back to a
        // plain manual_refill. Any acquire mode is pollable via acquire_active, so the
        // winning impl is uniformly "acquire" (the running mode surfaces in acquire_mode).
        for (int m = mode; m >= 1; m--) {
            if (writeKnob("acquire", Integer.toString(m)).ok()) {
                // impl names the actual migrating mode that ran ("v1".."v3");
                // degraded when it's below what was asked for.
                return Result.ok(fmt("v%d", m), m != mode);
            }
        }
        var refill = writeKnob("manual_refill", "1");
        return refill.ok()
            ? Result.ok("manual_refill", true)
            : Result.unsupported("no acquire / manual_refill knob");
    }

    /** Interrupt a running acquire (write 0 to the knob). No worker exists on v6. */
    @NonNull
    Result stopAcquire() {
        var t = writeKnob("acquire", "0");
        return t.ok() ? Result.ok("acquire") : Result.unsupported(t.error);
    }

    /* ================================================================== */
    /*  Ladder plumbing (internal)                                        */
    /* ================================================================== */

    /** insmod ladder: both keys, then {@code pool_want=} (v7), {@code pool_target=} (v6), bare. */
    private enum LoadImpl { BOTH, POOL_WANT, POOL_TARGET, BARE }

    /** Marker for single-implementation actions. */
    private enum Only { DEFAULT }

    private static <I, V> boolean rung(
        @Nullable I only, @NonNull List<Try<I, V>> log,
        @NonNull I impl, @NonNull Supplier<Try<I, V>> action
    ) {
        if (only != null && only != impl) return false;
        var t = action.get();
        log.add(t);
        return t.ok();
    }

    /** Collapse a finished ladder trace into a {@link Result}. */
    @NonNull
    private static <I, V> Result collapse(@NonNull List<Try<I, V>> log) {
        if (log.isEmpty()) return Result.failed(null, "no attempt");
        var last = log.get(log.size() - 1);
        boolean degraded = log.size() > 1;   // earlier rungs failed before this won
        return last.ok()
            ? Result.ok(String.valueOf(last.impl), degraded)
            : Result.failed(String.valueOf(last.impl), last.error);
    }

    @NonNull
    private static Usage usageOf(@NonNull List<Try<Source, List<UsageEntry>>> log) {
        var last = log.isEmpty() ? null : log.get(log.size() - 1);
        if (last != null && last.ok() && last.value != null) {
            return new Usage(last.value, last.impl);
        }
        return new Usage(new ArrayList<>(), Source.SCAN);
    }

    /* ================================================================== */
    /*  Module lifecycle ladders                                          */
    /* ================================================================== */

    @NonNull
    private List<Try<LoadImpl, Void>> loadLadder(long pages) {
        var log = new ArrayList<Try<LoadImpl, Void>>();
        var w = fmt("pool_want=\"%d\"", pages);
        var t = fmt("pool_target=\"%d\"", pages);
        // Pass BOTH size params first. A lenient kernel silently ignores the param
        // the module doesn't have, so it still gets the right size via the one it
        // does - pool_want (v7) or pool_target (v6). This is essential: on a v6
        // module `pool_want=` alone loads fine (returns 0) but is ignored, leaving
        // the pool at its compiled 1024 default - the ladder must not stop there.
        // Strict kernels reject the unknown param, so fall back to each key alone,
        // then a bare (default-size) load.
        if (rung(null, log, LoadImpl.BOTH,
            () -> insmod(LoadImpl.BOTH, fmt("%s %s", w, t)))) return log;
        if (rung(null, log, LoadImpl.POOL_WANT,
            () -> insmod(LoadImpl.POOL_WANT, w))) return log;
        if (rung(null, log, LoadImpl.POOL_TARGET,
            () -> insmod(LoadImpl.POOL_TARGET, t))) return log;
        rung(null, log, LoadImpl.BARE, () -> insmod(LoadImpl.BARE, null));
        return log;
    }

    private static Try<LoadImpl, Void> insmod(@NonNull LoadImpl impl, @Nullable String arg) {
        var r = (arg == null)
            ? run("insmod %s", escapedString(KO_PATH))
            : run("insmod %s %s", escapedString(KO_PATH), arg);
        return r.isSuccess() ? Try.ok(impl, null) : Try.fail(impl, reason(r, "insmod"));
    }

    @NonNull
    private List<Try<Only, Void>> unloadLadder() {
        var log = new ArrayList<Try<Only, Void>>();
        rung(null, log, Only.DEFAULT, () -> {
            var r = runList("rmmod", "gh_hugepage_reserve");
            return r.isSuccess()
                ? Try.<Only, Void>ok(Only.DEFAULT, null)
                : Try.<Only, Void>fail(Only.DEFAULT, reason(r, "rmmod"));
        });
        return log;
    }

    /** The configured target from settings.prop (pool_want, legacy pool_target). */
    private long savedWantPages() {
        if (existsSticky(SETTINGS_PROP)) {
            var s = parseProp(safeRead(SETTINGS_PROP));
            var v = s.getOrDefault("pool_want", s.get("pool_target"));
            if (v != null) try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_WANT_PAGES;
    }

    /* ================================================================== */
    /*  Shell helpers                                                     */
    /* ================================================================== */

    /** Write a knob; returns a bare success/fail Try (single-impl call sites). */
    private static Try<Only, Void> writeKnob(@NonNull String knob, @NonNull String value) {
        return writeKnobTry(Only.DEFAULT, knob, value);
    }

    /** Write a knob under the given impl marker; the Try carries the errno reason. */
    private static <I> Try<I, Void> writeKnobTry(
        @NonNull I impl, @NonNull String knob, @NonNull String value
    ) {
        var r = run("echo %s > %s/%s", value, SYSFS_PARAMS, knob);
        return r.isSuccess()
            ? Try.<I, Void>ok(impl, null)
            : Try.<I, Void>fail(impl, reason(r, knob));
    }

    /** Persist the target to settings.prop under both keys (whichever loader reads). */
    private static Try<Only, Void> writeSettings(long pages) {
        var a = run("echo 'pool_want=%s' > %s", pages, SETTINGS_PROP);
        var b = run("echo 'pool_target=%s' >> %s", pages, SETTINGS_PROP);
        return (a.isSuccess() && b.isSuccess())
            ? Try.<Only, Void>ok(Only.DEFAULT, null)
            : Try.<Only, Void>fail(Only.DEFAULT, reason(a.isSuccess() ? b : a, "settings.prop"));
    }

    @NonNull
    private static String reason(@NonNull RunResult r, @NonNull String what) {
        var msg = r.getErrString().trim();
        if (msg.isEmpty()) msg = r.getOutString().trim();
        if (msg.isEmpty()) msg = fmt("exit %d", r.getCode());
        return fmt("%s: %s", what, msg);
    }

    /**
     * {@code test -e} that retries once on a negative. A lone root {@code test -e}
     * can flake under load; retrying only the negative absorbs that transient miss
     * without caching a possibly-stale positive.
     */
    private static boolean existsSticky(@NonNull String path) {
        return shellCheckExists(path) || shellCheckExists(path);
    }

    @NonNull
    private static String safeRead(@NonNull String path) {
        try {
            return shellReadFile(path);
        } catch (Exception e) {
            return "";
        }
    }

    @NonNull
    private static Map<String, String> parseProp(@NonNull String raw) {
        var map = new LinkedHashMap<String, String>();
        for (var line : raw.split("\n")) {
            var parts = line.split("=", 2);
            if (parts.length == 2) map.put(parts[0].trim(), parts[1].trim());
        }
        return map;
    }

    private static long getLong(@NonNull Map<String, String> m, @NonNull String key, long def) {
        var v = m.get(key);
        if (v == null) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /* ================================================================== */
    /*  Pool target (v6 read-only knob, cached)                           */
    /* ================================================================== */

    private volatile long poolTargetCache = -1;
    private volatile long poolTargetAtMs = Long.MIN_VALUE;

    /**
     * v6 grow target from the read-only {@code pool_target} param, cached with a
     * short TTL (static until the module is reloaded). Returns -1 if unavailable.
     */
    private long poolTarget() {
        long now = SystemClock.elapsedRealtime();
        if (poolTargetAtMs != Long.MIN_VALUE && now - poolTargetAtMs < STAT_TTL_MS) {
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

    /* ================================================================== */
    /*  Usage sources (KO attribution / THP scan)                         */
    /* ================================================================== */

    private Try<Source, List<UsageEntry>> usageFromKo() {
        if (!koAvailable()) return Try.fail(Source.KO, "KO attribution knobs absent");
        try {
            // Reconcile, then read reconciled per-owner live page counts.
            run("echo 1 > %s/reconcile", SYSFS_PARAMS);
            var livePages = new LinkedHashMap<Integer, Long>();
            for (var ln : safeRead(pathJoin(SYSFS_PARAMS, "served_summary")).split("\n")) {
                ln = ln.trim();
                if (!ln.startsWith("owner ")) continue;
                int pid = -1;
                long pages = 0;
                for (var tok : ln.split("\\s+")) {
                    if (tok.startsWith("pid=")) pid = parseIntSafe(tok.substring(4));
                    else if (tok.startsWith("pages=")) pages = parseLongSafe(tok.substring(6));
                }
                if (pid > 0) livePages.put(pid, pages);
            }
            var owners = parseOwners(safeRead(SYSFS_OWNERS));
            var pids = new ArrayList<Integer>();
            for (var o : owners) pids.add(o.pid);
            var procInfo = readProcInfo(pids);

            var out = new ArrayList<UsageEntry>();
            for (var o : owners) {
                var info = procInfo.get(o.pid);
                char state = info != null ? info.state : '?';
                boolean alive = info != null && info.alive;
                // Occupancy = reconciled served count (not the /proc scan; /proc is
                // only used for run state + liveness).
                Long served = livePages.get(o.pid);
                out.add(new UsageEntry(o.pid, o.comm, served != null ? served : 0L, state, alive));
            }
            out.sort((a, b) -> Long.compare(b.pages, a.pages));
            return Try.ok(Source.KO, out);
        } catch (Exception e) {
            return Try.fail(Source.KO, fmt("KO read: %s", e.getMessage()));
        }
    }

    private Try<Source, List<UsageEntry>> usageFromScan() {
        // Running VMs (pid -> name) come from the daemon; scan only those, not all
        // of /proc (system-wide THP is noise unrelated to the pool).
        var vmMap = vmNames(false);
        if (vmMap.isEmpty()) return Try.fail(Source.SCAN, "no running VM pids");
        var procInfo = readProcInfo(vmMap.keySet());

        var out = new ArrayList<UsageEntry>();
        for (var e : vmMap.entrySet()) {
            int pid = e.getKey();
            var info = procInfo.get(pid);
            char state = info != null ? info.state : '?';
            long thpKb = info != null ? info.thpKb : -1;
            long pages = thpKb > 0 ? thpKb / KB_PER_PAGE : 0;
            boolean alive = info != null && info.alive;
            out.add(new UsageEntry(pid, e.getValue(), pages, state, alive));
        }
        out.sort((a, b) -> Long.compare(b.pages, a.pages));
        return Try.ok(Source.SCAN, out);
    }

    /* ---- vm_owners parsing ("pid=<n> served=<n> comm=<rest>") ---- */

    private static final class Owner {
        final int pid;
        @NonNull final String comm;

        Owner(int pid, @NonNull String comm) {
            this.pid = pid;
            this.comm = comm;
        }
    }

    @NonNull
    private static List<Owner> parseOwners(@NonNull String raw) {
        var list = new ArrayList<Owner>();
        for (var line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int pid = -1;
            String comm = "?";
            int commIdx = line.indexOf("comm=");
            if (commIdx >= 0) comm = line.substring(commIdx + 5).trim();
            for (var tok : line.split("\\s+")) {
                if (tok.startsWith("pid=")) pid = parseIntSafe(tok.substring(4));
            }
            if (pid > 0) list.add(new Owner(pid, comm));
        }
        return list;
    }

    private static int parseIntSafe(@NonNull String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parseLongSafe(@NonNull String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /* ================================================================== */
    /*  VM name resolution (daemon vm_list), cached                       */
    /* ================================================================== */

    @NonNull
    private volatile Map<Integer, String> vmNames = new LinkedHashMap<>();
    private volatile long vmNamesAtMs = Long.MIN_VALUE;
    private volatile boolean vmNamesInit = false;

    /**
     * Best-effort pid&rarr;VM-name map from the daemon, cached for
     * {@link #VM_TTL_MS}. Within the TTL the cached map is returned without
     * contacting the daemon. On a timeout the last known map is kept (labels stay
     * stable instead of blanking to raw pids). Pass {@code force} to bypass the cache.
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
}
