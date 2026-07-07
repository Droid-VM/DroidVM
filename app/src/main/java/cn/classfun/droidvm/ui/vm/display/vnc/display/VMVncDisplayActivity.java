package cn.classfun.droidvm.ui.vm.display.vnc.display;

import static android.view.Gravity.BOTTOM;
import static android.view.Gravity.CENTER;
import static android.view.Gravity.CENTER_HORIZONTAL;
import static android.view.Gravity.CENTER_VERTICAL;
import static android.view.Gravity.END;
import static android.view.Gravity.START;
import static android.view.Gravity.TOP;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.DragTouchListener;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.vm.display.vnc.base.BaseVncActivity;

public final class VMVncDisplayActivity extends BaseVncActivity {
    private static final long AUTO_HIDE_DELAY_MS = 3000;
    private static final long OP_LABEL_HIDE_DELAY_MS = 2000;
    private static final String PREFS_NAME = "droidvm_prefs";
    private static final String KEY_INPUT_MODE = "display_input_mode";
    private static final float TAP_SLOP = 20f;
    private static final long TAP_TIMEOUT = 250;
    private static final long DOUBLE_TAP_TIMEOUT = 300;
    private static final float DEFAULT_ZOOM = 1f;
    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 5f;
    private static final float SNAP_THRESHOLD = 15f;
    private static final float MIN_SCALE_DIST = 24f;
    private static final float SCROLL_THRESHOLD = 8f;
    private static final int MASK_LEFT = 1;
    private static final int MASK_MIDDLE = 2;
    private static final int MASK_RIGHT = 4;
    private static final int MASK_SCROLL_UP = 8;
    private static final int MASK_SCROLL_DOWN = 16;

    private enum InputMode {TOUCH, MOUSE}
    private LinearLayout statusBar;
    private MaterialButton btnFullscreen;
    private FrameLayout displayContainer;
    private FloatingActionButton fabMenu;
    private TextView operationLabel;
    private boolean isFullscreen = false;
    private boolean extraKeysVisible = true;
    private InputMode inputMode = InputMode.TOUCH;
    private SharedPreferences prefs;
    private float cursorX, cursorY;
    private int baseViewW, baseViewH;
    private float zoom = DEFAULT_ZOOM;
    private float panX, panY;
    private int gestureMaxPointers;
    private boolean gestureMoved;
    private long gestureStartTime;
    private float gestureStartMidX, gestureStartMidY;
    private long lastTapTime;
    private int lastTapFingerCount;
    private float lastTouchX, lastTouchY;
    private float lastMidX, lastMidY;
    private float initialAngle;
    private float rotationBase;
    private float initialDist;
    private float initialZoom;
    private float lastScrollMidY;

