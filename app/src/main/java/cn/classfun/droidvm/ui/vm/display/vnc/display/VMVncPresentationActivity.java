package cn.classfun.droidvm.ui.vm.display.vnc.display;

import static cn.classfun.droidvm.ui.vm.display.base.DisplayPresentation.displayStateName;

import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.ui.MaterialMenu;
import cn.classfun.droidvm.ui.vm.display.base.DisplayPresentation;
import cn.classfun.droidvm.ui.vm.display.base.DisplayTouchPadPanel;
import cn.classfun.droidvm.ui.vm.display.vnc.base.BaseVncActivity;
import cn.classfun.droidvm.ui.vm.display.vnc.input.VncTouchPadPanel;

public final class VMVncPresentationActivity
    extends BaseVncActivity
    implements DisplayManager.DisplayListener {
    private DisplayTouchPadPanel touchpadPanel;
    private VncTouchPadPanel vncTouchPad;
    private int targetDisplayId = -1;
    private DisplayManager displayManager;
    private DisplayPresentation pres;

    @Override
    public void onDisplayAdded(int displayId) {
    }

    @Override
    public void onDisplayChanged(int displayId) {
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (pres != null && pres.getDisplayId() == displayId) {
            pres.dismiss();
            pres = null;
            Toast.makeText(this, R.string.display_lost, Toast.LENGTH_SHORT).show();
            finish();
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
        setupToolbarMenu();
        displayManager = getSystemService(DisplayManager.class);
        displayManager.registerDisplayListener(this, mainHandler);
        DisplayPresentation.showDisplaySelectionDialog(this, disp -> {
            if (disp == null) finish();
            else selectDisplay(disp);
        });
    }

    private void setupToolbarMenu() {
        var id = View.generateViewId();
        var menu = toolbar.getMenu();
        var item = menu.add(0, id, 0, R.string.vnc_menu);
        item.setIcon(R.drawable.ic_more_vert);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        toolbar.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == id) {
                showMenu(toolbar.findViewById(id));
                return true;
            }
            return false;
        });
    }

    private void showMenu(@NonNull View anchor) {
        var popup = new MaterialMenu(this, anchor);
        popup.inflate(R.menu.menu_vnc_presentation_menu);
        popup.setOnMenuItemClickListener(this::onMenuItemClicked);
        popup.show();
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
        if (pres != null) {
            pres.dismiss();
            pres = null;
        }
        if (displayManager != null)
            displayManager.unregisterDisplayListener(this);
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
}
