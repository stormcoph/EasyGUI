package com.stormcph.easygui.client.chart;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import com.stormcph.easygui.client.widget.Widget;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A radar (spider) chart comparing one or more series across 3–8 named axes.
 *
 * <p>Define the axes once with {@link #setAxes(List)}, then add series of normalized
 * {@code 0..1} values with {@link #addSeries}. The values array is kept by reference
 * and read every frame, so mutating it (or re-adding a series under the same name)
 * makes the shape glide to the new values — every vertex animates on its own
 * {@link SmoothValue}, which also grows fresh series out of the center on appear.
 * Colors default to the theme-coordinated ramp ({@link SeriesColors}, series 0 =
 * accent); each series draws as a translucent filled polygon with a crisp outline over
 * a concentric N-gon web with spokes, axis names sitting just outside their vertices.
 * Defaults to 90×90 — readable on a HUD, happy to scale up.</p>
 */
@Environment(EnvType.CLIENT)
public class RadarChart extends Widget {
    private static final int MIN_AXES = 3;
    private static final int MAX_AXES = 8;
    private static final float HAIRLINE_WIDTH = 1f;
    private static final float OUTLINE_WIDTH = 1f;
    private static final float FILL_ALPHA = 0.25f;
    /** Gap between a vertex and its axis label. */
    private static final float LABEL_GAP = 3f;

    /** One data series with its live values and per-vertex animation state. */
    private static final class Series {
        final String name;
        float[] values;
        int explicitColor;
        SmoothValue[] anim = new SmoothValue[0];
        /** Screen-space polygon points; reallocated only when the axis count changes. */
        float[] pts = new float[0];

        Series(String name, float[] values, int explicitColor) {
            this.name = name;
            this.values = values;
            this.explicitColor = explicitColor;
        }
    }

    private String[] axes = new String[0];
    private final List<Series> seriesList = new ArrayList<>();

    /** Web ring scratch; reallocated only when the axis count changes. */
    private float[] webPts = new float[0];
    private final float[] cosA = new float[MAX_AXES];
    private final float[] sinA = new float[MAX_AXES];

    public RadarChart() {
        this.width = 90f;
        this.height = 90f;
    }

    // ------------------------------------------------------------------
    // Axes and series
    // ------------------------------------------------------------------

    /**
     * Sets the axis names, clockwise from the top. Radar charts need 3–8 axes; extra
     * names are dropped and with fewer than 3 the chart shows its empty state.
     */
    public RadarChart setAxes(List<String> names) {
        Objects.requireNonNull(names, "names");
        int n = Math.min(names.size(), MAX_AXES);
        String[] next = new String[n];
        for (int i = 0; i < n; i++) {
            next[i] = Objects.requireNonNull(names.get(i), "axis name");
        }
        this.axes = next;
        return this;
    }

    /** Adds (or updates) a series with an auto-assigned theme-coordinated color. */
    public RadarChart addSeries(String name, float[] values) {
        return addSeries(name, values, 0);
    }

    /**
     * Adds a series of normalized {@code 0..1} values, one per axis (missing entries
     * count as 0, extras are ignored), with an explicit color ({@code 0}: auto-assign
     * from the theme ramp). The array is kept by reference and read every frame, so
     * mutate it to animate the shape. Re-adding an existing name swaps its values (and
     * color, when given) in place, keeping the vertex animation state.
     */
    public RadarChart addSeries(String name, float[] values, int color) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(values, "values");
        for (Series series : seriesList) {
            if (series.name.equals(name)) {
                series.values = values;
                if (color != 0) {
                    series.explicitColor = color;
                }
                return this;
            }
        }
        seriesList.add(new Series(name, values, color));
        return this;
    }

    /** Removes all series (the web keeps rendering as long as axes are set). */
    public RadarChart clearSeries() {
        seriesList.clear();
        return this;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        int n = axes.length;
        if (n < MIN_AXES) {
            int textColor = enabled ? theme.textMuted : ColorUtil.multiplyAlpha(theme.textMuted, 0.45f);
            Text2D.drawCentered(graphics, "no data", x + width / 2f,
                    y + (height - Text2D.lineHeight()) / 2f + 0.5f, textColor);
            return;
        }

        float lineHeight = Text2D.lineHeight();
        float cx = x + width / 2f;
        float cy = y + height / 2f;
        float radius = Math.min(width, height) / 2f - (lineHeight + LABEL_GAP);
        boolean labels = radius >= 8f;
        if (!labels) {
            radius = Math.min(width, height) / 2f - 1f; // tiny widget: spend the label room on the web
        }
        if (radius < 3f) {
            return;
        }

        // Axis directions, clockwise from the top
        for (int i = 0; i < n; i++) {
            double angle = -Math.PI / 2.0 + Math.PI * 2.0 * i / n;
            cosA[i] = (float) Math.cos(angle);
            sinA[i] = (float) Math.sin(angle);
        }

        drawWeb(graphics, theme, cx, cy, radius, n);

        for (int s = 0; s < seriesList.size(); s++) {
            Series series = seriesList.get(s);
            if (series.anim.length != n) {
                rebuildAnim(series, n);
            }
            if (series.pts.length != n * 2) {
                series.pts = new float[n * 2];
            }
            for (int i = 0; i < n; i++) {
                float target = i < series.values.length ? Mth.clamp(series.values[i], 0f, 1f) : 0f;
                series.anim[i].setTarget(target);
                float v = series.anim[i].get();
                series.pts[i * 2] = cx + cosA[i] * radius * v;
                series.pts[i * 2 + 1] = cy + sinA[i] * radius * v;
            }
            int color = series.explicitColor != 0 ? series.explicitColor : SeriesColors.color(theme, s);
            Render2D.fillPolygon(graphics, series.pts, dim(ColorUtil.withAlpha(color, FILL_ALPHA)));
            Render2D.polylineClosed(graphics, series.pts, OUTLINE_WIDTH, dim(color));
        }

        if (labels) {
            drawAxisLabels(graphics, theme, cx, cy, radius, n, lineHeight);
        }
    }

    /** Concentric N-gon levels plus a spoke per axis, in hairline outline color. */
    private void drawWeb(GuiGraphics graphics, Theme theme, float cx, float cy, float radius, int n) {
        int gridColor = dim(theme.outline);
        int levels = radius >= 22f ? 3 : 2;
        if (webPts.length != n * 2) {
            webPts = new float[n * 2];
        }
        for (int level = 1; level <= levels; level++) {
            float r = radius * level / levels;
            for (int i = 0; i < n; i++) {
                webPts[i * 2] = cx + cosA[i] * r;
                webPts[i * 2 + 1] = cy + sinA[i] * r;
            }
            Render2D.polylineClosed(graphics, webPts, HAIRLINE_WIDTH, gridColor);
        }
        for (int i = 0; i < n; i++) {
            Render2D.line(graphics, cx, cy, cx + cosA[i] * radius, cy + sinA[i] * radius,
                    HAIRLINE_WIDTH, gridColor, false, false);
        }
    }

    /** Axis names just outside their vertices, aligned away from the chart by side. */
    private void drawAxisLabels(GuiGraphics graphics, Theme theme, float cx, float cy,
                                float radius, int n, float lineHeight) {
        int labelColor = dim(theme.textMuted);
        for (int i = 0; i < n; i++) {
            float lx = cx + cosA[i] * (radius + LABEL_GAP);
            float ly = cy + sinA[i] * (radius + LABEL_GAP);
            float textY;
            if (sinA[i] < -0.35f) {
                textY = ly - lineHeight;
            } else if (sinA[i] > 0.35f) {
                textY = ly + 1f;
            } else {
                textY = ly - lineHeight / 2f;
            }
            if (cosA[i] > 0.35f) {
                Text2D.draw(graphics, axes[i], lx + 1f, textY, labelColor);
            } else if (cosA[i] < -0.35f) {
                Text2D.drawRightAligned(graphics, axes[i], lx - 1f, textY, labelColor);
            } else {
                Text2D.drawCentered(graphics, axes[i], lx, textY, labelColor);
            }
        }
    }

    /** Resizes the per-vertex animation array, keeping existing vertices' state. */
    private static void rebuildAnim(Series series, int n) {
        SmoothValue[] next = new SmoothValue[n];
        for (int i = 0; i < n; i++) {
            next[i] = i < series.anim.length ? series.anim[i] : new SmoothValue(0f, 8f);
        }
        series.anim = next;
    }

    private int dim(int color) {
        return enabled ? color : ColorUtil.multiplyAlpha(color, 0.45f);
    }
}
