package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * A rounded checkbox whose check mark animates in with a draw-on effect.
 */
@Environment(EnvType.CLIENT)
public class Checkbox extends Widget {
    private static final float BOX_SIZE = 14f;

    private String label;
    private boolean value;
    private Consumer<Boolean> onChange;

    private final SmoothValue checkAnim = new SmoothValue(0f, 16f);

    public Checkbox(String label, boolean initial, Consumer<Boolean> onChange) {
        this.label = label;
        this.value = initial;
        this.onChange = onChange;
        this.checkAnim.setInstant(initial ? 1f : 0f);
        this.height = BOX_SIZE + 2;
    }

    public boolean getValue() {
        return value;
    }

    public Checkbox setValue(boolean value) {
        this.value = value;
        checkAnim.setTarget(value ? 1f : 0f);
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float t = checkAnim.get();
        float hover = hoverAmount();

        float boxY = y + (height - BOX_SIZE) / 2f;
        int empty = ColorUtil.lerp(theme.surfaceVariant, theme.surfaceHover, hover);
        int filled = ColorUtil.lerp(theme.accent, theme.accentHover, hover);
        int bg = ColorUtil.lerp(empty, filled, t);
        if (!enabled) {
            bg = ColorUtil.multiplyAlpha(bg, 0.45f);
        }

        Render2D.fillRoundedRect(graphics, x, boxY, BOX_SIZE, BOX_SIZE, 4f, bg);
        if (t < 0.95f) {
            Render2D.strokeRoundedRect(graphics, x, boxY, BOX_SIZE, BOX_SIZE, 4f, 1f,
                    ColorUtil.multiplyAlpha(theme.outline, 1f - t));
        }

        if (t > 0.01f) {
            // Check mark drawn on progressively, as a single mitered polyline so the
            // joint doesn't double-blend while the mark is translucent
            float sw = 1.6f;
            int c = ColorUtil.multiplyAlpha(theme.onAccent, Math.min(1f, t * 1.5f));
            float x1 = x + BOX_SIZE * 0.24f, y1 = boxY + BOX_SIZE * 0.52f;
            float x2 = x + BOX_SIZE * 0.43f, y2 = boxY + BOX_SIZE * 0.70f;
            float x3 = x + BOX_SIZE * 0.78f, y3 = boxY + BOX_SIZE * 0.30f;
            if (t <= 0.45f) {
                float seg1 = t / 0.45f;
                Render2D.polyline(graphics, new float[]{
                        x1, y1,
                        x1 + (x2 - x1) * seg1, y1 + (y2 - y1) * seg1
                }, sw, c, true);
            } else {
                float seg2 = (t - 0.45f) / 0.55f;
                Render2D.polyline(graphics, new float[]{
                        x1, y1,
                        x2, y2,
                        x2 + (x3 - x2) * seg2, y2 + (y3 - y2) * seg2
                }, sw, c, true);
            }
        }

        if (label != null && !label.isEmpty()) {
            int color = enabled ? theme.text : theme.textMuted;
            Text2D.drawVerticallyCentered(graphics, label, x + BOX_SIZE + 7, y, height, color);
        }

        if (focused) {
            drawFocusRing(graphics, x, boxY, BOX_SIZE, BOX_SIZE, 4f);
        }
    }

    private void toggle() {
        value = !value;
        checkAnim.setTarget(value ? 1f : 0f);
        if (onChange != null) {
            onChange.accept(value);
        }
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || button != 0 || !contains(mouseX, mouseY)) {
            return false;
        }
        toggle();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused || !enabled) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE) {
            toggle();
            return true;
        }
        return false;
    }
}
