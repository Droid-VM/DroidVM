package cn.classfun.droidvm.ui.hugepage;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import cn.classfun.droidvm.R;

/**
 * Deterministic per-process colors for the hugepage usage bar and list icons.
 * The same pid always maps to the same hue (stable across refreshes); in dark
 * mode colors are light, in light mode they are dark, so they read against the
 * surface either way.
 */
final class HugePageColor {
    private HugePageColor() {
    }

    static boolean isDark(Context ctx) {
        int mode = ctx.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Golden-angle hue spread keyed by pid, theme-aware saturation/value. */
    static int forPid(int pid, boolean dark) {
        float hue = ((pid * 137.508f) % 360f + 360f) % 360f;
        float[] hsv = {hue, dark ? 0.50f : 0.72f, dark ? 0.90f : 0.55f};
        return Color.HSVToColor(hsv);
    }

    /** Color for the "waiting for acquire" capacity-deficit portion. */
    static int pending(@NonNull Context ctx) {
        return ContextCompat.getColor(ctx, R.color.hugepage_pending);
    }
}
