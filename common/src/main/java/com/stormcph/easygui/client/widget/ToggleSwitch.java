package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;

/**
 * An iOS-style animated toggle switch with an optional label to its right.
 * The clickable area covers the track and the label.
 */
@Environment(EnvType.CLIENT)
public class ToggleSwitch extends Widget {
    private static final float TRACK_WIDTH = 26f;
    private static final float TRACK_HEIGHT = 14f;

    private String label;
    private boolean value;
    private Consumer<Boolean> onChange;

    private final SmoothValue knobAnim = new SmoothValue(0f, 16f);

    public ToggleSwitch(String label, boolean initial, Consumer<Boolean> onChange) {
        this.label = label;
        this.value = initial;
        this.onChange = onChange;
        this.knobAnim.setInstant(initial ? 1f : 0f);
        this.height = TRACK_HEIGHT + 2;
    }

    public boolean getValue() {
        return value;
    }

    public ToggleSwitch setValue(boolean value) {
        this.value = value;
        knobAnim.setTarget(value ? 1f : 0f);
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float t = knobAnim.get();
        float hover = hoverAmount();

        float trackY = y + (height - TRACK_HEIGHT) / 2f;
        int offColor = ColorUtil.lerp(theme.surfaceHover, ColorUtil.shift(theme.surfaceHover, 0.08f), hover);
        int onColor = ColorUtil.lerp(theme.accent, theme.accentHover, hover);
        int track = ColorUtil.lerp(offColor, onColor, t);
        if (!enabled) {
            track = ColorUtil.multiplyAlpha(track, 0.45f);
        }

        Render2D.fillRoundedRect(graphics, x, trackY, TRACK_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT / 2f, track);

        float knobRadius = TRACK_HEIGHT / 2f - 2.5f + hover * 0.5f;
        float knobX = x + TRACK_HEIGHT / 2f + (TRACK_WIDTH - TRACK_HEIGHT) * t;
        float knobY = trackY + TRACK_HEIGHT / 2f;
        Render2D.fillCircle(graphics, knobX + 0.5f, knobY + 0.8f, knobRadius, ColorUtil.withAlpha(0xFF000000, 0.25f));
        Render2D.fillCircle(graphics, knobX, knobY, knobRadius, 0xFFFFFFFF);

        if (label != null && !label.isEmpty()) {
            int color = enabled ? theme.text : theme.textMuted;
            Text2D.drawVerticallyCentered(graphics, label, x + TRACK_WIDTH + 8, y, height, color);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || button != 0 || !contains(mouseX, mouseY)) {
            return false;
        }
        value = !value;
        knobAnim.setTarget(value ? 1f : 0f);
        if (onChange != null) {
            onChange.accept(value);
        }
        return true;
    }
}
