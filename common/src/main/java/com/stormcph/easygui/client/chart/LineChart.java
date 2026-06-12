package com.stormcph.easygui.client.chart;

import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.stat.TimeSeries;
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
 * A multi-series line chart with an animated auto-scaling value axis — pair it with
 * {@link com.stormcph.easygui.client.stat.Metric} series for live HUD graphs (it reads
 * the data during render only, no ticking) or full-size statistics panels.
 *
 * <p>Add series with {@link #addSeries}; colors default to theme-coordinated hues via
 * {@link SeriesColors} (series 0 = accent). Window the data by time
 * ({@link #setTimeWindow(double)}) or by sample count ({@link #setSampleWindow(int)}) —
 * the last one set wins. Defaults are minimal: subtle gridlines only; opt into axis
 * labels, a legend, an area fill, midpoint smoothing or step lines with the chainable
 * setters ({@code setSmooth}/{@code setStep} are mutually exclusive — the last setter
 * wins). Data is downsampled to ~2 points per pixel column with min/max bucketing, and
 * the {@link ChartScale} rescale glides, so the chart reads correctly anywhere from a
 * 160×70 HUD card (the default size) up to a 400×200 screen panel.</p>
 */
@Environment(EnvType.CLIENT)
public class LineChart extends Widget {
    private static final float LINE_WIDTH = 1.2f;
    private static final float FILL_ALPHA = 0.15f;
    private static final float LEGEND_DOT_RADIUS = 2f;

    /** One data series and its per-series render scratch. */
    private static final class Series {
        final String name;
        final TimeSeries data;
        final int explicitColor;
        final ChartScale.Sampler sampler = new ChartScale.Sampler();
        /** Final polyline points; reallocated only when the point count changes. */
        float[] pts = new float[0];
        int count;

        Series(String name, TimeSeries data, int explicitColor) {
            this.name = name;
            this.data = data;
            this.explicitColor = explicitColor;
        }
    }

    private final List<Series> seriesList = new ArrayList<>();
    private final ChartScale scale = new ChartScale();
    private double timeWindow;
    private int sampleWindow;
    private boolean showGrid = true;
    private boolean showAxisLabels;
    private boolean showLegend;
    private boolean fill;
    private boolean smooth;
    private boolean step;

    /** Shared pre-transform point scratch (capacity-grown, reused across series). */
    private float[] baseXY = new float[0];
    private int baseCount;
    private final float[] trapezoid = new float[8];

    public LineChart() {
        this.width = 160f;
        this.height = 70f;
    }

    // ------------------------------------------------------------------
    // Series
    // ------------------------------------------------------------------

    /** Adds a series with an auto-assigned theme-coordinated color. */
    public LineChart addSeries(String name, TimeSeries data) {
        return addSeries(name, data, 0);
    }

    /** Adds a series with an explicit color ({@code 0}: auto-assign from the theme). */
    public LineChart addSeries(String name, TimeSeries data, int color) {
        seriesList.add(new Series(Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(data, "data"), color));
        return this;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** Shows only samples from the last {@code seconds}; clears any sample window. */
    public LineChart setTimeWindow(double seconds) {
        this.timeWindow = seconds;
        if (seconds > 0) {
            this.sampleWindow = 0;
        }
        return this;
    }

    /**
     * Shows only the newest {@code count} samples per series; clears any time window.
     * In this mode each series is spread by index across the full width (series with
     * different sample counts each span the whole chart) — use a time window when
     * series must stay aligned in time.
     */
    public LineChart setSampleWindow(int count) {
        this.sampleWindow = count;
        if (count > 0) {
            this.timeWindow = 0;
        }
        return this;
    }

    /** Horizontal gridlines at the axis ticks (subtle; on by default). */
    public LineChart setShowGrid(boolean showGrid) {
        this.showGrid = showGrid;
        return this;
    }

    /** Tiny muted tick value labels along the left edge, inside the plot. */
    public LineChart setShowAxisLabels(boolean showAxisLabels) {
        this.showAxisLabels = showAxisLabels;
        return this;
    }

    /** A top row of color dot + series name per series. */
    public LineChart setShowLegend(boolean showLegend) {
        this.showLegend = showLegend;
        return this;
    }

    /** Translucent area fill under each line. */
    public LineChart setFill(boolean fill) {
        this.fill = fill;
        return this;
    }

    /** Midpoint smoothing of the polyline. Mutually exclusive with {@link #setStep}; last setter wins. */
    public LineChart setSmooth(boolean smooth) {
        this.smooth = smooth;
        if (smooth) {
            this.step = false;
        }
        return this;
    }

    /** Step-line mode. Mutually exclusive with {@link #setSmooth}; last setter wins. */
    public LineChart setStep(boolean step) {
        this.step = step;
        if (step) {
            this.smooth = false;
        }
        return this;
    }

    /** The value axis, for advanced setup: {@code chart.scale().setIncludeZero(true)}. */
    public ChartScale scale() {
        return scale;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();

        float chartY = y;
        float chartH = height;
        if (showLegend && !seriesList.isEmpty()) {
            drawLegend(graphics, theme);
            float legendHeight = Text2D.lineHeight() + 3f;
            chartY += legendHeight;
            chartH -= legendHeight;
        }
        if (chartH <= 2f) {
            return;
        }

        // Sample every series and gather the combined value bounds
        boolean anyData = false;
        float dataMin = Float.POSITIVE_INFINITY;
        float dataMax = Float.NEGATIVE_INFINITY;
        int maxPoints = Math.max(4, (int) Math.ceil(width) * 2);
        for (Series series : seriesList) {
            series.count = series.sampler.sample(series.data, timeWindow, sampleWindow, maxPoints, true);
            if (series.count > 0) {
                anyData = true;
                dataMin = Math.min(dataMin, series.sampler.dataMin());
                dataMax = Math.max(dataMax, series.sampler.dataMax());
            }
        }
        if (!anyData) {
            int textColor = enabled ? theme.textMuted : ColorUtil.multiplyAlpha(theme.textMuted, 0.45f);
            Text2D.drawCentered(graphics, "no data", x + width / 2f,
                    chartY + (chartH - Text2D.lineHeight()) / 2f + 0.5f, textColor);
            return;
        }

        scale.update(dataMin, dataMax);

        if (showGrid || showAxisLabels) {
            drawGridAndLabels(graphics, theme, chartY, chartH);
        }

        // Shared x domain so multiple series stay aligned in time
        long t0 = 0L;
        long t1 = 0L;
        if (timeWindow > 0) {
            t1 = System.nanoTime();
            t0 = t1 - (long) (timeWindow * 1.0E9);
        } else if (sampleWindow <= 0) {
            t0 = Long.MAX_VALUE;
            t1 = Long.MIN_VALUE;
            for (Series series : seriesList) {
                if (series.count > 0) {
                    t0 = Math.min(t0, series.sampler.timeNanos(0));
                    t1 = Math.max(t1, series.sampler.timeNanos(series.count - 1));
                }
            }
        }

        for (int i = 0; i < seriesList.size(); i++) {
            Series series = seriesList.get(i);
            if (series.count == 0) {
                continue;
            }
            int color = series.explicitColor != 0 ? series.explicitColor : SeriesColors.color(theme, i);
            if (!enabled) {
                color = ColorUtil.multiplyAlpha(color, 0.45f);
            }
            buildBasePoints(series, chartY, chartH, t0, t1);
            buildFinalPoints(series);
            if (series.count == 1) {
                Render2D.fillCircle(graphics, series.pts[0], series.pts[1], LINE_WIDTH, color);
                continue;
            }
            if (fill) {
                drawFill(graphics, series.pts, chartY + chartH, ColorUtil.multiplyAlpha(color, FILL_ALPHA));
            }
            Render2D.polyline(graphics, series.pts, LINE_WIDTH, color, false);
        }
    }

    private void drawLegend(GuiGraphics graphics, Theme theme) {
        int textColor = enabled ? theme.textMuted : ColorUtil.multiplyAlpha(theme.textMuted, 0.45f);
        float entryX = x;
        for (int i = 0; i < seriesList.size(); i++) {
            Series series = seriesList.get(i);
            int color = series.explicitColor != 0 ? series.explicitColor : SeriesColors.color(theme, i);
            if (!enabled) {
                color = ColorUtil.multiplyAlpha(color, 0.45f);
            }
            float entryWidth = LEGEND_DOT_RADIUS * 2f + 3f + Text2D.width(series.name);
            if (entryX > x && entryX + entryWidth > x + width) {
                break; // out of room; drop the remaining entries
            }
            Render2D.fillCircle(graphics, entryX + LEGEND_DOT_RADIUS, y + Text2D.lineHeight() / 2f,
                    LEGEND_DOT_RADIUS, color);
            Text2D.draw(graphics, series.name, entryX + LEGEND_DOT_RADIUS * 2f + 3f, y + 0.5f, textColor);
            entryX += entryWidth + 8f;
        }
    }

    private void drawGridAndLabels(GuiGraphics graphics, Theme theme, float chartY, float chartH) {
        int gridColor = enabled ? theme.outline : ColorUtil.multiplyAlpha(theme.outline, 0.45f);
        int labelColor = enabled ? theme.textMuted : ColorUtil.multiplyAlpha(theme.textMuted, 0.45f);
        int ticks = scale.computeTicks(chartH);
        for (int i = 0; i < ticks; i++) {
            float tickY = scale.toY(scale.tickValue(i), chartY, chartH);
            if (showGrid) {
                Render2D.line(graphics, x, tickY, x + width, tickY, 1f, gridColor, false, false);
            }
            if (showAxisLabels) {
                String label = ChartScale.formatValue(scale.tickValue(i), scale.tickStep());
                float labelY = tickY - Text2D.lineHeight() - 1f;
                if (labelY < chartY) {
                    labelY = tickY + 2f;
                }
                Text2D.draw(graphics, label, x + 2f, labelY, labelColor);
            }
        }
    }

    /** Maps the sampled data into screen space ({@link #baseXY}), before smoothing/stepping. */
    private void buildBasePoints(Series series, float chartY, float chartH, long t0, long t1) {
        int n = series.count;
        if (baseXY.length < n * 2) {
            baseXY = new float[Math.max(n * 2, baseXY.length * 2)];
        }
        boolean byIndex = sampleWindow > 0 || t1 <= t0;
        for (int i = 0; i < n; i++) {
            float px;
            if (byIndex) {
                px = n == 1 ? x + width : x + width * i / (n - 1);
            } else {
                double t = (double) (series.sampler.timeNanos(i) - t0) / (double) (t1 - t0);
                px = x + width * (float) Mth.clamp(t, 0.0, 1.0);
            }
            baseXY[i * 2] = px;
            baseXY[i * 2 + 1] = scale.toY(series.sampler.value(i), chartY, chartH);
        }
        baseCount = n;
    }

    /** Applies the smooth/step transform from {@link #baseXY} into {@code series.pts}. */
    private void buildFinalPoints(Series series) {
        int n = baseCount;
        int outN;
        if (smooth && n >= 3) {
            outN = 2 * n;
        } else if (step && n >= 2) {
            outN = 2 * n - 1;
        } else {
            outN = n;
        }
        if (series.pts.length != outN * 2) {
            series.pts = new float[outN * 2];
        }
        float[] out = series.pts;
        if (smooth && n >= 3) {
            // One round of midpoint (corner-cutting) smoothing: keep the endpoints,
            // replace each interior corner with points 25% / 75% along its segments
            int k = 0;
            out[k++] = baseXY[0];
            out[k++] = baseXY[1];
            for (int i = 0; i + 1 < n; i++) {
                float x0 = baseXY[i * 2];
                float y0 = baseXY[i * 2 + 1];
                float x1 = baseXY[i * 2 + 2];
                float y1 = baseXY[i * 2 + 3];
                out[k++] = x0 + (x1 - x0) * 0.25f;
                out[k++] = y0 + (y1 - y0) * 0.25f;
                out[k++] = x0 + (x1 - x0) * 0.75f;
                out[k++] = y0 + (y1 - y0) * 0.75f;
            }
            out[k++] = baseXY[(n - 1) * 2];
            out[k] = baseXY[(n - 1) * 2 + 1];
        } else if (step && n >= 2) {
            // Hold each value until the next sample: horizontal then vertical segments
            int k = 0;
            out[k++] = baseXY[0];
            out[k++] = baseXY[1];
            for (int i = 1; i < n; i++) {
                out[k++] = baseXY[i * 2];
                out[k++] = baseXY[(i - 1) * 2 + 1];
                out[k++] = baseXY[i * 2];
                out[k++] = baseXY[i * 2 + 1];
            }
        } else {
            System.arraycopy(baseXY, 0, out, 0, n * 2);
        }
    }

    /** Area fill as per-segment convex trapezoids (the whole area would be concave). */
    private void drawFill(GuiGraphics graphics, float[] pts, float baseline, int fillColor) {
        int n = pts.length / 2;
        for (int i = 0; i + 1 < n; i++) {
            float x0 = pts[i * 2];
            float y0 = pts[i * 2 + 1];
            float x1 = pts[i * 2 + 2];
            float y1 = pts[i * 2 + 3];
            if ((baseline - y0 < 0.01f && baseline - y1 < 0.01f) || x1 - x0 < 0.01f) {
                continue;
            }
            trapezoid[0] = x0;
            trapezoid[1] = y0;
            trapezoid[2] = x1;
            trapezoid[3] = y1;
            trapezoid[4] = x1;
            trapezoid[5] = baseline;
            trapezoid[6] = x0;
            trapezoid[7] = baseline;
            Render2D.fillPolygon(graphics, trapezoid, fillColor);
        }
    }
}
