package com.stormcph.easygui.client.chart;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.stat.TimeSeries;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Arrays;
import java.util.Locale;

/**
 * The shared value-axis engine behind the chart widgets ({@link Sparkline},
 * {@link LineChart}, …).
 *
 * <p>Feed it the visible data's min/max once per frame via {@link #update(float, float)}
 * and map values to pixels with {@link #toY} (or read the bounds back with
 * {@link #min()}/{@link #max()}). Target bounds are rounded outward to "nice"
 * 1-2-5×10ⁿ numbers, and the <em>displayed</em> bounds glide toward them on
 * {@link SmoothValue}s, so charts rescale smoothly instead of snapping when a spike
 * enters or leaves the window. {@link #computeTicks(float)} generates nice tick values
 * for gridlines and axis labels (about one tick per 24&nbsp;px of height). Fix the axis
 * with {@link #setRange(float, float)}, or force zero into view with
 * {@link #setIncludeZero(boolean)}.</p>
 *
 * <p>The nested {@link Sampler} is the matching data front-end: it windows and
 * downsamples a {@link TimeSeries} into reusable scratch arrays with min/max bucketing,
 * so charts stay allocation-free on steady frames and spikes survive downsampling.
 * Both are pure helpers — neither is a widget.</p>
 */
@Environment(EnvType.CLIENT)
public final class ChartScale {
    /** Aim for roughly one tick per this many pixels of axis height. */
    private static final float TICK_SPACING_PX = 24f;
    /** Safety cap on generated ticks. */
    private static final int MAX_TICKS = 64;

    private final SmoothValue displayMin = new SmoothValue(0f, 8f);
    private final SmoothValue displayMax = new SmoothValue(1f, 8f);
    private boolean initialized;
    private boolean fixed;
    private float fixedMin;
    private float fixedMax = 1f;
    private boolean includeZero;

    private float[] ticks = new float[16];
    private int tickCount;
    private float tickStep = 1f;

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** Fixes the axis to exactly {@code [min, max]} instead of auto-scaling to the data. */
    public ChartScale setRange(float min, float max) {
        this.fixed = true;
        this.fixedMin = Math.min(min, max);
        this.fixedMax = Math.max(min, max);
        return this;
    }

    /** Returns to auto-scaling after {@link #setRange(float, float)}. */
    public ChartScale setAutoRange() {
        this.fixed = false;
        return this;
    }

    /** When auto-scaling, always keeps zero inside the displayed range. */
    public ChartScale setIncludeZero(boolean includeZero) {
        this.includeZero = includeZero;
        return this;
    }

    /** Responsiveness of the animated rescale (see {@link SmoothValue}; default 8). */
    public ChartScale setRescaleSpeed(float speed) {
        displayMin.setSpeed(speed);
        displayMax.setSpeed(speed);
        return this;
    }

    // ------------------------------------------------------------------
    // Per-frame update and mapping
    // ------------------------------------------------------------------

    /**
     * Retargets the axis at the visible data's extremes. Call once per frame
     * <em>before</em> mapping values; skip the call while there is no data (the axis
     * then simply holds its last bounds). The very first update applies instantly so
     * a fresh chart doesn't glide in from nowhere; afterwards the bounds animate.
     */
    public void update(float dataMin, float dataMax) {
        float targetMin;
        float targetMax;
        if (fixed) {
            targetMin = fixedMin;
            targetMax = fixedMax;
        } else {
            float lo = Math.min(dataMin, dataMax);
            float hi = Math.max(dataMin, dataMax);
            if (includeZero) {
                lo = Math.min(lo, 0f);
                hi = Math.max(hi, 0f);
            }
            if (hi - lo < 1.0E-6f) {
                // Flat data: open up a small symmetric range so the line sits mid-chart
                float pad = Math.max(Math.abs(hi) * 0.1f, 1f);
                lo -= pad;
                hi += pad;
            }
            float step = niceStep((hi - lo) / 4f);
            targetMin = (float) Math.floor(lo / step) * step;
            targetMax = (float) Math.ceil(hi / step) * step;
            if (targetMax - targetMin < step) {
                targetMax = targetMin + step;
            }
        }
        if (!initialized) {
            displayMin.setInstant(targetMin);
            displayMax.setInstant(targetMax);
            initialized = true;
        } else {
            displayMin.setTarget(targetMin);
            displayMax.setTarget(targetMax);
        }
    }

    /** The currently displayed (animated) lower bound. */
    public float min() {
        return displayMin.get();
    }

