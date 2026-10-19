// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.hugepage;

import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellReadFile;
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
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.HugePageOperationClient;
import cn.classfun.droidvm.lib.utils.Try;

/** Read facade for huge-page screens. All mutations use the coordinated native owner. */
final class HugePageModel {
    private Result operate(String kind, Object... fields) {
        var receipt = HugePageOperationClient.getInstance().execute(
            HugePageOperationClient.operation(kind, fields));
        return new Result(receipt.succeeded ? Outcome.OK : Outcome.FAILED,
            receipt.implementation, receipt.error, receipt.degraded, receipt.saved, receipt.applied);
    }

    Result saveTargets(long pages, long withCma, int acquireMode) {
        return operate("targets", "poolPages", pages, "cmaPages", withCma < 0 ? null : withCma,
            "persist", true, "acquireMode", acquireMode);
    }

    Result setCma(boolean enabled, long pages, boolean persist, @Nullable String lever) {
        return operate("cma", "enabled", enabled, "pages", pages, "persist", persist, "lever", lever);
    }

    Result saveCma(long pages, @Nullable String lever) {
        return operate("cma-save", "pages", pages, "lever", lever);
    }

    Result reconcile() {
        return operate("reconcile");
    }

    Result dismissCrash() {
        return operate("dismiss-crash");
    }

    static final String SYSFS_BASE = "/sys/module/gh_hugepage_reserve";
    static final String SYSFS_PARAMS = pathJoin(SYSFS_BASE, "parameters");
    private static final String SYSFS_OWNERS = pathJoin(SYSFS_PARAMS, "vm_owners");
    private static final String MAGISK_BASE = "/data/adb/modules/gh-hugepage-reserve";
    private static final String MODULE_PROP = pathJoin(MAGISK_BASE, "module.prop");
    private static final String DISABLE_FILE = pathJoin(MAGISK_BASE, "disable");
    private static final String SETTINGS_PROP = pathJoin(MAGISK_BASE, "settings.prop");
    /** A THP huge page is 2 MiB = 2048 KiB. */
    private static final long KB_PER_PAGE = 2048;

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
        final boolean saved;
        final boolean applied;

        private Result(@NonNull Outcome outcome, @Nullable String impl,
                       @Nullable String detail, boolean degraded) {
            this(outcome, impl, detail, degraded, false, false);
        }

