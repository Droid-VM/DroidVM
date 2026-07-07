package cn.classfun.droidvm.ui.hugepage;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;

import cn.classfun.droidvm.R;

/**
 * The acquire-mode glyph drawn as a liquid-fill tank: an open-top box (no lid)
 * with the download icon centred inside. The box fills with "water" up to a
 * per-mode level - v1 shallow, v2 mid, v3 near-full - so the fill height, not a
 * number badge, is what tells the three modes apart.
 *
 * <p>The download glyph is split at the water line and drawn in opposite colours
 * either side of it: {@code colorPrimary} above the surface (on the empty tank),
 * {@code colorOnPrimary} for the submerged part (carved out of the water). The
 * fill level is set per slot via {@code app:acv_fill} in {@code acquire_buttons.xml}.
 * Greying/hiding is done by the host toggling the parent slot's alpha/visibility,
 * so this view just draws itself at full strength.
 */
public final class AcquireContainerView extends View {
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path walls = new Path();     // open-top outline (stroked)
    private final Path interior = new Path();  // rounded-bottom fill region (clip)
    private final RectF box = new RectF();
    @Nullable
    private final Drawable glyph;

    private final int colorOutline;
    private final int colorWater;
    private final int colorInterior;
    private final int colorGlyphAbove;
    private final int colorGlyphBelow;

    /** 0 = empty tank, 1 = full. Sets how high the water rises inside the box. */
    private float fill = 0.5f;

    public AcquireContainerView(Context context) {
        this(context, null);
    }

    public AcquireContainerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        Drawable d = AppCompatResources.getDrawable(context, R.drawable.ic_download);
        glyph = d != null ? d.mutate() : null;   // mutate: per-instance tint
        int primary = MaterialColors.getColor(context,
            androidx.appcompat.R.attr.colorPrimary, Color.BLUE);
        int onPrimary = MaterialColors.getColor(context,
            com.google.android.material.R.attr.colorOnPrimary, Color.WHITE);
        colorOutline = primary;
        colorWater = primary;
        colorInterior = ColorUtils.setAlphaComponent(primary, 0x24);  // faint tank tint
        colorGlyphAbove = primary;                // over the empty tank
        colorGlyphBelow = onPrimary;              // the opposite colour, under water
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.AcquireContainerView);
            fill = clamp01(a.getFloat(R.styleable.AcquireContainerView_acv_fill, fill));
            a.recycle();
        }
    }

    /** Set the fill level (0..1) at runtime; clamped. */
    public void setFill(float value) {
        float v = clamp01(value);
        if (v != fill) {
            fill = v;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int def = Math.round(44 * density);
        setMeasuredDimension(resolveSize(def, widthMeasureSpec),
            resolveSize(def, heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float side = Math.min(w, h) * 0.62f;      // square tank, centred
        float left = (w - side) / 2f;
        float top = (h - side) / 2f;
        float right = left + side;
        float bottom = top + side;
        box.set(left, top, right, bottom);
        float radius = side * 0.18f;               // rounded bottom corners
        float stroke = Math.max(1.5f * density, side * 0.07f);
        float inset = stroke / 2f;                 // keep the stroke inside the box

        // Water surface, measured from the bottom up. Clamp inside the tank.
        float waterY = Math.max(top, Math.min(bottom, bottom - fill * side));

        // Interior region (square top, rounded bottom) clips the tint fills so
        // the water follows the rounded corners.
        interior.reset();
        interior.addRoundRect(box,
            new float[]{0, 0, 0, 0, radius, radius, radius, radius},
            Path.Direction.CW);

        canvas.save();
        canvas.clipPath(interior);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colorInterior);            // faint tint over the whole tank
        canvas.drawRect(left, top, right, bottom, paint);
        paint.setColor(colorWater);               // opaque water in the lower part
        canvas.drawRect(left, waterY, right, bottom, paint);
        canvas.restore();

        // Open-top walls: left side, rounded bottom, right side - no top edge.
        walls.reset();
        walls.moveTo(left + inset, top);
        walls.lineTo(left + inset, bottom - radius);
        walls.quadTo(left + inset, bottom - inset, left + radius, bottom - inset);
        walls.lineTo(right - radius, bottom - inset);
        walls.quadTo(right - inset, bottom - inset, right - inset, bottom - radius);
        walls.lineTo(right - inset, top);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(colorOutline);
        canvas.drawPath(walls, paint);

        // Download glyph, centred and split at the water line: primary above the
        // surface, the inverted on-primary colour carved out of the water below.
        if (glyph != null) {
            float g = side * 0.60f;
            int gl = Math.round(left + (side - g) / 2f);
            int gt = Math.round(top + (side - g) / 2f);
            glyph.setBounds(gl, gt, Math.round(gl + g), Math.round(gt + g));

            canvas.save();
            canvas.clipRect(left, top, right, waterY);
            glyph.setTint(colorGlyphAbove);
            glyph.draw(canvas);
            canvas.restore();

            canvas.save();
            canvas.clipRect(left, waterY, right, bottom);
            glyph.setTint(colorGlyphBelow);
            glyph.draw(canvas);
            canvas.restore();
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
