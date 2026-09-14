// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Set;

import cn.classfun.droidvm.lib.store.base.DataItem;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

/**
 * How loud the VMM is for one VM: the {@code log_level} key, and the {@code --log-level} filter
 * it becomes.
 *
 * <p>Per VM and not per phone, because this is an instrument and not a preference: it is turned
 * up on the one VM whose device is being dug into, for as long as the dig lasts, and left alone
 * everywhere else. crosvm's own default is {@code info} and a VM that never asked for anything
 * else is passed no flag at all, so the ordinary command line does not change.</p>
 *
 * <p>Why it exists at all (defect <b>D60</b>, {@code logs/vpu_wp/B11-acceptance.md} section 8).
 * Every {@code debug!} in the media stack was dead weight on a phone. The VMM forwards its own
 * level to each device helper it launches -- a helper is exec'd as
 * {@code /proc/self/exe --log-level <filter> device media ...}, so a helper says what the VMM
 * says -- but nothing on a phone could move the VMM off {@code info}: the app emitted no
 * {@code --log-level}, {@code extra_options} are appended after the {@code run} subcommand where
 * argh refuses a top-level option ({@code arg parsing failed: Unrecognized argument:
 * --log-level}, measured), and crosvm's logger reads no environment variable
 * ({@code base/src/syslog.rs}). So the last two acceptance rounds needed a debug line and could
 * not get one. This key is the missing end of that chain.</p>
 *
 * <p><b>The flag is top-level</b>, which is the whole reason a stored string cannot do this job:
 * {@code --log-level} belongs to {@code CrosvmCmdlineArgs} ({@code src/crosvm/cmdline.rs}), so it
 * must appear between the crosvm binary and {@code run}. See
 * {@link cn.classfun.droidvm.daemon.vm.backend.CrosvmBackendInstance} for where it is emitted and
 * {@code plans/VPU_DESIGN.md} section 9.</p>
 *
 * <p><b>The syntax.</b> The value is an {@code env_logger} filter, which is what crosvm hands to
 * {@code env_logger::filter::Builder::parse} ({@code base/src/syslog.rs}). Accepted here: a
 * comma-separated list of directives, each one either a bare level name -- {@code off},
 * {@code error}, {@code warn}, {@code info}, {@code debug}, {@code trace}, any case -- or
 * {@code target=level} for a module path ({@code debug,disk=off},
 * {@code info,devices::virtio::media=debug}), optionally followed by {@code /<regex>} which
 * env_logger matches against the message text. Lenient about the target, strict about the level:
 * a directive with no {@code =} must <em>be</em> a level, so {@code verbose} is refused here
 * rather than silently becoming a filter for a module called "verbose". That is the one thing
 * worth catching, because env_logger itself never fails a parse -- it drops what it cannot read
 * and carries on, so a typo on the far side is a VM that logs exactly as before with nothing to
 * say why.</p>
 */
public final class VmmLogLevel {
    /** The stored key. Not {@code vpu_}-prefixed: the level is the whole VMM's, not the VPU's. */
    public static final String KEY = "log_level";

    /**
     * What crosvm does with no flag at all ({@code cmdline.rs}: {@code default = "info"}). A
     * config that stores exactly this is passed nothing, so "the default" and "unset" produce the
     * same command line rather than two spellings of it.
     */
    public static final String DEFAULT = "info";

    /** Long enough for a compound filter, short enough that a pasted accident is refused. */
    private static final int MAX_LENGTH = 256;

    private static final Set<String> LEVELS =
        Set.of("off", "error", "warn", "info", "debug", "trace");

    private VmmLogLevel() {
    }

    /** The stored filter, or {@link #DEFAULT} when the VM has never been given one. */
    @NonNull
    public static String get(@NonNull DataItem config) {
        var stored = config.optString(KEY, DEFAULT);
        return stored == null || stored.trim().isEmpty() ? DEFAULT : stored.trim();
    }

