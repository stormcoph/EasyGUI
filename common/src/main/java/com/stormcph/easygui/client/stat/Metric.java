package com.stormcph.easygui.client.stat;

import com.mojang.logging.LogUtils;
import dev.architectury.event.events.client.ClientTickEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleSupplier;

/**
 * A live statistic with history — the friendly facade over {@link TimeSeries}.
 * Every metric owns its series ({@link #series()}); hand that to a chart widget and
 * the chart stays current for free.
 *
 * <p>Two flavors, created through static factories:</p>
 * <ul>
 *   <li>{@link #counter()} — an event counter. Call {@link #add(double)} whenever the
 *       event happens; each client tick the accumulated count is converted into an
 *       events-per-second rate (smoothed with a short EMA, ~0.25&nbsp;s time constant)
 *       and one rate sample is pushed into the series. "Blocks per second" is exactly
 *       this: {@code bps.add(1)} on every block break, then chart {@code bps.series()}
 *       or read {@link #perSecond()}.</li>
 *   <li>{@link #gauge(DoubleSupplier, int)} — polls the supplier N times per second
 *       and pushes whatever it returns; {@link #value()} is the latest sample.</li>
 * </ul>
 *
 * <p>All metrics share one client-tick driver, registered lazily on the first metric
 * creation (the same pattern as {@code EasyConfig.bootstrap()}). A metric samples until
 * {@link #dispose()} (or {@link #remove(Metric)}) — create it once and keep the
 * reference, never per frame. Client thread only.</p>
 */
@Environment(EnvType.CLIENT)
public final class Metric {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** EMA time constant for counter rates, in seconds (~5 ticks to settle). */
    private static final double RATE_TIME_CONSTANT = 0.25;

    private static final CopyOnWriteArrayList<Metric> LIVE = new CopyOnWriteArrayList<>();
    private static boolean tickerRegistered;

    private enum Kind {
        COUNTER,
        GAUGE
    }

    private final Kind kind;
    private final TimeSeries series = new TimeSeries();

    // Gauge state
    private final DoubleSupplier source;
    private final int tickInterval;
    private int tickCounter;

    // Counter state
    private double accumulated;
    private double rate;
    private long lastTickNanos = -1L;

    // Optional display smoothing
    private float emaAlpha;
    private double smoothed;
    private boolean smoothedInit;

    private Metric(Kind kind, DoubleSupplier source, int tickInterval) {
        this.kind = kind;
        this.source = source;
        this.tickInterval = tickInterval;
        ensureTicker();
        LIVE.add(this);
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /**
     * An event counter: feed it with {@link #add(double)}, read an events-per-second
     * rate from {@link #perSecond()} or the series (one rate sample per client tick).
     */
    public static Metric counter() {
        return new Metric(Kind.COUNTER, null, 1);
    }

    /**
     * A polled value: {@code source} is sampled {@code samplesPerSecond} times per
     * second (clamped to 1..20 — client ticks are the clock) and pushed into the series.
     */
    public static Metric gauge(DoubleSupplier source, int samplesPerSecond) {
        Objects.requireNonNull(source, "source");
        int sps = Mth.clamp(samplesPerSecond, 1, 20);
        return new Metric(Kind.GAUGE, source, Math.max(1, Math.round(20f / sps)));
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** The backing series (owned by this metric) — pass it to chart widgets. */
    public TimeSeries series() {
        return series;
    }

    /**
     * Counters: the current smoothed events-per-second rate.
     * Gauges: the latest polled sample.
     */
    public double value() {
        return kind == Kind.COUNTER ? rate : series.latest();
    }

    /** The current smoothed events-per-second rate. Counter metrics only. */
    public double perSecond() {
        if (kind != Kind.COUNTER) {
            throw new IllegalStateException("perSecond() is only valid on counter metrics");
        }
        return rate;
    }

    /**
     * {@link #value()} run through the extra display EMA enabled by
     * {@link #smooth(float)} (identical to {@code value()} when smoothing is off).
     */
    public double smoothedValue() {
        return emaAlpha > 0f && smoothedInit ? smoothed : value();
    }

    // ------------------------------------------------------------------
    // Writing / configuration
    // ------------------------------------------------------------------

    /** Records {@code n} events. Counter metrics only — call this from your event hook. */
    public void add(double n) {
        if (kind != Kind.COUNTER) {
            throw new IllegalStateException("add() is only valid on counter metrics");
        }
        accumulated += n;
    }

    /**
     * Enables additional display smoothing: each new sample is blended into
     * {@link #smoothedValue()} with the given EMA alpha (0..1; higher = snappier,
     * {@code 0} disables). The series itself always keeps the raw samples.
     */
    public Metric smooth(float emaAlpha) {
        this.emaAlpha = Mth.clamp(emaAlpha, 0f, 1f);
        return this;
    }

    /** Stops sampling and unregisters this metric from the shared ticker. */
    public void dispose() {
        LIVE.remove(this);
    }

    /** Static alias for {@link #dispose()}; null-safe. */
    public static void remove(Metric metric) {
        if (metric != null) {
            metric.dispose();
        }
    }

    // ------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------

    /** Registers the single shared client-tick driver. Lazy, idempotent. */
    private static synchronized void ensureTicker() {
        if (tickerRegistered) {
            return;
        }
        tickerRegistered = true;
        ClientTickEvent.CLIENT_POST.register(minecraft -> tickAll());
    }

    private static void tickAll() {
        for (Metric metric : LIVE) {
            try {
                metric.tick();
            } catch (Throwable t) {
                LOGGER.error("EasyGUI: metric tick threw; removing the metric", t);
                metric.dispose();
            }
        }
    }

    private void tick() {
        if (kind == Kind.GAUGE) {
            if (++tickCounter >= tickInterval) {
                tickCounter = 0;
                pushSample((float) source.getAsDouble());
            }
            return;
        }
        // Counter: turn the events accumulated since the last tick into a rate.
        long now = System.nanoTime();
        if (lastTickNanos < 0L) {
            lastTickNanos = now;
            return;
        }
        double dt = (now - lastTickNanos) / 1.0E9;
        lastTickNanos = now;
        if (dt <= 0.0) {
            return;
        }
        double instantaneous = accumulated / dt;
        accumulated = 0.0;
        double blend = 1.0 - Math.exp(-dt / RATE_TIME_CONSTANT);
        rate += (instantaneous - rate) * blend;
        pushSample((float) rate);
    }

    private void pushSample(float value) {
        series.push(value);
        if (emaAlpha > 0f) {
            if (smoothedInit) {
                smoothed += (value - smoothed) * emaAlpha;
            } else {
                smoothed = value;
                smoothedInit = true;
            }
        }
    }
}
