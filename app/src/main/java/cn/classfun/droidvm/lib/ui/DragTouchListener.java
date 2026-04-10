package cn.classfun.droidvm.lib.ui;

import static java.lang.Math.hypot;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class DragTouchListener implements View.OnTouchListener {
    private float dx = 0, dy = 0, startRawX = 0, startRawY = 0;
    private boolean dragging = false;
    private final int touchSlop;
    private final Runnable onClick;

    public DragTouchListener(Context ctx, @Nullable Runnable onClick) {
        this.onClick = onClick;
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
    }

    public boolean onTouch(View v, @NonNull MotionEvent event) {
        float rx = event.getRawX(), ry = event.getRawY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dx = v.getX() - rx;
                dy = v.getY() - ry;
                startRawX = rx;
                startRawY = ry;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    var dist = hypot(rx - startRawX, ry - startRawY);
                    if (dist > touchSlop) dragging = true;
                }
                if (dragging) {
                    var parent = (View) v.getParent();
                    float vw = v.getWidth() / 2F, vh = v.getHeight() / 2F;
                    int pw = parent.getWidth(), ph = parent.getHeight();
                    v.setX(max(-vw, min(rx + dx, pw - vw)));
                    v.setY(max(-vh, min(ry + dy, ph - vh)));
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    v.performClick();
                    if (onClick != null)
                        onClick.run();
                }
                return true;
        }
        return false;
    }
}
