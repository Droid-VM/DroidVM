package cn.classfun.droidvm.ui.hugepage;

import static cn.classfun.droidvm.lib.utils.FileUtils.shellCheckExists;
import static cn.classfun.droidvm.lib.utils.FileUtils.shellReadFile;
import static cn.classfun.droidvm.lib.utils.RunUtils.escapedString;
import static cn.classfun.droidvm.lib.utils.RunUtils.run;
import static cn.classfun.droidvm.lib.utils.RunUtils.runList;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.StringUtils.joinNonEmpty;
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
    /** The module's own preflight+insmod script (v10.1+); preferred load path. */
    private static final String LOAD_SCRIPT = pathJoin(MAGISK_BASE, "load.sh");
    /** ABI/BTF preflight helper the boot script feeds into insmod. */
    private static final String KAPI_CHECK = pathJoin(MAGISK_BASE, "kapi_check");
    /** insmod params are pasted into a shell line: allow only inert characters. */
    private static final Pattern SAFE_PARAM = Pattern.compile("[A-Za-z0-9_,.-]+");
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

    @NonNull
    Result unload() {
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
    /*  v11 movable->CMA levers + CMA reservoir persistence               */
    /* ================================================================== */

    /** settings.prop values recording which movable->CMA lever the user saved. */
    static final String LEVER_HOOK = "hook";
    static final String LEVER_FLAG = "flag";
    private static final String CMA_LEVER_KEY = "cma_movable_lever";

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
        var t = writeKnob(MTC_RESTRICT, on ? "1" : "0");
        return t.ok() ? Result.ok(MTC_RESTRICT) : Result.failed(MTC_RESTRICT, t.error);
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
        var t = writeKnob(MTC_GFP_HOOK, on ? "1" : "0");
        return t.ok() ? Result.ok(MTC_GFP_HOOK) : Result.failed(MTC_GFP_HOOK, t.error);
    }

    /** Read the gfp hook arm state: 1 = armed, 0 = disarmed, -1 = absent. */
    int readGfpHook() {
        return readIntParam(MTC_GFP_HOOK, -1);
    }

    /**
     * Persist the chosen movable->CMA lever ({@link #LEVER_HOOK} /
     * {@link #LEVER_FLAG}) under an app-owned settings.prop key, so a future
     * boot script can re-apply it as an insmod param. The shipped load.sh does
     * not read it yet, so this only records intent - deliberate: the lever is
     * applied live and only saved once it has proven it doesn't crash the boot.
     */
    @NonNull
    Result saveCmaLever(@NonNull String lever) {
        var changes = new LinkedHashMap<String, String>();
        changes.put(CMA_LEVER_KEY, lever);
        var t = updateSettings(changes);
        return t.ok() ? Result.ok("settings") : Result.failed("settings", t.error);
    }

    /** Forget the persisted lever (CMA switched off, or the user declined save). */
    @NonNull
    Result clearCmaLever() {
        var changes = new LinkedHashMap<String, String>();
        changes.put(CMA_LEVER_KEY, null);   // null value = remove the key
        var t = updateSettings(changes);
        return t.ok() ? Result.ok("settings") : Result.failed("settings", t.error);
    }

    /**
     * Drop a stale {@code cma_probe_result} left by an older app version. The
     * shipped boot script still cold-starts the whole CMA side on
     * {@code cma_probe_result=0}, so a leftover denial from the removed probe
     * would keep v11's reservoir off; remove it on sight. No-op when absent.
     */
    void clearLegacyProbeKey() {
        if (!parseProp(safeRead(SETTINGS_PROP)).containsKey("cma_probe_result")) return;
        var changes = new LinkedHashMap<String, String>();
        changes.put("cma_probe_result", null);
        updateSettings(changes);
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

    /**
     * Persist the with-CMA total for the next boot and best-effort apply it to
     * the running module. A non-zero target is also remembered under the
     * {@code _last} key for the next re-enable. Like {@link #saveTargets}, the
     * result reports whether the live write landed ({@code degraded} when only
     * the persist took effect).
     */
    @NonNull
    Result saveCmaTarget(long pages) {
        var persisted = updateSettings(cmaTargetChanges(pages));
        if (!persisted.ok()) return Result.failed("settings", persisted.error);
        var live = writeKnob("pool_want_with_cma", Long.toString(pages));
        return Result.ok(live.ok() ? "pool_want_with_cma" : "settings", !live.ok());
    }

    /**
     * Persist pool target and with-CMA total in ONE settings.prop rewrite, then
     * apply both live knobs ({@code pool_want} first - a value above the old
     * with-CMA total drags it up, and the second write then pins it exact).
     * {@code withCma < 0} leaves the CMA keys untouched and only sets the pool
     * target (persisted under both {@code pool_want} and legacy
     * {@code pool_target}; on v6 only the persist takes effect). Does <b>not</b>
     * fire an acquire -- the caller drives that from {@link Snapshot#deficit}.
     */
    @NonNull
    Result saveTargets(long pages, long withCma) {
        // Invariant (module plan.md sec.1): pool_want <= pool_want_with_cma. The kernel
        // clamps a low live write itself, but the persisted pair must agree
        // too or the next boot would insmod inconsistent targets.
        if (withCma >= 0) withCma = Math.max(withCma, pages);
        var changes = new LinkedHashMap<String, String>();
        changes.put("pool_want", Long.toString(pages));
        changes.put("pool_target", Long.toString(pages));
        if (withCma >= 0) changes.putAll(cmaTargetChanges(withCma));
        var persisted = updateSettings(changes);
        if (!persisted.ok()) return Result.failed("settings", persisted.error);
        var live = writeKnob("pool_want", Long.toString(pages));
        boolean liveOk = live.ok();
        if (withCma >= 0)
            liveOk &= writeKnob("pool_want_with_cma", Long.toString(withCma)).ok();
        return Result.ok(liveOk ? "pool_want" : "settings", !liveOk);
    }

    @NonNull
    private static Map<String, String> cmaTargetChanges(long pages) {
        var changes = new LinkedHashMap<String, String>();
        changes.put("pool_want_with_cma", Long.toString(pages));
        if (pages > 0) changes.put("pool_want_with_cma_last", Long.toString(pages));
        return changes;
    }

    /**
     * Live {@code pool_want_with_cma} write only (no settings.prop persist), so
     * the reservoir target set here is undone by a reboot. The enable flow uses
     * it to build the reservoir at runtime before the user decides whether to
     * save the movable->CMA lever.
     */
    @NonNull
    Result writeWantWithCma(long pages) {
        var t = writeKnob("pool_want_with_cma", Long.toString(pages));
        return t.ok() ? Result.ok("pool_want_with_cma")
            : Result.failed("pool_want_with_cma", t.error);
    }

    /* ================================================================== */
    /*  Ladder plumbing (internal)                                        */
    /* ================================================================== */

    /**
     * insmod ladder, richest first. {@code load.sh} is the module's own
     * preflight+insmod (v10+) and needs nothing from us. The remaining rungs
     * exist only for the released modules that predate it: v9 wants its ABI
     * guard, v6..v8 take size keys alone. Each rung drops the parameter group an
     * older module wouldn't recognise.
     */
    private enum LoadImpl {
        SCRIPT,
        GUARD_BOTH, GUARD_WANT, GUARD_TARGET,   // ABI guard (v9)
        BOTH, POOL_WANT, POOL_TARGET, BARE      // no guard (v6/v7/v8)
    }

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
        // Best rung: the module's own load.sh - the exact preflight+insmod the
        // boot script performs, so a runtime enable reproduces the boot-time
        // configuration and future preflight changes need no app change. It
        // reads the size from settings.prop, which is where `pages` came from.
        if (existsSticky(LOAD_SCRIPT) && rung(null, log, LoadImpl.SCRIPT, () -> {
            var r = run("sh %s", escapedString(LOAD_SCRIPT));
            return (r.isSuccess() && existsSticky(SYSFS_BASE))
                ? Try.<LoadImpl, Void>ok(LoadImpl.SCRIPT, null)
                : Try.<LoadImpl, Void>fail(LoadImpl.SCRIPT, reason(r, "load.sh"));
        })) return log;
        var w = fmt("pool_want=\"%d\"", pages);
        var t = fmt("pool_target=\"%d\"", pages);
        // No load.sh: a released module that predates it (v6..v9). Those have no
        // CMA parameters at all, so there is nothing to reconstruct here - only
        // v9's ABI guard, without which an ABI-drifted symbol can kCFI-panic on
        // first call. Every v10+ preflight lives in load.sh alone, so it can
        // never drift from what this app passes.
        var guard = kapiGuardArg();
        if (!guard.isEmpty()) {
            if (rung(null, log, LoadImpl.GUARD_BOTH,
                () -> insmod(LoadImpl.GUARD_BOTH, joinNonEmpty(" ", guard, w, t)))) return log;
            if (rung(null, log, LoadImpl.GUARD_WANT,
                () -> insmod(LoadImpl.GUARD_WANT, joinNonEmpty(" ", guard, w)))) return log;
            if (rung(null, log, LoadImpl.GUARD_TARGET,
                () -> insmod(LoadImpl.GUARD_TARGET, joinNonEmpty(" ", guard, t)))) return log;
        }
        // Pass BOTH size params first. A lenient kernel silently ignores the param
        // the module doesn't have, so it still gets the right size via the one it
        // does - pool_want (v7) or pool_target (v6). This is essential: on a v6
        // module `pool_want=` alone loads fine (returns 0) but is ignored, leaving
        // the pool at its compiled 1024 default - the ladder must not stop there.
        // Strict kernels reject the unknown param, so fall back to each key alone,
        // then a bare (default-size) load.
        if (rung(null, log, LoadImpl.BOTH,
            () -> insmod(LoadImpl.BOTH, joinNonEmpty(" ", w, t)))) return log;
        if (rung(null, log, LoadImpl.POOL_WANT,
            () -> insmod(LoadImpl.POOL_WANT, w))) return log;
        if (rung(null, log, LoadImpl.POOL_TARGET,
            () -> insmod(LoadImpl.POOL_TARGET, t))) return log;
        rung(null, log, LoadImpl.BARE, () -> insmod(LoadImpl.BARE, null));
        return log;
    }

    /**
     * v9's ABI guard, read from the module's {@code kapi_check} helper: it
     * compares the running kernel's real symbol signatures (from vmlinux BTF)
     * against what this .ko expects and names the drifted ones, which insmod
     * then leaves unresolved (their feature returns -ENOSYS) instead of
     * kCFI-panicking on first call. "" when the helper is absent (v6..v8) or
     * nothing drifted - fail-open, exactly like the v9 boot script.
     */
    @NonNull
    private String kapiGuardArg() {
        if (!existsSticky(KAPI_CHECK)) return "";
        var out = run("%s /sys/kernel/btf/vmlinux", escapedString(KAPI_CHECK));
        for (var line : out.getOutString().split("\n")) {
            line = line.trim();
            if (!line.startsWith("disable=")) continue;
            var v = line.substring("disable=".length()).trim();
            // A symbol-name list, pasted into a shell line: refuse anything else.
            if (!v.isEmpty() && SAFE_PARAM.matcher(v).matches())
                return fmt("disable_kapi=%s", v);
        }
        return "";
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
        var changes = new LinkedHashMap<String, String>();
        changes.put("pool_want", Long.toString(pages));
        changes.put("pool_target", Long.toString(pages));
        return updateSettings(changes);
    }

    /** Serializes settings.prop read-modify-write cycles within this process. */
    private static final Object SETTINGS_LOCK = new Object();

    /**
     * Read-modify-write settings.prop: apply {@code changes} (a null value
     * <b>removes</b> that key) and keep every other key (the file also carries
     * app-owned CMA state - {@code pool_want_with_cma}, {@code cma_movable_lever}
     * - that a blind rewrite would wipe). The boot script {@code source}s the
     * file, so lines stay plain {@code key=value}. Locked so concurrent writers
     * (a lever/CMA save vs a pool-size save) can't interleave their read/write
     * pairs and drop each other's keys.
     */
    private static Try<Only, Void> updateSettings(@NonNull Map<String, String> changes) {
        synchronized (SETTINGS_LOCK) {
            String raw;
            try {
                raw = shellReadFile(SETTINGS_PROP);
            } catch (Exception e) {
                // A missing file legitimately starts empty; an EXISTING file that
                // failed to read must abort - rewriting from an empty map would
                // silently drop every other persisted key (lever choice, CMA
                // targets) on a transient root hiccup.
                if (existsSticky(SETTINGS_PROP))
                    return Try.fail(Only.DEFAULT, "settings.prop: read failed");
                raw = "";
            }
            var s = parseProp(raw);
            for (var e : changes.entrySet()) {
                if (e.getValue() == null) s.remove(e.getKey());
                else s.put(e.getKey(), e.getValue());
            }
            var content = new StringBuilder();
            for (var e : s.entrySet())
                content.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            var r = run("printf '%%s' %s > %s",
                escapedString(content.toString()), SETTINGS_PROP);
            return r.isSuccess()
                ? Try.<Only, Void>ok(Only.DEFAULT, null)
                : Try.<Only, Void>fail(Only.DEFAULT, reason(r, "settings.prop"));
        }
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
