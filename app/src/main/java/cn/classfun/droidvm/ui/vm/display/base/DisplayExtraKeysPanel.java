package cn.classfun.droidvm.ui.vm.display.base;

import static android.view.HapticFeedbackConstants.KEYBOARD_TAP;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.classfun.droidvm.R;

public final class DisplayExtraKeysPanel extends LinearLayout {
    private static final long ANIM_DURATION = 200;
    private static final long KEY_REPEAT_DELAY_MS = 400;
    private static final long KEY_REPEAT_INTERVAL_MS = 50;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable activeRepeatRunnable;
    private LinearLayout fnKeysContainer;
    private boolean capsActive = false;
    private boolean fnxActive = false;
    @Nullable
    private KeyListener keyListener;

    private boolean ctrlDown, altDown, shiftDown, winDown;

    public DisplayExtraKeysPanel(@NonNull Context context) {
        super(context);
        init(context);
    }

    public DisplayExtraKeysPanel(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public DisplayExtraKeysPanel(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(@NonNull Context context) {
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.widget_display_extra_keys, this, true);
        fnKeysContainer = findViewById(R.id.fn_keys_container);
        setupKeys();
    }

    public void setKeyListener(@Nullable KeyListener listener) {
        this.keyListener = listener;
    }

    @SuppressWarnings("unused")
    public boolean isCtrlDown() {
        return ctrlDown;
    }

    @SuppressWarnings("unused")
    public boolean isAltDown() {
        return altDown;
    }

    @SuppressWarnings("unused")
    public boolean isShiftDown() {
        return shiftDown;
    }

    @SuppressWarnings("unused")
    public boolean isWinDown() {
        return winDown;
    }

    @SuppressWarnings("unused")
    public boolean isCapsActive() {
        return capsActive;
    }

    public void setCtrlDown(boolean v) {
        ctrlDown = v;
        updateToggleButtons();
    }

    public void setAltDown(boolean v) {
        altDown = v;
        updateToggleButtons();
    }

    public void setShiftDown(boolean v) {
        shiftDown = v;
        updateToggleButtons();
    }

    public void setWinDown(boolean v) {
        winDown = v;
        updateToggleButtons();
    }

    public void updateToggleButtons() {
        setToggleStyle(findViewById(R.id.btn_ctrl), ctrlDown);
        setToggleStyle(findViewById(R.id.btn_alt), altDown);
        setToggleStyle(findViewById(R.id.btn_shift), shiftDown);
        setToggleStyle(findViewById(R.id.btn_win), winDown);
        setToggleStyle(findViewById(R.id.btn_cap), capsActive);
        setToggleStyle(findViewById(R.id.btn_fnx), fnxActive);
    }

    private void setToggleStyle(@Nullable Button btn, boolean active) {
        if (btn == null) return;
        if (active) {
            btn.setBackgroundColor(getContext().getColor(R.color.extra_key_bg_active));
            btn.setTextColor(getContext().getColor(R.color.extra_key_text_active));
        } else {
            btn.setBackground(null);
            btn.setTextColor(getContext().getColor(R.color.extra_key_text));
        }
    }

    private void setupKeys() {
        setKeyRepeat(R.id.btn_esc, () -> fireKeyRepeat(KeyEvent.KEYCODE_ESCAPE));
        setKeyRepeat(R.id.btn_slash, () -> fireCharRepeat('/'));
        setKeyRepeat(R.id.btn_dash, () -> fireCharRepeat('-'));
        setKeyRepeat(R.id.btn_home, () -> fireKeyRepeat(KeyEvent.KEYCODE_MOVE_HOME));
        setKeyRepeat(R.id.btn_up, () -> fireKeyRepeat(KeyEvent.KEYCODE_DPAD_UP));
        setKeyRepeat(R.id.btn_end, () -> fireKeyRepeat(KeyEvent.KEYCODE_MOVE_END));
        setKeyRepeat(R.id.btn_pgup, () -> fireKeyRepeat(KeyEvent.KEYCODE_PAGE_UP));
        setKeyRepeat(R.id.btn_tab, () -> fireKeyRepeat(KeyEvent.KEYCODE_TAB));
        setupModifierKey(R.id.btn_ctrl, KeyEvent.KEYCODE_CTRL_LEFT);
        setupModifierKey(R.id.btn_alt, KeyEvent.KEYCODE_ALT_LEFT);
        setKeyRepeat(R.id.btn_left, () -> fireKeyRepeat(KeyEvent.KEYCODE_DPAD_LEFT));
        setKeyRepeat(R.id.btn_down, () -> fireKeyRepeat(KeyEvent.KEYCODE_DPAD_DOWN));
        setKeyRepeat(R.id.btn_right, () -> fireKeyRepeat(KeyEvent.KEYCODE_DPAD_RIGHT));
        setKeyRepeat(R.id.btn_pgdn, () -> fireKeyRepeat(KeyEvent.KEYCODE_PAGE_DOWN));
        setupModifierKey(R.id.btn_win, KeyEvent.KEYCODE_META_LEFT);
        setKeyClick(R.id.btn_cap, v -> {
            capsActive = !capsActive;
            if (keyListener != null) keyListener.onCapsToggle(capsActive);
            updateToggleButtons();
        });
        setupModifierKey(R.id.btn_shift, KeyEvent.KEYCODE_SHIFT_LEFT);
        setKeyRepeat(R.id.btn_del, () -> fireKeyRepeat(KeyEvent.KEYCODE_FORWARD_DEL));
        setKeyRepeat(R.id.btn_ins, () -> fireKeyRepeat(KeyEvent.KEYCODE_INSERT));
        setKeyRepeat(R.id.btn_enter, () -> fireKeyRepeat(KeyEvent.KEYCODE_ENTER));
        setKeyClick(R.id.btn_fnx, v -> {
            fnxActive = !fnxActive;
            setRowVisible(fnKeysContainer, fnxActive);
            updateToggleButtons();
        });
        setKeyRepeat(R.id.btn_f1, () -> fireKeyRepeat(KeyEvent.KEYCODE_F1));
        setKeyRepeat(R.id.btn_f2, () -> fireKeyRepeat(KeyEvent.KEYCODE_F2));
        setKeyRepeat(R.id.btn_f3, () -> fireKeyRepeat(KeyEvent.KEYCODE_F3));
        setKeyRepeat(R.id.btn_f4, () -> fireKeyRepeat(KeyEvent.KEYCODE_F4));
        setKeyRepeat(R.id.btn_f5, () -> fireKeyRepeat(KeyEvent.KEYCODE_F5));
        setKeyRepeat(R.id.btn_f6, () -> fireKeyRepeat(KeyEvent.KEYCODE_F6));
        setKeyRepeat(R.id.btn_f7, () -> fireKeyRepeat(KeyEvent.KEYCODE_F7));
        setKeyRepeat(R.id.btn_f8, () -> fireKeyRepeat(KeyEvent.KEYCODE_F8));
        setKeyRepeat(R.id.btn_f9, () -> fireKeyRepeat(KeyEvent.KEYCODE_F9));
        setKeyRepeat(R.id.btn_f10, () -> fireKeyRepeat(KeyEvent.KEYCODE_F10));
        setKeyRepeat(R.id.btn_f11, () -> fireKeyRepeat(KeyEvent.KEYCODE_F11));
        setKeyRepeat(R.id.btn_f12, () -> fireKeyRepeat(KeyEvent.KEYCODE_F12));
    }

    private void fireKeyRepeat(int keyCode) {
        if (keyListener != null) keyListener.onKeyRepeat(keyCode);
    }

    private void fireCharRepeat(char ch) {
        if (keyListener != null) keyListener.onCharRepeat(ch);
    }

    private void setKeyClick(int id, OnClickListener listener) {
        findViewById(id).setOnClickListener(v -> {
            v.performHapticFeedback(KEYBOARD_TAP);
            listener.onClick(v);
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setKeyRepeat(int id, Runnable action) {
        findViewById(id).setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    v.performHapticFeedback(KEYBOARD_TAP);
                    action.run();
                    stopKeyRepeat();
                    activeRepeatRunnable = () -> {
                        action.run();
                        handler.postDelayed(activeRepeatRunnable, KEY_REPEAT_INTERVAL_MS);
                    };
                    handler.postDelayed(activeRepeatRunnable, KEY_REPEAT_DELAY_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    stopKeyRepeat();
                    return true;
            }
            return false;
        });
    }

    private void setupModifierKey(int btnId, int keyCode) {
        View btn = findViewById(btnId);
        btn.setOnClickListener(v -> {
            v.performHapticFeedback(KEYBOARD_TAP);
            if (keyListener != null) keyListener.onModifierClick(keyCode);
        });
        btn.setOnLongClickListener(v -> {
            v.performHapticFeedback(KEYBOARD_TAP);
            if (keyListener != null) keyListener.onModifierLongClick(keyCode);
            return true;
        });
    }

    public void stopKeyRepeat() {
        if (activeRepeatRunnable != null) {
            handler.removeCallbacks(activeRepeatRunnable);
            activeRepeatRunnable = null;
        }
    }

    public void animateIn() {
        animateRowIn(this);
    }

    public void animateOut() {
        animateRowOut(this);
    }

    public void setVisibleAnimated(boolean visible) {
        if (visible) animateIn();
        else animateOut();
    }

    private void animateRowIn(@NonNull View row) {
        if (row.getVisibility() == VISIBLE) return;
        row.setVisibility(VISIBLE);
        row.measure(
            MeasureSpec.makeMeasureSpec(
                ((View) row.getParent()).getWidth(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        );
        int target = row.getMeasuredHeight();
        var lp = row.getLayoutParams();
        lp.height = 0;
        row.requestLayout();
        var anim = ValueAnimator.ofInt(0, target);
        anim.setDuration(ANIM_DURATION);
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            row.requestLayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                row.requestLayout();
            }
        });
        anim.start();
    }

    private void animateRowOut(@NonNull View row) {
        if (row.getVisibility() == GONE) return;
        int start = row.getHeight();
        var lp = row.getLayoutParams();
        var anim = ValueAnimator.ofInt(start, 0);
        anim.setDuration(ANIM_DURATION);
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            row.requestLayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                row.setVisibility(GONE);
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                row.requestLayout();
            }
        });
        anim.start();
    }

    private void setRowVisible(@NonNull View row, boolean visible) {
        if (visible) animateRowIn(row);
        else animateRowOut(row);
    }
}
