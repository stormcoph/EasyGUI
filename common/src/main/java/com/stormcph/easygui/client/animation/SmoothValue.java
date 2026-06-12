package com.stormcph.easygui.client.animation;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * A frame-rate independent, exponentially smoothed value — the workhorse behind hover
 * effects, smooth scrolling, toggle knobs, fades, etc.
 *
 * <p>Set a target with {@link #setTarget(float)} and read {@link #get()} every frame;
 * the value glides toward the target with no per-frame bookkeeping required. Time is
 * tracked internally so the motion looks identical at 30 and 300 fps.</p>
 */
@Environment(EnvType.CLIENT)
public class SmoothValue {
    private float value;
    private float target;
    private float speed;
    private long lastNanos = -1L;

    /**
     * @param initial starting value
     * @param speed   responsiveness; roughly "how many times per second the remaining
     *                distance is cut by ~63%". 8–12 feels snappy for UI, 4–6 is gentle.
     */
    public SmoothValue(float initial, float speed) {
        this.value = initial;
        this.target = initial;
        this.speed = speed;
    }

    /** Current smoothed value (advances the simulation based on real elapsed time). */
    public float get() {
        advance();
        return value;
    }

    public float getTarget() {
        return target;
    }

    public SmoothValue setTarget(float target) {
        advance();
        this.target = target;
        return this;
    }

    /** Jumps directly to {@code value} with no animation. */
    public SmoothValue setInstant(float value) {
        this.value = value;
        this.target = value;
        this.lastNanos = -1L;
        return this;
    }

    public SmoothValue setSpeed(float speed) {
        this.speed = speed;
        return this;
    }

    /** True once the value is within {@code epsilon} of its target. */
    public boolean isSettled(float epsilon) {
        advance();
        return Math.abs(target - value) <= epsilon;
    }

    private void advance() {
        long now = System.nanoTime();
        if (lastNanos < 0) {
            lastNanos = now;
            return;
        }
        float dt = (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        if (dt <= 0) {
            return;
        }
        // Clamp huge gaps (window unfocused, lag spike) so values don't teleport oddly
        dt = Math.min(dt, 0.25f);
        float blend = 1f - (float) Math.exp(-speed * dt);
        value += (target - value) * blend;
        if (Math.abs(target - value) < 1.0E-5f) {
            value = target;
        }
    }
}
