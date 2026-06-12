package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.DoubleFunction;

/**
 * A horizontal slider with a circular thumb that grows on hover/drag and an optional
 * live value readout on the right.
 */
@Environment(EnvType.CLIENT)
public class Slider extends Widget {
    private static final float TRACK_HEIGHT = 4f;

    private final double min;
    private final double max;
    private final double step;
    private double value;
    private Consumer<Double> onChange;
    private DoubleFunction<String> valueFormatter;
    private float valueDisplayWidth;

    private final SmoothValue thumbScale = new SmoothValue(0f, 16f);
    private boolean dragging;

    public Slider(double min, double max, double step, double initial, Consumer<Double> onChange) {
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = Mth.clamp(initial, min, max);
        this.onChange = onChange;
        this.height = 16f;
    }

    /** Shows a formatted value to the right of the track, reserving {@code displayWidth} pixels. */
    public Slider setValueFormatter(DoubleFunction<String> formatter, float displayWidth) {
        this.valueFormatter = formatter;
        this.valueDisplayWidth = displayWidth;
        return this;
    }

    public double getValue() {
        return value;
    }

    public Slider setValue(double newValue) {
        this.value = snap(Mth.clamp(newValue, min, max));
        return this;
    }

    private double snap(double v) {
        if (step <= 0) {
            return v;
        }
        return Mth.clamp(min + Math.round((v - min) / step) * step, min, max);
    }

    private float trackWidth() {
        return valueFormatter != null ? width - valueDisplayWidth - 8 : width;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float hover = hoverAmount();
        thumbScale.setTarget(dragging ? 1f : hover * 0.6f);
        float grow = thumbScale.get();

        float tw = trackWidth();
        float trackY = y + (height - TRACK_HEIGHT) / 2f;
        float t = max > min ? (float) ((value - min) / (max - min)) : 0f;
        float thumbX = x + tw * t;

        int trackColor = enabled ? theme.surfaceHover : ColorUtil.multiplyAlpha(theme.surfaceHover, 0.45f);
        int fillColor = enabled ? ColorUtil.lerp(theme.accent, theme.accentHover, hover)
                : ColorUtil.multiplyAlpha(theme.accent, 0.45f);

        Render2D.fillRoundedRect(graphics, x, trackY, tw, TRACK_HEIGHT, TRACK_HEIGHT / 2f, trackColor);
        if (t > 0.001f) {
            Render2D.fillRoundedRect(graphics, x, trackY, Math.max(TRACK_HEIGHT, tw * t),
                    TRACK_HEIGHT, TRACK_HEIGHT / 2f, fillColor);
        }

        float thumbRadius = 5f + grow * 1.5f;
        float cy = trackY + TRACK_HEIGHT / 2f;
        Render2D.fillCircle(graphics, thumbX + 0.4f, cy + 0.7f, thumbRadius, ColorUtil.withAlpha(0xFF000000, 0.25f));
        Render2D.fillCircle(graphics, thumbX, cy, thumbRadius, 0xFFFFFFFF);
        if (grow > 0.05f) {
            Render2D.strokeCircle(graphics, thumbX, cy, thumbRadius + 2.2f, 1.4f,
                    ColorUtil.multiplyAlpha(fillColor, grow * 0.5f));
        }

        if (valueFormatter != null) {
            Text2D.drawRightAligned(graphics, valueFormatter.apply(value), x + width,
                    y + (height - Text2D.lineHeight()) / 2f + 0.5f,
                    enabled ? theme.textMuted : ColorUtil.multiplyAlpha(theme.textMuted, 0.45f));
        }

        if (focused) {
            drawFocusRing(graphics, x, y, width, height, height / 2f);
        }
    }

    private void updateFromMouse(double mouseX) {
        float tw = trackWidth();
        double t = Mth.clamp((mouseX - x) / tw, 0.0, 1.0);
        applyValue(snap(min + (max - min) * t));
    }

    private void applyValue(double newValue) {
        if (newValue != value) {
            value = newValue;
            if (onChange != null) {
                onChange.accept(value);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || button != 0 || !contains(mouseX, mouseY)) {
            return false;
        }
        dragging = true;
        updateFromMouse(mouseX);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == 0) {
            updateFromMouse(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == 0) {
            dragging = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused || !enabled) {
            return false;
        }
        // One step per press; continuous sliders move 1% of the range
        double amount = step > 0 ? step : (max - min) * 0.01;
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            applyValue(snap(Mth.clamp(value - amount, min, max)));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            applyValue(snap(Mth.clamp(value + amount, min, max)));
            return true;
        }
        return false;
    }
}
