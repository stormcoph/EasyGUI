package com.stormcph.easygui.client.font;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * A single line of text assembled from styled runs, laid out left-to-right on a shared
 * baseline. Each run carries its own {@link TrueTypeFont}, size and {@link TextStyle}, so a
 * bold or gradient word can sit inside an otherwise plain sentence:
 *
 * <pre>{@code
 * TrueTypeFont f = Fonts.inter();
 * StyledText line = new StyledText()
 *         .append("A ", f, 12f, theme.text)
 *         .append("bold", f, 12f, TextStyle.of(theme.text).setBold(true))
 *         .append(" word.", f, 12f, theme.text);
 * line.draw(graphics, x, y);
 * }</pre>
 *
 * <p>Runs of differing size share one baseline (the block is as tall as its tallest run).
 * Gradients are resolved per run, not across the whole line. This is deliberately a single
 * line — paragraph wrapping of styled text is a separate, larger feature. All methods run on
 * the render thread and honor the {@link com.stormcph.easygui.client.render.Render2D} global
 * alpha fade.</p>
 */
@Environment(EnvType.CLIENT)
public final class StyledText {
    /** One styled fragment of a {@link StyledText}. */
    public static final class Run {
        final String text;
        final TrueTypeFont font;
        final float size;
        final TextStyle style;

        Run(String text, TrueTypeFont font, float size, TextStyle style) {
            this.text = text;
            this.font = font;
            this.size = size;
            this.style = style;
        }
    }

    private final List<Run> runs = new ArrayList<>();

    /** Appends a run drawn with {@code font} at {@code size} using {@code style}. */
    public StyledText append(String text, TrueTypeFont font, float size, TextStyle style) {
        runs.add(new Run(text == null ? "" : text, font, size, style == null ? new TextStyle() : style));
        return this;
    }

    /** Appends a plain, single-color run. */
    public StyledText append(String text, TrueTypeFont font, float size, int color) {
        return append(text, font, size, TextStyle.of(color));
    }

    /** Total advance width of the line in GUI units (tracking included). */
    public float width() {
        float w = 0f;
        for (Run r : runs) {
            if (r.font != null) {
                w += r.font.width(r.text, r.size, r.style);
            }
        }
        return w;
    }

    /** Height of the tallest run's line box, in GUI units — the block's height. */
    public float height() {
        float h = 0f;
        for (Run r : runs) {
            if (r.font != null) {
                h = Math.max(h, r.font.lineHeight(r.size));
            }
        }
        return h;
    }

    /**
     * Draws the runs left-to-right starting with the block's top-left at {@code (x, y)},
     * aligning every run to the shared baseline. Returns the end X position.
     */
    public float draw(GuiGraphics graphics, float x, float y) {
        if (runs.isEmpty()) {
            return x;
        }
        float ascent = 0f;
        for (Run r : runs) {
            if (r.font != null) {
                ascent = Math.max(ascent, r.font.ascent(r.size));
            }
        }
        float penX = x;
        for (Run r : runs) {
            if (r.font == null || r.text.isEmpty()) {
                continue;
            }
            float runY = y + ascent - r.font.ascent(r.size);
            penX = r.font.draw(graphics, r.text, penX, runY, r.size, r.style);
        }
        return penX;
    }
}
