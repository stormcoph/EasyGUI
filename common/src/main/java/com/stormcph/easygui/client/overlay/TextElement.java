package com.stormcph.easygui.client.overlay;

import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * A single-line HUD text element driven by a template with {@code {placeholder}}
 * tokens, e.g. {@code "FPS {fps} · {x} {y} {z}"} — tokens resolve through the global
 * {@link Placeholders} registry every frame, while the template itself is parsed only
 * once (in {@link #setTemplate(String)}) into literal/token segments. Unregistered
 * tokens render literally ({@code {typo}}) so mistakes stay visible.
 *
 * <p>Four {@link ColorMode color modes}: {@link ColorMode#STATIC} draws everything in
 * one color ({@link #setColor(int)}, default theme text); {@link ColorMode#TWO_TONE}
 * mutes the literal segments and brightens the placeholder values;
 * {@link ColorMode#GRADIENT} blends {@link #setGradient(int, int)} across the rendered
 * string; {@link ColorMode#RAINBOW_WAVE} runs a per-character hue wave whose phase
 * comes from one shared static clock, so every rainbow element on screen pulses in
 * sync. The gradient and rainbow modes draw per character with width stepping.</p>
 *
 * <p>Respects {@link HudStyle#isTextShadow()}; size comes from the rendered text's
 * metrics, so the styled background plate hugs the live string.</p>
 */
@Environment(EnvType.CLIENT)
public class TextElement extends HudOverlay {
    /** One full hue cycle of the shared rainbow clock, in milliseconds. */
    private static final long RAINBOW_PERIOD_MS = 4000L;
    /** Horizontal distance over which the rainbow hue wraps once, in GUI pixels. */
    private static final float RAINBOW_WAVE_LENGTH = 140f;

    /** How the rendered string is colored. */
    public enum ColorMode {
        /** Everything in one color ({@link #setColor(int)}, default theme text). */
        STATIC,
        /** Literal segments muted, placeholder values bright — easy-to-scan stat lines. */
        TWO_TONE,
        /** {@link #setGradient(int, int)} blended left-to-right across the string. */
        GRADIENT,
        /** Per-character hue wave, phase-synced across all rainbow elements. */
        RAINBOW_WAVE
    }

    /** A parsed template piece: either a literal run or a {@code {token}} key. */
    private record Segment(String literal, String key) {
        static Segment literal(String text) {
            return new Segment(text, null);
        }

        static Segment token(String key) {
            return new Segment(null, key);
        }
    }

    @FunctionalInterface
    private interface CharColor {
        int color(int index, float xCursor, float charWidth);
    }

    private final List<Segment> segments = new ArrayList<>();
    private String template = "";
    private String[] parts = new String[0];
    private String rendered = "";
    private long lastRefreshMillis = -1L;

    private ColorMode colorMode = ColorMode.STATIC;
    private int color;
    private boolean hasCustomColor;
    private int gradientFrom;
    private int gradientTo;
    private boolean hasGradient;

    public TextElement(String template) {
        setTemplate(template);
    }

    // ------------------------------------------------------------------
    // Template
    // ------------------------------------------------------------------

    /**
     * Replaces the template and re-parses it (once — rendering only re-resolves the
     * tokens). {@code {key}} marks a placeholder; an unmatched {@code '{'} is kept as
     * literal text.
     */
    public TextElement setTemplate(String template) {
        this.template = template != null ? template : "";
        segments.clear();
        String t = this.template;
        StringBuilder literal = new StringBuilder();
        int i = 0;
        int n = t.length();
        while (i < n) {
            if (t.charAt(i) == '{') {
                int close = t.indexOf('}', i + 1);
                if (close > i + 1) {
                    if (!literal.isEmpty()) {
                        segments.add(Segment.literal(literal.toString()));
                        literal.setLength(0);
                    }
                    segments.add(Segment.token(t.substring(i + 1, close)));
                    i = close + 1;
                    continue;
                }
            }
            literal.append(t.charAt(i));
            i++;
        }
        if (!literal.isEmpty()) {
            segments.add(Segment.literal(literal.toString()));
        }
        parts = new String[segments.size()];
        lastRefreshMillis = -1L; // force a re-resolve on the next frame
        return this;
    }

    public String getTemplate() {
        return template;
    }

    // ------------------------------------------------------------------
    // Colors
    // ------------------------------------------------------------------

    /** The color mode (default {@link ColorMode#STATIC}). */
    public TextElement setColorMode(ColorMode colorMode) {
        this.colorMode = colorMode != null ? colorMode : ColorMode.STATIC;
        return this;
    }

    public ColorMode getColorMode() {
        return colorMode;
    }

    /**
     * The {@link ColorMode#STATIC} color, also the bright tone of
     * {@link ColorMode#TWO_TONE}. Defaults to the live theme text color.
     */
    public TextElement setColor(int color) {
        this.color = color;
        this.hasCustomColor = true;
        return this;
    }

    /**
     * The {@link ColorMode#GRADIENT} endpoint colors (left → right across the rendered
     * string) — also switches to gradient mode. Defaults to the theme accent pair.
     */
    public TextElement setGradient(int colorA, int colorB) {
        this.gradientFrom = colorA;
        this.gradientTo = colorB;
        this.hasGradient = true;
        this.colorMode = ColorMode.GRADIENT;
        return this;
    }

    // ------------------------------------------------------------------
    // Resolution (once per frame)
    // ------------------------------------------------------------------

    private void refresh() {
        long now = Util.getMillis();
        if (now == lastRefreshMillis) {
            return;
        }
        lastRefreshMillis = now;
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            String value;
            if (segment.key() == null) {
                value = segment.literal();
            } else {
                value = Placeholders.resolve(segment.key());
                if (value == null) {
                    value = "{" + segment.key() + "}"; // unregistered: keep the raw token visible
                }
            }
            parts[i] = value;
            joined.append(value);
        }
        rendered = joined.toString();
    }

    @Override
    public float getWidth() {
        refresh();
        return Text2D.width(rendered);
    }

    @Override
    public float getHeight() {
        return Text2D.lineHeight();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, float x, float y, float partialTick) {
        refresh();
        if (rendered.isEmpty()) {
            return;
        }
        Theme theme = theme();
        boolean shadow = getStyle().isTextShadow();
        switch (colorMode) {
            case STATIC -> Text2D.draw(graphics, rendered, x, y,
                    hasCustomColor ? color : theme.text, shadow);
            case TWO_TONE -> {
                int bright = hasCustomColor ? color : theme.text;
                float cursor = 0f;
                for (int i = 0; i < segments.size(); i++) {
                    String part = parts[i];
                    if (part.isEmpty()) {
                        continue;
                    }
                    Text2D.draw(graphics, part, x + cursor, y,
                            segments.get(i).key() == null ? theme.textMuted : bright, shadow);
                    cursor += Text2D.width(part);
                }
            }
            case GRADIENT -> {
                int from = hasGradient ? gradientFrom : theme.accent;
                int to = hasGradient ? gradientTo : theme.accentHover;
                float total = Math.max(1f, Text2D.width(rendered));
                drawPerChar(graphics, x, y, shadow, (index, cursor, charWidth) ->
                        ColorUtil.lerp(from, to, (cursor + charWidth / 2f) / total));
            }
            case RAINBOW_WAVE -> {
                float phase = rainbowPhase();
                drawPerChar(graphics, x, y, shadow, (index, cursor, charWidth) ->
                        ColorUtil.hsv(phase - cursor / RAINBOW_WAVE_LENGTH, 0.8f, 1f));
            }
        }
    }

    /** Draws the rendered string one character at a time, stepping x by each glyph's width. */
    private void drawPerChar(GuiGraphics graphics, float x, float y, boolean shadow, CharColor colorAt) {
        float cursor = 0f;
        int index = 0;
        int i = 0;
        int n = rendered.length();
        while (i < n) {
            int codePoint = rendered.codePointAt(i);
            String ch = new String(Character.toChars(codePoint));
            float charWidth = Text2D.width(ch);
            Text2D.draw(graphics, ch, x + cursor, y, colorAt.color(index, cursor, charWidth), shadow);
            cursor += charWidth;
            index++;
            i += Character.charCount(codePoint);
        }
    }

    /**
     * The shared rainbow phase (0..1), derived from {@link Util#getMillis()} — static,
     * so every {@link ColorMode#RAINBOW_WAVE} element on screen pulses in sync.
     */
    private static float rainbowPhase() {
        return (Util.getMillis() % RAINBOW_PERIOD_MS) / (float) RAINBOW_PERIOD_MS;
    }
}
