package cn.classfun.droidvm.ui.vm.display.base;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.function.Consumer;

import cn.classfun.droidvm.R;

public final class DisplayPresentation extends Presentation {
    private ImageView ivDisplay;
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