    /**
     * Stores a filter, after checking it. Throws rather than storing a value the command-line
     * builder would then have to drop: the editor or the caller that set it is where a bad value
     * can still be reported to whoever typed it.
     *
     * @throws IllegalArgumentException if {@code filter} is not one this accepts
     */
    public static void set(@NonNull DataItem config, @NonNull String filter) {
        config.set(KEY, parse(filter));
    }

    /**
     * The filter this VM's command line must carry, or {@code null} for "emit nothing".
     *
     * <p>{@code null} means the default, whether it got there by being unset, blank, or spelt
     * out. It never means "the value was bad" -- that is an exception, so the caller can say so
     * in the log instead of a VM quietly running at a level nobody chose.</p>
     *
     * @throws IllegalArgumentException if the stored value is not a filter this accepts
     */
    @Nullable
    public static String filterFor(@NonNull DataItem config) {
        var stored = get(config);
        var filter = parse(stored);
        return filter.equalsIgnoreCase(DEFAULT) ? null : filter;
    }

    /**
     * The filter, trimmed, or an exception naming what is wrong with it.
     *
     * @throws IllegalArgumentException if it is not a filter this accepts
     */
    @NonNull
    public static String parse(@Nullable String raw) {
        if (raw == null) throw refuse("null", "there is no value");
        var filter = raw.trim();
        if (filter.isEmpty()) throw refuse(raw, "it is empty");
        if (filter.length() > MAX_LENGTH)
            throw refuse(filter, fmtLength(filter.length()));
        var directives = filter;
        // env_logger takes an optional message regex after the directives; it is the rest of the
        // string, so it is split off first and only sanity-checked for shape.
        int slash = filter.indexOf('/');
        if (slash >= 0) {
            directives = filter.substring(0, slash);
            var regex = filter.substring(slash + 1);
            if (regex.isEmpty()) throw refuse(filter, "the '/' has no regex after it");
            if (!isPrintable(regex))
                throw refuse(filter, "the regex after '/' has whitespace or control characters");
        }
        if (directives.isEmpty()) throw refuse(filter, "it has no directive before the '/'");
        for (var directive : directives.split(",", -1)) {
            if (directive.isEmpty())
                throw refuse(filter, "it has an empty directive (a stray or doubled comma)");
            int eq = directive.indexOf('=');
            if (eq < 0) {
                if (!isLevel(directive))
                    throw refuse(filter, fmtNotALevel(directive));
                continue;
            }
            var target = directive.substring(0, eq);
            var level = directive.substring(eq + 1);
            if (!isTarget(target))
                throw refuse(filter, fmt("'%s' is not a module path (letters, digits, "
                    + "'_', '-', '.' and ':')", target));
            if (!isLevel(level))
                throw refuse(filter, fmtNotALevel(level));
        }
        return filter;
    }

    /** Whether {@link #parse} would accept this string. */
    public static boolean isValid(@Nullable String raw) {
        try {
            parse(raw);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isLevel(@NonNull String s) {
        return LEVELS.contains(s.toLowerCase(Locale.ROOT));
    }

    private static boolean isTarget(@NonNull String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.' || c == ':' || c == '*';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean isPrintable(@NonNull String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ' || c > '~') return false;
        }
        return true;
    }

    private static String fmtNotALevel(@NonNull String part) {
        return fmt("'%s' is not a level (%s); a directive with no '=' must be one", part,
            String.join(", ", "off", "error", "warn", "info", "debug", "trace"));
    }

    private static String fmtLength(int length) {
        return fmt("it is %d characters, over the %d this accepts", length, MAX_LENGTH);
    }

    private static IllegalArgumentException refuse(@NonNull String raw, @NonNull String why) {
        return new IllegalArgumentException(
            fmt("%s '%s' is not a crosvm --log-level filter: %s", KEY, raw, why));
    }
}
