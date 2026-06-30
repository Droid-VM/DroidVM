package cn.classfun.droidvm.ui.hugepage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import cn.classfun.droidvm.R;

/**
 * A rounded horizontal bar split into colored segments (one per process) plus a
 * track for the remaining/available portion. Widths are proportional; the caller
 * passes a {@code total} that is always &gt;= the sum of segment values, so the
 * segments can never overflow the bar (no crash on over-accounting).
 */
public final class SegmentedBar extends View {
    private int[] colors = new int[0];
    private float[] values = new float[0];
    private float total = 0f;
    private int trackColor;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final float radius;

    public SegmentedBar(Context context) {
        this(context, null);
    }

    public SegmentedBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        radius = 3f * getResources().getDisplayMetrics().density;
        trackColor = ContextCompat.getColor(context, R.color.hugepage_bar_track);
    }

    public void setData(@NonNull int[] colors, @NonNull float[] values, float total) {
        this.colors = colors;
        this.values = values;
        this.total = total;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(trackColor);
        canvas.drawRect(0, 0, w, h, paint);

        if (total <= 0f) return;
        float x = 0f;
        int n = Math.min(colors.length, values.length);
        for (int i = 0; i < n; i++) {
            float segW = w * (values[i] / total);
            if (segW <= 0f) continue;
            paint.setColor(colors[i]);
            canvas.drawRect(x, 0, Math.min(x + segW, w), h, paint);
            x += segW;
        }
    }
}
