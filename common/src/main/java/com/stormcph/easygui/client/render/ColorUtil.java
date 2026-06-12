package com.stormcph.easygui.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Helpers for packed ARGB colors (the format used everywhere in EasyGUI).
 */
@Environment(EnvType.CLIENT)
public final class ColorUtil {
    private ColorUtil() {
    }

    public static int argb(int alpha, int red, int green, int blue) {
        return (alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | (blue & 0xFF);
    }

    public static int alpha(int color) {
        return color >>> 24;
    }

    public static int red(int color) {
        return color >> 16 & 0xFF;
    }

    public static int green(int color) {
        return color >> 8 & 0xFF;
    }

    public static int blue(int color) {
        return color & 0xFF;
    }

    /** Replaces the alpha channel ({@code alpha} in 0..255). */
    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (clamp255(alpha) << 24);
    }

    /** Replaces the alpha channel ({@code alpha} in 0..1). */
    public static int withAlpha(int color, float alpha) {
        return withAlpha(color, (int) (alpha * 255f + 0.5f));
    }

    /** Multiplies the existing alpha channel by {@code factor} (0..1). */
    public static int multiplyAlpha(int color, float factor) {
        return withAlpha(color, (int) (alpha(color) * factor + 0.5f));
    }

    /** Per-channel linear interpolation between two ARGB colors. */
    public static int lerp(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (alpha(from) + (alpha(to) - alpha(from)) * t);
        int r = (int) (red(from) + (red(to) - red(from)) * t);
        int g = (int) (green(from) + (green(to) - green(from)) * t);
        int b = (int) (blue(from) + (blue(to) - blue(from)) * t);
        return argb(a, r, g, b);
    }

    /** Brightens (positive amount) or darkens (negative amount) a color; amount in -1..1. */
    public static int shift(int color, float amount) {
        int target = amount >= 0 ? 0xFFFFFFFF : 0xFF000000;
        int shifted = lerp(color, target, Math.abs(amount));
        return withAlpha(shifted, alpha(color));
    }

    /** Packs HSV components (each 0..1; hue wraps) into an opaque ARGB color. */
    public static int hsv(float hue, float saturation, float value) {
        hue = hue - (float) Math.floor(hue);
        float h6 = hue * 6f;
        int sector = (int) h6 % 6;
        float f = h6 - (int) h6;
        float p = value * (1f - saturation);
        float q = value * (1f - f * saturation);
        float t = value * (1f - (1f - f) * saturation);
        float r, g, b;
        switch (sector) {
            case 0 -> { r = value; g = t; b = p; }
            case 1 -> { r = q; g = value; b = p; }
            case 2 -> { r = p; g = value; b = t; }
            case 3 -> { r = p; g = q; b = value; }
            case 4 -> { r = t; g = p; b = value; }
            default -> { r = value; g = p; b = q; }
        }
        return argb(255, Math.round(r * 255f), Math.round(g * 255f), Math.round(b * 255f));
    }

    /** Extracts {hue, saturation, value} (each 0..1) from an ARGB color; alpha is ignored. */
    public static float[] toHsv(int color) {
        float r = red(color) / 255f;
        float g = green(color) / 255f;
        float b = blue(color) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float hue;
        if (delta == 0f) {
            hue = 0f;
        } else if (max == r) {
            hue = (((g - b) / delta) % 6f + 6f) % 6f / 6f;
        } else if (max == g) {
            hue = ((b - r) / delta + 2f) / 6f;
        } else {
            hue = ((r - g) / delta + 4f) / 6f;
        }
        float saturation = max == 0f ? 0f : delta / max;
        return new float[]{hue, saturation, max};
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
