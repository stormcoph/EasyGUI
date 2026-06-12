package com.stormcph.easygui.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Text drawing helpers with float positioning, alignment, truncation, and support for
 * the global alpha fade from {@link Render2D#pushAlpha(float)}.
 */
@Environment(EnvType.CLIENT)
public final class Text2D {
    private Text2D() {
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    public static int width(String text) {
        return font().width(text);
    }

    public static int lineHeight() {
        return font().lineHeight;
    }

    public static void draw(GuiGraphics graphics, String text, float x, float y, int color) {
        draw(graphics, text, x, y, color, false);
    }

    public static void draw(GuiGraphics graphics, String text, float x, float y, int color, boolean shadow) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int c = Render2D.applyGlobalAlpha(color);
        // Vanilla treats nearly-transparent text as fully opaque; skip instead.
        if (ColorUtil.alpha(c) < 4) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(Render2D.roundToPixel(x), Render2D.roundToPixel(y), 0);
        graphics.drawString(font(), text, 0, 0, c, shadow);
        graphics.pose().popPose();
    }

    /** Draws text horizontally centered on {@code centerX}. */
    public static void drawCentered(GuiGraphics graphics, String text, float centerX, float y, int color) {
        draw(graphics, text, centerX - width(text) / 2f, y, color);
    }

    /** Draws text right-aligned so it ends at {@code rightX}. */
    public static void drawRightAligned(GuiGraphics graphics, String text, float rightX, float y, int color) {
        draw(graphics, text, rightX - width(text), y, color);
    }

    /** Draws text vertically centered within a row of height {@code rowHeight} starting at {@code y}. */
    public static void drawVerticallyCentered(GuiGraphics graphics, String text, float x, float y,
                                              float rowHeight, int color) {
        draw(graphics, text, x, y + (rowHeight - lineHeight()) / 2f + 0.5f, color);
    }

    /** Truncates with an ellipsis if {@code text} is wider than {@code maxWidth}. */
    public static String truncate(String text, int maxWidth) {
        if (width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int budget = maxWidth - width(ellipsis);
        if (budget <= 0) {
            return ellipsis;
        }
        return font().plainSubstrByWidth(text, budget) + ellipsis;
    }
}
