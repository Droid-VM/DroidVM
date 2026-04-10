package cn.classfun.droidvm.lib.ui;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class TabSwipeHelper {

    public interface Callback {
        int getTabCount();

        int getCurrentTabIndex();

        @NonNull
        View getTabView(int index);

        void onTabSwitchedByDrag(int newIndex);
    }

    @FunctionalInterface
    @SuppressWarnings("unused")
    public interface TouchDispatcher {
        void dispatch(MotionEvent ev);
    }

    private static final float SNAP_FRACTION = 0.3f;
    private static final int SNAP_VELOCITY = 500;
    private static final int SETTLE_DURATION = 200;
    private static final int ANIM_DURATION = 250;
    private static final int ANIM_MULTI_TOTAL = 400;
    private static final int ANIM_MULTI_STEP_MIN = 80;

    private final Activity activity;
    private final Callback callback;
    private VelocityTracker velocityTracker;
    private float touchStartX, touchStartY;
    private boolean isDragging = false;
    private boolean touchDecided = false;
    private boolean settling = false;
    private View dragPrevView, dragCurrentView, dragNextView;
    private Runnable animationEndCallback;

    public TabSwipeHelper(@NonNull Activity activity, @NonNull Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public boolean isSettling() {
        return settling;
    }

    @SuppressWarnings("SameReturnValue")
    private boolean onMotionEventDown(
        @NonNull MotionEvent ev,
        @NonNull TouchDispatcher ignored
    ) {
        touchStartX = ev.getX();
        touchStartY = ev.getY();
        isDragging = false;
        touchDecided = false;
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        else velocityTracker.clear();
        velocityTracker.addMovement(ev);
        return false;
    }

    private boolean onMotionEventMove(
        @NonNull MotionEvent ev,
        @NonNull TouchDispatcher superDispatch
    ) {
        if (velocityTracker != null)
            velocityTracker.addMovement(ev);
        if (!touchDecided) {
            float adx = Math.abs(ev.getX() - touchStartX);
            float ady = Math.abs(ev.getY() - touchStartY);
            int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            if (adx > slop || ady > slop) {
                touchDecided = true;
                if (adx > ady) {
                    float dx = ev.getX() - touchStartX;
                    int pos = callback.getCurrentTabIndex();
                    int last = callback.getTabCount() - 1;
                    isDragging = !((dx > 0 && pos == 0) ||
                        (dx < 0 && pos == last));
                }
                if (isDragging) {
                    prepareDrag();
                    MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    superDispatch.dispatch(cancel);
                    cancel.recycle();
                }
            }
        }
        if (isDragging) {
            updateDrag(ev.getX() - touchStartX);
            return true;
        }
        return false;
    }

    private boolean onMotionEventUp(
        @NonNull MotionEvent ev,
        @NonNull TouchDispatcher ignored
    ) {
        if (isDragging && velocityTracker != null) {
            velocityTracker.addMovement(ev);
            velocityTracker.computeCurrentVelocity(1000);
            finishDrag(
                ev.getX() - touchStartX,
                velocityTracker.getXVelocity()
            );
            isDragging = false;
            touchDecided = false;
            return true;
        }
        isDragging = false;
        touchDecided = false;
        return false;
    }

    private boolean onMotionEventCancel(
        @NonNull MotionEvent ev,
        @NonNull TouchDispatcher ignored
    ) {
        if (isDragging) {
            finishDrag(ev.getX() - touchStartX, 0);
            isDragging = false;
            touchDecided = false;
            return true;
        }
        touchDecided = false;
        return false;
    }

    public boolean onDispatchTouchEvent(
        @NonNull MotionEvent ev,
        @NonNull TouchDispatcher superDispatch
    ) {
        if (settling) return false;
        if (callback.getTabCount() <= 1) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onMotionEventDown(ev, superDispatch);
            case MotionEvent.ACTION_MOVE:
                return onMotionEventMove(ev, superDispatch);
            case MotionEvent.ACTION_UP:
                return onMotionEventUp(ev, superDispatch);
            case MotionEvent.ACTION_CANCEL:
                return onMotionEventCancel(ev, superDispatch);
        }
        return false;
    }

    private int getWidth() {
        return activity.getWindow().getDecorView().getWidth();
    }

    private void prepareDrag() {
        int pos = callback.getCurrentTabIndex();
        int count = callback.getTabCount();
        dragCurrentView = callback.getTabView(pos);
        dragPrevView = pos > 0 ? callback.getTabView(pos - 1) : null;
        dragNextView = pos < count - 1 ? callback.getTabView(pos + 1) : null;
        int width = dragCurrentView.getWidth();
        if (width <= 0) width = getWidth();
        if (dragPrevView != null) {
            dragPrevView.animate().cancel();
            dragPrevView.setTranslationX(-width);
            dragPrevView.setVisibility(View.VISIBLE);
        }
        if (dragNextView != null) {
            dragNextView.animate().cancel();
            dragNextView.setTranslationX(width);
            dragNextView.setVisibility(View.VISIBLE);
        }
    }

    private void updateDrag(float dx) {
        if (dragCurrentView == null) return;
        int width = dragCurrentView.getWidth();
        if (dragPrevView == null && dx > 0) dx = 0;
        if (dragNextView == null && dx < 0) dx = 0;
        dragCurrentView.setTranslationX(dx);
        if (dragPrevView != null) dragPrevView.setTranslationX(dx - width);
        if (dragNextView != null) dragNextView.setTranslationX(dx + width);
    }

    private void finishDrag(float dx, float velocityX) {
        if (dragCurrentView == null) return;
        int width = dragCurrentView.getWidth();
        if (dragPrevView == null && dx > 0) dx = 0;
        if (dragNextView == null && dx < 0) dx = 0;
        boolean goNext = false, goPrev = false;
        if (Math.abs(velocityX) > SNAP_VELOCITY) {
            if (velocityX > 0 && dragPrevView != null) goPrev = true;
            else if (velocityX < 0 && dragNextView != null) goNext = true;
        } else if (Math.abs(dx) > width * SNAP_FRACTION) {
            if (dx > 0 && dragPrevView != null) goPrev = true;
            else if (dx < 0 && dragNextView != null) goNext = true;
        }
        settling = true;
        int current = callback.getCurrentTabIndex();
        if (goPrev) settleDragTo(current - 1);
        else if (goNext) settleDragTo(current + 1);
        else settleBack();
    }

    private void settleDragTo(int targetIdx) {
        int current = callback.getCurrentTabIndex();
        int width = dragCurrentView.getWidth();
        boolean toNext = targetIdx > current;
        View targetView = toNext ? dragNextView : dragPrevView;
        View otherView = toNext ? dragPrevView : dragNextView;
        if (otherView != null) {
            otherView.animate().cancel();
            otherView.setVisibility(View.GONE);
            otherView.setTranslationX(0);
        }
        dragCurrentView.animate()
            .translationX(toNext ? -width : width)
            .setDuration(SETTLE_DURATION)
            .withEndAction(() -> {
                dragCurrentView.setVisibility(View.GONE);
                dragCurrentView.setTranslationX(0);
            })
            .start();
        targetView.animate()
            .translationX(0)
            .setDuration(SETTLE_DURATION)
            .withEndAction(() -> {
                settling = false;
                callback.onTabSwitchedByDrag(targetIdx);
            })
            .start();
    }

    private void settleBack() {
        int width = dragCurrentView.getWidth();
        dragCurrentView.animate()
            .translationX(0)
            .setDuration(SETTLE_DURATION)
            .withEndAction(() -> settling = false)
            .start();
        if (dragPrevView != null) {
            dragPrevView.animate()
                .translationX(-width)
                .setDuration(SETTLE_DURATION)
                .withEndAction(() -> {
                    dragPrevView.setVisibility(View.GONE);
                    dragPrevView.setTranslationX(0);
                })
                .start();
        }
        if (dragNextView != null) {
            dragNextView.animate()
                .translationX(width)
                .setDuration(SETTLE_DURATION)
                .withEndAction(() -> {
                    dragNextView.setVisibility(View.GONE);
                    dragNextView.setTranslationX(0);
                })
                .start();
        }
    }

    public void animateToTab(int newIndex, int direction) {
        animateToTab(newIndex, direction, null);
    }

    public void animateToTab(int newIndex, int direction, @Nullable Runnable onComplete) {
        int count = callback.getTabCount();
        if (newIndex < 0 || newIndex >= count) return;
        this.animationEndCallback = onComplete;
        for (int i = 0; i < count; i++) {
            View v = callback.getTabView(i);
            v.animate().cancel();
            v.setTranslationX(0);
        }
        if (direction == 0) {
            for (int i = 0; i < count; i++)
                callback.getTabView(i).setVisibility(
                    i == newIndex ? View.VISIBLE : View.GONE);
            fireAnimationEnd();
            return;
        }
        int oldIdx = -1;
        for (int i = 0; i < count; i++) {
            if (callback.getTabView(i).getVisibility() == View.VISIBLE) {
                oldIdx = i;
                break;
            }
        }
        if (oldIdx < 0 || oldIdx == newIndex) {
            for (int i = 0; i < count; i++)
                callback.getTabView(i).setVisibility(
                    i == newIndex ? View.VISIBLE : View.GONE);
            fireAnimationEnd();
            return;
        }
        for (int i = 0; i < count; i++)
            if (i != oldIdx) callback.getTabView(i).setVisibility(View.GONE);
        int steps = Math.abs(newIndex - oldIdx);
        if (steps == 1) {
            doAnimateStep(oldIdx, newIndex, direction, ANIM_DURATION, -1);
        } else {
            int perStep = Math.max(ANIM_MULTI_TOTAL / steps, ANIM_MULTI_STEP_MIN);
            doAnimateStep(oldIdx, oldIdx + direction, direction, perStep, newIndex);
        }
    }

    public void setTabImmediate(int index) {
        animateToTab(index, 0);
    }

    private void fireAnimationEnd() {
        if (animationEndCallback != null) {
            var cb = animationEndCallback;
            animationEndCallback = null;
            cb.run();
        }
    }

    private void doAnimateStep(
        int outIdx, int inIdx,
        int direction, int duration, int finalIdx
    ) {
        View outView = callback.getTabView(outIdx);
        View inView = callback.getTabView(inIdx);
        int width = outView.getWidth();
        if (width <= 0) width = getWidth();
        Runnable animateEndAction = () -> {
            outView.setVisibility(View.GONE);
            outView.setTranslationX(0);
            if (finalIdx >= 0 && inIdx != finalIdx) {
                doAnimateStep(
                    inIdx, inIdx + direction,
                    direction, duration, finalIdx
                );
            } else {
                fireAnimationEnd();
            }
        };
        outView.animate()
            .translationX(-direction * width)
            .setDuration(duration)
            .withEndAction(animateEndAction)
            .start();
        inView.setTranslationX(direction * width);
        inView.setVisibility(View.VISIBLE);
        inView.animate()
            .translationX(0)
            .setDuration(duration)
            .start();
    }
}

