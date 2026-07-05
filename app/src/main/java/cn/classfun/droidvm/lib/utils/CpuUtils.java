package cn.classfun.droidvm.lib.utils;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

        CpuCore(int index, long maxFreqKHz, int tier, boolean big) {
            this.index = index;
            this.maxFreqKHz = maxFreqKHz;
            this.tier = tier;
            this.big = big;
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
        var distinct = new TreeSet<Long>();
        for (int i = 0; i < indices.size(); i++) {
            freqs[i] = readMaxFreqKHz(indices.get(i));
            if (freqs[i] > 0) distinct.add(freqs[i]);
        }
        // Ascending tier index per distinct frequency; unknown (0) stays tier 0.
        var tierOf = new ArrayList<>(distinct);
        var cores = new ArrayList<CpuCore>(indices.size());
        for (int i = 0; i < indices.size(); i++) {
            int tier = freqs[i] > 0 ? tierOf.indexOf(freqs[i]) : 0;
            cores.add(new CpuCore(indices.get(i), freqs[i], tier, tier > 0));
        }
        return cores;
    }

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

    /** Format a KHz frequency as e.g. "2.60 GHz"; empty string when unknown. */
    @NonNull
    public static String formatFreq(long khz) {
        if (khz <= 0) return "";
        return String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0);
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

    @NonNull
    private static List<Integer> parseCsv(@NonNull String csv) {
        var out = new ArrayList<Integer>();
        if (csv.isEmpty()) return out;
        for (var part : csv.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
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

    private static long readMaxFreqKHz(int index) {
        // cpuinfo_max_freq is the hardware ceiling; scaling_max_freq is the
        // policy ceiling (usually equal). Try the direct read first, then a
        // root-backed read, before giving up on this core.
        long v = tryReadFreq(fmt("%s/cpu%d/cpufreq/cpuinfo_max_freq", CPU_ROOT, index));
        if (v > 0) return v;
        return tryReadFreq(fmt("%s/cpu%d/cpufreq/scaling_max_freq", CPU_ROOT, index));
    }

    private static long tryReadFreq(@NonNull String path) {
        String raw = null;
        try {
            raw = FileUtils.readFile(path);
        } catch (Exception directFailed) {
            try {
                raw = FileUtils.shellReadFile(path);
            } catch (Exception rootFailed) {
                Log.d(TAG, "freq read failed: " + path);
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

    @NonNull
    private static String fmt(@NonNull String f, Object... args) {
        return String.format(Locale.US, f, args);
    }
}
