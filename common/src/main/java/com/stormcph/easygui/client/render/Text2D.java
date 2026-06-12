package com.stormcph.easygui.client.render;

import com.stormcph.easygui.client.font.TrueTypeFont;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Text drawing helpers with float positioning, alignment, truncation, and support for
 * the global alpha fade from {@link Render2D#pushAlpha(float)}.
 *
 * <p>By default text renders with the vanilla font. Install a {@link TrueTypeFont} with
 * {@link #setUiFont} and <em>every</em> EasyGUI widget (they all draw through this class)
 * switches to it instantly — measurement included.</p>
 */
@Environment(EnvType.CLIENT)
public final class Text2D {
    private static TrueTypeFont uiFont;
    private static float uiFontSize = 9f;

    private Text2D() {
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    // ------------------------------------------------------------------
    // Custom UI font
    // ------------------------------------------------------------------

    /**
     * Routes all EasyGUI text through {@code font} at {@code size} (in GUI units; vanilla
     * text is 9 tall, so sizes near 9 keep widget layouts intact). Pass {@code null} to
     * restore the vanilla font.
     */
    public static void setUiFont(TrueTypeFont font, float size) {
        uiFont = font;
        uiFontSize = size;
    }

    /** Restores the vanilla font. */
    public static void clearUiFont() {
        uiFont = null;
    }

    /** The active custom UI font, or {@code null} when vanilla text is used. */
    public static TrueTypeFont getUiFont() {
        return uiFont;
    }

    public static float getUiFontSize() {
        return uiFontSize;
    }

    // ------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------

    public static int width(String text) {
        if (uiFont != null) {
            return Mth.ceil(uiFont.width(text, uiFontSize));
        }
        return font().width(text);
    }

    /**
     * Sub-pixel text width. {@link #width} ceils per call, which accumulates visible
     * letter-spacing when stepping through a string character by character (gradient or
     * rainbow text with a TTF font) — use this for cursor stepping instead.
     */
    public static float exactWidth(String text) {
        if (uiFont != null) {
            return uiFont.width(text, uiFontSize);
        }
        return font().width(text);
    }

    public static int lineHeight() {
        if (uiFont != null) {
            return Math.round(uiFontSize);
        }
        return font().lineHeight;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    public static void draw(GuiGraphics graphics, String text, float x, float y, int color) {
        draw(graphics, text, x, y, color, false);
    }

    public static void draw(GuiGraphics graphics, String text, float x, float y, int color, boolean shadow) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (uiFont != null) {
            uiFont.draw(graphics, text, x, y, uiFontSize, color, shadow);
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
        draw(graphics, text, centerX - rawWidth(text) / 2f, y, color);
    }

    /** Draws text right-aligned so it ends at {@code rightX}. */
    public static void drawRightAligned(GuiGraphics graphics, String text, float rightX, float y, int color) {
        draw(graphics, text, rightX - rawWidth(text), y, color);
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
        if (uiFont != null) {
            return uiFont.trimToWidth(text, budget, uiFontSize) + ellipsis;
        }
        return font().plainSubstrByWidth(text, budget) + ellipsis;
    }

    /** Sub-pixel width used for alignment (avoids the cumulative rounding of {@link #width}). */
    private static float rawWidth(String text) {
        if (uiFont != null) {
            return uiFont.width(text, uiFontSize);
        }
        return font().width(text);
    }
}
