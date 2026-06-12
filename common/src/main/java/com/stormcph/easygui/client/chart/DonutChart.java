package com.stormcph.easygui.client.chart;

import com.stormcph.easygui.client.animation.Animation;
import com.stormcph.easygui.client.animation.Easing;
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
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * A donut (ring) chart for part-of-a-whole breakdowns, which doubles as a radial gauge.
 *
 * <p><b>Donut mode</b> (the default): add named slices with {@link #addSegment}; calling
 * it again with the same label updates that slice in place, so live data is just
 * re-adding each frame. Colors default to the theme-coordinated ramp
 * ({@link SeriesColors}, slice 0 = accent). Slices sweep in sequentially when the chart
 * first gets data, and share changes glide on per-slice {@link SmoothValue}s, with small
 * angular gaps keeping slices readable. An optional legend (color dot + label + percent)
 * appears right of the donut when there is room ({@link #setShowLegend(boolean)}).</p>
 *
 * <p><b>Gauge mode</b>: {@link #setGauge(double, double)} turns the ring into a
 * 270°&nbsp;radial gauge of a single value — set it with {@link #setValue(double)} or
 * keep it live with {@link #setValue(DoubleSupplier)}. The track renders in the theme's
 * hover surface, the fill in accent, blending toward the danger color once the fraction
 * passes {@link #setDangerFrom(float)}.</p>
 *
 * <p>Both modes can show a value in the hole ({@link #setCenterText(Supplier)}) with a
 * small muted caption under it ({@link #setCenterLabel(String)}). Defaults to 80×80 —
 * compact enough for HUD overlays, happy to scale up.</p>
 */
@Environment(EnvType.CLIENT)
public class DonutChart extends Widget {
    /** Angular gap between donut slices, in degrees (shrinks when many slices). */
    private static final float GAP_DEG = 2.5f;
    /** The gauge sweeps 270°, leaving its opening centered at the bottom. */
    private static final float GAUGE_SWEEP = 270f;
    private static final float GAUGE_START = -GAUGE_SWEEP / 2f;
    private static final float LEGEND_DOT_RADIUS = 2f;
    private static final float LEGEND_PADDING = 6f;
    /** Minimum legend column width before the legend is dropped entirely. */
    private static final float LEGEND_MIN_WIDTH = 30f;

    /** One slice and its animated share of the whole. */
    private static final class Segment {
        final String label;
        double value;
        int explicitColor;
        final SmoothValue share = new SmoothValue(0f, 8f);

        Segment(String label, double value, int explicitColor) {
            this.label = label;
            this.value = value;
            this.explicitColor = explicitColor;
        }
    }

    private final List<Segment> segments = new ArrayList<>();
    private final Animation appear = new Animation(700, Easing.CUBIC_OUT);
    private float thickness = 7f;
    private boolean showLegend;

    private boolean gauge;
    private double gaugeMin;
    private double gaugeMax = 1.0;
    private double gaugeValue;
    private DoubleSupplier valueSupplier;
    private final SmoothValue gaugeFill = new SmoothValue(0f, 8f);
    private float dangerFrom = 1f;

    private Supplier<String> centerText;
    private String centerLabel;

    /** Normalized smoothed shares, refreshed each frame (grow-and-reuse). */
    private float[] shareScratch = new float[0];

    public DonutChart() {
        this.width = 80f;
        this.height = 80f;
    }

    // ------------------------------------------------------------------
    // Segments (donut mode)
    // ------------------------------------------------------------------

    /** Adds (or updates) a slice with an auto-assigned theme-coordinated color. */
    public DonutChart addSegment(String label, double value) {
        return addSegment(label, value, 0);
    }

    /**
     * Adds a slice with an explicit color ({@code 0}: auto-assign from the theme ramp).
     * If a slice with the same label already exists, its value (and color, when given)
     * is updated in place instead — so the share animates rather than resetting.
     */
    public DonutChart addSegment(String label, double value, int color) {
        Objects.requireNonNull(label, "label");
        for (Segment segment : segments) {
            if (segment.label.equals(label)) {
                segment.value = value;
                if (color != 0) {
                    segment.explicitColor = color;
                }
                return this;
            }
        }
        segments.add(new Segment(label, value, color));
        return this;
    }

    /** Removes all slices; the appear animation replays when data next arrives. */
    public DonutChart clearSegments() {
        segments.clear();
        appear.stop();
        return this;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** Ring thickness in GUI pixels (clamped to the radius at render time). */
    public DonutChart setThickness(float thickness) {
        this.thickness = Math.max(1f, thickness);
        return this;
    }

    /** Shows a legend (color dot + label + percent) right of the donut when it fits. */
    public DonutChart setShowLegend(boolean showLegend) {
        this.showLegend = showLegend;
        return this;
    }

    /**
     * Switches to gauge mode: a 270° radial gauge mapping {@code [min, max]} onto the
     * sweep. Feed it with {@link #setValue(double)} or {@link #setValue(DoubleSupplier)}.
     */
    public DonutChart setGauge(double min, double max) {
        this.gauge = true;
        this.gaugeMin = Math.min(min, max);
        this.gaugeMax = Math.max(min, max);
        return this;
    }

    /** Sets the gauge value directly (the fill glides toward it). */
    public DonutChart setValue(double value) {
        this.gaugeValue = value;
        this.valueSupplier = null;
        return this;
    }

    /** Polls the gauge value from {@code supplier} every frame (e.g. {@code metric::value}). */
    public DonutChart setValue(DoubleSupplier supplier) {
        this.valueSupplier = supplier;
        return this;
    }

    /**
     * Above this gauge fraction (0..1) the fill blends from accent toward the theme's
     * danger color, reaching full danger at 1. Default {@code 1} (never blends).
     */
    public DonutChart setDangerFrom(float fraction) {
        this.dangerFrom = fraction;
        return this;
    }

    /** Value text drawn centered in the hole (theme text color); {@code null} to hide. */
    public DonutChart setCenterText(Supplier<String> centerText) {
        this.centerText = centerText;
        return this;
    }

    /** Small muted caption under the center text; {@code null} to hide. */
    public DonutChart setCenterLabel(String centerLabel) {
        this.centerLabel = centerLabel;
        return this;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();

        // Refresh the animated shares (donut mode reads them; the legend shows percents)
        int n = segments.size();
        if (shareScratch.length < n) {
            shareScratch = new float[Math.max(n, shareScratch.length * 2)];
        }
        double total = 0;
        for (Segment segment : segments) {
            total += Math.max(0, segment.value);
        }
        float sum = 0f;
        for (int i = 0; i < n; i++) {
            Segment segment = segments.get(i);
            segment.share.setTarget(total > 0 ? (float) (Math.max(0, segment.value) / total) : 0f);
            shareScratch[i] = segment.share.get();
            sum += shareScratch[i];
        }
        boolean hasData = sum > 1.0E-4f;
        if (hasData) {
            for (int i = 0; i < n; i++) {
                shareScratch[i] /= sum;
            }
        }

        // Layout: the donut squares up on the left when the legend is shown and fits
        boolean legend = false;
        float areaW = width;
        float legendX = 0f;
        if (showLegend && !gauge && n > 0) {
            float side = Math.min(width, height);
            if (width - side - LEGEND_PADDING >= LEGEND_MIN_WIDTH) {
                legend = true;
                areaW = side;
                legendX = x + side + LEGEND_PADDING;
            }
        }
        float cx = x + areaW / 2f;
        float cy = y + height / 2f;
        float radius = Math.min(areaW, height) / 2f - 0.5f;
        if (radius < 2f) {
            return;
        }
        float ringThickness = Math.min(thickness, radius);

        if (gauge) {
            renderGauge(graphics, theme, cx, cy, radius, ringThickness);
        } else {
            renderDonut(graphics, theme, cx, cy, radius, ringThickness, hasData);
        }
        drawCenterText(graphics, theme, cx, cy);
        if (legend) {
            drawLegend(graphics, theme, legendX, x + width);
        }
    }

    private void renderDonut(GuiGraphics graphics, Theme theme, float cx, float cy,
                             float radius, float ringThickness, boolean hasData) {
        if (!hasData) {
            // Empty state: just the muted track ring
            Render2D.strokeCircle(graphics, cx, cy, radius, ringThickness, dim(theme.surfaceHover));
            return;
        }
        if (!appear.isStarted()) {
            appear.start();
        }
        float reveal = appear.value() * 360f;

        int n = segments.size();
        int active = 0;
        for (int i = 0; i < n; i++) {
            if (shareScratch[i] > 0.002f) {
                active++;
            }
        }
        float gap = active >= 2 ? Math.min(GAP_DEG, 90f / active) : 0f;
        float usable = 360f - gap * active;

        float cursor = 0f;
        for (int i = 0; i < n; i++) {
            if (shareScratch[i] <= 0.002f) {
                continue;
            }
            float sweep = shareScratch[i] * usable;
            float start = cursor;
            float end = cursor + sweep;
            cursor = end + gap;
            float drawEnd = Math.min(end, reveal);
            if (drawEnd - start < 0.1f) {
                continue; // not yet revealed by the appear sweep
            }
            Render2D.drawArc(graphics, cx, cy, radius, ringThickness, start, drawEnd, segmentColor(theme, i));
        }
    }

    private void renderGauge(GuiGraphics graphics, Theme theme, float cx, float cy,
                             float radius, float ringThickness) {
        double value = valueSupplier != null ? valueSupplier.getAsDouble() : gaugeValue;
        float target = gaugeMax > gaugeMin
                ? (float) Mth.clamp((value - gaugeMin) / (gaugeMax - gaugeMin), 0.0, 1.0)
                : 0f;
        gaugeFill.setTarget(target);
        float fraction = gaugeFill.get();

        Render2D.drawArc(graphics, cx, cy, radius, ringThickness,
                GAUGE_START, GAUGE_START + GAUGE_SWEEP, dim(theme.surfaceHover));
        if (fraction > 0.003f) {
            float dangerBlend = dangerFrom < 1f
                    ? Mth.clamp((fraction - dangerFrom) / (1f - dangerFrom), 0f, 1f)
                    : 0f;
            int fillColor = dim(ColorUtil.lerp(theme.accent, theme.danger, dangerBlend));
            Render2D.drawArc(graphics, cx, cy, radius, ringThickness,
                    GAUGE_START, GAUGE_START + GAUGE_SWEEP * fraction, fillColor);
        }
    }

    private void drawCenterText(GuiGraphics graphics, Theme theme, float cx, float cy) {
        String value = centerText != null ? centerText.get() : null;
        boolean hasValue = value != null && !value.isEmpty();
        boolean hasLabel = centerLabel != null && !centerLabel.isEmpty();
        if (!hasValue && !hasLabel) {
            return;
        }
        float lineHeight = Text2D.lineHeight();
        if (hasValue && hasLabel) {
            float top = cy - (lineHeight * 2f + 1f) / 2f;
            Text2D.drawCentered(graphics, value, cx, top, dim(theme.text));
            Text2D.drawCentered(graphics, centerLabel, cx, top + lineHeight + 1f, dim(theme.textMuted));
        } else if (hasValue) {
            Text2D.drawCentered(graphics, value, cx, cy - lineHeight / 2f, dim(theme.text));
        } else {
            Text2D.drawCentered(graphics, centerLabel, cx, cy - lineHeight / 2f, dim(theme.textMuted));
        }
    }

    private void drawLegend(GuiGraphics graphics, Theme theme, float legendX, float rightX) {
        int textColor = dim(theme.textMuted);
        float rowHeight = Text2D.lineHeight() + 2f;
        int rows = Math.min(segments.size(), Math.max(1, (int) (height / rowHeight)));
        float top = y + (height - rows * rowHeight) / 2f;
        float textX = legendX + LEGEND_DOT_RADIUS * 2f + 3f;
        for (int i = 0; i < rows; i++) {
            Segment segment = segments.get(i);
            float rowY = top + i * rowHeight;
            Render2D.fillCircle(graphics, legendX + LEGEND_DOT_RADIUS, rowY + Text2D.lineHeight() / 2f,
                    LEGEND_DOT_RADIUS, segmentColor(theme, i));
            String percent = (int) (shareScratch[i] * 100f + 0.5f) + "%";
            float percentWidth = Text2D.width(percent);
            String label = Text2D.truncate(segment.label, (int) (rightX - textX - percentWidth - 4f));
            Text2D.draw(graphics, label, textX, rowY + 0.5f, textColor);
            Text2D.drawRightAligned(graphics, percent, rightX, rowY + 0.5f, textColor);
        }
    }

    private int segmentColor(Theme theme, int index) {
        Segment segment = segments.get(index);
        int color = segment.explicitColor != 0 ? segment.explicitColor : SeriesColors.color(theme, index);
        return dim(color);
    }

    private int dim(int color) {
        return enabled ? color : ColorUtil.multiplyAlpha(color, 0.45f);
    }
}
