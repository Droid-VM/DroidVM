// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.lib.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.tabs.TabLayout;

/**
 * A tab bar that spreads its tabs evenly across the full width while they fit, and falls back
 * to natural-width scrollable tabs when they do not.
 *
 * <p>Material offers either behaviour but never both: {@code MODE_FIXED} always fills and never
 * scrolls, so long labels get squeezed, while {@code MODE_AUTO} equalises the tabs to the widest
 * one and centres them, leaving the rest of the bar empty. This picks between the two on every
 * measure pass instead.
 *
 * <p>Set {@code app:tabMinWidth} in the layout: Material's own default is 72dp on phones but
 * 160dp under {@code sw600dp}, which is wide enough to push a handful of short tabs off a tablet
 * screen.
 */
public final class AdaptiveTabLayout extends TabLayout {

    public AdaptiveTabLayout(@NonNull Context context) {
        super(context);
    }

    public AdaptiveTabLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public AdaptiveTabLayout(
        @NonNull Context context,
        @Nullable AttributeSet attrs,
        int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            int available =
                MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
            int widest = widestTabWidth();
            // Filling gives every tab the same width, so the widest label is what decides
            // whether spreading them out would squeeze anything.
            if (widest > 0 && available > 0)
                setSpreadTabs(widest * getTabCount() <= available);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * Width of the widest tab at its natural size. Measured with an unspecified spec, so the
     * answer does not depend on the mode currently in effect and the choice cannot oscillate.
     */
    private int widestTabWidth() {
        View strip = getChildCount() > 0 ? getChildAt(0) : null;
        if (!(strip instanceof ViewGroup)) return 0;
        var tabs = (ViewGroup) strip;
        int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        int widest = 0;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            View tab = tabs.getChildAt(i);
            if (tab.getVisibility() == GONE) continue;
            tab.measure(unspecified, unspecified);
            widest = Math.max(widest, tab.getMeasuredWidth());
        }
        return widest;
    }

    private void setSpreadTabs(boolean spread) {
        int mode = spread ? MODE_FIXED : MODE_SCROLLABLE;
        int gravity = spread ? GRAVITY_FILL : GRAVITY_START;
        if (getTabMode() == mode && getTabGravity() == gravity) return;
        // Material rejects MODE_SCROLLABLE + GRAVITY_FILL and MODE_FIXED + GRAVITY_START with a
        // warning, and both setters apply the pair immediately. GRAVITY_CENTER is valid with
        // either mode, so step through it to keep every intermediate state a supported one.
        setTabGravity(GRAVITY_CENTER);
        setTabMode(mode);
        setTabGravity(gravity);
    }
}
