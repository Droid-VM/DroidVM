// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.vm.display.base;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;

/** Intercept window-wide captured events before a focused IME child can consume them. */
public final class CapturedPointerLayout extends LinearLayout {
    public interface Handler {
        boolean onEvent(MotionEvent event);
        void onCaptureChanged(boolean captured);
    }
    private Handler handler;
    public CapturedPointerLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusableInTouchMode(true);
    }
    public void setCaptureHandler(Handler handler) { this.handler = handler; }
    @Override public boolean dispatchCapturedPointerEvent(MotionEvent event) {
        return handler != null && handler.onEvent(event) || super.dispatchCapturedPointerEvent(event);
    }
    @Override public void onPointerCaptureChange(boolean captured) {
        super.onPointerCaptureChange(captured);
        if (handler != null) handler.onCaptureChanged(captured);
    }
}
