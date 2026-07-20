package cn.classfun.droidvm.ui.hugepage;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import cn.classfun.droidvm.R;

/**
 * Deterministic per-process colors for the hugepage usage bar and list icons.
 * In dark mode colors are light, in light mode they are dark, so they read
 * against the surface either way.
 *
 * <p>Hues are assigned by golden-angle steps over each pid's <b>rank among the
 * pids currently shown</b> (ascending pid order), not over the raw pid. Keying
 * the angle by pid could land two live VMs almost on the same hue (any pid
 * difference near a multiple of 360/137.508 - e.g. 34 - wraps to within a few
 * degrees), whereas consecutive ranks are always 137.5&deg; apart, so no two of
 * up to ~7 VMs come closer than ~32&deg; (&ge;52&deg; for four or fewer). Ranks
 * are stable while the same VMs run - new pids are usually larger and append at
 * the end - and both hugepage screens derive the map from the same owner list,
 * so a VM keeps one color everywhere until the set of VMs itself changes.
 */
final class HugePageColor {
    /** Golden angle in degrees: consecutive ranks land maximally apart. */
    private static final float GOLDEN_ANGLE = 137.508f;

    private HugePageColor() {
    }

    static boolean isDark(Context ctx) {
        int mode = ctx.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Assign a color to every pid in {@code pids} (duplicates collapse), keyed
     * by ascending-pid rank. Pass the <b>full</b> pid list of the screen (not a
     * filtered subset) so both hugepage screens agree on the ranks.
     */
    @NonNull
    static Map<Integer, Integer> forPids(@NonNull Collection<Integer> pids, boolean dark) {
        var map = new LinkedHashMap<Integer, Integer>();
        int rank = 0;
        for (var pid : new TreeSet<>(pids)) map.put(pid, forRank(rank++, dark));
        return map;
    }

    /** Golden-angle hue for one rank, theme-aware saturation/value. */
    static int forRank(int rank, boolean dark) {
        float hue = ((rank * GOLDEN_ANGLE) % 360f + 360f) % 360f;
        float[] hsv = {hue, dark ? 0.50f : 0.72f, dark ? 0.90f : 0.55f};
        return Color.HSVToColor(hsv);
    }

    /** Color for the "waiting for acquire" capacity-deficit portion. */
    static int pending(@NonNull Context ctx) {
        return ContextCompat.getColor(ctx, R.color.hugepage_pending);
    }

    /** Reservoir portion currently occupied by other apps' allocations. */
    static int cmaUsed(@NonNull Context ctx) {
        return ContextCompat.getColor(ctx, R.color.hugepage_cma_used);
    }

    /** Reservoir portion still free in buddy. */
    static int cmaFree(@NonNull Context ctx) {
        return ContextCompat.getColor(ctx, R.color.hugepage_cma_free);
    }

    /** Available-pool portion not flippable to CMA as whole pageblocks. */
    static int availNonCma(@NonNull Context ctx) {
        return ContextCompat.getColor(ctx, R.color.hugepage_avail_non_cma);
    }

    /**
     * Opaque gray for the synthetic "available" list row's icon - the bar's
     * translucent track color is too faint as an icon tint.
     */
    static int availIcon(boolean dark) {
        return dark ? 0xFFB5B5B5 : 0xFF8A8A8A;
    }
}