    /** The currently displayed (animated) upper bound. */
    public float max() {
        return displayMax.get();
    }

    /**
     * Maps a value to {@code 0..1} within the displayed bounds (0 = min, 1 = max),
     * clamped — so while the axis is still gliding toward a fresh spike, the spike pins
     * to the chart edge instead of drawing outside the widget.
     */
    public float normalize(float value) {
        float lo = displayMin.get();
        float hi = displayMax.get();
        float range = hi - lo;
        if (range <= 1.0E-6f) {
            return 0.5f;
        }
        float t = (value - lo) / range;
        return t < 0f ? 0f : Math.min(t, 1f);
    }

    /** Maps a value to a pixel y inside {@code [top, top + height]} (top = displayed max). */
    public float toY(float value, float top, float height) {
        return top + (1f - normalize(value)) * height;
    }

    // ------------------------------------------------------------------
    // Ticks
    // ------------------------------------------------------------------

    /**
     * Generates nice tick values covering the displayed range, aiming for about one
     * tick per 24&nbsp;px of {@code pixelHeight}. Fills a reused internal array; read
     * the results with {@link #tickValue(int)} (and {@link #tickStep()} for label
     * precision). Returns the tick count.
     */
    public int computeTicks(float pixelHeight) {
        float lo = displayMin.get();
        float hi = displayMax.get();
        tickCount = 0;
        if (hi - lo <= 1.0E-6f || pixelHeight <= 0f) {
            return 0;
        }
        int target = Math.max(2, (int) (pixelHeight / TICK_SPACING_PX));
        tickStep = niceStep((hi - lo) / target);
        float first = (float) Math.ceil(lo / tickStep) * tickStep;
        for (int i = 0; tickCount < MAX_TICKS; i++) {
            float v = first + i * tickStep;
            if (v > hi + tickStep * 0.001f) {
                break;
            }
            if (tickCount == ticks.length) {
                ticks = Arrays.copyOf(ticks, ticks.length * 2);
            }
            ticks[tickCount++] = v;
        }
        return tickCount;
    }

    /** Tick count from the last {@link #computeTicks(float)}. */
    public int tickCount() {
        return tickCount;
    }

    /** A tick value from the last {@link #computeTicks(float)}. */
    public float tickValue(int index) {
        return ticks[index];
    }

    /** The tick spacing from the last {@link #computeTicks(float)}. */
    public float tickStep() {
        return tickStep;
    }

    // ------------------------------------------------------------------
    // Static helpers (shared by all chart widgets)
    // ------------------------------------------------------------------

    /** Rounds {@code rough} up/down to the nearest "nice" step: 1, 2 or 5 × 10ⁿ. */
    public static float niceStep(float rough) {
        if (!(rough > 0f)) {
            return 1f;
        }
        float pow10 = (float) Math.pow(10, Math.floor(Math.log10(rough)));
        float fraction = rough / pow10;
        float nice = fraction < 1.5f ? 1f : fraction < 3.5f ? 2f : fraction < 7.5f ? 5f : 10f;
        return nice * pow10;
    }

    /**
     * Formats an axis value with just enough decimals for the given tick step
     * (e.g. step 20 → {@code "140"}, step 0.5 → {@code "19.5"}).
     */
    public static String formatValue(float value, float step) {
        String format;
        if (step >= 1f) {
            format = "%.0f";
        } else if (step >= 0.1f) {
            format = "%.1f";
        } else if (step >= 0.01f) {
            format = "%.2f";
        } else {
            format = "%.3f";
        }
        return String.format(Locale.ROOT, format, value);
    }

    // ------------------------------------------------------------------
    // Sampler
    // ------------------------------------------------------------------

    /**
     * A reusable {@link TimeSeries} reader for chart rendering: copies the series into
     * internal scratch arrays (never mutating the series), applies an optional time
     * window and/or newest-N sample limit, then downsamples to at most {@code maxPoints}
     * with min/max bucketing — each bucket emits its minimum and maximum in time order,
     * so spikes survive no matter how dense the data is. Scratch arrays are grown on
     * demand and reused; steady-state frames allocate nothing.
     *
     * <p>Each chart owns one sampler per series (it carries per-series scratch state).
     * After {@link #sample}, read points via {@link #count()}, {@link #value(int)} and
     * {@link #timeNanos(int)}; {@link #dataMin()}/{@link #dataMax()} cover the whole
     * windowed data <em>before</em> downsampling — hand exactly those to
     * {@link ChartScale#update(float, float)}.</p>
     */
    public static final class Sampler {
        private long[] rawTimes = new long[0];
        private float[] rawValues = new float[0];
        private long[] outTimes = new long[0];
        private float[] outValues = new float[0];
        private int outCount;
        private float dataMin;
        private float dataMax;

