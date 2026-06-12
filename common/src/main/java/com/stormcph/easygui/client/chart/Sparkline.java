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

import java.util.Locale;
import java.util.Objects;

/**
 * A tiny, axis-less single-series chart — "the value over time, at a glance" — sized
 * for HUD overlays and dense rows (defaults to 60×20, happily scales up).
 *
 * <p>Hand it a {@link TimeSeries} (e.g. {@code Metrics.fps().series()}) and it stays
 * live for free: the series is read during render only and never mutated. The value
 * axis auto-scales through a {@link ChartScale}, so rescales glide instead of snapping.
 * Pick a {@link Variant} ({@code LINE}, {@code AREA}, {@code BARS}), optionally window
 * the data with {@link #setTimeWindow(double)} and show the current value with
 * {@link #setShowValue(boolean)}. Data is downsampled to ~2 points per pixel column
 * with min/max bucketing, so spikes stay visible at any density.</p>
 */
@Environment(EnvType.CLIENT)
public class Sparkline extends Widget {
    /** How the samples are drawn. */
    public enum Variant {
        /** A plain polyline. */
        LINE,
        /** The polyline plus a translucent fill down to the bottom edge. */
        AREA,
        /** One thin column per sample, anchored at the bottom edge. */
        BARS
    }

    private static final float LINE_WIDTH = 1f;
    private static final float AREA_ALPHA = 0.18f;

    private final TimeSeries series;
    private final ChartScale scale = new ChartScale();
    private final ChartScale.Sampler sampler = new ChartScale.Sampler();
    private Variant variant = Variant.LINE;
    private double timeWindow;
    private int color;
    private boolean showValue;

    /** Polyline scratch; reallocated only when the point count changes. */
    private float[] linePts = new float[0];
    private final float[] trapezoid = new float[8];

