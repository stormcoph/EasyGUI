package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.util.Mth;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A slim progress bar. Set a 0..1 progress (changes animate smoothly), or enable
 * indeterminate mode for a sweeping activity animation.
 */
@Environment(EnvType.CLIENT)
public class ProgressBar extends Widget {
    private final SmoothValue progress = new SmoothValue(0f, 10f);
    private boolean indeterminate;

    public ProgressBar() {
        this.height = 4f;
    }

    public ProgressBar setProgress(float value) {
        progress.setTarget(Mth.clamp(value, 0f, 1f));
        return this;
    }

    public ProgressBar setIndeterminate(boolean indeterminate) {
        this.indeterminate = indeterminate;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float r = height / 2f;
        Render2D.fillRoundedRect(graphics, x, y, width, height, r, theme.surfaceHover);

        if (indeterminate) {
            float t = (Util.getMillis() % 1400L) / 1400f;
            float barWidth = width * 0.35f;
            float travel = width + barWidth;
            float barX = x - barWidth + travel * t;
            float clippedX = Math.max(x, barX);
            float clippedEnd = Math.min(x + width, barX + barWidth);
            if (clippedEnd > clippedX) {
                Render2D.fillRoundedRect(graphics, clippedX, y, clippedEnd - clippedX, height, r, theme.accent);
            }
        } else {
            float p = progress.get();
            if (p > 0.001f) {
                Render2D.fillRoundedRect(graphics, x, y, Math.max(height, width * p), height, r, theme.accent);
            }
        }
    }
}
