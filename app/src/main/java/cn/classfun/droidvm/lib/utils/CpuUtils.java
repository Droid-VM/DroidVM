// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.utils;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Host CPU topology helper: enumerates cores, reads each core's max frequency
 * from sysfs and groups them into frequency tiers so callers can tell the
 * little cluster apart from the big/prime clusters (for CPU-affinity binding).
 * Reads are best-effort; a core whose frequency cannot be read is reported with
 * {@code maxFreqKHz == 0} and treated as tier 0.
 */
public final class CpuUtils {
    private static final String TAG = "CpuUtils";
    private static final String CPU_ROOT = "/sys/devices/system/cpu";
    private static final Pattern CPU_DIR = Pattern.compile("cpu\\d+");

    private CpuUtils() {
    }

    /** A single host CPU core and its cluster classification. */
    public static final class CpuCore {
        public final int index;
        public final long maxFreqKHz; // 0 when unknown
        public final int tier;        // 0 = lowest-freq cluster, ascending
        public final boolean big;     // true when not in the lowest-freq cluster
        /**
         * Scheduler capacity on the kernel's 1024-per-biggest-core scale, as
         * crosvm's {@code --cpu-capacity} wants it. Read from sysfs when the
         * kernel exports it, otherwise derived from the frequency ratio; 0 only
         * when neither is available.
         */
        public final long capacity;

        CpuCore(int index, long maxFreqKHz, int tier, boolean big, long capacity) {
            this.index = index;
            this.maxFreqKHz = maxFreqKHz;
            this.tier = tier;
            this.big = big;
            this.capacity = capacity;
        }
    }

    /**
     * Enumerate host cores sorted by index, each tagged with its frequency tier.
     * Never returns null; falls back to {@link Runtime#availableProcessors()}
     * with unknown frequencies if sysfs cannot be read.
     */
    @NonNull
    public static List<CpuCore> getCores() {
        var indices = listCoreIndices();
        var freqs = new long[indices.size()];
        var caps = new long[indices.size()];
        var distinct = new TreeSet<Long>();
        long maxFreq = 0;
        for (int i = 0; i < indices.size(); i++) {
            freqs[i] = readMaxFreqKHz(indices.get(i));
            caps[i] = readCapacity(indices.get(i));
            if (freqs[i] > 0) distinct.add(freqs[i]);
            maxFreq = Math.max(maxFreq, freqs[i]);
        }
        // Ascending tier index per distinct frequency; unknown (0) stays tier 0.
        var tierOf = new ArrayList<>(distinct);
        var cores = new ArrayList<CpuCore>(indices.size());
        for (int i = 0; i < indices.size(); i++) {
            int tier = freqs[i] > 0 ? tierOf.indexOf(freqs[i]) : 0;
            // No cpu_capacity in sysfs (common outside big.LITTLE-aware kernels):
            // fall back to the frequency ratio against the fastest core, which is
            // what the arm64 kernel itself does when the DT omits capacities.
            long cap = caps[i];
            if (cap <= 0 && freqs[i] > 0 && maxFreq > 0)
                cap = Math.max(1, Math.round(MAX_CAPACITY * (double) freqs[i] / maxFreq));
            cores.add(new CpuCore(indices.get(i), freqs[i], tier, tier > 0, cap));
        }
        return cores;
    }

    /** Capacity of the biggest core on the kernel's scale. */
    public static final long MAX_CAPACITY = 1024;

    /** Number of distinct frequency clusters (1 when frequencies are unknown). */
    public static int tierCount(@NonNull List<CpuCore> cores) {
        int max = 0;
        for (var c : cores) max = Math.max(max, c.tier);
        return max + 1;
    }

    /**
     * Default selection for "filter out the little cores": every core that is
     * not in the lowest-frequency cluster. When there is only a single cluster
     * (or detection failed) all cores are returned, since filtering is moot.
     */
    @NonNull
    public static String defaultBigCoresCsv() {
        var cores = getCores();
        boolean single = tierCount(cores) <= 1;
        var sb = new StringBuilder();
        for (var c : cores) {
            if (single || c.big) {
                if (sb.length() > 0) sb.append(',');
                sb.append(c.index);
            }
        }
        return sb.toString();
    }

    // Frequency uses decimal (SI) steps of 1000, unlike SizeUtils' binary units.
    private static final String[] FREQ_UNITS = {"Hz", "kHz", "MHz", "GHz", "THz"};

    /** Format a KHz frequency as e.g. "2.60 GHz"; empty string when unknown. */
    @NonNull
    public static String formatFreq(long khz) {
        if (khz <= 0) return "";
        double value = khz * 1000.0;
        int tier = 0;
        while (value >= 1000.0 && tier < FREQ_UNITS.length - 1) {
            value /= 1000.0;
            tier++;
        }
        return fmt("%.2f %s", value, FREQ_UNITS[tier]);
    }

