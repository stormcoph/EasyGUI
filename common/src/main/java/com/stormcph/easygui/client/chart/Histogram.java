package com.stormcph.easygui.client.chart;

import com.stormcph.easygui.client.animation.SmoothValue;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A distribution chart: bins the current contents of a {@link TimeSeries} into value
 * buckets and draws one accent bar per bucket — "what values does this stat usually
 * take?", the natural companion to {@link Sparkline}'s "what is it doing over time?".
 *
 * <p>The series is re-binned on every render (cheap at the series' usual few hundred
 * samples), with bucket boundaries aligned to "nice" widths via
 * {@link ChartScale#niceStep(float)} so the bins land on round values. Pick the bucket
 * count with {@link #setBins(int)} (default 20; {@code 0} = automatic via Sturges'
 * rule), optionally restrict the data with {@link #setTimeWindow(double)}, and show a
 * tiny muted {@code min/avg/max/p99} summary row with {@link #setShowStats(boolean)}.
 * Bar heights and the count axis both animate (a {@link SmoothValue} per bin plus a
 * {@link ChartScale}), so the histogram morphs smoothly as the distribution shifts.
 * Bars are filled with a subtle vertical gradient of the accent color. Defaults to
 * 160×70 — HUD-card friendly.</p>
 */
@Environment(EnvType.CLIENT)
public class Histogram extends Widget {
    /** Hard cap on bucket count (nice-width alignment can exceed the requested count). */
    private static final int MAX_BINS = 128;
    private static final float BAR_SPEED = 12f;
    /** Alpha multiplier for the gradient's bottom stop (top is the full bar color). */
    private static final float GRADIENT_BOTTOM_ALPHA = 0.6f;

    private final TimeSeries series;
    private final ChartScale scale = new ChartScale().setIncludeZero(true);
    private final ChartScale.Sampler sampler = new ChartScale.Sampler();
    /** One height animator per bin index (kept across frames so re-binning morphs). */
    private final List<SmoothValue> binAnims = new ArrayList<>();

    private int bins = 20;
    private double timeWindow;
    private boolean showStats;
    private int color;

    // Scratch arrays (grow-and-reuse; steady frames allocate only the stats String)
    private int[] counts = new int[0];
    private float[] sorted = new float[0];

    public Histogram(TimeSeries series) {
        this.series = Objects.requireNonNull(series, "series");
        this.width = 160f;
        this.height = 70f;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /**
     * Sets the target bucket count (default 20, capped at 128). Pass {@code 0} for
     * automatic sizing via Sturges' rule ({@code ceil(log2 n) + 1}). The actual count
     * may differ slightly because bucket widths snap to nice 1-2-5 values.
     */
    public Histogram setBins(int bins) {
        this.bins = Mth.clamp(bins, 0, MAX_BINS);
        return this;
    }

    /** Bins only samples from the last {@code seconds} ({@code <= 0}: the whole series). */
    public Histogram setTimeWindow(double seconds) {
        this.timeWindow = seconds;
        return this;
    }

    /** A tiny muted summary row under the bars: {@code min 12  avg 16.4  max 31  p99 28}. */
    public Histogram setShowStats(boolean showStats) {
        this.showStats = showStats;
        return this;
    }

    /** Overrides the bar color (default: theme accent). Pass {@code 0} to reset. */
    public Histogram setColor(int color) {
        this.color = color;
        return this;
    }

    /** The count axis, for advanced setup: {@code histogram.scale().setRescaleSpeed(12)}. */
    public ChartScale scale() {
        return scale;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        int barColor = color != 0 ? color : theme.accent;
        int textColor = theme.textMuted;
        if (!enabled) {
            barColor = ColorUtil.multiplyAlpha(barColor, 0.45f);
            textColor = ColorUtil.multiplyAlpha(textColor, 0.45f);
        }

        float plotH = height;
        if (showStats) {
            plotH -= Text2D.lineHeight() + 2f;
        }
        if (plotH <= 4f) {
            return;
        }

        // Raw windowed copy of the series (maxPoints is unbounded, so no downsampling)
        int n = sampler.sample(series, timeWindow, 0, Integer.MAX_VALUE, false);
        if (n == 0) {
            Text2D.drawCentered(graphics, "no data", x + width / 2f,
                    y + (plotH - Text2D.lineHeight()) / 2f + 0.5f, textColor);
            return;
        }

        // Bucket layout: nice widths, boundaries aligned to multiples of the width
        float lo = sampler.dataMin();
        float hi = sampler.dataMax();
        int targetBins = bins > 0 ? bins : sturges(n);
        float binWidth;
        if (hi - lo < 1.0E-6f) {
            binWidth = Math.max(Math.abs(hi) * 0.1f, 1f); // flat data: one bucket holds it all
        } else {
            binWidth = ChartScale.niceStep((hi - lo) / targetBins);
        }
        float binStart = (float) Math.floor(lo / binWidth) * binWidth;
        int binCount = Math.max(1, (int) Math.ceil((hi - binStart) / binWidth + 1.0E-4f));
        if (binCount > MAX_BINS) {
            // Nice alignment overflowed the cap; fall back to exact division
            binWidth = (hi - lo) / MAX_BINS;
            binStart = lo;
            binCount = MAX_BINS;
        }

        // Count samples per bucket (recomputed every render)
        if (counts.length < binCount) {
            counts = new int[Math.max(binCount, counts.length * 2)];
        }
        Arrays.fill(counts, 0, binCount, 0);
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            float v = sampler.value(i);
            sum += v;
            counts[Mth.clamp((int) ((v - binStart) / binWidth), 0, binCount - 1)]++;
        }
        int maxCount = 0;
        for (int b = 0; b < binCount; b++) {
            maxCount = Math.max(maxCount, counts[b]);
        }
        scale.update(0f, maxCount);

        while (binAnims.size() < binCount) {
            binAnims.add(new SmoothValue(0f, BAR_SPEED));
        }

        // Bars: accent fill with a subtle vertical gradient, rounded by radiusSmall
        float slotW = width / binCount;
        float gap = Mth.clamp(slotW * 0.15f, 0.5f, 2f);
        float barW = Math.max(0.5f, slotW - gap);
        float radius = Math.min(theme.radiusSmall, barW / 2f);
        float bottom = y + plotH;
        int bottomColor = ColorUtil.multiplyAlpha(barColor, GRADIENT_BOTTOM_ALPHA);
        for (int b = 0; b < binCount; b++) {
            SmoothValue anim = binAnims.get(b);
            anim.setTarget(counts[b]);
            float smoothedCount = anim.get();
            if (smoothedCount <= 0.02f) {
                continue;
            }
            float top = scale.toY(smoothedCount, y, plotH);
            float barH = bottom - top;
            if (barH < 0.5f) {
                barH = 0.5f;
                top = bottom - barH;
            }
            float bx = x + slotW * b + (slotW - barW) / 2f;
            Render2D.fillRoundedRectGradient(graphics, bx, top, barW, barH, radius, barColor, bottomColor);
        }

        if (showStats) {
            drawStats(graphics, n, sum, textColor);
        }
    }

    /** The {@code min/avg/max/p99} row, computed exactly over the windowed copy. */
    private void drawStats(GuiGraphics graphics, int n, double sum, int textColor) {
        if (sorted.length < n) {
            sorted = new float[Math.max(n, sorted.length * 2)];
        }
        for (int i = 0; i < n; i++) {
            sorted[i] = sampler.value(i);
        }
        Arrays.sort(sorted, 0, n);
        double position = 0.99 * (n - 1);
        int lower = (int) position;
        float p99 = lower >= n - 1
                ? sorted[n - 1]
                : sorted[lower] + (sorted[lower + 1] - sorted[lower]) * (float) (position - lower);
        String text = "min " + formatStat(sorted[0])
                + "  avg " + formatStat((float) (sum / n))
                + "  max " + formatStat(sorted[n - 1])
                + "  p99 " + formatStat(p99);
        Text2D.draw(graphics, Text2D.truncate(text, (int) width), x,
                y + height - Text2D.lineHeight(), textColor);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Sturges' rule: {@code ceil(log2 n) + 1} buckets for n samples. */
    private static int sturges(int n) {
        return Mth.clamp((int) Math.ceil(Math.log(n) / Math.log(2.0)) + 1, 1, MAX_BINS);
    }

    /** Compact stat text: integers stay integers, small values keep a decimal or two. */
    private static String formatStat(float value) {
        float abs = Math.abs(value);
        if (abs >= 100f || Math.abs(value - Math.round(value)) < 0.05f) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (abs >= 10f) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
