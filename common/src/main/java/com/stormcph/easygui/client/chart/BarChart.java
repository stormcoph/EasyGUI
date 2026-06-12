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
import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * A categorical bar chart — one value per labeled category, rather than values over
 * time (for that, see {@link LineChart} / {@link Sparkline}).
 *
 * <p>Three ways to feed it: {@link #setData(List, float[])} for a single static series
 * (call it again any time — bars glide to the new values), {@link #addBar(String,
 * DoubleSupplier)} for live bars polled every frame, or repeated {@link #addSeries(String,
 * float[])} calls (plus {@link #setLabels(List)}) for multiple series drawn grouped
 * side-by-side — or stacked via {@link #setStacked(boolean)}. Every bar glides toward
 * its target on a per-bar {@link SmoothValue}, and the value axis is a zero-anchored
 * {@link ChartScale}, so data changes animate instead of snapping. Vertical by default;
 * {@link #setHorizontal(boolean)} flips it. Category labels are on by default
 * ({@link #setShowLabels(boolean)}, truncated to fit); value labels
 * ({@link #setShowValues(boolean)}) and a {@link LineChart}-style legend
 * ({@link #setShowLegend(boolean)}, multi-series only) are opt-in. Series colors come
 * from {@link SeriesColors} (series 0 = theme accent). Defaults to 160×70 — HUD-card
 * friendly, happily scales up.</p>
 */
@Environment(EnvType.CLIENT)
public class BarChart extends Widget {
    /** Bars never fully vanish: a half-pixel nub marks zero-valued categories. */
    private static final float MIN_BAR_LENGTH = 0.5f;
    private static final float BAR_SPEED = 12f;
    private static final float LEGEND_DOT_RADIUS = 2f;
    private static final float EPS = 1.0E-4f;

    /** One named static series; values are indexed by category (missing entries read 0). */
    private static final class Series {
        final String name;
        final float[] values;

        Series(String name, float[] values) {
            this.name = name;
            this.values = values;
        }
    }

    private final List<String> categories = new ArrayList<>();
    private final List<Series> seriesList = new ArrayList<>();
    private final List<DoubleSupplier> liveBars = new ArrayList<>();
    private final ChartScale scale = new ChartScale().setIncludeZero(true);
    /** One animator per (series, category) cell, indexed {@code s * categoryCount + c}. */
    private final List<SmoothValue> barAnims = new ArrayList<>();

    private boolean horizontal;
    private boolean stacked;
    private boolean showValues;
    private boolean showLabels = true;
    private boolean showLegend;

    // Scratch arrays (grow-and-reuse; steady frames allocate only label/value Strings)
    private float[] liveValues = new float[0];
    private float[] smoothedValues = new float[0];
    private int[] seriesColors = new int[0];

    public BarChart() {
        this.width = 160f;
        this.height = 70f;
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    /**
     * Replaces all data with one static series. Safe to call repeatedly with fresh
     * values (e.g. once per second) — the bars animate toward the new targets. When
     * {@code values} is longer than {@code labels}, the extra categories are labeled
     * {@code #N}. Clears any live bars and extra series.
     */
    public BarChart setData(List<String> labels, float[] values) {
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(values, "values");
        liveBars.clear();
        seriesList.clear();
        categories.clear();
        categories.addAll(labels);
        padCategories(values.length);
        seriesList.add(new Series("", values.clone()));
        return this;
    }

    /**
     * Appends a live bar whose value is polled every frame — perfect for stats that
     * change continuously ({@code chart.addBar("fps", Metrics.fps()::value)}). Live
     * bars form a single series and replace any static data from
     * {@link #setData(List, float[])} / {@link #addSeries(String, float[])}.
     */
    public BarChart addBar(String label, DoubleSupplier value) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(value, "value");
        if (liveBars.isEmpty()) {
            seriesList.clear();
            categories.clear();
        }
        categories.add(label);
        liveBars.add(value);
        return this;
    }

    /**
     * Appends a named static series for grouped (or, with {@link #setStacked(boolean)},
     * stacked) multi-series bars. The values are snapshotted; the array index is the
     * category index. Set category labels with {@link #setLabels(List)} — missing ones
     * default to {@code #N}. Clears any live bars.
     */
    public BarChart addSeries(String name, float[] values) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(values, "values");
        if (!liveBars.isEmpty()) {
            liveBars.clear();
            categories.clear();
        }
        seriesList.add(new Series(name, values.clone()));
        padCategories(values.length);
        return this;
    }

    /** Replaces the category labels (padded with {@code #N} up to the data's length). */
    public BarChart setLabels(List<String> labels) {
        Objects.requireNonNull(labels, "labels");
        categories.clear();
        categories.addAll(labels);
        int needed = liveBars.isEmpty() ? maxSeriesLength() : liveBars.size();
        padCategories(needed);
        return this;
    }

    /** Removes all categories, series and live bars. */
    public BarChart clearData() {
        categories.clear();
        seriesList.clear();
        liveBars.clear();
        return this;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** Horizontal bars (categories down the left edge) instead of vertical columns. */
    public BarChart setHorizontal(boolean horizontal) {
        this.horizontal = horizontal;
        return this;
    }

    /** Stacks multiple series into one bar per category instead of grouping side-by-side. */
    public BarChart setStacked(boolean stacked) {
        this.stacked = stacked;
        return this;
    }

    /** Draws each bar's value next to its outer end (muted, compact format). */
    public BarChart setShowValues(boolean showValues) {
        this.showValues = showValues;
        return this;
    }

    /** Category labels below (vertical) or left of (horizontal) the bars. On by default. */
    public BarChart setShowLabels(boolean showLabels) {
        this.showLabels = showLabels;
        return this;
    }

    /** A top row of color dot + series name, shown when there are multiple series. */
    public BarChart setShowLegend(boolean showLegend) {
        this.showLegend = showLegend;
        return this;
    }

    /** The value axis, for advanced setup: {@code chart.scale().setRange(0, 100)}. */
    public ChartScale scale() {
        return scale;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        boolean live = !liveBars.isEmpty();
        int catCount = categories.size();
        int serCount = live ? 1 : seriesList.size();
        int labelColor = enabled ? theme.textMuted : ColorUtil.multiplyAlpha(theme.textMuted, 0.45f);

        if (catCount == 0 || serCount == 0) {
            Text2D.drawCentered(graphics, "no data", x + width / 2f,
                    y + (height - Text2D.lineHeight()) / 2f + 0.5f, labelColor);
            return;
        }

        if (live) {
            if (liveValues.length < catCount) {
                liveValues = new float[Math.max(catCount, liveValues.length * 2)];
            }
            for (int c = 0; c < catCount; c++) {
                liveValues[c] = c < liveBars.size() ? (float) liveBars.get(c).getAsDouble() : 0f;
            }
        }

        // Layout: legend on top, category labels at the bottom (vertical) or left (horizontal)
        float plotX = x;
        float plotY = y;
        float plotW = width;
        float plotH = height;
        if (showLegend && serCount > 1) {
            drawLegend(graphics, theme);
            float legendHeight = Text2D.lineHeight() + 3f;
            plotY += legendHeight;
            plotH -= legendHeight;
        }
        if (showLabels) {
            if (horizontal) {
                float labelWidth = 0f;
                for (int c = 0; c < catCount; c++) {
                    labelWidth = Math.max(labelWidth, Text2D.width(categories.get(c)));
                }
                labelWidth = Math.min(labelWidth, width * 0.4f);
                if (labelWidth > 0f) {
                    plotX += labelWidth + 3f;
                    plotW -= labelWidth + 3f;
                }
            } else {
                plotH -= Text2D.lineHeight() + 2f;
            }
        }
        if (plotW <= 4f || plotH <= 4f) {
            return;
        }

        boolean stackMode = stacked && serCount > 1;

        // Animated value axis, retargeted at the data's (stacked) extremes; zero stays in view
        float dataMin = Float.POSITIVE_INFINITY;
        float dataMax = Float.NEGATIVE_INFINITY;
        if (stackMode) {
            for (int c = 0; c < catCount; c++) {
                float pos = 0f;
                float neg = 0f;
                for (int s = 0; s < serCount; s++) {
                    float v = targetValue(s, c);
                    if (v > 0f) {
                        pos += v;
                    } else {
                        neg += v;
                    }
                }
                dataMax = Math.max(dataMax, pos);
                dataMin = Math.min(dataMin, neg);
            }
        } else {
            for (int s = 0; s < serCount; s++) {
                for (int c = 0; c < catCount; c++) {
                    float v = targetValue(s, c);
                    dataMin = Math.min(dataMin, v);
                    dataMax = Math.max(dataMax, v);
                }
            }
        }
        scale.update(dataMin, dataMax);

        // Per-bar animation toward the current targets
        int cells = serCount * catCount;
        while (barAnims.size() < cells) {
            barAnims.add(new SmoothValue(0f, BAR_SPEED));
        }
        if (smoothedValues.length < cells) {
            smoothedValues = new float[Math.max(cells, smoothedValues.length * 2)];
        }
        for (int s = 0; s < serCount; s++) {
            for (int c = 0; c < catCount; c++) {
                int i = s * catCount + c;
                SmoothValue anim = barAnims.get(i);
                anim.setTarget(targetValue(s, c));
                smoothedValues[i] = anim.get();
            }
        }

        if (seriesColors.length < serCount) {
            seriesColors = new int[Math.max(serCount, seriesColors.length * 2)];
        }
        for (int s = 0; s < serCount; s++) {
            int color = SeriesColors.color(theme, s);
            seriesColors[s] = enabled ? color : ColorUtil.multiplyAlpha(color, 0.45f);
        }

        if (horizontal) {
            drawHorizontal(graphics, theme, plotX, plotY, plotW, plotH, serCount, catCount, stackMode, labelColor);
        } else {
            drawVertical(graphics, theme, plotX, plotY, plotW, plotH, serCount, catCount, stackMode, labelColor);
        }
    }

    private void drawVertical(GuiGraphics graphics, Theme theme, float plotX, float plotY,
                              float plotW, float plotH, int serCount, int catCount,
                              boolean stackMode, int labelColor) {
        float slotW = plotW / catCount;
        float groupGap = Mth.clamp(slotW * 0.2f, 1f, 8f);
        float groupW = Math.max(1f, slotW - groupGap);
        float yZero = scale.toY(0f, plotY, plotH);
        int lineH = Text2D.lineHeight();

        for (int c = 0; c < catCount; c++) {
            float slotX = plotX + slotW * c;
            float groupX = slotX + (slotW - groupW) / 2f;

            if (stackMode) {
                float radius = Math.min(theme.radiusSmall, groupW / 2f);
                int lastPos = -1;
                int lastNeg = -1;
                for (int s = 0; s < serCount; s++) {
                    float v = smoothedValues[s * catCount + c];
                    if (v > EPS) {
                        lastPos = s;
                    } else if (v < -EPS) {
                        lastNeg = s;
                    }
                }
                float cumPos = 0f;
                float cumNeg = 0f;
                for (int s = 0; s < serCount; s++) {
                    float v = smoothedValues[s * catCount + c];
                    if (v > EPS) {
                        float y0 = scale.toY(cumPos, plotY, plotH);
                        cumPos += v;
                        float y1 = scale.toY(cumPos, plotY, plotH);
                        vBar(graphics, groupX, groupW, y0, y1, radius, s == lastPos, true, seriesColors[s]);
                    } else if (v < -EPS) {
                        float y0 = scale.toY(cumNeg, plotY, plotH);
                        cumNeg += v;
                        float y1 = scale.toY(cumNeg, plotY, plotH);
                        vBar(graphics, groupX, groupW, y0, y1, radius, s == lastNeg, false, seriesColors[s]);
                    }
                }
                if (lastPos < 0 && lastNeg < 0) {
                    vBar(graphics, groupX, groupW, yZero - MIN_BAR_LENGTH, yZero, radius, true, true,
                            seriesColors[0]);
                }
                if (showValues) {
                    float total = 0f;
                    for (int s = 0; s < serCount; s++) {
                        total += targetValue(s, c);
                    }
                    boolean positive = cumPos > EPS || lastNeg < 0;
                    float labelY = positive
                            ? scale.toY(cumPos, plotY, plotH) - lineH - 1f
                            : scale.toY(cumNeg, plotY, plotH) + 1f;
                    labelY = Mth.clamp(labelY, plotY, plotY + plotH - lineH);
                    Text2D.drawCentered(graphics, formatCompact(total), groupX + groupW / 2f, labelY, labelColor);
                }
            } else {
                float inGap = serCount > 1 ? Mth.clamp(groupW * 0.06f, 0.5f, 2f) : 0f;
                float barW = Math.max(1f, (groupW - inGap * (serCount - 1)) / serCount);
                float radius = Math.min(theme.radiusSmall, barW / 2f);
                for (int s = 0; s < serCount; s++) {
                    float bx = groupX + s * (barW + inGap);
                    float v = smoothedValues[s * catCount + c];
                    boolean positive = v >= 0f;
                    float yVal = scale.toY(v, plotY, plotH);
                    if (Math.abs(yVal - yZero) < MIN_BAR_LENGTH) {
                        yVal = positive ? yZero - MIN_BAR_LENGTH : yZero + MIN_BAR_LENGTH;
                    }
                    vBar(graphics, bx, barW, yZero, yVal, radius, true, positive, seriesColors[s]);
                    if (showValues) {
                        float labelY = positive ? Math.min(yVal, yZero) - lineH - 1f : Math.max(yVal, yZero) + 1f;
                        labelY = Mth.clamp(labelY, plotY, plotY + plotH - lineH);
                        Text2D.drawCentered(graphics, formatCompact(targetValue(s, c)),
                                bx + barW / 2f, labelY, labelColor);
                    }
                }
            }

            if (showLabels) {
                String label = categories.get(c);
                if (!label.isEmpty()) {
                    Text2D.drawCentered(graphics, Text2D.truncate(label, (int) slotW),
                            slotX + slotW / 2f, plotY + plotH + 2f, labelColor);
                }
            }
        }
    }

    private void drawHorizontal(GuiGraphics graphics, Theme theme, float plotX, float plotY,
                                float plotW, float plotH, int serCount, int catCount,
                                boolean stackMode, int labelColor) {
        float slotH = plotH / catCount;
        float groupGap = Mth.clamp(slotH * 0.2f, 1f, 8f);
        float groupH = Math.max(1f, slotH - groupGap);
        float xZero = plotX + scale.normalize(0f) * plotW;
        float labelBudget = plotX - x - 3f;
        int lineH = Text2D.lineHeight();

        for (int c = 0; c < catCount; c++) {
            float slotY = plotY + slotH * c;
            float groupY = slotY + (slotH - groupH) / 2f;

            if (stackMode) {
                float radius = Math.min(theme.radiusSmall, groupH / 2f);
                int lastPos = -1;
                int lastNeg = -1;
                for (int s = 0; s < serCount; s++) {
                    float v = smoothedValues[s * catCount + c];
                    if (v > EPS) {
                        lastPos = s;
                    } else if (v < -EPS) {
                        lastNeg = s;
                    }
                }
                float cumPos = 0f;
                float cumNeg = 0f;
                for (int s = 0; s < serCount; s++) {
                    float v = smoothedValues[s * catCount + c];
                    if (v > EPS) {
                        float x0 = plotX + scale.normalize(cumPos) * plotW;
                        cumPos += v;
                        float x1 = plotX + scale.normalize(cumPos) * plotW;
                        hBar(graphics, groupY, groupH, x0, x1, radius, s == lastPos, true, seriesColors[s]);
                    } else if (v < -EPS) {
                        float x0 = plotX + scale.normalize(cumNeg) * plotW;
                        cumNeg += v;
                        float x1 = plotX + scale.normalize(cumNeg) * plotW;
                        hBar(graphics, groupY, groupH, x0, x1, radius, s == lastNeg, false, seriesColors[s]);
                    }
                }
                if (lastPos < 0 && lastNeg < 0) {
                    hBar(graphics, groupY, groupH, xZero, xZero + MIN_BAR_LENGTH, radius, true, true,
                            seriesColors[0]);
                }
                if (showValues) {
                    float total = 0f;
                    for (int s = 0; s < serCount; s++) {
                        total += targetValue(s, c);
                    }
                    boolean positive = cumPos > EPS || lastNeg < 0;
                    float endX = plotX + scale.normalize(positive ? cumPos : cumNeg) * plotW;
                    drawValueLabelH(graphics, formatCompact(total), endX, groupY + groupH / 2f,
                            positive, labelColor);
                }
            } else {
                float inGap = serCount > 1 ? Mth.clamp(groupH * 0.06f, 0.5f, 2f) : 0f;
                float barH = Math.max(1f, (groupH - inGap * (serCount - 1)) / serCount);
                float radius = Math.min(theme.radiusSmall, barH / 2f);
                for (int s = 0; s < serCount; s++) {
                    float by = groupY + s * (barH + inGap);
                    float v = smoothedValues[s * catCount + c];
                    boolean positive = v >= 0f;
                    float xVal = plotX + scale.normalize(v) * plotW;
                    if (Math.abs(xVal - xZero) < MIN_BAR_LENGTH) {
                        xVal = positive ? xZero + MIN_BAR_LENGTH : xZero - MIN_BAR_LENGTH;
                    }
                    hBar(graphics, by, barH, xZero, xVal, radius, true, positive, seriesColors[s]);
                    if (showValues) {
                        drawValueLabelH(graphics, formatCompact(targetValue(s, c)), xVal, by + barH / 2f,
                                positive, labelColor);
                    }
                }
            }

            if (showLabels && labelBudget > 0f) {
                String label = categories.get(c);
                if (!label.isEmpty()) {
                    Text2D.drawRightAligned(graphics, Text2D.truncate(label, (int) labelBudget),
                            plotX - 3f, slotY + (slotH - lineH) / 2f + 0.5f, labelColor);
                }
            }
        }
    }

    /** A value label at a horizontal bar's end, kept inside the widget bounds. */
    private void drawValueLabelH(GuiGraphics graphics, String text, float endX, float centerY,
                                 boolean positive, int color) {
        float ty = centerY - Text2D.lineHeight() / 2f + 0.5f;
        if (positive) {
            float tx = endX + 2f;
            if (tx + Text2D.width(text) > x + width) {
                Text2D.drawRightAligned(graphics, text, x + width, ty, color);
            } else {
                Text2D.draw(graphics, text, tx, ty, color);
            }
        } else {
            Text2D.draw(graphics, text, Math.max(x, endX - 2f - Text2D.width(text)), ty, color);
        }
    }

    private void drawLegend(GuiGraphics graphics, Theme theme) {
        int textColor = enabled ? theme.textMuted : ColorUtil.multiplyAlpha(theme.textMuted, 0.45f);
        float entryX = x;
        for (int s = 0; s < seriesList.size(); s++) {
            Series series = seriesList.get(s);
            int color = SeriesColors.color(theme, s);
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

    /** One vertical bar/segment between two pixel rows; only the outer end is rounded. */
    private static void vBar(GuiGraphics graphics, float bx, float bw, float yEdge0, float yEdge1,
                             float radius, boolean roundOuter, boolean positive, int color) {
        float top = Math.min(yEdge0, yEdge1);
        float h = Math.abs(yEdge1 - yEdge0);
        if (h < 0.05f) {
            return;
        }
        if (!roundOuter || radius <= 0f) {
            Render2D.fillRect(graphics, bx, top, bw, h, color);
        } else if (positive) {
            Render2D.fillRoundedRect(graphics, bx, top, bw, h, radius, radius, 0f, 0f, color);
        } else {
            Render2D.fillRoundedRect(graphics, bx, top, bw, h, 0f, 0f, radius, radius, color);
        }
    }

    /** One horizontal bar/segment between two pixel columns; only the outer end is rounded. */
    private static void hBar(GuiGraphics graphics, float by, float bh, float xEdge0, float xEdge1,
                             float radius, boolean roundOuter, boolean positive, int color) {
        float left = Math.min(xEdge0, xEdge1);
        float w = Math.abs(xEdge1 - xEdge0);
        if (w < 0.05f) {
            return;
        }
        if (!roundOuter || radius <= 0f) {
            Render2D.fillRect(graphics, left, by, w, bh, color);
        } else if (positive) {
            Render2D.fillRoundedRect(graphics, left, by, w, bh, 0f, radius, radius, 0f, color);
        } else {
            Render2D.fillRoundedRect(graphics, left, by, w, bh, radius, 0f, 0f, radius, color);
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** The current target value for a (series, category) cell. */
    private float targetValue(int s, int c) {
        if (!liveBars.isEmpty()) {
            return liveValues[c];
        }
        float[] values = seriesList.get(s).values;
        return c < values.length ? values[c] : 0f;
    }

    private void padCategories(int needed) {
        while (categories.size() < needed) {
            categories.add("#" + (categories.size() + 1));
        }
    }

    private int maxSeriesLength() {
        int max = 0;
        for (Series series : seriesList) {
            max = Math.max(max, series.values.length);
        }
        return max;
    }

    /** Compact value text: integers stay integers, small values keep a decimal or two. */
    private static String formatCompact(float value) {
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
