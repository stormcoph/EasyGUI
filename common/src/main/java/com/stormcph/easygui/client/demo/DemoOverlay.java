package com.stormcph.easygui.client.demo;

import com.stormcph.easygui.client.overlay.Anchor;
import com.stormcph.easygui.client.overlay.HudOverlay;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
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
    }

    @Override
    public float getWidth() {
        return 30 + Text2D.width(label());
    }

    @Override
    public float getHeight() {
        return 20;
    }

    private String label() {
        return "EasyGUI  " + Minecraft.getInstance().getFps() + " fps";
    }

    @Override
    public void render(GuiGraphics graphics, float x, float y, float partialTick) {
        Theme theme = theme();
        float w = getWidth();
        float h = getHeight();

        Render2D.dropShadow(graphics, x, y, w, h, 6f, 4f, ColorUtil.multiplyAlpha(theme.shadow, 0.7f));
        Render2D.fillRoundedRect(graphics, x, y, w, h, 6f, ColorUtil.withAlpha(theme.surface, 0.88f));
        Render2D.strokeRoundedRect(graphics, x, y, w, h, 6f, 1f, theme.outline);

        float pulse = (float) (Math.sin(Util.getMillis() / 450.0) * 0.5 + 0.5);
        int dot = ColorUtil.lerp(theme.accent, theme.accentHover, pulse);
        Render2D.fillCircle(graphics, x + 11, y + h / 2f, 3f + pulse * 0.6f, dot);
        Render2D.strokeCircle(graphics, x + 11, y + h / 2f, 5f + pulse * 1.4f, 1f,
                ColorUtil.withAlpha(dot, 0.35f * (1f - pulse)));

        Text2D.drawVerticallyCentered(graphics, label(), x + 20, y, h, theme.text);
    }
}
