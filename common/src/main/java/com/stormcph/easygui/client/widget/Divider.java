package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A hairline separator. Horizontal by default (optionally with a centered section
 * label); {@link #setVertical} turns it into a vertical rule for splitting columns.
 */
@Environment(EnvType.CLIENT)
public class Divider extends Widget {
    private static final float LABEL_GAP = 6f;

    private String label;
    private boolean vertical;

    public Divider() {
        this.height = 8f;
    }

    public Divider(String label) {
        this.label = label;
        this.height = 10f;
    }

    public Divider setLabel(String label) {
        this.label = label;
        return this;
    }

    public Divider setVertical(boolean vertical) {
        this.vertical = vertical;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        int lineColor = theme.outline;

        if (vertical) {
            Render2D.fillRect(graphics, x + width / 2f - 0.5f, y, 1f, height, lineColor);
            return;
        }

        float lineY = y + height / 2f - 0.5f;
        if (label == null || label.isEmpty()) {
            Render2D.fillRect(graphics, x, lineY, width, 1f, lineColor);
            return;
        }

        float labelW = Text2D.width(label);
        float sideW = (width - labelW - LABEL_GAP * 2) / 2f;
        if (sideW > 1f) {
            Render2D.fillRect(graphics, x, lineY, sideW, 1f, lineColor);
            Render2D.fillRect(graphics, x + width - sideW, lineY, sideW, 1f, lineColor);
        }
        Text2D.draw(graphics, label, x + (width - labelW) / 2f,
                y + (height - Text2D.lineHeight()) / 2f + 0.5f,
                ColorUtil.multiplyAlpha(theme.textMuted, 0.9f));
    }
}
