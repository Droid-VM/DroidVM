// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.vnc.display;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.ui.vm.display.base.DisplayPresentation.displayStateName;

import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.vm.display.base.DisplayPresentation;
import cn.classfun.droidvm.ui.vm.display.base.DisplayTouchPadPanel;
import cn.classfun.droidvm.ui.vm.display.base.UsbHidInput;
import cn.classfun.droidvm.ui.vm.display.vnc.base.BaseVncActivity;
import cn.classfun.droidvm.ui.vm.display.vnc.input.VncTouchPadPanel;

public final class VMVncPresentationActivity
    extends BaseVncActivity
    implements DisplayManager.DisplayListener, UsbHidInput.Listener {
    private DisplayTouchPadPanel touchpadPanel;
    private VncTouchPadPanel vncTouchPad;
    private int targetDisplayId = -1;
    private DisplayManager displayManager;
    private DisplayPresentation pres;
    private UsbHidInput usbHidInput;
    private boolean usbInputEnabled;
    private int mouseButtonMask;
    private static final float MOUSE_SPEED = 2.5f;

    @Override
    public void onDisplayAdded(int displayId) {
    }

    @Override
    public void onDisplayChanged(int displayId) {
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (pres != null && pres.getDisplayId() == displayId) {
            dismissPresentation();
            Toast.makeText(this, R.string.display_lost, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /**
     * Drops the presentation window, and the decoder that was drawing into it first.
     *
     * <p>Order matters and only in this direction: the decoder's Surface belongs to a view in that
     * window, so dismissing first would leave the pipeline holding a Surface whose window is gone
     * and a socket the server still counts as its one client.</p>
     */
    private void dismissPresentation() {
        setH264View(null);
        if (pres != null) {
            pres.dismiss();
            pres = null;
        }
    }

    @Override
    protected int getContentLayoutId() {
        return R.layout.activity_vm_vnc_presentation;
    }

    @NonNull
    @Override
    protected String getActivityTitle() {
        return vmName.isEmpty() ?
            getString(R.string.vnc_presentation_title) :
            getString(R.string.vnc_presentation_title_with_name, vmName);
    }

    @Override
    protected void onBindExtraViews() {
        touchpadPanel = findViewById(R.id.touchpad_panel);
    }

    @Override
    protected void onSetupActivity() {
        vncTouchPad = new VncTouchPadPanel(touchpadPanel);
        ivDisplay.setTextCommitListener(createTextCommitListener());
        touchpadPanel.getTouchpadArea().setOnClickListener(v -> toggleSoftKeyboard());
        setupPointerCapture();
        setupPresentationMenu();
        displayManager = getSystemService(DisplayManager.class);
        displayManager.registerDisplayListener(this, mainHandler);
        DisplayPresentation.showDisplaySelectionDialog(this, disp -> {
            if (disp == null) finish();
            else selectDisplay(disp);
        });
    }

    private void setupPointerCapture() {
        var area = touchpadPanel.getTouchpadArea();
        area.setFocusable(true);
        area.setFocusableInTouchMode(true);
        area.setOnCapturedPointerListener(this::onCapturedPointer);
    }

    private boolean onCapturedPointer(View v, MotionEvent event) {
        var c = vncClient;
        if (c == null || !c.isConnected()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                float dx = event.getX() * MOUSE_SPEED;
                float dy = event.getY() * MOUSE_SPEED;
                if (dx != 0 || dy != 0) {
                    vncTouchPad.moveCursor(dx, dy);
                    sendPointer();
                }
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
                mouseButtonMask |= buttonToMask(event.getActionButton());
                sendPointer();
                return true;
            case MotionEvent.ACTION_BUTTON_RELEASE:
                mouseButtonMask &= ~buttonToMask(event.getActionButton());
                sendPointer();
                return true;
            case MotionEvent.ACTION_SCROLL:
                float scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scroll != 0) {
                    c.sendPointer(
                        (int) vncTouchPad.getCursorX(),
                        (int) vncTouchPad.getCursorY(),
                        scroll > 0 ? 8 : 16
                    );
                    sendPointer();
                }
                return true;
        }
        return false;
    }

    private void sendPointer() {
        var c = vncClient;
        if (c == null || !c.isConnected()) return;
        c.sendPointer(
            (int) vncTouchPad.getCursorX(),
            (int) vncTouchPad.getCursorY(),
            mouseButtonMask
        );
    }

    private static int buttonToMask(int button) {
        switch (button) {
            case MotionEvent.BUTTON_PRIMARY: return 1;
            case MotionEvent.BUTTON_TERTIARY: return 2;
            case MotionEvent.BUTTON_SECONDARY: return 4;
            default: return 0;
        }
    }

    @Override
    protected void onFramebufferReady(int width, int height) {
        vncTouchPad.setFramebufferSize(width, height);
        startPresentationOnTarget();
    }

    @Override
    protected void onBitmapUpdated(@NonNull Bitmap bitmap) {
        if (pres != null)
            pres.updateBitmap(bitmap);
    }

    @Override
    protected void onClearDisplay() {
        super.onClearDisplay();
        if (pres != null) pres.clearBitmap();
    }

    @Override
    protected void onDestroyExtra() {
        stopInputCapture();
        dismissPresentation();
        if (displayManager != null)
            displayManager.unregisterDisplayListener(this);
    }

    /**
     * The stream's picture is the presentation's picture, so the decoder view is fitted to it the
     * way the RFB {@link android.widget.ImageView} beneath is fitted by its scale type. Nothing
     * else about the H.264 path is different here -- the probe, the fallback, the retry ladder and
     * the liveness timeout are all the base console's, working against a view on another display.
     */
    @Override
    protected void onH264StreamChanged(boolean live, int width, int height) {
        if (pres != null) pres.fitH264(live ? width : 0, live ? height : 0);
    }

    @Override
    protected void onVncClientCreated() {
        vncTouchPad.setVncClient(vncClient);
    }

    private void startPresentationOnTarget() {
        if (targetDisplayId < 0) return;
        if (pres != null) return;
        var targetDisplay = displayManager.getDisplay(targetDisplayId);
        if (targetDisplay == null) {
            Toast.makeText(this, R.string.display_no_display, Toast.LENGTH_SHORT).show();
            return;
        }
        pres = new DisplayPresentation(this, targetDisplay);
        try {
            pres.show();
        } catch (Exception e) {
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.display_start_failed)
                .setMessage(e.getMessage())
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(d -> finish())
                .show();
            return;
        }
        startInputCapture();
        // The decoder's view exists only now, with the window that holds it -- which is why this is
        // not in onSetupActivity: until a display has been chosen there is nowhere for a decoded
        // frame to go. Nothing to ask for afterwards: the stream, if there is one, has been
        // arriving on the RFB connection all along, and the next rect finds this pipeline.
        setH264View(pres.getH264View());
        synchronized (bitmapLock) {
            if (displayBitmap != null && !displayBitmap.isRecycled())
                pres.updateBitmap(displayBitmap);
        }
    }

    private void selectDisplay(@NonNull Display display) {
        Runnable start = () -> {
            targetDisplayId = display.getDisplayId();
            startPresentationOnTarget();
        };
        if (display.getState() == Display.STATE_ON) {
            start.run();
            return;
        }
        var msg = getString(
            R.string.display_not_on_message,
            display.getName(),
            displayStateName(this, display.getState())
        );
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.display_not_on_title)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener(d -> start.run())
            .show();
    }

    private void setupPresentationMenu() {
        int id = View.generateViewId();
        var item = toolbar.getMenu().add(0, id, 0, R.string.menu);
        item.setIcon(R.drawable.ic_more_vert);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        toolbar.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == id) {
                showPresentationMenu();
                return true;
            }
            return false;
        });
    }

    private void showPresentationMenu() {
        var popup = new MaterialMenu(this, toolbar);
        popup.inflate(R.menu.menu_vnc_presentation_menu);
        popup.setOnMenuItemClickListener(this::onMenuItemClicked);
        popup.show();
    }

    private void startInputCapture() {
        if (usbHidInput == null) {
            usbHidInput = new UsbHidInput(this);
            usbHidInput.setListener(this);
        }
        mouseButtonMask = 0;
        usbInputEnabled = true;
        usbHidInput.start();
        usbHidInput.setLedState(capsLockOn, numLockOn, false);
        ledStateListener = (caps, num) -> {
            if (usbHidInput != null)
                usbHidInput.setLedState(caps, num, false);
        };
        requestMouseCapture();
    }

    private void requestMouseCapture() {
        var area = touchpadPanel.getTouchpadArea();
        area.post(() -> {
            if (!usbInputEnabled) return;
            area.requestFocus();
            area.requestPointerCapture();
            Log.i(TAG, fmt(
                "requestPointerCapture, hasCapture=%b",
                area.hasPointerCapture()
            ));
        });
    }

    private void stopInputCapture() {
        ledStateListener = null;
        if (usbHidInput != null) usbHidInput.stop();
        var area = touchpadPanel.getTouchpadArea();
        area.releasePointerCapture();
        mouseButtonMask = 0;
        usbInputEnabled = false;
    }

    @Override
    public void onKeyEvent(int keysym, boolean down) {
        var c = vncClient;
        if (c != null && c.isConnected())
            c.sendKey(keysym, down);
    }

    @Override
    public void onMouseMove(int dx, int dy) {
        var c = vncClient;
        if (c == null || !c.isConnected()) return;
        vncTouchPad.moveCursor(dx, dy);
        c.sendPointer(
            (int) vncTouchPad.getCursorX(),
            (int) vncTouchPad.getCursorY(),
            mouseButtonMask
        );
    }

    @Override
    public void onMouseButton(int button, boolean pressed) {
        var c = vncClient;
        if (c == null || !c.isConnected()) return;
        int mask = 1 << button;
        if (pressed) mouseButtonMask |= mask;
        else mouseButtonMask &= ~mask;
        c.sendPointer(
            (int) vncTouchPad.getCursorX(),
            (int) vncTouchPad.getCursorY(),
            mouseButtonMask
        );
    }

    @Override
    public void onMouseScroll(boolean up) {
        var c = vncClient;
        if (c == null || !c.isConnected()) return;
        int scrollMask = up ? 8 : 16;
        c.sendPointer(
            (int) vncTouchPad.getCursorX(),
            (int) vncTouchPad.getCursorY(),
            scrollMask
        );
        c.sendPointer(
            (int) vncTouchPad.getCursorX(),
            (int) vncTouchPad.getCursorY(),
            mouseButtonMask
        );
    }

    @Override
    public void onDeviceChanged(int keyboards, int mice) {
        var msg = (keyboards + mice > 0) ?
            getString(R.string.usb_input_status, keyboards, mice) :
            getString(R.string.usb_input_no_device);
        Toast.makeText(VMVncPresentationActivity.this, msg, Toast.LENGTH_SHORT).show();
    }
}
