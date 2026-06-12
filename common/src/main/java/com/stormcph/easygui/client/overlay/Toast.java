package com.stormcph.easygui.client.overlay;

import com.stormcph.easygui.client.render.Icon;
import com.stormcph.easygui.client.render.Icons;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * A single toast notification: a title, an optional body and icon, a display duration,
 * and a semantic {@link Variant} that picks the accent color and default icon. Build one
 * fluently and hand it to {@link Toasts#show(Toast)}:
 *
 * <pre>{@code
 * Toasts.show(Toast.success("Config saved")
 *         .withBody("Your changes were written to disk.")
 *         .withDuration(3));
 * }</pre>
 *
 * <p>Variants map to theme colors — {@link Variant#SUCCESS} → {@code theme.success},
 * {@link Variant#ERROR} → {@code theme.danger}, {@link Variant#INFO} →
 * {@code theme.accent} — plus a fixed warning amber for {@link Variant#WARNING} (the
 * theme has no warning slot). The color is resolved live each frame, so theme swaps
 * re-tint toasts that are already on screen.</p>
 *
 * <p>A toast is a one-shot description, not a handle: showing the same instance twice
 * produces two independent cards, and the countdown only starts once the toast actually
 * appears (queued overflow toasts wait with a full timer).</p>
 */
@Environment(EnvType.CLIENT)
public final class Toast {

    /** Semantic flavor — picks the accent strip color and the default icon. */
    public enum Variant {
        /** Positive/confirmation; {@code theme.success}, check icon. */
        SUCCESS,
        /** Failure/destructive; {@code theme.danger}, cross icon. */
        ERROR,
        /** Neutral information; {@code theme.accent}, info icon. */
        INFO,
        /** Caution; warning amber, triangle icon. */
        WARNING
    }

    /** Amber accent for {@link Variant#WARNING} — the theme palette has no warning slot. */
    private static final int WARNING_AMBER = 0xFFE5A33D;
    private static final double DEFAULT_DURATION_SECONDS = 4.0;
    private static final double MIN_DURATION_SECONDS = 0.25;

    private final Variant variant;
    private final String title;
    private String body;
    private Icon icon;
    private double durationSeconds = DEFAULT_DURATION_SECONDS;

    private Toast(Variant variant, String title) {
        this.variant = variant;
        this.title = title != null ? title : "";
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /** A green confirmation toast ({@code theme.success}, check icon). */
    public static Toast success(String title) {
        return new Toast(Variant.SUCCESS, title);
    }

    /** A red failure toast ({@code theme.danger}, cross icon). */
    public static Toast error(String title) {
        return new Toast(Variant.ERROR, title);
    }

    /** A neutral information toast ({@code theme.accent}, info icon). */
    public static Toast info(String title) {
        return new Toast(Variant.INFO, title);
    }

    /** An amber caution toast (warning triangle icon). */
    public static Toast warning(String title) {
        return new Toast(Variant.WARNING, title);
    }

    // ------------------------------------------------------------------
    // Fluent configuration
    // ------------------------------------------------------------------

    /** Secondary text under the title; word-wrapped to at most two lines, then ellipsized. */
    public Toast withBody(String body) {
        this.body = body;
        return this;
    }

    /** Replaces the variant's default icon. Pass {@code null} to restore the default. */
    public Toast withIcon(Icon icon) {
        this.icon = icon;
        return this;
    }

    /**
     * How long the toast stays on screen once shown, in seconds (default 4). The exit
     * animation plays after this elapses. Clamped to a small minimum so a toast is
     * never invisible.
     */
    public Toast withDuration(double seconds) {
        this.durationSeconds = Math.max(MIN_DURATION_SECONDS, seconds);
        return this;
    }

    // ------------------------------------------------------------------
    // Accessors (read by the toast overlay)
    // ------------------------------------------------------------------

    public Variant getVariant() {
        return variant;
    }

    public String getTitle() {
        return title;
    }

    /** The body text, or {@code null} when the toast has none. */
    public String getBody() {
        return body;
    }

    public double getDurationSeconds() {
        return durationSeconds;
    }

    /** The icon to draw: the custom one if set, otherwise the variant default. */
    public Icon icon() {
        if (icon != null) {
            return icon;
        }
        return switch (variant) {
            case SUCCESS -> Icons.CHECK;
            case ERROR -> Icons.CLOSE;
            case INFO -> Icons.INFO;
            case WARNING -> Icons.WARNING;
        };
    }

    /** The accent color for the strip, icon, and progress sliver, resolved live from {@code theme}. */
    public int accentColor(Theme theme) {
        return switch (variant) {
            case SUCCESS -> theme.success;
            case ERROR -> theme.danger;
            case INFO -> theme.accent;
            case WARNING -> WARNING_AMBER;
        };
    }
}
