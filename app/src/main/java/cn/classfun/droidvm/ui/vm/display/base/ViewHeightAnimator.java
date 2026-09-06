// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.R;

/**
 * Slide a bottom-docked row/panel open or closed by animating its layout height. The view's
 * pre-animation layout height (fixed dp or WRAP_CONTENT) is remembered on first use and restored
 * when the animation finishes - blindly resetting to WRAP_CONTENT would permanently squash rows
 * whose height comes from their own fixed layout_height (their match_parent children then wrap to
 * text height).
 */
public final class ViewHeightAnimator {
    private static final long DURATION = 200;

    private ViewHeightAnimator() {
    }

    public static void setVisible(@NonNull View view, boolean visible) {
        if (visible) show(view);
        else hide(view);
    }

    /** The view's own layout height before any animation touched it. */
    private static int originalHeight(@NonNull View view) {
        Object tag = view.getTag(R.id.view_height_animator_original);
        if (tag instanceof Integer) return (Integer) tag;
        int height = view.getLayoutParams().height;
        view.setTag(R.id.view_height_animator_original, height);
        return height;
    }

    public static void show(@NonNull View view) {
        if (view.getVisibility() == View.VISIBLE) return;
        int original = originalHeight(view);
        view.setVisibility(View.VISIBLE);
        int target;
        if (original > 0) {
            target = original;
        } else {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(
                    ((View) view.getParent()).getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );
            target = view.getMeasuredHeight();
        }
        var lp = view.getLayoutParams();
        lp.height = 0;
        view.requestLayout();
        var anim = ValueAnimator.ofInt(0, target);
        anim.setDuration(DURATION);
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            view.requestLayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                lp.height = original;
                view.requestLayout();
            }
        });
        anim.start();
    }

    public static void hide(@NonNull View view) {
        if (view.getVisibility() == View.GONE) return;
        int original = originalHeight(view);
        int start = view.getHeight();
        var lp = view.getLayoutParams();
        var anim = ValueAnimator.ofInt(start, 0);
        anim.setDuration(DURATION);
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            view.requestLayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                view.setVisibility(View.GONE);
                lp.height = original;
                view.requestLayout();
            }
        });
        anim.start();
    }
}
