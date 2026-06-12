package com.stormcph.easygui.client.demo;

import com.stormcph.easygui.client.overlay.Anchor;
import com.stormcph.easygui.client.overlay.HudOverlay;
import com.stormcph.easygui.client.render.Blur;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.render.shader.Shaders;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Example HUD overlay: a small watermark card with a pulsing accent dot and live FPS.
 * Toggled from the demo screen.
 */
@Environment(EnvType.CLIENT)
public class DemoOverlay extends HudOverlay {
    public DemoOverlay() {
        setAnchor(Anchor.TOP_LEFT);
        setOffsets(6, 6);
        setPersistId("demo_watermark"); // position edits survive restarts
    }

    @Override
    public float getWidth() {
        return 30 + Text2D.width(label());
    }

    @Override
    public float getHeight() {
        return 24;
    }

    private String label() {
        return "EasyGUI  " + Minecraft.getInstance().getFps() + " fps";
    }

    @Override
    public void render(GuiGraphics graphics, float x, float y, float partialTick) {
        Theme theme = theme();
        float w = getWidth();
        float h = getHeight();

        // Frosted glass over the world when the blur shaders are available
        boolean frosted = Blur.isAvailable();
        Render2D.dropShadow(graphics, x, y, w, h, 6f, 4f,
                ColorUtil.multiplyAlpha(theme.shadow, 0.7f), !frosted);
        if (!frosted || !Render2D.fillRoundedRectBlurred(graphics, x, y, w, h, 6f, 5f,
                ColorUtil.withAlpha(theme.surface, 0.72f))) {
            Render2D.fillRoundedRect(graphics, x, y, w, h, 6f, ColorUtil.withAlpha(theme.surface, 0.88f));
        }
        Render2D.strokeRoundedRect(graphics, x, y, w, h, 6f, 1f, theme.outline);

        // Content centers in the area above the liquid strip
        float contentH = h - 5;
        float pulse = (float) (Math.sin(Util.getMillis() / 450.0) * 0.5 + 0.5);
        int dot = ColorUtil.lerp(theme.accent, theme.accentHover, pulse);
        Render2D.fillCircle(graphics, x + 11, y + contentH / 2f, 3f + pulse * 0.6f, dot);
        Render2D.strokeCircle(graphics, x + 11, y + contentH / 2f, 5f + pulse * 1.4f, 1f,
                ColorUtil.withAlpha(dot, 0.35f * (1f - pulse)));

        Text2D.drawVerticallyCentered(graphics, label(), x + 20, y, contentH, theme.text);

        // Animated liquid accent strip, drawn directly with Render2D + a built-in shader
        Render2D.shadedRoundedRect(graphics, Shaders.LIQUID, x + 7, y + h - 5, w - 14, 2f, 1f,
                0xFFFFFFFF, null);
    }
}
