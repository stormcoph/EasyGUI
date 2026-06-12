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

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A two-thumb slider selecting a min/max pair, with the same track styling and thumb
 * grow animations as {@link Slider}. The segment between the thumbs is filled with the
 * accent color; clicking grabs the nearest thumb.
 */
@Environment(EnvType.CLIENT)
public class RangeSlider extends Widget {
    private static final float TRACK_HEIGHT = 4f;
    private static final int THUMB_LOW = 0;
    private static final int THUMB_HIGH = 1;

    private final double min;
    private final double max;
    private final double step;
    private double lowValue;
    private double highValue;
    private BiConsumer<Double, Double> onChange;
    private BiFunction<Double, Double, String> valueFormatter;
    private float valueDisplayWidth;

    private final SmoothValue lowThumbScale = new SmoothValue(0f, 16f);
    private final SmoothValue highThumbScale = new SmoothValue(0f, 16f);
    private int draggingThumb = -1;

    public RangeSlider(double min, double max, double step, double initialLow, double initialHigh,
                       BiConsumer<Double, Double> onChange) {
        this.min = min;
        this.max = max;
        this.step = step;
        this.lowValue = snap(Mth.clamp(initialLow, min, max));
        this.highValue = snap(Mth.clamp(Math.max(initialHigh, this.lowValue), min, max));
        this.onChange = onChange;
        this.height = 16f;
    }

    /** Shows a formatted range to the right of the track, reserving {@code displayWidth} pixels. */
    public RangeSlider setValueFormatter(BiFunction<Double, Double, String> formatter, float displayWidth) {
        this.valueFormatter = formatter;
        this.valueDisplayWidth = displayWidth;
        return this;
    }

    public double getLowValue() {
        return lowValue;
    }

    public double getHighValue() {
        return highValue;
    }

    public RangeSlider setValues(double newLow, double newHigh) {
        this.lowValue = snap(Mth.clamp(newLow, min, max));
        this.highValue = snap(Mth.clamp(Math.max(newHigh, this.lowValue), min, max));
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

    private float thumbX(double value) {
        float t = max > min ? (float) ((value - min) / (max - min)) : 0f;
        return x + trackWidth() * t;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float hover = hoverAmount();

        float tw = trackWidth();
        float trackY = y + (height - TRACK_HEIGHT) / 2f;
        float cy = trackY + TRACK_HEIGHT / 2f;
        float lowX = thumbX(lowValue);
        float highX = thumbX(highValue);

        // Each thumb grows when dragged, or when it's the one the cursor is nearest to
        int nearThumb = isHovered() ? nearestThumb(mouseX) : -1;
        lowThumbScale.setTarget(draggingThumb == THUMB_LOW ? 1f
                : nearThumb == THUMB_LOW ? hover * 0.6f : 0f);
        highThumbScale.setTarget(draggingThumb == THUMB_HIGH ? 1f
                : nearThumb == THUMB_HIGH ? hover * 0.6f : 0f);

        int trackColor = enabled ? theme.surfaceHover : ColorUtil.multiplyAlpha(theme.surfaceHover, 0.45f);
        int fillColor = enabled ? ColorUtil.lerp(theme.accent, theme.accentHover, hover)
                : ColorUtil.multiplyAlpha(theme.accent, 0.45f);

        Render2D.fillRoundedRect(graphics, x, trackY, tw, TRACK_HEIGHT, TRACK_HEIGHT / 2f, trackColor);
        if (highX - lowX > 0.001f) {
            Render2D.fillRoundedRect(graphics, lowX, trackY, Math.max(TRACK_HEIGHT, highX - lowX),
                    TRACK_HEIGHT, TRACK_HEIGHT / 2f, fillColor);
        }

        // Draw the dragged thumb last so it stays on top when the two overlap
        if (draggingThumb == THUMB_LOW) {
            drawThumb(graphics, highX, cy, highThumbScale.get(), fillColor);
            drawThumb(graphics, lowX, cy, lowThumbScale.get(), fillColor);
        } else {
            drawThumb(graphics, lowX, cy, lowThumbScale.get(), fillColor);
            drawThumb(graphics, highX, cy, highThumbScale.get(), fillColor);
        }

        if (valueFormatter != null) {
            Text2D.drawRightAligned(graphics, valueFormatter.apply(lowValue, highValue), x + width,
                    y + (height - Text2D.lineHeight()) / 2f + 0.5f,
                    enabled ? theme.textMuted : ColorUtil.multiplyAlpha(theme.textMuted, 0.45f));
        }
    }

    private void drawThumb(GuiGraphics graphics, float cx, float cy, float grow, int accentColor) {
        float radius = 5f + grow * 1.5f;
        Render2D.fillCircle(graphics, cx + 0.4f, cy + 0.7f, radius, ColorUtil.withAlpha(0xFF000000, 0.25f));
        Render2D.fillCircle(graphics, cx, cy, radius, 0xFFFFFFFF);
        if (grow > 0.05f) {
            Render2D.strokeCircle(graphics, cx, cy, radius + 2.2f, 1.4f,
                    ColorUtil.multiplyAlpha(accentColor, grow * 0.5f));
        }
    }

    /** The thumb closest to the cursor; ties (overlapping thumbs) break by click side. */
    private int nearestThumb(double mouseX) {
        float lowX = thumbX(lowValue);
        float highX = thumbX(highValue);
        double distLow = Math.abs(mouseX - lowX);
        double distHigh = Math.abs(mouseX - highX);
        if (distLow == distHigh) {
            return mouseX < lowX ? THUMB_LOW : THUMB_HIGH;
        }
        return distLow < distHigh ? THUMB_LOW : THUMB_HIGH;
    }

    private void updateFromMouse(double mouseX) {
        float tw = trackWidth();
        double t = Mth.clamp((mouseX - x) / tw, 0.0, 1.0);
        double snapped = snap(min + (max - min) * t);
        if (draggingThumb == THUMB_LOW) {
            double newLow = Math.min(snapped, highValue);
            if (newLow != lowValue) {
                lowValue = newLow;
                fireChange();
            }
        } else {
            double newHigh = Math.max(snapped, lowValue);
            if (newHigh != highValue) {
                highValue = newHigh;
                fireChange();
            }
        }
    }

    private void fireChange() {
        if (onChange != null) {
            onChange.accept(lowValue, highValue);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || button != 0 || !contains(mouseX, mouseY)) {
            return false;
        }
        draggingThumb = nearestThumb(mouseX);
        updateFromMouse(mouseX);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingThumb >= 0 && button == 0) {
            updateFromMouse(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingThumb >= 0 && button == 0) {
            draggingThumb = -1;
            return true;
        }
        return false;
    }
}
