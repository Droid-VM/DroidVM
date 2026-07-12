package cn.classfun.droidvm.ui.hugepage;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
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
 *
 * <p>An optional {@code labels} array enables an Apple-storage-bar style: each
 * segment wide enough to fit its label draws it inside the colored block, with
 * the text colour chosen automatically for contrast. When {@code labels} is null
 * the bar stays a plain thin meter.
 */
public final class SegmentedBar extends View {
    private int[] colors = new int[0];
    private float[] values = new float[0];
    // Optional per-segment sub-split: the LEFT subLeftValues[i] share of segment
    // i is drawn in subLeftColors[i], the rest in colors[i]. Pure color detail -
    // the segment stays ONE logical block with ONE (centred) label.
    @Nullable
    private float[] subLeftValues = null;
    @Nullable
    private int[] subLeftColors = null;
    @Nullable
    private String[] labels = null;
    // Per-segment label data pre-split, pre-measured and pre-coloured in setData,
    // so onDraw (a hot path) allocates and measures nothing. labelTop[i] == null
    // means "no label for this segment".
    @Nullable
    private String[] labelTop;
    @Nullable
    private String[] labelBottom;
    private float[] labelTopW = new float[0];
    private float[] labelBotW = new float[0];
    private int[] labelTextColor = new int[0];
    private float total = 0f;
    private final int trackColor;
    private final boolean darkTheme;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final float density;
    private final float labelPad;

    public SegmentedBar(Context context) {
        this(context, null);
    }

