package com.stormcph.easygui.client.stat;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Arrays;

/**
 * A fixed-capacity ring buffer of timestamped {@code float} samples — the storage
 * primitive behind {@link Metric} and the chart widgets.
 *
 * <p>Samples are kept in parallel primitive arrays (no boxing) and always iterate
 * oldest → newest. The buffer is bounded by capacity (the oldest sample is overwritten
 * once full) and optionally by age ({@link #setMaxAge(double)}): stale samples are
 * dropped automatically on every push and query, so a 10-second window really only ever
 * contains the last 10 seconds. Window aggregates ({@link #min()}, {@link #mean()},
 * {@link #median()}, {@link #percentile(double)}…) are computed over whatever the
 * buffer currently holds; {@link #median()}/{@link #percentile(double)} sort into one
 * reusable scratch array, so there is no per-call allocation churn.</p>
 *
 * <p>Not thread-safe — use from the client thread only.</p>
 */
@Environment(EnvType.CLIENT)
public final class TimeSeries {
    /** Default capacity: 600 samples = 30 seconds of once-per-tick data. */
    public static final int DEFAULT_CAPACITY = 600;

    private final long[] times;
    private final float[] values;
    private final int capacity;
    /** Ring index of the oldest sample. */
    private int head;
    private int size;
    /** Age bound in nanoseconds; {@code <= 0} means unbounded. */
    private long maxAgeNanos;
    /** Reused by {@link #percentile(double)}; allocated once, on first use. */
    private float[] scratch;

    public TimeSeries() {
        this(DEFAULT_CAPACITY);
    }

