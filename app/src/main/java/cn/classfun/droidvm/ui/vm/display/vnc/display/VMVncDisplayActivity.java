package cn.classfun.droidvm.ui.vm.display.vnc.display;

import static android.view.Gravity.CENTER;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.DragTouchListener;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.vm.display.vnc.base.BaseVncActivity;

public final class VMVncDisplayActivity extends BaseVncActivity {
    private static final long AUTO_HIDE_DELAY_MS = 3000;
    private LinearLayout statusBar;
    private MaterialButton btnFullscreen;
    private FrameLayout displayContainer;
    private FloatingActionButton fabMenu;
    private boolean isFullscreen = false;
    private boolean extraKeysVisible = true;


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
    }

    @Override
    protected void onSetupActivity() {
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());
        displayContainer.addOnLayoutChangeListener((
            v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom
        ) -> {
            int cw = right - left, ch = bottom - top;
            if (cw > 0 && ch > 0) v.post(() -> updateAspectRatio(cw, ch));
        });
        displayContainer.setOnClickListener(v -> {
            showBars();
            toggleSoftKeyboard();
        });
        setupDisplayTouch();
        setupFab();
    }

    @Override
    protected void onFramebufferReady(int width, int height) {
        updateAspectRatio(displayContainer.getWidth(), displayContainer.getHeight());
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
        ivDisplay.setOnTouchListener(this::onDisplayTouch);
    }

    private void updateAspectRatio(int containerW, int containerH) {
        if (containerW <= 0 || containerH <= 0 || fbWidth <= 0 || fbHeight <= 0) return;
        float vmAspect = (float) fbWidth / fbHeight;
        float containerAspect = (float) containerW / containerH;
        int viewW, viewH;
        if (vmAspect > containerAspect) {
            viewW = containerW;
            viewH = Math.round(containerW / vmAspect);
        } else {
            viewH = containerH;
            viewW = Math.round(containerH * vmAspect);
        }
        ivDisplay.setLayoutParams(new FrameLayout.LayoutParams(viewW, viewH, CENTER));
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
            hideBars();
            extraKeysPanel.setVisibility(GONE);
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            showBars();
            if (extraKeysVisible) extraKeysPanel.animateIn();
            controller.show(WindowInsets.Type.systemBars());
        }
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
        }
        return super.onMenuItemClicked(item);
    }
}
