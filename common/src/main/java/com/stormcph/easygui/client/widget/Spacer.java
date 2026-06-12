package com.stormcph.easygui.client.widget;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

/**
 * An invisible, flexible gap for {@link LinearLayout}. When the layout has a fixed
 * main-axis size, leftover space is split across all spacers proportionally to their
 * {@linkplain #setWeight(float) weight} — one spacer pushes everything after it to the
 * far end, a spacer on each side centers the content between them. In an
 * {@linkplain LinearLayout#setAutoSize(boolean) auto-sized} layout there is no leftover
 * space, so spacers collapse to nothing.
 *
 * <p>Renders nothing and never consumes input.</p>
 */
@Environment(EnvType.CLIENT)
public class Spacer extends Widget {
    private float weight = 1f;

    /**
     * Share of the leftover main-axis space relative to the other spacers (default 1).
     * A weight of 0 receives no space.
     */
    public Spacer setWeight(float weight) {
        this.weight = weight;
        return this;
    }

    public float getWeight() {
        return weight;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        // Intentionally empty: a spacer is pure layout, it draws nothing.
    }
}