    public SegmentedBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        labelPad = 6f * density;
        trackColor = ContextCompat.getColor(context, R.color.hugepage_bar_track);
        darkTheme = HugePageColor.isDark(context);
        var dm = getResources().getDisplayMetrics();
        textPaint.setTextSize(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 11f, dm));    // label line
        subTextPaint.setTextSize(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 10f, dm));    // capacity line
    }

    /** Plain thin meter: segments only, no in-bar labels. */
    public void setData(@NonNull int[] colors, @NonNull float[] values, float total) {
        setData(colors, values, null, total);
    }

    /** Apple-style: each wide-enough segment draws {@code labels[i]} inside it. */
    public void setData(@NonNull int[] colors, @NonNull float[] values,
                        @Nullable String[] labels, float total) {
        setData(colors, values, labels, null, null, total);
    }

    /** Full form: labels plus optional per-segment left sub-splits (see fields). */
    public void setData(@NonNull int[] colors, @NonNull float[] values,
                        @Nullable String[] labels,
                        @Nullable float[] subLeftValues, @Nullable int[] subLeftColors,
                        float total) {
        this.colors = colors;
        this.values = values;
        this.labels = labels;
        this.subLeftValues = subLeftValues;
        this.subLeftColors = subLeftColors;
        this.total = total;
        prepareLabels();
        invalidate();
    }

    /** Pre-split, pre-measure and pre-colour labels so onDraw does none of it. */
    private void prepareLabels() {
        if (labels == null) {
            labelTop = null;
            labelBottom = null;
            return;
        }
        int m = labels.length;
        labelTop = new String[m];
        labelBottom = new String[m];
        labelTopW = new float[m];
        labelBotW = new float[m];
        labelTextColor = new int[m];
        for (int i = 0; i < m; i++) {
            var label = labels[i];
            if (label == null || label.isEmpty()) {
                labelTop[i] = null;         // no label for this segment
                continue;
            }
            int nl = label.indexOf('\n');   // "top\nbottom"; no split() regex
            labelTop[i] = nl < 0 ? label : label.substring(0, nl);
            labelBottom[i] = nl < 0 ? null : label.substring(nl + 1);
            labelTopW[i] = textPaint.measureText(labelTop[i]);
            labelBotW[i] = labelBottom[i] != null
                ? subTextPaint.measureText(labelBottom[i]) : 0f;
            labelTextColor[i] = i < colors.length
                ? textColorFor(colors[i]) : 0xFFFFFFFF;
        }
    }

    /**
     * Parameters for the storage-style bar - one holder instead of the long
     * positional argument list. Only {@code usedColors}/{@code usedValues}/
     * {@code want} are always meaningful; the CMA and {@code availNonCma} fields
     * stay 0 for the classic [used][available][waiting] bar.
     */
    public static final class StorageSpec {
        /** one colour per used segment */
        @NonNull public int[] usedColors = new int[0];
        /** one value per used segment (same length as usedColors) */
        @NonNull public float[] usedValues = new float[0];
        /** per-segment labels, or {@code null} for the plain (unlabelled) meter */
        @Nullable public String[] usedLabels;
        public float avail;
        @Nullable public String availLabel;
        /** left sub-split of the available block (non-cma-able share) */
        public float availNonCma;
        public int availNonCmaColor;
        public float cmaFree;
        @Nullable public String cmaFreeLabel;
        public int cmaFreeColor;
        public float cmaOther;
        @Nullable public String cmaOtherLabel;
        public int cmaOtherColor;
        public int deficitColor;
        public float deficit;
        @Nullable public String deficitLabel;
        public float want;
    }

    /**
     * Assemble and set a storage-style bar shared by both hugepage screens:
     * the {@code used} segments, then the available portion as a track-coloured
     * gap, then the deficit ("waiting for acquire") pinned flush against the
     * right edge. {@code total = max(want, sum(used) + avail)}, so the deficit
     * ends exactly at the bar's right edge. Pass {@code usedLabels != null} for
     * the labelled (status-screen) bar or {@code null} for the plain meter.
     *
     * @param usedColors one colour per used segment
     * @param usedValues one value per used segment (same length as usedColors)
     * @param usedLabels per-segment labels, or {@code null} for no labels
     */
    public void setStorage(
        @NonNull int[] usedColors, @NonNull float[] usedValues,
        @Nullable String[] usedLabels,
        float avail, @Nullable String availLabel,
        int deficitColor, float deficit, @Nullable String deficitLabel,
        float want
    ) {
        var s = new StorageSpec();
        s.usedColors = usedColors;
        s.usedValues = usedValues;
        s.usedLabels = usedLabels;
        s.avail = avail;
        s.availLabel = availLabel;
        s.deficitColor = deficitColor;
        s.deficit = deficit;
        s.deficitLabel = deficitLabel;
        s.want = want;
        setStorage(s);
    }

    /**
     * v10 variant of {@link #setStorage}: the bar reads
     * [VMs][available][CMA free][CMA lent][waiting]. The two CMA parts are
     * ordinary labelled segments; only <b>available</b> carries the pure-color
     * left sub-split [non-cma-able | normal] (its left {@code availNonCma}
     * share draws in {@code availNonCmaColor}) under a single label. Leave the
     * CMA values and {@code availNonCma} at 0 for the classic bar (the plain
     * overload does exactly that).
     */
    public void setStorage(@NonNull StorageSpec s) {
        int n = s.usedValues.length;
        boolean withLabels = s.usedLabels != null;
        int[] c = new int[n + 4];
        float[] v = new float[n + 4];
        String[] l = withLabels ? new String[n + 4] : null;
        float[] sv = new float[n + 4];
        int[] sc = new int[n + 4];
        float seg = 0;
        for (int i = 0; i < n; i++) {
            c[i] = s.usedColors[i];
            v[i] = Math.max(0, s.usedValues[i]);
            if (withLabels) l[i] = s.usedLabels[i];
            seg += v[i];
        }
        float a = Math.max(0, s.avail);
        c[n] = trackColor;                       // available: [non-cma-able|normal]
        v[n] = a;
        sv[n] = Math.min(a, Math.max(0, s.availNonCma));
        sc[n] = s.availNonCmaColor;
        if (withLabels) l[n] = s.availLabel;
        c[n + 1] = s.cmaFreeColor;               // CMA free in buddy
        v[n + 1] = Math.max(0, s.cmaFree);
        if (withLabels) l[n + 1] = s.cmaFreeLabel;
        c[n + 2] = s.cmaOtherColor;              // CMA lent to other apps
        v[n + 2] = Math.max(0, s.cmaOther);
        if (withLabels) l[n + 2] = s.cmaOtherLabel;
        c[n + 3] = s.deficitColor;               // deficit, flush right
        v[n + 3] = Math.max(0, s.deficit);
        if (withLabels) l[n + 3] = s.deficitLabel;
        setData(c, v, l, sv, sc, Math.max(s.want, seg + a + v[n + 1] + v[n + 2]));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float radius = Math.min(6f * density, h / 3f);
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
            float segRight = Math.min(x + segW, w);
            // Optional left sub-split: same logical segment, two fill colors.
            float subW = 0f;
            if (subLeftValues != null && subLeftColors != null
                && i < subLeftValues.length && i < subLeftColors.length
                && subLeftValues[i] > 0f && values[i] > 0f) {
                subW = Math.min(segW, segW * (subLeftValues[i] / values[i]));
            }
            if (subW > 0f) {
                paint.setColor(subLeftColors[i]);
                canvas.drawRect(x, 0, Math.min(x + subW, segRight), h, paint);
            }
            paint.setColor(colors[i]);
            canvas.drawRect(Math.min(x + subW, segRight), 0, segRight, h, paint);
            drawLabel(canvas, i, x, segRight, h);
            x += segW;
        }
    }

    /**
     * Draw the block label as two stacked, centred lines: {@code "top\nbottom"}
     * (label over capacity). A label with no newline draws as a single line.
     * The whole label is omitted if the widest line would not fit the block.
     */
    private void drawLabel(@NonNull Canvas canvas, int i, float left, float right, float h) {
        if (labelTop == null || i >= labelTop.length) return;
        String top = labelTop[i];
        if (top == null) return;                    // no / empty label
        String bottom = labelBottom[i];

        float segW = right - left;
        float topW = labelTopW[i];
        float botW = labelBotW[i];
        if (segW < Math.max(topW, botW) + 2f * labelPad) return;   // omit if it won't fit

        int color = labelTextColor[i];
        textPaint.setColor(color);
        float topH = textPaint.descent() - textPaint.ascent();
        if (bottom == null) {
            float ty = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(top, left + (segW - topW) / 2f, ty, textPaint);
            return;
        }
        subTextPaint.setColor(color);
        float gap = 1f * density;
        float botH = subTextPaint.descent() - subTextPaint.ascent();
        float startY = (h - (topH + gap + botH)) / 2f;
        float topBaseline = startY - textPaint.ascent();
        float botBaseline = startY + topH + gap - subTextPaint.ascent();
        canvas.drawText(top, left + (segW - topW) / 2f, topBaseline, textPaint);
        canvas.drawText(bottom, left + (segW - botW) / 2f, botBaseline, subTextPaint);
    }

    /**
     * Pick black or white text for readability over a (possibly translucent)
     * segment colour by compositing it over the theme surface and comparing
     * perceived luminance.
     */
    private int textColorFor(int segColor) {
        int a = Color.alpha(segColor);
        int bg = darkTheme ? 0xFF1C1B1F : 0xFFFFFFFF;
        int r = (Color.red(segColor) * a + Color.red(bg) * (255 - a)) / 255;
        int g = (Color.green(segColor) * a + Color.green(bg) * (255 - a)) / 255;
        int b = (Color.blue(segColor) * a + Color.blue(bg) * (255 - a)) / 255;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return lum > 0.6 ? 0xDE000000 : 0xFFFFFFFF;
    }
}