    /**
     * Collapse a core-index CSV into a compact range string, e.g.
     * "4,5,6,7" -> "4-7", "0,4,5,6,7" -> "0,4-7". Returns "" for empty input.
     */
    @NonNull
    public static String compactRanges(@NonNull String csv) {
        var nums = parseCsv(csv);
        if (nums.isEmpty()) return "";
        var sb = new StringBuilder();
        int start = nums.get(0), prev = start;
        for (int i = 1; i <= nums.size(); i++) {
            int cur = i < nums.size() ? nums.get(i) : Integer.MIN_VALUE;
            if (cur == prev + 1) {
                prev = cur;
                continue;
            }
            if (sb.length() > 0) sb.append(',');
            if (start == prev) sb.append(start);
            else sb.append(start).append('-').append(prev);
            start = prev = cur;
        }
        return sb.toString();
    }

    /**
     * Convert a core-index CSV into the hex CPU mask that toybox {@code taskset}
     * expects (no "0x" prefix), e.g. "4,5,6,7" -> "f0", "0,1" -> "3". Returns
     * "" for empty input. Bit N set means core N is allowed.
     */
    @NonNull
    public static String coresCsvToHexMask(@NonNull String csv) {
        var nums = parseCsv(csv);
        if (nums.isEmpty()) return "";
        var mask = java.math.BigInteger.ZERO;
        for (int idx : nums) {
            if (idx >= 0) mask = mask.setBit(idx);
        }
        return mask.signum() == 0 ? "" : mask.toString(16);
    }

    /**
     * Parse a crosvm CPUSET spec -- a comma-separated list of indices and
     * {@code low-high} ranges, e.g. {@code "0,1-3,5"} -- into ascending unique
     * indices. Unparsable or reversed parts are skipped rather than throwing,
     * matching the best-effort style of the rest of this class; callers that
     * need to reject bad input compare the result against the input instead.
     */
    @NonNull
    public static List<Integer> parseCpuSet(@NonNull String spec) {
        var set = new TreeSet<Integer>();
        for (var part : spec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-', 1);
            try {
                if (dash < 0) {
                    set.add(Integer.parseInt(part));
                    continue;
                }
                int lo = Integer.parseInt(part.substring(0, dash).trim());
                int hi = Integer.parseInt(part.substring(dash + 1).trim());
                if (lo > hi) continue;
                for (int i = lo; i <= hi; i++) set.add(i);
            } catch (NumberFormatException ignored) {
            }
        }
        return new ArrayList<>(set);
    }

    @NonNull
    private static List<Integer> parseCsv(@NonNull String csv) {
        return parseCpuSet(csv);
    }

    @NonNull
    private static List<Integer> listCoreIndices() {
        var out = new ArrayList<Integer>();
        var root = new File(CPU_ROOT);
        var dirs = root.listFiles();
        if (dirs != null) {
            for (var d : dirs) {
                if (!d.isDirectory() || !CPU_DIR.matcher(d.getName()).matches()) continue;
                try {
                    out.add(Integer.parseInt(d.getName().substring(3)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (out.isEmpty()) {
            // sysfs unreadable -- fall back to the runtime-reported count.
            int n = Math.max(1, Runtime.getRuntime().availableProcessors());
            for (int i = 0; i < n; i++) out.add(i);
        } else {
            out.sort(Integer::compareTo);
        }
        return out;
    }

    private static long readCapacity(int index) {
        return tryReadLong(fmt("%s/cpu%d/cpu_capacity", CPU_ROOT, index));
    }

    private static long readMaxFreqKHz(int index) {
        // cpuinfo_max_freq is the hardware ceiling; scaling_max_freq is the
        // policy ceiling (usually equal). Try the direct read first, then a
        // root-backed read, before giving up on this core.
        long v = tryReadLong(fmt("%s/cpu%d/cpufreq/cpuinfo_max_freq", CPU_ROOT, index));
        if (v > 0) return v;
        return tryReadLong(fmt("%s/cpu%d/cpufreq/scaling_max_freq", CPU_ROOT, index));
    }

    private static long tryReadLong(@NonNull String path) {
        String raw = null;
        try {
            raw = FileUtils.readFile(path);
        } catch (Exception directFailed) {
            try {
                raw = FileUtils.shellReadFile(path);
            } catch (Exception rootFailed) {
                Log.d(TAG, fmt("freq read failed: %s", path));
            }
        }
        if (raw == null) return 0;
        raw = raw.trim();
        if (raw.isEmpty()) return 0;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
