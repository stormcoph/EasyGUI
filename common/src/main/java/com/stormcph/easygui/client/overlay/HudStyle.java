package com.stormcph.easygui.client.overlay;

import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Mth;

/**
 * Uniform per-element styling for {@link HudOverlay}s: scale, opacity, content padding,
 * and an optional background plate (solid or frosted glass) with hairline outline and
 * drop shadow.
 *
 * <p>Every overlay owns a mutable style ({@link HudOverlay#getStyle()}). The default is
 * fully neutral — scale 1, full opacity, no padding, no background — so an untouched
 * overlay renders exactly as it would without a style. All setters chain and take effect
 * immediately, which lets the HUD editor bind widgets directly to a live style instance;
 * {@link #copy()} and {@link #copyFrom(HudStyle)} support style profiles.</p>
 */
@Environment(EnvType.CLIENT)
public class HudStyle {

    /** How the plate behind the overlay content is filled. */
    public enum Background {
        /** No plate; the content renders directly over the world. */
        NONE,
        /** Rounded rectangle filled with {@link #getBackgroundColor()}. */
        SOLID,
        /**
         * Real gaussian blur of everything behind the overlay, tinted with
         * {@link #getBackgroundColor()} — the frosted-glass look. Falls back to a solid
         * fill when the blur shaders are unavailable.
         */
        FROSTED
    }

    private float scale = 1f;
    private float opacity = 1f;
    private float padding = 0f;
    private Background background = Background.NONE;
    private int backgroundColor = ColorUtil.withAlpha(Theme.getDefault().surface, 0.75f);
    private float radius = 6f;
    private boolean outline;
    private boolean shadow;
    private boolean textShadow;

    /** Uniform render scale around the overlay's anchored origin (clamped to 0.5–3, default 1). */
    public HudStyle setScale(float scale) {
        this.scale = Mth.clamp(scale, 0.5f, 3f);
        return this;
    }

    public float getScale() {
        return scale;
    }

    /** Overall element opacity, 0–1 (multiplied with the show/hide fade; default 1). */
    public HudStyle setOpacity(float opacity) {
        this.opacity = Mth.clamp(opacity, 0f, 1f);
        return this;
    }

    public float getOpacity() {
        return opacity;
    }

    /** Space between the background plate's edge and the overlay content, in GUI pixels. */
    public HudStyle setPadding(float padding) {
        this.padding = Math.max(0f, padding);
        return this;
    }

    public float getPadding() {
        return padding;
    }

    /** The background plate mode (default {@link Background#NONE}). */
    public HudStyle setBackground(Background background) {
        this.background = background != null ? background : Background.NONE;
        return this;
    }

    public Background getBackground() {
        return background;
    }

    /**
     * Fill color of a {@link Background#SOLID} plate, or the tint laid over a
     * {@link Background#FROSTED} blur (its alpha is how strongly the tint covers the
     * glass). Defaults to a translucent theme-surface look.
     */
    public HudStyle setBackgroundColor(int color) {
        this.backgroundColor = color;
        return this;
    }

    public int getBackgroundColor() {
        return backgroundColor;
    }

    /** Corner radius of the background plate (default 6). */
    public HudStyle setRadius(float radius) {
        this.radius = Math.max(0f, radius);
        return this;
    }

    public float getRadius() {
        return radius;
    }

    /** Hairline outline around the background plate, in the theme outline color. */
    public HudStyle setOutline(boolean outline) {
        this.outline = outline;
        return this;
    }

    public boolean isOutline() {
        return outline;
    }

    /** Soft drop shadow behind the background plate. */
    public HudStyle setShadow(boolean shadow) {
        this.shadow = shadow;
        return this;
    }

    public boolean isShadow() {
        return shadow;
    }

    /**
     * Whether overlay text should render with a drop shadow. The core render path does
     * not enforce this — text-drawing overlays read it and pass it to
     * {@code Text2D.draw(..., shadow)} themselves.
     */
    public HudStyle setTextShadow(boolean textShadow) {
        this.textShadow = textShadow;
        return this;
    }

    public boolean isTextShadow() {
        return textShadow;
    }

    /** An independent copy of this style (for profiles and presets). */
    public HudStyle copy() {
        return new HudStyle().copyFrom(this);
    }

    /**
     * Copies every field from {@code source} into this style in place — applies a profile
     * without breaking references held by overlays or bound editor widgets.
     */
    public HudStyle copyFrom(HudStyle source) {
        this.scale = source.scale;
        this.opacity = source.opacity;
        this.padding = source.padding;
        this.background = source.background;
        this.backgroundColor = source.backgroundColor;
        this.radius = source.radius;
        this.outline = source.outline;
        this.shadow = source.shadow;
        this.textShadow = source.textShadow;
        return this;
    }
}
