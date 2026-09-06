// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.vm.display.base;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.function.Consumer;

import cn.classfun.droidvm.R;

public final class DisplayPresentation extends Presentation {
    private ImageView ivDisplay;
    private FrameLayout root;
    private TextureView h264View;
    /** The stream's size, kept so the fit can be redone when the window's own size changes. */
    private int streamWidth;
    private int streamHeight;
    private static final String DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED =
        "android.hardware.display.category.ALL_INCLUDING_DISABLED";

    public DisplayPresentation(@NonNull Context context, @NonNull Display display) {
        super(context, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.presentation_display);
        ivDisplay = findViewById(R.id.iv_presentation_display);
        root = findViewById(R.id.presentation_root);
        h264View = findViewById(R.id.texture_h264);
        // The external display can change size under a running stream -- a mode change, a
        // projection that resizes -- and the decoder view is sized in pixels rather than by a
        // scale type, so the fit has to be redone rather than merely re-measured.
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> applyH264Fit());
    }

    /**
     * The view the H.264 decoder draws into on this display, or null before the window is built.
     *
     * <p>It belongs to this window and not to the console activity, which is the whole difference
     * between this path and the phone console's: the picture is on another display, so the decoder
     * has to be pointed at a Surface that is also on it.</p>
     */
    @Nullable
    public TextureView getH264View() {
        return h264View;
    }

    /**
     * Letterboxes the decoder view to a stream of [width]x[height], or clears the fit with zeroes.
     *
     * <p>This is what {@code fitCenter} does for the RFB {@link ImageView} beside it, done by hand
     * because a {@link TextureView} has no scale type: it stretches its Surface to whatever bounds
     * it is given. Left alone at {@code match_parent} it would show the guest's screen distorted on
     * any display whose aspect differs from the guest's -- and the fallback to the ImageView
     * underneath would then visibly change shape, which is the one thing the two views showing the
     * same picture are supposed to make impossible.</p>
     */
    public void fitH264(int width, int height) {
        streamWidth = width;
        streamHeight = height;
        applyH264Fit();
    }

    private void applyH264Fit() {
        if (h264View == null || root == null) return;
        if (streamWidth <= 0 || streamHeight <= 0) return;
        var areaW = root.getWidth();
        var areaH = root.getHeight();
        if (areaW <= 0 || areaH <= 0) return;
        var scale = Math.min(areaW / (float) streamWidth, areaH / (float) streamHeight);
        var fitW = Math.max(1, Math.round(streamWidth * scale));
        var fitH = Math.max(1, Math.round(streamHeight * scale));
        var lp = h264View.getLayoutParams();
        if (lp.width == fitW && lp.height == fitH) return;
        h264View.setLayoutParams(new FrameLayout.LayoutParams(fitW, fitH, Gravity.CENTER));
    }

    public void updateBitmap(@NonNull Bitmap bitmap) {
        if (ivDisplay != null) ivDisplay.setImageBitmap(bitmap);
    }

    public void clearBitmap() {
        if (ivDisplay != null) ivDisplay.setImageBitmap(null);
    }

    public int getDisplayId() {
        return getDisplay().getDisplayId();
    }

    @NonNull
    public static String displayStateName(Context ctx, int state) {
        switch (state) {
            case Display.STATE_OFF:
                return ctx.getString(R.string.display_state_off);
            case Display.STATE_ON:
                return ctx.getString(R.string.display_state_on);
            case Display.STATE_DOZE:
                return ctx.getString(R.string.display_state_doze);
            case Display.STATE_DOZE_SUSPEND:
                return ctx.getString(R.string.display_state_doze_suspend);
            case Display.STATE_ON_SUSPEND:
                return ctx.getString(R.string.display_state_on_suspend);
            default:
                return ctx.getString(R.string.display_state_unknown);
        }
    }

    public static void showDisplaySelectionDialog(
        @NonNull Context ctx,
        @NonNull Consumer<Display> onSelect
    ) {
        var dm = ctx.getSystemService(DisplayManager.class);
        var displays = dm.getDisplays(DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED);
        if (displays.length == 0) {
            Toast.makeText(ctx, R.string.display_no_display, Toast.LENGTH_SHORT).show();
            onSelect.accept(null);
            return;
        }
        var names = new String[displays.length];
        for (int i = 0; i < displays.length; i++)
            names[i] = displays[i].getName();
        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.display_select_display)
            .setItems(names, (d, which) -> onSelect.accept(displays[which]))
            .setNegativeButton(android.R.string.cancel, (d, w) -> onSelect.accept(null))
            .setOnCancelListener(d -> onSelect.accept(null))
            .show();
    }
}
