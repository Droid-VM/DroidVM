package cn.classfun.droidvm.ui.vm.display.base;

import static android.view.HapticFeedbackConstants.KEYBOARD_TAP;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.R;

public final class DisplayTouchPadPanel extends LinearLayout {
    private static final float TOUCHPAD_SENSITIVITY = 1.5f;
    private static final float TAP_SLOP = 20f;
    private static final long TAP_TIMEOUT = 250;
    private static final float SCROLL_THRESHOLD = 8f;

    public enum Buttons {
        LEFT,
        MIDDLE,
        RIGHT;

        public int toMask() {
            return 1 << ordinal();
        }
    }

    public interface TouchPadListener {
        void onCursorMove(float dx, float dy);

        void onTap();

        void onScroll(boolean up);

        void onMouseButton(Buttons button, boolean pressed);
    }

    @Nullable
    private TouchPadListener listener;
    private FrameLayout touchpadArea;
    private View scrollBar;

    private float lastTouchX, lastTouchY;
    private boolean touchActive = false;
    private long touchDownTime;
    private float touchDownX, touchDownY;
    private boolean twoFingerActive = false;
    private float lastTwoFingerY;
    private float scrollBarLastY;

    public DisplayTouchPadPanel(@NonNull Context context) {
        super(context);
        init(context);
    }

    public DisplayTouchPadPanel(
        @NonNull Context context,
        @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        init(context);
    }

    public DisplayTouchPadPanel(
        @NonNull Context context,
        @Nullable AttributeSet attrs,
        int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(@NonNull Context context) {
        setOrientation(VERTICAL);
        var inf = LayoutInflater.from(context);
        inf.inflate(R.layout.widget_display_touchpad, this, true);
        touchpadArea = findViewById(R.id.touchpad_area);
        scrollBar = findViewById(R.id.scroll_bar);
        setupTouchpad();
        setupScrollBar();
        setupMouseButtons();
    }

    public void setTouchPadListener(@Nullable TouchPadListener listener) {
        this.listener = listener;
    }

    @NonNull
    public FrameLayout getTouchpadArea() {
        return touchpadArea;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchpad() {
        touchpadArea.setOnTouchListener(this::onTouchpadTouch);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupScrollBar() {
        scrollBar.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scrollBarLastY = event.getY();
                    v.performHapticFeedback(KEYBOARD_TAP);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = event.getY() - scrollBarLastY;
                    if (Math.abs(dy) > SCROLL_THRESHOLD) {
                        if (listener != null) listener.onScroll(dy < 0);
                        scrollBarLastY = event.getY();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return false;
        });
    }

    private void setupMouseButtons() {
        setMouseButton(R.id.btn_mouse_left, Buttons.LEFT);
        setMouseButton(R.id.btn_mouse_middle, Buttons.MIDDLE);
        setMouseButton(R.id.btn_mouse_right, Buttons.RIGHT);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setMouseButton(int id, Buttons button) {
        findViewById(id).setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.performHapticFeedback(KEYBOARD_TAP);
                    if (listener != null) listener.onMouseButton(button, true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (listener != null) listener.onMouseButton(button, false);
                    return true;
            }
            return false;
        });
    }

    @SuppressWarnings("unused")
    private boolean onTouchpadTouch(View v, @NonNull MotionEvent event) {
        int pointerCount = event.getPointerCount();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchDownTime = System.currentTimeMillis();
                touchActive = true;
                twoFingerActive = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointerCount == 2) {
                    twoFingerActive = true;
                    lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (twoFingerActive && pointerCount >= 2) {
                    float midY = (event.getY(0) + event.getY(1)) / 2f;
                    float dy = midY - lastTwoFingerY;
                    lastTwoFingerY = midY;
                    if (Math.abs(dy) > 2) {
                        if (listener != null) listener.onScroll(dy < 0);
                    }
                } else if (touchActive && pointerCount == 1) {
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    if (listener != null) listener.onCursorMove(
                        dx * TOUCHPAD_SENSITIVITY,
                        dy * TOUCHPAD_SENSITIVITY
                    );
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!twoFingerActive && touchActive) {
                    float dist = (float) Math.hypot(
                        event.getX() - touchDownX,
                        event.getY() - touchDownY
                    );
                    long elapsed = System.currentTimeMillis() - touchDownTime;
                    if (dist < TAP_SLOP && elapsed < TAP_TIMEOUT) {
                        if (listener != null) listener.onTap();
                    }
                }
                touchActive = false;
                twoFingerActive = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                if (pointerCount <= 2) twoFingerActive = false;
                return true;
        }
        return false;
    }
}
