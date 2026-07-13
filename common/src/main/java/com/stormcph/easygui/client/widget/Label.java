package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.font.Fonts;
import com.stormcph.easygui.client.font.StyledText;
import com.stormcph.easygui.client.font.TextStyle;
import com.stormcph.easygui.client.font.TrueTypeFont;
import com.stormcph.easygui.client.render.Text2D;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Supplier;

/**
 * A text label. Supports static text or a {@link Supplier} for live values,
 * muted styling, alignment, and pose-based scaling for headings.
 *
 * <p>For decorative typography (gradients, outlines, soft shadows, underline, tracking) give
 * it a {@link TextStyle} via {@link #setStyle}; for a line mixing several styles inline, a
 * {@link StyledText} via {@link #setStyledText}. Both render through a {@link TrueTypeFont}
 * (the active UI font, or the bundled Inter as a fallback).</p>
 */
@Environment(EnvType.CLIENT)
public class Label extends Widget {
    public enum Align {LEFT, CENTER, RIGHT}

    private final Supplier<String> text;
    private int color;
    private boolean hasCustomColor;
    private boolean muted;
    private Align align = Align.LEFT;
    private float scale = 1f;
    private TextStyle style;
    private StyledText styledText;

    /** An empty label — pair with {@link #setStyledText} for inline mixed-style content. */
    public Label() {
        this("");
    }

    public Label(String text) {
        this(() -> text);
    }

    public Label(Supplier<String> text) {
        this.text = text;
        this.height = Text2D.lineHeight();
    }

    public Label setColor(int color) {
        this.color = color;
        this.hasCustomColor = true;
        return this;
    }

    public Label setMuted(boolean muted) {
        this.muted = muted;
        return this;
    }

    public Label setAlign(Align align) {
        this.align = align;
        return this;
    }

    public Label setScale(float scale) {
        this.scale = scale;
        return this;
    }

    /**
     * Renders the label's text with a decorative {@link TextStyle} (gradient, outline, soft
     * shadow, underline, tracking…) through a {@link TrueTypeFont}. The effective font size is
     * the UI font size multiplied by {@link #setScale(float) scale}, so headings still scale.
     */
    public Label setStyle(TextStyle style) {
        this.style = style;
        return this;
    }

    /** Renders an inline mix of styles ({@link StyledText}); overrides the plain text/style. */
    public Label setStyledText(StyledText styledText) {
        this.styledText = styledText;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        if (styledText != null) {
            renderStyledText(graphics);
            return;
        }
        String value = text.get();
        if (value == null || value.isEmpty()) {
            return;
        }
        if (style != null) {
            renderStyled(graphics, value);
            return;
        }
        int c = hasCustomColor ? color : (muted ? theme().textMuted : theme().text);
        float drawY = y + Math.max(0, (height - Text2D.lineHeight() * scale) / 2f);

        graphics.pose().pushPose();
        if (scale != 1f) {
            graphics.pose().translate(x, drawY, 0);
            graphics.pose().scale(scale, scale, 1f);
            float scaledWidth = width / scale;
            switch (align) {
                case LEFT -> Text2D.draw(graphics, value, 0, 0, c);
                case CENTER -> Text2D.drawCentered(graphics, value, scaledWidth / 2f, 0, c);
                case RIGHT -> Text2D.drawRightAligned(graphics, value, scaledWidth, 0, c);
            }
        } else {
            switch (align) {
                case LEFT -> Text2D.draw(graphics, value, x, drawY, c);
                case CENTER -> Text2D.drawCentered(graphics, value, x + width / 2f, drawY, c);
                case RIGHT -> Text2D.drawRightAligned(graphics, value, x + width, drawY, c);
            }
        }
        graphics.pose().popPose();
    }

    /** Draws {@code value} with the decorative {@link TextStyle} through a TrueType font. */
    private void renderStyled(GuiGraphics graphics, String value) {
        TrueTypeFont font = Text2D.getUiFont();
        if (font == null) {
            font = Fonts.inter();
        }
        if (font == null) {
            // No TrueType font available; fall back to a plain draw in the fill color.
            int c = hasCustomColor ? color : style.getColor();
            Text2D.draw(graphics, value, x, y, c);
            return;
        }
        float size = Text2D.getUiFontSize() * scale;
        float textWidth = font.width(value, size, style);
        float drawX = switch (align) {
            case LEFT -> x;
            case CENTER -> x + (width - textWidth) / 2f;
            case RIGHT -> x + width - textWidth;
        };
        float drawY = y + Math.max(0, (height - size) / 2f);
        font.draw(graphics, value, drawX, drawY, size, style);
    }

    /** Draws the inline {@link StyledText}, aligned within the label's bounds. */
    private void renderStyledText(GuiGraphics graphics) {
        float textWidth = styledText.width();
        float drawX = switch (align) {
            case LEFT -> x;
            case CENTER -> x + (width - textWidth) / 2f;
            case RIGHT -> x + width - textWidth;
        };
        float drawY = y + Math.max(0, (height - styledText.height()) / 2f);
        styledText.draw(graphics, drawX, drawY);
    }
}
