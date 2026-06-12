package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.render.Text2D;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Supplier;

/**
 * A text label. Supports static text or a {@link Supplier} for live values,
 * muted styling, alignment, and pose-based scaling for headings.
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

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        String value = text.get();
        if (value == null || value.isEmpty()) {
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
}
