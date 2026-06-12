package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.render.Render2D;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;

/**
 * An indeterminate loading spinner: a rotating arc whose sweep breathes between
 * short and long, like a material-design progress ring.
 */
@Environment(EnvType.CLIENT)
public class Spinner extends Widget {
    private int color;
    private boolean hasCustomColor;
    private float thickness = 2.5f;

    public Spinner() {
        setSize(16, 16);
    }

    public Spinner setColor(int color) {
        this.color = color;
        this.hasCustomColor = true;
        return this;
    }

    public Spinner setThickness(float thickness) {
        this.thickness = thickness;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        long now = Util.getMillis();
        float rotation = (now % 1100L) / 1100f * 360f;
        float breathe = (float) (Math.sin((now % 1600L) / 1600.0 * Math.PI * 2) * 0.5 + 0.5);
        float sweep = 50f + breathe * 210f;

        float radius = Math.min(width, height) / 2f - 1;
        int c = hasCustomColor ? color : theme().accent;
        Render2D.drawArc(graphics, x + width / 2f, y + height / 2f, radius, thickness,
                rotation, rotation + sweep, c);
    }
}