    private final Runnable hideOperationLabel = () -> {
        if (operationLabel != null) operationLabel.setVisibility(GONE);
    };


    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_vm_vnc_display;
    }

    @Override
    protected String getActivityTitle() {
        return vmName.isEmpty() ? getString(R.string.vnc_display_title) : vmName;
    }

    @Override
    protected void onBindExtraViews() {
        statusBar = findViewById(R.id.status_bar);
        btnFullscreen = findViewById(R.id.btn_fullscreen);
        displayContainer = findViewById(R.id.display_container);
        fabMenu = findViewById(R.id.fab_menu);
        operationLabel = findViewById(R.id.tv_operation);
    }

    @Override
    protected void onSetupActivity() {
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        inputMode = InputMode.values()[prefs.getInt(KEY_INPUT_MODE, 0)];
        setupCutoutMode();
        setupWindowInsets();
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());
        displayContainer.addOnLayoutChangeListener((
            v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom
        ) -> {
            int cw = right - left, ch = bottom - top;
            if (cw > 0 && ch > 0) v.post(() -> updateAspectRatio(cw, ch));
        });
        setupOperationLabel();
        setupDisplayTouch();
        setupFab();
        applyInputMode();
    }

    private void setupCutoutMode() {
        var params = getWindow().getAttributes();
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(params);
    }

    private void setupWindowInsets() {
        var content = (ViewGroup) findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int top = isFullscreen ? 0 : sysBars.top;
            int bottom = ime.bottom;
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void setupOperationLabel() {
        if (operationLabel == null) return;
        var bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(0x80000000);
        operationLabel.setBackground(bg);
    }

    @Override
    protected void onFramebufferReady(int width, int height) {
        updateAspectRatio(displayContainer.getWidth(), displayContainer.getHeight());
        if (inputMode == InputMode.MOUSE) {
            if (cursorX < 0 || cursorX >= width) cursorX = width / 2f;
            if (cursorY < 0 || cursorY >= height) cursorY = height / 2f;
            ensureCursorVisible();
        }
    }

    @Override
    protected void onBitmapUpdated(@NonNull Bitmap bitmap) {
        ivDisplay.setImageBitmap(bitmap);
    }

    @Override
    protected void onStatusChanged(String text, VncStatus status) {
        mainHandler.removeCallbacks(this::hideBars);
        if (!isFullscreen) showBars();
    }

    @Override
    protected void onDestroyExtra() {
        mainHandler.removeCallbacks(this::hideBars);
        mainHandler.removeCallbacks(hideOperationLabel);
    }

    private boolean onDisplayTouch(View v, MotionEvent event) {
        if (vncClient == null || !vncClient.isConnected()) return false;
        if (fbWidth <= 0 || fbHeight <= 0) return false;
        float viewX = event.getX(), viewY = event.getY();
        float ivW = v.getWidth(), ivH = v.getHeight();
        float imgAspect = (float) fbWidth / fbHeight;
        float viewAspect = ivW / max(ivH, 1);
        float drawnW, drawnH, offsetX, offsetY;
        if (imgAspect > viewAspect) {
            drawnW = ivW;
            drawnH = ivW / imgAspect;
            offsetX = 0;
            offsetY = (ivH - drawnH) / 2;
        } else {
            drawnH = ivH;
            drawnW = ivH * imgAspect;
            offsetX = (ivW - drawnW) / 2;
            offsetY = 0;
        }
        int vncX = (int) ((viewX - offsetX) / drawnW * fbWidth);
        int vncY = (int) ((viewY - offsetY) / drawnH * fbHeight);
        vncX = max(0, min(vncX, fbWidth - 1));
        vncY = max(0, min(vncY, fbHeight - 1));
        int mask;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                mask = 1;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mask = 0;
                break;
            default:
                return false;
        }
        vncClient.sendPointer(vncX, vncY, mask);
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDisplayTouch() {
        ivDisplay.setTextCommitListener(createTextCommitListener());
    }

    @SuppressLint("ClickableViewAccessibility")
    private void applyInputMode() {
        if (inputMode == InputMode.MOUSE) {
            ivDisplay.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ivDisplay.setClickable(false);
            ivDisplay.setOnTouchListener(null);
            displayContainer.setClickable(true);
            displayContainer.setOnClickListener(null);
            displayContainer.setOnTouchListener(this::onMouseTouch);
            if (operationLabel != null) operationLabel.setVisibility(GONE);
            applyViewSize();
            applyViewTransform();
            ensureCursorVisible();
        } else {
            ivDisplay.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ivDisplay.setClickable(true);
            ivDisplay.setOnTouchListener(this::onDisplayTouch);
            displayContainer.setOnTouchListener(null);
            displayContainer.setClickable(true);
            displayContainer.setOnClickListener(v -> {
                showBars();
                toggleSoftKeyboard();
            });
            if (operationLabel != null) operationLabel.setVisibility(GONE);
        }
    }

    private void setInputMode(InputMode mode) {
        if (inputMode == mode) return;
        inputMode = mode;
        prefs.edit().putInt(KEY_INPUT_MODE, mode.ordinal()).apply();
        resetViewTransform();
        if (mode == InputMode.MOUSE) {
            if (fbWidth > 0) cursorX = fbWidth / 2f;
            if (fbHeight > 0) cursorY = fbHeight / 2f;
        }
        applyViewSize();
        applyInputMode();
    }

    private void resetViewTransform() {
        zoom = DEFAULT_ZOOM;
        panX = 0;
        panY = 0;
        ivDisplay.setTranslationX(0);
        ivDisplay.setTranslationY(0);
        ivDisplay.setRotation(0);
    }

    private int currentViewW() {
        return inputMode == InputMode.MOUSE
            ? Math.round(baseViewW * zoom) : baseViewW;
    }

    private int currentViewH() {
        return inputMode == InputMode.MOUSE
            ? Math.round(baseViewH * zoom) : baseViewH;
    }

    private void applyViewSize() {
        if (baseViewW <= 0 || baseViewH <= 0) return;
        int w = currentViewW(), h = currentViewH();
        var lp = ivDisplay.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) lp).gravity = CENTER;
            lp.width = w;
            lp.height = h;
        } else {
            lp = new FrameLayout.LayoutParams(w, h, CENTER);
        }
        ivDisplay.setLayoutParams(lp);
    }

    private void applyViewTransform() {
        ivDisplay.setTranslationX(panX);
        ivDisplay.setTranslationY(panY);
    }

    private float midX(@NonNull MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i);
        return s / e.getPointerCount();
    }

    private float midY(@NonNull MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
        return s / e.getPointerCount();
    }

    @SuppressLint("ClickableViewAccessibility")
    private boolean onMouseTouch(View v, MotionEvent event) {
        if (vncClient == null || !vncClient.isConnected()) return false;
        if (fbWidth <= 0 || fbHeight <= 0) return false;
        int pc = event.getPointerCount();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureMaxPointers = 1;
                gestureMoved = false;
                gestureStartTime = System.currentTimeMillis();
                gestureStartMidX = event.getX();
                gestureStartMidY = event.getY();
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                gestureMaxPointers = max(gestureMaxPointers, pc);
                // Re-anchor the tap reference to the multi-finger midpoint and
                // restart the tap window. The ACTION_DOWN anchor was a single
                // finger position, so the two-finger midpoint sits ~half a
                // finger-spread away and would instantly trip gestureMoved,
                // making a still two-finger tap (right click) impossible.
                gestureStartMidX = midX(event);
                gestureStartMidY = midY(event);
                gestureStartTime = System.currentTimeMillis();
                gestureMoved = false;
                if (pc == 2) initTwoFinger(event);
                if (pc >= 3) lastScrollMidY = midY(event);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float mx = midX(event), my = midY(event);
                float dist = (float) Math.hypot(
                    mx - gestureStartMidX, my - gestureStartMidY);
                if (dist > TAP_SLOP) gestureMoved = true;
                if (gestureMaxPointers >= 3 && pc >= 3) {
                    float dy = my - lastScrollMidY;
                    if (Math.abs(dy) > SCROLL_THRESHOLD) {
                        boolean up = dy < 0;
                        vncClient.sendPointer((int) cursorX, (int) cursorY,
                            up ? MASK_SCROLL_UP : MASK_SCROLL_DOWN);
                        vncClient.sendPointer((int) cursorX, (int) cursorY, 0);
                        showOperation(up
                            ? R.string.vnc_op_scroll_up
                            : R.string.vnc_op_scroll_down);
                        lastScrollMidY = my;
                    }
                } else if (gestureMaxPointers >= 2 && pc >= 2) {
                    handleTwoFinger(event);
                } else if (gestureMaxPointers <= 1 && pc == 1) {
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    if (dx != 0 || dy != 0) {
                        moveCursor(dx, dy);
                        showOperation(R.string.vnc_op_mouse_move);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (gestureMaxPointers >= 2) {
                    ivDisplay.setRotation(snapRotation(ivDisplay.getRotation()));
                    clampPan();
                    ivDisplay.setTranslationX(panX);
                    ivDisplay.setTranslationY(panY);
                    updateOperationLabelPosition();
                } else {
                    ensureCursorVisible();
                }
                if (!gestureMoved
                    && System.currentTimeMillis() - gestureStartTime < TAP_TIMEOUT) {
                    boolean dbl = lastTapFingerCount == gestureMaxPointers
                        && System.currentTimeMillis() - lastTapTime < DOUBLE_TAP_TIMEOUT;
                    handleTap(gestureMaxPointers, dbl);
                    lastTapTime = System.currentTimeMillis();
                    lastTapFingerCount = gestureMaxPointers;
                } else {
                    lastTapFingerCount = 0;
                }
                gestureMaxPointers = 0;
                return true;
            case MotionEvent.ACTION_CANCEL:
                gestureMaxPointers = 0;
                lastTapFingerCount = 0;
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (gestureMaxPointers == 2 && pc <= 2)
                    ivDisplay.setRotation(snapRotation(ivDisplay.getRotation()));
                return true;
        }
        return false;
    }

    private void handleTap(int fingerCount, boolean dbl) {
        switch (fingerCount) {
            case 1:
                sendClick(MASK_LEFT);
                if (dbl) sendClick(MASK_LEFT);
                showOperation(dbl
                    ? R.string.vnc_op_left_double_click
                    : R.string.vnc_op_left_click);
                break;
            case 2:
                sendClick(MASK_RIGHT);
                if (dbl) sendClick(MASK_RIGHT);
                showOperation(dbl
                    ? R.string.vnc_op_right_double_click
                    : R.string.vnc_op_right_click);
                break;
            default:
                sendClick(MASK_MIDDLE);
                if (dbl) sendClick(MASK_MIDDLE);
                showOperation(dbl
                    ? R.string.vnc_op_middle_double_click
                    : R.string.vnc_op_middle_click);
                break;
        }
    }

    private void sendClick(int mask) {
        vncClient.sendPointer((int) cursorX, (int) cursorY, mask);
        vncClient.sendPointer((int) cursorX, (int) cursorY, 0);
    }

    private void initTwoFinger(@NonNull MotionEvent event) {
        lastMidX = (event.getX(0) + event.getX(1)) / 2f;
        lastMidY = (event.getY(0) + event.getY(1)) / 2f;
        initialAngle = twoFingerAngle(event);
        rotationBase = ivDisplay.getRotation();
        initialDist = twoFingerDistance(event);
        initialZoom = zoom;
    }

    private void handleTwoFinger(@NonNull MotionEvent event) {
        float mx = (event.getX(0) + event.getX(1)) / 2f;
        float my = (event.getY(0) + event.getY(1)) / 2f;
        panX += mx - lastMidX;
        panY += my - lastMidY;
        lastMidX = mx;
        lastMidY = my;
        clampPan();
        ivDisplay.setTranslationX(panX);
        ivDisplay.setTranslationY(panY);
        float angle = twoFingerAngle(event);
        float dAngle = normalizeAngle(angle - initialAngle);
        ivDisplay.setRotation(snapNear(rotationBase + dAngle));
        float dist = twoFingerDistance(event);
        if (initialDist > MIN_SCALE_DIST) {
            float nz = max(MIN_ZOOM, min(initialZoom * dist / initialDist, MAX_ZOOM));
            if (nz != zoom) {
                zoom = nz;
                applyViewSize();
            }
        }
        updateOperationLabelPosition();
    }

    private void clampPan() {
        int cW = displayContainer.getWidth();
        int cH = displayContainer.getHeight();
        int vW = currentViewW();
        int vH = currentViewH();
        panX = max(-(cW + vW) / 2f, min(panX, (cW + vW) / 2f));
        panY = max(-(cH + vH) / 2f, min(panY, (cH + vH) / 2f));
    }

    private float twoFingerAngle(@NonNull MotionEvent e) {
        float dx = e.getX(1) - e.getX(0);
        float dy = e.getY(1) - e.getY(0);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    private float twoFingerDistance(@NonNull MotionEvent e) {
        float dx = e.getX(1) - e.getX(0);
        float dy = e.getY(1) - e.getY(0);
        return (float) Math.hypot(dx, dy);
    }

    private float normalizeAngle(float a) {
        while (a > 180) a -= 360;
        while (a < -180) a += 360;
        return a;
    }

    private float snapNear(float deg) {
        float snapped = Math.round(deg / 90f) * 90f;
        if (Math.abs(deg - snapped) <= SNAP_THRESHOLD) return snapped;
        return deg;
    }

    private float snapRotation(float deg) {
        return Math.round(deg / 90f) * 90f;
    }

    private void moveCursor(float dx, float dy) {
        if (fbWidth <= 0 || fbHeight <= 0) return;
        double rad = Math.toRadians(ivDisplay.getRotation());
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        float vncDx = dx * cos + dy * sin;
        float vncDy = -dx * sin + dy * cos;
        cursorX = max(0, min(cursorX + vncDx, fbWidth - 1));
        cursorY = max(0, min(cursorY + vncDy, fbHeight - 1));
        vncClient.sendPointer((int) cursorX, (int) cursorY, 0);
        ensureCursorVisible();
    }

    private void ensureCursorVisible() {
        if (fbWidth <= 0 || fbHeight <= 0) return;
        int cW = displayContainer.getWidth();
        int cH = displayContainer.getHeight();
        int viewW = currentViewW();
        int viewH = currentViewH();
        if (cW <= 0 || cH <= 0 || viewW <= 0 || viewH <= 0) return;
        double rad = Math.toRadians(ivDisplay.getRotation());
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        float localX = cursorX * viewW / (float) fbWidth;
        float localY = cursorY * viewH / (float) fbHeight;
        float relX = localX - viewW / 2f;
        float relY = localY - viewH / 2f;
        float rotX = relX * cos - relY * sin;
        float rotY = relX * sin + relY * cos;
        float screenX = cW / 2f + panX + rotX;
        float screenY = cH / 2f + panY + rotY;
        if (screenX < 0) panX -= screenX;
        else if (screenX > cW) panX -= (screenX - cW);
        if (screenY < 0) panY -= screenY;
        else if (screenY > cH) panY -= (screenY - cH);
        clampPan();
        ivDisplay.setTranslationX(panX);
        ivDisplay.setTranslationY(panY);
        updateOperationLabelPosition();
    }

    private void updateOperationLabelPosition() {
        if (operationLabel == null) return;
        int cW = displayContainer.getWidth();
        int cH = displayContainer.getHeight();
        if (cW <= 0 || cH <= 0) return;
        float r = ivDisplay.getRotation();
        operationLabel.setRotation(r);
        int deg = ((Math.round(r / 90f) % 4) + 4) % 4;
        var lp = (FrameLayout.LayoutParams) operationLabel.getLayoutParams();
        int m = (int) dp(8);
        switch (deg) {
            case 0:
                lp.gravity = TOP | CENTER_HORIZONTAL;
                lp.setMargins(0, m, 0, 0);
                break;
            case 1:
                lp.gravity = END | CENTER_VERTICAL;
                lp.setMargins(0, 0, m, 0);
                break;
            case 2:
                lp.gravity = BOTTOM | CENTER_HORIZONTAL;
                lp.setMargins(0, 0, 0, m);
                break;
            default:
                lp.gravity = START | CENTER_VERTICAL;
                lp.setMargins(m, 0, 0, 0);
                break;
        }
        operationLabel.setLayoutParams(lp);
    }

    private void showOperation(int resId) {
        if (operationLabel == null) return;
        operationLabel.setText(resId);
        operationLabel.setVisibility(VISIBLE);
        mainHandler.removeCallbacks(hideOperationLabel);
        mainHandler.postDelayed(hideOperationLabel, OP_LABEL_HIDE_DELAY_MS);
    }

    private void updateAspectRatio(int containerW, int containerH) {
        if (containerW <= 0 || containerH <= 0 || fbWidth <= 0 || fbHeight <= 0) return;
        float vmAspect = (float) fbWidth / fbHeight;
        float containerAspect = (float) containerW / containerH;
        if (vmAspect > containerAspect) {
            baseViewW = containerW;
            baseViewH = Math.round(containerW / vmAspect);
        } else {
            baseViewH = containerH;
            baseViewW = Math.round(containerH * vmAspect);
        }
        applyViewSize();
        if (inputMode == InputMode.MOUSE) ensureCursorVisible();
    }

    private void showBars() {
        toolbar.setVisibility(VISIBLE);
        statusBar.setVisibility(VISIBLE);
        if (status == VncStatus.CONNECTED)
            mainHandler.postDelayed(this::hideBars, AUTO_HIDE_DELAY_MS);
    }

    private void hideBars() {
        if (isFullscreen) return;
        toolbar.setVisibility(GONE);
        statusBar.setVisibility(GONE);
    }

    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;
        var controller = getWindow().getInsetsController();
        if (controller == null) return;
        if (isFullscreen) {
            mainHandler.removeCallbacks(this::hideBars);
            toolbar.setVisibility(GONE);
            statusBar.setVisibility(GONE);
            extraKeysPanel.setVisibility(GONE);
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            showBars();
            if (extraKeysVisible) extraKeysPanel.animateIn();
            controller.show(WindowInsets.Type.systemBars());
        }
        ViewCompat.requestApplyInsets(findViewById(android.R.id.content));
    }

    private void toggleExtraKeys() {
        extraKeysVisible = !extraKeysVisible;
        extraKeysPanel.setVisibleAnimated(extraKeysVisible);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupFab() {
        var listener = new DragTouchListener(this, this::showFabMenu);
        fabMenu.setOnTouchListener(listener);
    }

    private void showFabMenu() {
        var popup = new MaterialMenu(this, fabMenu);
        popup.inflate(R.menu.menu_vnc_display_menu);
        var item = popup.getMenu().findItem(R.id.menu_input_mode);
        if (item != null) {
            if (inputMode == InputMode.TOUCH) {
                item.setTitle(R.string.vnc_menu_input_mode_mouse);
                item.setIcon(R.drawable.ic_mouse);
            } else {
                item.setTitle(R.string.vnc_menu_input_mode_touch);
                item.setIcon(R.drawable.ic_touchpad);
            }
        }
        popup.setOnMenuItemClickListener(this::onMenuItemClicked);
        popup.show();
    }

    @Override
    protected boolean onMenuItemClicked(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_extra_keys) {
            toggleExtraKeys();
            return true;
        } else if (id == R.id.menu_fullscreen) {
            toggleFullscreen();
            return true;
        } else if (id == R.id.menu_input_mode) {
            setInputMode(inputMode == InputMode.TOUCH
                ? InputMode.MOUSE : InputMode.TOUCH);
            return true;
        }
        return super.onMenuItemClicked(item);
    }
}
