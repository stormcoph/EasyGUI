package com.stormcph.easygui.client.chart;

import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Theme-coordinated colors for multi-series charts.
 *
 * <p>Series 0 gets the theme accent; each subsequent series rotates the accent's hue by
 * a fixed step around the color wheel while keeping its saturation and value, so any
 * number of series looks coordinated with zero configuration — and re-skins live when
 * the theme changes, like every other EasyGUI color.</p>
 */
@Environment(EnvType.CLIENT)
public final class SeriesColors {
    /** Hue rotation between consecutive series (fraction of the color wheel). */
    public static final float HUE_STEP = 0.13f;

    private SeriesColors() {
    }

    /** The auto-assigned color for series {@code index} (0-based) under {@code theme}. */
    public static int color(Theme theme, int index) {
        int base = theme.accent;
        if (index <= 0) {
            return base;
        }
        float[] hsv = ColorUtil.toHsv(base);
        int rotated = ColorUtil.hsv(hsv[0] + HUE_STEP * index, hsv[1], hsv[2]);
        return ColorUtil.withAlpha(rotated, ColorUtil.alpha(base));
    }
}