    public Sparkline(TimeSeries series) {
        this.series = Objects.requireNonNull(series, "series");
        this.width = 60f;
        this.height = 20f;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    public Sparkline setVariant(Variant variant) {
        this.variant = Objects.requireNonNull(variant, "variant");
        return this;
    }

    /** Shows only samples from the last {@code seconds} ({@code <= 0}: show everything). */
    public Sparkline setTimeWindow(double seconds) {
        this.timeWindow = seconds;
        return this;
    }

    /** Overrides the line/bar color (default: theme accent). Pass {@code 0} to reset. */
    public Sparkline setColor(int color) {
        this.color = color;
        return this;
    }

    /** Draws the latest value right-aligned next to the chart (muted, vertically centered). */
    public Sparkline setShowValue(boolean showValue) {
        this.showValue = showValue;
        return this;
    }

    /** The value axis, for advanced setup: {@code spark.scale().setRange(0, 100)}. */
    public ChartScale scale() {
        return scale;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        int chartColor = color != 0 ? color : theme.accent;
        int valueColor = theme.textMuted;
        if (!enabled) {
            chartColor = ColorUtil.multiplyAlpha(chartColor, 0.45f);
            valueColor = ColorUtil.multiplyAlpha(valueColor, 0.45f);
        }

        float chartW = width;
        String valueText = null;
        if (showValue && !series.isEmpty()) {
            valueText = formatValue(series.latest());
            chartW = Math.max(8f, width - Text2D.width(valueText) - 4f);
        }

        int maxPoints = variant == Variant.BARS
                ? Math.max(2, (int) Math.ceil(chartW / 3f))
                : Math.max(4, (int) Math.ceil(chartW) * 2);
        int n = sampler.sample(series, timeWindow, 0, maxPoints, variant != Variant.BARS);
        if (n == 0) {
            // Empty series: just a muted hairline baseline
            Render2D.line(graphics, x, y + height - 0.5f, x + width, y + height - 0.5f, 1f,
                    theme.outline, false, false);
            return;
        }

        if (variant == Variant.BARS) {
            // Bars are anchored at the bottom, so they need a zero baseline to make sense
            scale.update(Math.min(sampler.dataMin(), 0f), Math.max(sampler.dataMax(), 0f));
        } else {
            scale.update(sampler.dataMin(), sampler.dataMax());
        }

        long t0;
        long t1;
        if (timeWindow > 0) {
            t1 = System.nanoTime();
            t0 = t1 - (long) (timeWindow * 1.0E9);
        } else {
            t0 = sampler.timeNanos(0);
            t1 = sampler.timeNanos(n - 1);
        }

        switch (variant) {
            case LINE -> {
                buildLine(n, t0, t1, chartW);
                if (n == 1) {
                    Render2D.fillCircle(graphics, linePts[0], linePts[1], 1f, chartColor);
                } else {
                    Render2D.polyline(graphics, linePts, LINE_WIDTH, chartColor, false);
                }
            }
            case AREA -> {
                buildLine(n, t0, t1, chartW);
                if (n == 1) {
                    Render2D.fillCircle(graphics, linePts[0], linePts[1], 1f, chartColor);
                } else {
                    // Per-segment convex trapezoids (the whole area would be concave)
                    float base = y + height;
                    int fillColor = ColorUtil.multiplyAlpha(chartColor, AREA_ALPHA);
                    for (int i = 0; i + 1 < n; i++) {
                        float x0 = linePts[i * 2];
                        float y0 = linePts[i * 2 + 1];
                        float x1 = linePts[i * 2 + 2];
                        float y1 = linePts[i * 2 + 3];
                        if ((base - y0 < 0.01f && base - y1 < 0.01f) || x1 - x0 < 0.01f) {
                            continue;
                        }
                        trapezoid[0] = x0;
                        trapezoid[1] = y0;
                        trapezoid[2] = x1;
                        trapezoid[3] = y1;
                        trapezoid[4] = x1;
                        trapezoid[5] = base;
                        trapezoid[6] = x0;
                        trapezoid[7] = base;
                        Render2D.fillPolygon(graphics, trapezoid, fillColor);
                    }
                    Render2D.polyline(graphics, linePts, LINE_WIDTH, chartColor, false);
                }
            }
            case BARS -> {
                float barWidth = Math.max(1f, chartW / n - 1f);
                for (int i = 0; i < n; i++) {
                    float cx = xFor(sampler.timeNanos(i), t0, t1, chartW);
                    float bx = Mth.clamp(cx - barWidth / 2f, x, x + chartW - barWidth);
                    float by = scale.toY(sampler.value(i), y, height);
                    float barHeight = Math.max(0.5f, y + height - by);
                    Render2D.fillRect(graphics, bx, y + height - barHeight, barWidth, barHeight, chartColor);
                }
            }
        }

        if (valueText != null) {
            Text2D.drawRightAligned(graphics, valueText, x + width,
                    y + (height - Text2D.lineHeight()) / 2f + 0.5f, valueColor);
        }
    }

    /** Fills {@link #linePts} with screen-space points for the sampled data. */
    private void buildLine(int n, long t0, long t1, float chartW) {
        if (linePts.length != n * 2) {
            linePts = new float[n * 2];
        }
        for (int i = 0; i < n; i++) {
            linePts[i * 2] = xFor(sampler.timeNanos(i), t0, t1, chartW);
            linePts[i * 2 + 1] = scale.toY(sampler.value(i), y, height);
        }
    }

    /** Maps a timestamp into {@code [x, x + chartW]}; the newest data sits at the right edge. */
    private float xFor(long time, long t0, long t1, float chartW) {
        if (t1 <= t0) {
            return x + chartW;
        }
        double t = (double) (time - t0) / (double) (t1 - t0);
        return x + chartW * (float) Mth.clamp(t, 0.0, 1.0);
    }

    private static String formatValue(float value) {
        float abs = Math.abs(value);
        if (abs >= 100f) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (abs >= 10f) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
