// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.widgets.row;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A horizontal LinearLayout whose children shrink instead of clipping when the row is too
 * narrow for them. With room to spare it behaves exactly like its parent class (weights
 * expand, gravity applies); once the children's natural widths overflow the row, every
 * child is scaled down by the one factor that just makes the row fit -- the same ratio for
 * all of them, dictated by the overflow, so the row reads as itself at a smaller size
 * rather than as a different layout.
 *
 * <p>The shrink path measures children at their natural size and applies the factor as a
 * draw-time scale ({@code scaleX/scaleY}), laying each child at its scaled position by
 * hand. Touch targets follow the transform (Android hit-tests through it), so controls
 * stay tappable at their visual location; they do get smaller, which is the point.
 *
 * <p>Weighted children get their leftover-space share only on the roomy path -- in the
 * shrunk row there is no leftover, so a weighted {@code Space} collapses and the row
 * packs left-to-right. Children keep their measured (unscaled) sizes, so anything that
 * depends on post-layout pixel sizes inside a shrunk row would see pre-scale values.
 */
public final class ShrinkToFitRow extends LinearLayout {
    private float fit = 1f;

    public ShrinkToFitRow(@NonNull Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Natural pass: what the row wants with no width limit (weights get nothing).
        int un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        super.onMeasure(un, un);
        int needed = getMeasuredWidth();
        int avail = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
            || needed <= avail) {
            fit = 1f;
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int padH = getPaddingLeft() + getPaddingRight();
        int padV = getPaddingTop() + getPaddingBottom();
        fit = needed - padH <= 0 ? 1f : (avail - padH) / (float) (needed - padH);
        // Children keep the natural measurement; the row reports the scaled footprint
        // (its height shrinks with the content -- same ratio vertically as horizontally).
        int h = padV + Math.round((getMeasuredHeight() - padV) * fit);
        setMeasuredDimension(avail, resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (fit >= 1f) {
            // Coming back from a shrunk state (rotation, text change) the transforms
            // must not linger.
            for (int i = 0; i < getChildCount(); i++) {
                var c = getChildAt(i);
                c.setScaleX(1f);
                c.setScaleY(1f);
            }
            super.onLayout(changed, l, t, r, b);
            return;
        }
        float x = getPaddingLeft();
        int contentH = b - t - getPaddingTop() - getPaddingBottom();
        for (int i = 0; i < getChildCount(); i++) {
            var c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            var lp = (LayoutParams) c.getLayoutParams();
            int w = c.getMeasuredWidth();
            int h = c.getMeasuredHeight();
            x += lp.leftMargin * fit;
            int cl = Math.round(x);
            // Center the SCALED height; the transform pivots at the child's top-left, so
            // the layout position is where the visual ends up.
            int ct = getPaddingTop() + Math.round((contentH - h * fit) / 2f);
            c.layout(cl, ct, cl + w, ct + h);
            c.setPivotX(0f);
            c.setPivotY(0f);
            c.setScaleX(fit);
            c.setScaleY(fit);
            x += (w + lp.rightMargin) * fit;
        }
    }
}
