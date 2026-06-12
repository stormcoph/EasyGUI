package com.stormcph.easygui.client.animation;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;

/**
 * A one-shot, duration-based animation with an easing curve.
 * For open/close transitions, ripples, reveals — anything with a defined start and end.
 */
@Environment(EnvType.CLIENT)
public class Animation {
    private final int durationMs;
    private final Easing easing;
    private float from;
    private float to;
    private long startMillis = -1L;

    public Animation(int durationMs, Easing easing) {
        this(0f, 1f, durationMs, easing);
    }

    public Animation(float from, float to, int durationMs, Easing easing) {
        this.from = from;
        this.to = to;
        this.durationMs = Math.max(1, durationMs);
        this.easing = easing;
    }

    /** (Re)starts the animation from the beginning. */
    public Animation start() {
        startMillis = Util.getMillis();
        return this;
    }

    /** Restarts with new endpoints. */
    public Animation start(float from, float to) {
        this.from = from;
        this.to = to;
        return start();
    }

    public boolean isRunning() {
        return startMillis >= 0 && progress() < 1f;
    }

    public boolean isStarted() {
        return startMillis >= 0;
    }

    public boolean isFinished() {
        return startMillis >= 0 && progress() >= 1f;
    }

    public void stop() {
        startMillis = -1L;
    }

    /** Raw linear progress 0..1 (0 if never started). */
    public float progress() {
        if (startMillis < 0) {
            return 0f;
        }
        return Math.min(1f, (Util.getMillis() - startMillis) / (float) durationMs);
    }

    /** Eased, interpolated value between {@code from} and {@code to}. */
    public float value() {
        return from + (to - from) * easing.applyClamped(progress());
    }
}
