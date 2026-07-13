package com.stormcph.easygui.client.font;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * A fluent, reusable description of how a run of text is decorated — the "CSS" of EasyGUI
 * typography. Pass one to {@link TrueTypeFont#draw(net.minecraft.client.gui.GuiGraphics,
 * String, float, float, float, TextStyle)} (or hand several to a {@link StyledText}) to get
 * modern display text: tight letter-spacing, gradient fills, soft/blurred shadows, outlined
 * or hollow lettering, underline/strikethrough and a cheap faux-bold.
 *
 * <p>Setters return {@code this}, so styles read like a builder:</p>
 * <pre>{@code
 * TextStyle headline = new TextStyle()
 *         .setGradient(0xFF8EC5FF, 0xFF6D4AFF) // vertical fill
 *         .setTracking(2f)                     // +2px between letters
 *         .setShadow(0xB0000000, 0f, 2f, 3f);  // soft drop shadow
 * font.draw(graphics, "DISPLAY", x, y, 28f, headline);
 * }</pre>
 *
 * <p>Everything is expressed in GUI units (the same space {@link TrueTypeFont#draw} uses) and
 * respects the global alpha fade from {@link com.stormcph.easygui.client.render.Render2D
 * #pushAlpha(float)}. Colors are packed ARGB. A {@code TextStyle} is mutable and cheap; keep
 * one around and reuse it, or {@link #copy()} it to branch off a variant.</p>
 */
@Environment(EnvType.CLIENT)
public final class TextStyle {
    /** Axis of a two-color text gradient. */
    public enum GradientDir {VERTICAL, HORIZONTAL}

    private int color = 0xFFFFFFFF;
    private boolean gradient;
    private int gradientStart = 0xFFFFFFFF;
    private int gradientEnd = 0xFFFFFFFF;
    private GradientDir gradientDir = GradientDir.VERTICAL;
    private float trackingPx;
    private float trackingEm;
    private boolean shadow;
    private int shadowColor = 0x80000000;
    private float shadowOffsetX = 1f;
    private float shadowOffsetY = 1f;
    private float shadowBlur;
    private boolean outline;
    private int outlineColor = 0xFF000000;
    private float outlineThickness = 1f;
    private boolean hollow;
    private boolean underline;
    private boolean strikethrough;
    private boolean decorationColorSet;
    private int decorationColor;
    private boolean bold;

    public TextStyle() {
    }

    /** Shorthand for {@code new TextStyle().setColor(color)}. */
    public static TextStyle of(int color) {
        return new TextStyle().setColor(color);
    }

    // ------------------------------------------------------------------
    // Fill
    // ------------------------------------------------------------------

    /** Solid fill color (packed ARGB). Clears any gradient. */
    public TextStyle setColor(int color) {
        this.color = color;
        this.gradient = false;
        return this;
    }

    /** Two-color vertical gradient across the string's bounds (top → bottom). */
    public TextStyle setGradient(int top, int bottom) {
        return setGradient(top, bottom, GradientDir.VERTICAL);
    }

    /** Two-color horizontal gradient across the string's bounds (left → right). */
    public TextStyle setGradientH(int left, int right) {
        return setGradient(left, right, GradientDir.HORIZONTAL);
    }

    /** Two-color gradient along {@code dir}, interpolated across the string's bounding box. */
    public TextStyle setGradient(int start, int end, GradientDir dir) {
        this.gradient = true;
        this.gradientStart = start;
        this.gradientEnd = end;
        this.gradientDir = dir;
        return this;
    }

    /**
     * Draws the fill twice, half a pixel apart, to fake a heavier weight — useful when only a
     * single (regular) font file is loaded and no real bold face is available.
     */
    public TextStyle setBold(boolean bold) {
        this.bold = bold;
        return this;
    }

    // ------------------------------------------------------------------
    // Letter-spacing (tracking)
    // ------------------------------------------------------------------

    /** Extra advance after every glyph, in GUI pixels (adds to any em tracking). */
    public TextStyle setTracking(float pixels) {
        this.trackingPx = pixels;
        return this;
    }

    /** Extra advance after every glyph, as a fraction of the font size (adds to pixel tracking). */
    public TextStyle setTrackingEm(float em) {
        this.trackingEm = em;
        return this;
    }

    /** Resolved letter-spacing in GUI units at {@code size}: {@code pixels + em * size}. */
    public float resolveTracking(float size) {
        return trackingPx + trackingEm * size;
    }

    // ------------------------------------------------------------------
    // Shadow
    // ------------------------------------------------------------------

    /** Hard drop shadow at {@code (offsetX, offsetY)} GUI pixels. */
    public TextStyle setShadow(int color, float offsetX, float offsetY) {
        return setShadow(color, offsetX, offsetY, 0f);
    }

    /**
     * Soft drop shadow. {@code blur} is a softness radius in GUI pixels — the text is drawn
     * several times at small offsets with reduced alpha (no shader involved), so {@code 0}
     * gives a crisp shadow and larger values a fuzzy glow.
     */
    public TextStyle setShadow(int color, float offsetX, float offsetY, float blur) {
        this.shadow = true;
        this.shadowColor = color;
        this.shadowOffsetX = offsetX;
        this.shadowOffsetY = offsetY;
        this.shadowBlur = blur;
        return this;
    }

    // ------------------------------------------------------------------
    // Outline
    // ------------------------------------------------------------------

    /**
     * Outlines each glyph by stroking its real vector contours in {@code color},
     * {@code thickness} GUI pixels wide — crisp at any thickness, and the basis for
     * {@link #setHollow(boolean) hollow} text.
     */
    public TextStyle setOutline(int color, float thickness) {
        this.outline = true;
        this.outlineColor = color;
        this.outlineThickness = thickness;
        return this;
    }

    /**
     * Removes the fill so only the outline shows ("hollow" text). Enable an outline first —
     * a hollow style with no outline draws nothing.
     */
    public TextStyle setHollow(boolean hollow) {
        this.hollow = hollow;
        return this;
    }

    // ------------------------------------------------------------------
    // Underline / strikethrough
    // ------------------------------------------------------------------

    /** Draws a line at the baseline, its thickness scaling with the font size. */
    public TextStyle setUnderline(boolean underline) {
        this.underline = underline;
        return this;
    }

    /** Draws a line through the middle of the text, its thickness scaling with the font size. */
    public TextStyle setStrikethrough(boolean strikethrough) {
        this.strikethrough = strikethrough;
        return this;
    }

    /** Overrides the underline/strikethrough color (defaults to the fill color). */
    public TextStyle setDecorationColor(int color) {
        this.decorationColorSet = true;
        this.decorationColor = color;
        return this;
    }

    /** A deep copy, for branching off a variant without disturbing the original. */
    public TextStyle copy() {
        TextStyle s = new TextStyle();
        s.color = color;
        s.gradient = gradient;
        s.gradientStart = gradientStart;
        s.gradientEnd = gradientEnd;
        s.gradientDir = gradientDir;
        s.trackingPx = trackingPx;
        s.trackingEm = trackingEm;
        s.shadow = shadow;
        s.shadowColor = shadowColor;
        s.shadowOffsetX = shadowOffsetX;
        s.shadowOffsetY = shadowOffsetY;
        s.shadowBlur = shadowBlur;
        s.outline = outline;
        s.outlineColor = outlineColor;
        s.outlineThickness = outlineThickness;
        s.hollow = hollow;
        s.underline = underline;
        s.strikethrough = strikethrough;
        s.decorationColorSet = decorationColorSet;
        s.decorationColor = decorationColor;
        s.bold = bold;
        return s;
    }

    // ------------------------------------------------------------------
    // Getters (mostly for the drawing pipeline)
    // ------------------------------------------------------------------

    public int getColor() {
        return color;
    }

    public boolean isGradient() {
        return gradient;
    }

    public int getGradientStart() {
        return gradientStart;
    }

    public int getGradientEnd() {
        return gradientEnd;
    }

    public GradientDir getGradientDir() {
        return gradientDir;
    }

    public boolean isBold() {
        return bold;
    }

    public boolean isShadow() {
        return shadow;
    }

    public int getShadowColor() {
        return shadowColor;
    }

    public float getShadowOffsetX() {
        return shadowOffsetX;
    }

    public float getShadowOffsetY() {
        return shadowOffsetY;
    }

    public float getShadowBlur() {
        return shadowBlur;
    }

    public boolean isOutline() {
        return outline;
    }

    public int getOutlineColor() {
        return outlineColor;
    }

    public float getOutlineThickness() {
        return outlineThickness;
    }

    public boolean isHollow() {
        return hollow;
    }

    public boolean isUnderline() {
        return underline;
    }

    public boolean isStrikethrough() {
        return strikethrough;
    }

    /** The color used for underline/strikethrough given this style's fill and outline. */
    public int resolveDecorationColor() {
        if (decorationColorSet) {
            return decorationColor;
        }
        if (hollow && outline) {
            return outlineColor;
        }
        return gradient ? gradientStart : color;
    }
}