        private Result(@NonNull Outcome outcome, @Nullable String impl,
                       @Nullable String detail, boolean degraded, boolean saved, boolean applied) {
            this.outcome = outcome;
            this.impl = impl;
            this.detail = detail;
            this.degraded = degraded;
            this.saved = saved;
            this.applied = applied;
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
     * read-only {@code pool_target}, else current capacity. {@code deficit} is the
     * work acquire still has - the larger of the pool shortfall and the reservoir
     * shortfall (see {@link #state()}); the acquire buttons are usable exactly when
     * {@code loaded && deficit > 0}. It is <b>not</b> the bar's waiting-to-acquire
     * block, which each screen derives from its own segments.
     */
    static final class Snapshot {
        // Field defaults double as the not-loaded snapshot (the short ctor
        // touches only installed/bootEnabled and leaves the rest as declared).
        boolean installed = false;    // Magisk module files present
        boolean loaded = false;       // insmod'd (sysfs node exists)
        boolean statsOk = false;      // refill_stat was readable (false on a transient failure)
        boolean bootEnabled = false;  // will load next boot (no Magisk disable file)
        long targetIdeal = 0;         // unified target (want): pool_want ?? pool_target ?? built
        long built = 0;               // pool_total (capacity assembled)
        long free = 0;                // pool_avail (in the pool now)
        long lent = 0;                // served (out to VMs); 0 if the module can't report it (v6)
        long deficit = 0;             // toward want-with-cma (v10 on) or targetIdeal
        boolean acquiring = false;    // an acquire worker is running
        int acquireMode = -1;         // which mode (1/2/3), or -1 if the module can't report it
        boolean hasPoolWant = false;  // pool_want knob reported (v7 runtime-resizable)
        boolean softDisabled = false; // v7 pool_want <= 1 (reserve released, module stays loaded)
        // v10 CMA reservoir (refill_stat additions); all -1 / 0 on pre-v10 modules.
        boolean hasCma = false;       // refill_stat reports pool_want_with_cma (v10 module)
        long wantWithCma = 0;         // total target incl. reservoir (pages); 0 = CMA off
        long cmaPool = 0;             // reservoir size (2 MB-page equivalents)
        long availCmaAble = -1;       // avail pages flippable as whole pageblocks; -1 unreported
        int cmaPbOrder = -1;          // pageblock order; -1 = CMA side off this boot
        // Raw display strings from refill_stat (pass-through, "-" when absent).
        @NonNull String state = "-";
        @NonNull String totalServed = "-";
        @NonNull String totalRefilled = "-";
        @NonNull String activeVms = "-";
        @NonNull String acquireStopReason = "-";   // why the last acquire stopped ("-" if unreported)

        /**
         * Not-loaded snapshot: only the install + next-boot flags are known;
         * every pool field keeps its default (the {@code loaded == false} view).
         */
        Snapshot(boolean installed, boolean bootEnabled) {
            this.installed = installed;
            this.bootEnabled = bootEnabled;
        }

        /**
         * Loaded snapshot parsed straight from a {@code refill_stat} key=value
         * map. {@code poolTargetFn} supplies v6's read-only {@code pool_target}
         * lazily - it is only consulted when {@code pool_want} is absent.
         */
        Snapshot(@NonNull Map<String, String> s, boolean installed, boolean bootEnabled,
                 @NonNull LongSupplier poolTargetFn) {
            this.installed = installed;
            this.bootEnabled = bootEnabled;
            this.loaded = true;
            this.statsOk = !s.isEmpty();   // empty == the read failed transiently
            this.hasPoolWant = s.containsKey("pool_want");
            this.built = getLong(s, "pool_total", 0);
            this.free = getLong(s, "pool_avail", 0);
            this.lent = getLong(s, "served", 0);
            long rawWant = getLong(s, "pool_want", -1);
            long want = rawWant;
            if (want < 0) want = poolTargetFn.getAsLong();   // v6: separate read-only knob
            if (want < 0) want = built;                       // last resort: current capacity
            this.targetIdeal = want;
            // v10 reservoir state; pre-v10 modules report none of these keys.
            this.hasCma = s.containsKey("pool_want_with_cma");
            this.wantWithCma = getLong(s, "pool_want_with_cma", 0);
            this.cmaPool = getLong(s, "pool_cma", 0);
            this.availCmaAble = getLong(s, "pool_avail_cma_able", -1);
            this.cmaPbOrder = (int) getLong(s, "cma_pb_order", -1);
            // What acquire still has to do, mirroring the module's own "already at
            // target" test (acquire_set): it runs when the POOL is short
            // (avail + served < pool_want) OR the RESERVOIR is short
            // (avail + served + pool_cma < pool_want_with_cma). Those are separate
            // shortfalls: raising pool_want while the reservoir already covers the
            // total leaves no total deficit, yet acquire must still stage pages in
            // from the reservoir to fill the pool. Reporting only the total would
            // grey out the acquire buttons exactly then.
            long poolDeficit = Math.max(0, want - free - lent);
            long reservoirDeficit = wantWithCma > 0
                ? Math.max(0, wantWithCma - free - lent - cmaPool) : 0;
            this.deficit = Math.max(poolDeficit, reservoirDeficit);
            this.acquiring = "1".equals(s.get("acquire_active"));
            this.acquireMode = (int) getLong(s, "acquire_mode", -1);
            this.softDisabled = hasPoolWant && rawWant <= 1;
            this.state = s.getOrDefault("state", "-");
            this.totalServed = s.getOrDefault("total_served", "-");
            this.totalRefilled = s.getOrDefault("total_refilled", "-");
            this.activeVms = s.getOrDefault("active_vms", "-");
            this.acquireStopReason = s.getOrDefault("acquire_stop_reason", "-");
        }

        /** The v10 reservoir is on right now (module tracks a with-CMA total). */
        boolean cmaActive() {
            return hasCma && wantWithCma > 0;
        }
    }

    /**
     * Reservoir occupancy snapshot from the {@code cma_usage} knob (~1 s cache in
     * the module). {@code ok == false} when the knob is absent/unreadable - the
     * caller then shows the reservoir as one undivided block. Only the occupied
     * amount is carried: the free part is derived from {@code pool_cma}.
     */
    static final class CmaUsage {
        final boolean ok;
        final long usedMb;

        private CmaUsage(boolean ok, long usedMb) {
            this.ok = ok;
            this.usedMb = usedMb;
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
        if (!loaded) return new Snapshot(installed, bootEnabled);
        var s = parseProp(safeRead(pathJoin(SYSFS_PARAMS, "refill_stat")));
        return new Snapshot(s, installed, bootEnabled, this::poolTarget);
    }

    /** Reservoir occupancy from {@code cma_usage}; {@code ok=false} when absent. */
    @NonNull
    CmaUsage cmaUsage() {
        var raw = safeRead(pathJoin(SYSFS_PARAMS, "cma_usage"));
        if (raw.trim().isEmpty()) return new CmaUsage(false, 0);
        // Tokens are key=value but not strictly one per line (blocks_* share a
        // line), so scan word-wise instead of reusing the line parser.
        var map = new LinkedHashMap<String, String>();
        for (var tok : raw.split("[\\s\\n]+")) {
            var parts = tok.split("=", 2);
            if (parts.length == 2) map.put(parts[0].trim(), parts[1].trim());
        }
        if (!map.containsKey("reservoir_mb")) return new CmaUsage(false, 0);
        return new CmaUsage(true, getLong(map, "used_mb", 0));
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

    /** The native owner performs load/restore/acquire or stop/wait/disable as one operation. */
    @NonNull
    Result setRuntimeEnabled(boolean on) {
        return operate("runtime", "enabled", on);
    }

    @NonNull
    Result unload() {
        return operate("unload");
    }

    /**
     * Enable (true) or disable (false) the module for the <b>next boot</b> via the
     * Magisk {@code disable} marker file. Independent of the running module.
     */
    @NonNull
    Result setBootEnabled(boolean on) {
        return operate("boot", "enabled", on);
    }

    /** Request one acquire from the native owner. Only it may select a supported implementation. */
    @NonNull
    Result acquire(int mode) {
        return operate("acquire", "mode", mode);
    }

    /** Stop an acquire through the same native coordinator as runtime and target edits. */
    @NonNull
    Result stopAcquire() {
        return operate("stop-acquire");
    }

    /* ================================================================== */
    /*  v11 movable->CMA levers + CMA reservoir persistence               */
    /* ================================================================== */

    /** settings.prop values recording which movable->CMA lever the user saved. */
    static final String LEVER_HOOK = "hook";
    static final String LEVER_FLAG = "flag";

    private static final String MTC_VENDER = "moveable_to_cma_vender_already_allowed";
    private static final String MTC_RESTRICT = "moveable_to_cma_restrict_cma_redirect_disabled";
    private static final String MTC_GFP_HOOK = "moveable_to_cma_gfp_cma_hook";

    /**
     * Whether the running kernel is 6.1. On 6.1 the {@code restrict_cma_redirect}
     * static key is side-effect-free, so the enable flow prefers flipping it (a
     * clean global switch); on 6.6/6.12 the same key also backs
     * {@code cma_has_pcplist()}, so the narrower gfp hook is used instead. Fails
     * safe to {@code false} (the hook path) when {@code uname} is unreadable.
     */
    boolean kernelIs61() {
        try {
            var r = runList("uname", "-r").getOutString().trim();
            return r.equals("6.1") || r.startsWith("6.1.");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Read {@code moveable_to_cma_vender_already_allowed}: {@code 1} = the
     * vendor kernel already redirects every movable allocation into CMA, so the
     * reservoir is consumable without touching either lever; {@code 0} = it does
     * not; {@code -1} = the param is absent (pre-v11 module, no lever support).
     */
    int mtcVenderAllowed() {
        return readIntParam(MTC_VENDER, -1);
    }

    /**
     * The "flag" lever: flip the kernel {@code restrict_cma_redirect} static key
     * (write 1 = open movable->CMA globally). Live only - not persisted here.
     */
    @NonNull
    Result setRestrictFlip(boolean on) {
        return operate("restrict", "enabled", on);
    }

    /** Read the flag state: 1 = redirect open, 0 = blocked, -1 = unresolvable. */
    int readRestrictState() {
        return readIntParam(MTC_RESTRICT, -1);
    }

    /**
     * The "hook" lever: arm the {@code __GFP_CMA} bypass hook (write 1 = let page
     * cache / mTHP anon consume the reservoir). Live only - not persisted here.
     */
    @NonNull
    Result setGfpHook(boolean on) {
        return operate("gfp-hook", "enabled", on);
    }

    /** Read the gfp hook arm state: 1 = armed, 0 = disarmed, -1 = absent. */
    int readGfpHook() {
        return readIntParam(MTC_GFP_HOOK, -1);
    }

    /** Save lever intent through the native owner; never rewrites settings.prop in the App. */
    @NonNull
    Result saveCmaLever(@NonNull String lever) {
        return operate("cma-lever", "value", lever);
    }

    /** Forget the persisted lever (CMA switched off, or the user declined save). */
    @NonNull
    Result clearCmaLever() {
        return operate("cma-lever", "value", null);
    }

    /** Explicit legacy-settings migration; reading or opening a screen never triggers it. */
    boolean hasLegacySettings() {
        return parseProp(safeRead(SETTINGS_PROP)).containsKey("cma_probe_result");
    }

    Result migrateSettings() {
        return operate("migrate-settings");
    }

    /* ================================================================== */
    /*  Advanced knobs: loader-fed settings.prop keys                     */
    /* ================================================================== */

    /**
     * The keys the advanced screen edits, in display order. All five are read by
     * {@code load.sh} out of settings.prop and handed to the module at insmod;
     * none of them is something the GUI can decide for the user, which is why
     * they live behind an advanced section that shows raw key/value.
     */
    static final List<String> ADVANCED_KEYS = List.of(
        "system_reserve_mb", "cma_reservoir_floor_mb",
        "boot_acquire", "boot_acquire_runs", "boot_acquire_wait");

    /** Module param carrying THIS device's default reserve, {@code min(RAM/2, 6144)}. */
    private static final String SYSTEM_RESERVE_DEFAULT = "system_reserve_mb_default";

    /**
     * One advanced knob, as the two facts that can disagree: what settings.prop
     * asks for and what the module is actually running. They differ while a save
     * awaits the next load, and also when the insmod ladder degraded past the rung
     * carrying that param - which is exactly the case a single number would hide.
     */
    static final class Knob {
        /** settings.prop value, or null when the key is absent (module default). */
        @Nullable final String saved;
        /** Module readback, or null when unavailable (not loaded, or older .ko). */
        @Nullable final String live;

        private Knob(@Nullable String saved, @Nullable String live) {
            this.saved = saved;
            this.live = live;
        }
    }

    /** Read every advanced knob: one settings.prop parse plus one read per param. */
    @NonNull
    Map<String, Knob> advancedKnobs() {
        var saved = parseProp(safeRead(SETTINGS_PROP));
        var out = new LinkedHashMap<String, Knob>();
        for (var key : ADVANCED_KEYS) {
            var live = safeRead(pathJoin(SYSFS_PARAMS, key)).trim();
            out.put(key, new Knob(saved.get(key), live.isEmpty() ? null : live));
        }
        return out;
    }

    /**
     * This device's default reserve in MB, or -1 when the module can't say. It is
     * NOT the built-in 6144: the module caps the request at half of RAM, so an
     * 8 GB phone defaults to 4096 and anything above that is a no-op there. A
     * configured value overwrites {@code system_reserve_mb}, so this param is the
     * only place the default survives once the user has set one.
     */
    int systemReserveDefaultMb() {
        return readIntParam(SYSTEM_RESERVE_DEFAULT, -1);
    }

    /** The native owner validates, saves and applies this advanced setting; the receipt records both outcomes. */
    @NonNull
    Result saveAdvanced(@NonNull String key, @Nullable String value) {
        return operate("advanced", "key", key, "value", value);
    }

    /** Read an integer sysfs param, or {@code def} when absent/unparseable. */
    private int readIntParam(@NonNull String name, int def) {
        var v = safeRead(pathJoin(SYSFS_PARAMS, name)).trim();
        if (v.isEmpty()) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * The last non-zero with-CMA total (pages) the user ran with, kept under an
     * app-owned settings.prop key so switching CMA off (which must persist
     * {@code pool_want_with_cma=0} for the boot script) doesn't forget the size.
     */
    long lastCmaTargetPages() {
        var s = parseProp(safeRead(SETTINGS_PROP));
        var v = s.get("pool_want_with_cma_last");
        if (v != null) try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    /** Save and apply the CMA target as one coordinated native operation. */
    @NonNull
    Result saveCmaTarget(long pages) {
        return operate("cma-target", "pages", pages, "persist", true);
    }

    /** Save coupled targets without acquiring; withCma=-1 preserves the CMA target. */
    @NonNull
    Result saveTargets(long pages, long withCma) {
        return saveTargets(pages, withCma, 0);
    }

    /** Apply the live CMA target without persisting it, through the native owner. */
    @NonNull
    Result writeWantWithCma(long pages) {
        return operate("cma-target", "pages", pages, "persist", false);
    }

    /* ================================================================== */
    /*  Read-source selection                                             */
    /* ================================================================== */

    private static <I, V> boolean rung(
        @Nullable I only, @NonNull List<Try<I, V>> log,
        @NonNull I impl, @NonNull Supplier<Try<I, V>> action
    ) {
        if (only != null && only != impl) return false;
        var t = action.get();
        log.add(t);
        return t.ok();
    }

    @NonNull
    private static Usage usageOf(@NonNull List<Try<Source, List<UsageEntry>>> log) {
        var last = log.isEmpty() ? null : log.get(log.size() - 1);
        if (last != null && last.ok() && last.value != null) {
            return new Usage(last.value, last.impl);
        }
        return new Usage(new ArrayList<>(), Source.SCAN);
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
            // Pure read: reconciliation is an explicit native-owner operation.
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
