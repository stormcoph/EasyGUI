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

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