        /** {@link #sample(TimeSeries, double, int, int, boolean)} with no sample limit, min/max bucketing on. */
        public int sample(TimeSeries series, double windowSeconds, int maxPoints) {
            return sample(series, windowSeconds, 0, maxPoints, true);
        }

        /**
         * Reads the series and prepares at most {@code maxPoints} render points.
         *
         * @param windowSeconds keep only samples younger than this ({@code <= 0}: keep all)
         * @param sampleLimit   keep only the newest N samples ({@code <= 0}: keep all);
         *                      applied before the time window
         * @param maxPoints     downsampling budget (charts use ~2 per pixel column)
         * @param minMax        {@code true}: each bucket emits min and max (lines/areas);
         *                      {@code false}: each bucket emits only its max (bars)
         * @return the number of output points (0 when the window is empty)
         */
        public int sample(TimeSeries series, double windowSeconds, int sampleLimit, int maxPoints, boolean minMax) {
            outCount = 0;
            dataMin = 0f;
            dataMax = 0f;
            int available = series.size();
            if (available == 0) {
                return 0;
            }
            maxPoints = Math.max(1, maxPoints);
            if (rawTimes.length < available) {
                int cap = Math.max(available, rawTimes.length * 2);
                rawTimes = new long[cap];
                rawValues = new float[cap];
            }
            int rawCount = series.copyInto(rawTimes, rawValues);
            int start = sampleLimit > 0 ? Math.max(0, rawCount - sampleLimit) : 0;
            if (windowSeconds > 0) {
                long cutoff = System.nanoTime() - (long) (windowSeconds * 1.0E9);
                while (start < rawCount && rawTimes[start] < cutoff) {
                    start++;
                }
            }
            int n = rawCount - start;
            if (n <= 0) {
                return 0;
            }

            float lo = Float.POSITIVE_INFINITY;
            float hi = Float.NEGATIVE_INFINITY;
            for (int i = start; i < rawCount; i++) {
                float v = rawValues[i];
                if (v < lo) {
                    lo = v;
                }
                if (v > hi) {
                    hi = v;
                }
            }
            dataMin = lo;
            dataMax = hi;

            int buckets = minMax ? Math.max(1, maxPoints / 2) : maxPoints;
            int needed = n <= maxPoints ? n : (minMax ? buckets * 2 : buckets);
            if (outTimes.length < needed) {
                int cap = Math.max(needed, outTimes.length * 2);
                outTimes = new long[cap];
                outValues = new float[cap];
            }
            if (n <= maxPoints) {
                System.arraycopy(rawTimes, start, outTimes, 0, n);
                System.arraycopy(rawValues, start, outValues, 0, n);
                outCount = n;
                return n;
            }

            for (int b = 0; b < buckets; b++) {
                int from = start + (int) ((long) n * b / buckets);
                int to = start + (int) ((long) n * (b + 1) / buckets);
                if (to <= from) {
                    to = from + 1;
                }
                int minIdx = from;
                int maxIdx = from;
                for (int i = from + 1; i < to; i++) {
                    if (rawValues[i] < rawValues[minIdx]) {
                        minIdx = i;
                    }
                    if (rawValues[i] > rawValues[maxIdx]) {
                        maxIdx = i;
                    }
                }
                if (minMax && minIdx != maxIdx) {
                    emit(Math.min(minIdx, maxIdx));
                    emit(Math.max(minIdx, maxIdx));
                } else {
                    emit(minMax ? minIdx : maxIdx);
                }
            }
            return outCount;
        }

        private void emit(int rawIndex) {
            outTimes[outCount] = rawTimes[rawIndex];
            outValues[outCount] = rawValues[rawIndex];
            outCount++;
        }

        /** Output point count from the last {@link #sample}. */
        public int count() {
            return outCount;
        }

        /** An output point's value (index {@code 0..count()-1}, oldest → newest). */
        public float value(int index) {
            return outValues[index];
        }

        /** An output point's {@link System#nanoTime()}-based timestamp. */
        public long timeNanos(int index) {
            return outTimes[index];
        }

        /** Minimum over the windowed data (pre-downsampling); {@code 0} when empty. */
        public float dataMin() {
            return dataMin;
        }

        /** Maximum over the windowed data (pre-downsampling); {@code 0} when empty. */
        public float dataMax() {
            return dataMax;
        }
    }
}