    public TimeSeries(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.times = new long[capacity];
        this.values = new float[capacity];
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /**
     * Bounds the buffer by age as well as capacity: samples older than {@code seconds}
     * are dropped on every push and query. Pass {@code <= 0} to disable (the default).
     */
    public TimeSeries setMaxAge(double seconds) {
        this.maxAgeNanos = seconds <= 0 ? 0L : (long) (seconds * 1.0E9);
        return this;
    }

    /** The age bound in seconds, or 0 when only capacity-bounded. */
    public double getMaxAge() {
        return maxAgeNanos <= 0 ? 0.0 : maxAgeNanos / 1.0E9;
    }

    public int capacity() {
        return capacity;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Appends a sample timestamped with {@link System#nanoTime()}. */
    public TimeSeries push(float value) {
        return push(System.nanoTime(), value);
    }

    /**
     * Appends a sample with an explicit {@link System#nanoTime()}-based timestamp.
     * Timestamps are expected to be monotonically non-decreasing; the oldest sample is
     * overwritten when the buffer is full.
     */
    public TimeSeries push(long nanoTime, float value) {
        prune(nanoTime);
        int index = (head + size) % capacity;
        times[index] = nanoTime;
        values[index] = value;
        if (size < capacity) {
            size++;
        } else {
            head = (head + 1) % capacity;
        }
        return this;
    }

    public void clear() {
        head = 0;
        size = 0;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    public int size() {
        pruneNow();
        return size;
    }

    public boolean isEmpty() {
        pruneNow();
        return size == 0;
    }

    /** The newest sample's value, or {@code 0} when empty. */
    public float latest() {
        pruneNow();
        return size == 0 ? 0f : values[(head + size - 1) % capacity];
    }

    /** The newest sample's timestamp ({@link System#nanoTime()} base), or {@code 0} when empty. */
    public long latestTimeNanos() {
        pruneNow();
        return size == 0 ? 0L : times[(head + size - 1) % capacity];
    }

    /** The oldest sample's timestamp ({@link System#nanoTime()} base), or {@code 0} when empty. */
    public long oldestTimeNanos() {
        pruneNow();
        return size == 0 ? 0L : times[head];
    }

    /** Seconds between the oldest and newest sample; {@code 0} with fewer than two samples. */
    public double timeSpanSeconds() {
        pruneNow();
        if (size < 2) {
            return 0.0;
        }
        return (times[(head + size - 1) % capacity] - times[head]) / 1.0E9;
    }

    /** Receives one {@code (nanoTime, value)} pair per sample. See {@link #forEach}. */
    @FunctionalInterface
    public interface SampleConsumer {
        void accept(long nanoTime, float value);
    }

    /** Visits every sample in order, oldest → newest. */
    public void forEach(SampleConsumer consumer) {
        pruneNow();
        for (int i = 0; i < size; i++) {
            int index = (head + i) % capacity;
            consumer.accept(times[index], values[index]);
        }
    }

    /**
     * Copies values into {@code dest}, oldest → newest, and returns how many were
     * written. If {@code dest} is smaller than the series, the <em>newest</em>
     * {@code dest.length} samples are copied (still oldest → newest).
     */
    public int copyInto(float[] dest) {
        pruneNow();
        int count = Math.min(size, dest.length);
        int start = size - count;
        for (int i = 0; i < count; i++) {
            dest[i] = values[(head + start + i) % capacity];
        }
        return count;
    }

    /**
     * Copies timestamps and values into the two arrays, oldest → newest, and returns
     * how many pairs were written (bounded by the shorter array; when smaller than the
     * series, the newest samples win).
     */
    public int copyInto(long[] timesDest, float[] valuesDest) {
        pruneNow();
        int count = Math.min(size, Math.min(timesDest.length, valuesDest.length));
        int start = size - count;
        for (int i = 0; i < count; i++) {
            int index = (head + start + i) % capacity;
            timesDest[i] = times[index];
            valuesDest[i] = values[index];
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Window aggregates (over current contents; all return 0 when empty)
    // ------------------------------------------------------------------

    public float min() {
        pruneNow();
        if (size == 0) {
            return 0f;
        }
        float result = Float.POSITIVE_INFINITY;
        for (int i = 0; i < size; i++) {
            result = Math.min(result, values[(head + i) % capacity]);
        }
        return result;
    }

    public float max() {
        pruneNow();
        if (size == 0) {
            return 0f;
        }
        float result = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < size; i++) {
            result = Math.max(result, values[(head + i) % capacity]);
        }
        return result;
    }

    public double sum() {
        pruneNow();
        return sumInternal();
    }

    public double mean() {
        pruneNow();
        return size == 0 ? 0.0 : sumInternal() / size;
    }

    /** Population standard deviation (divides by n); {@code 0} with fewer than two samples. */
    public double stdDev() {
        pruneNow();
        if (size < 2) {
            return 0.0;
        }
        double mean = sumInternal() / size;
        double squares = 0.0;
        for (int i = 0; i < size; i++) {
            double d = values[(head + i) % capacity] - mean;
            squares += d * d;
        }
        return Math.sqrt(squares / size);
    }

    public float median() {
        return percentile(0.5);
    }

    /**
     * The {@code p}-quantile of the current window, {@code p} in {@code 0..1}
     * (e.g. {@code 0.99} for p99 frame spikes), with linear interpolation between
     * ranks. Sorts a reusable scratch array — O(n&nbsp;log&nbsp;n), no allocation
     * after the first call.
     */
    public float percentile(double p) {
        pruneNow();
        if (size == 0) {
            return 0f;
        }
        if (scratch == null) {
            scratch = new float[capacity];
        }
        for (int i = 0; i < size; i++) {
            scratch[i] = values[(head + i) % capacity];
        }
        Arrays.sort(scratch, 0, size);
        double clamped = Math.max(0.0, Math.min(1.0, p));
        double position = clamped * (size - 1);
        int lower = (int) position;
        if (lower >= size - 1) {
            return scratch[size - 1];
        }
        float fraction = (float) (position - lower);
        return scratch[lower] + (scratch[lower + 1] - scratch[lower]) * fraction;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void pruneNow() {
        if (maxAgeNanos > 0) {
            prune(System.nanoTime());
        }
    }

    private void prune(long now) {
        if (maxAgeNanos <= 0) {
            return;
        }
        while (size > 0 && now - times[head] > maxAgeNanos) {
            head = (head + 1) % capacity;
            size--;
        }
    }

    private double sumInternal() {
        double total = 0.0;
        for (int i = 0; i < size; i++) {
            total += values[(head + i) % capacity];
        }
        return total;
    }
}
