package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;

/**
 * A numeric input with three ways to change the value: the +/&minus; end buttons step
 * it, dragging horizontally across the middle scrubs it (Blender-style), and a plain
 * click on the number opens inline type-to-edit (Enter commits, Escape reverts).
 */
@Environment(EnvType.CLIENT)
public class NumberStepper extends Widget {
    /** Horizontal drag distance (px) that scrubs the value by one step. */
    private static final float SCRUB_PIXELS_PER_STEP = 3f;
    private static final int MAX_EDIT_LENGTH = 12;

    private final double min;
    private final double max;
    private final double step;
    private double value;
    private Consumer<Double> onChange;
    private DoubleFunction<String> formatter;

    private boolean editing;
    private boolean editSelectAll;
    private String editText = "";
    private long lastInteraction;

    private boolean scrubbing;
    private boolean scrubMoved;
    private double scrubStartX;
    private double scrubStartValue;

    private final SmoothValue minusHover = new SmoothValue(0f, 16f);
    private final SmoothValue plusHover = new SmoothValue(0f, 16f);
    private final SmoothValue focusAnim = new SmoothValue(0f, 14f);

    public NumberStepper(double min, double max, double step, double initial, Consumer<Double> onChange) {
        this.min = min;
        this.max = max;
        this.step = step > 0 ? step : 1;
        this.value = snap(Mth.clamp(initial, min, max));
        this.onChange = onChange;
        this.height = 18f;
    }

    /** Overrides display formatting (typing still edits the plain number). */
    public NumberStepper setFormatter(DoubleFunction<String> formatter) {
        this.formatter = formatter;
        return this;
    }

    public double getValue() {
        return value;
    }

    public NumberStepper setValue(double newValue) {
        this.value = snap(Mth.clamp(newValue, min, max));
        return this;
    }

    private double snap(double v) {
        return Mth.clamp(min + Math.round((v - min) / step) * step, min, max);
    }

    private void applyValue(double newValue) {
        double snapped = snap(Mth.clamp(newValue, min, max));
        if (snapped != value) {
            value = snapped;
            if (onChange != null) {
                onChange.accept(value);
            }
        }
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    /** Decimal places implied by the step (0 for whole-number steps). */
    private int decimals() {
        double s = step;
        int d = 0;
        while (d < 4 && Math.abs(s - Math.round(s)) > 1e-6) {
            s *= 10;
            d++;
        }
        return d;
    }

    private String plainFormat(double v) {
        int d = decimals();
        return d == 0 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%." + d + "f", v);
    }

    private String displayText() {
        return formatter != null ? formatter.apply(value) : plainFormat(value);
    }

    private float buttonWidth() {
        return Math.min(height, 18f);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float hover = hoverAmount();
        focusAnim.setTarget(editing && focused ? 1f : 0f);
        float focusT = focusAnim.get();
        float r = theme.radiusSmall;
        float btnW = buttonWidth();

        boolean overMinus = isHovered() && mouseX < x + btnW;
        boolean overPlus = isHovered() && mouseX >= x + width - btnW;
        minusHover.setTarget(overMinus ? 1f : 0f);
        plusHover.setTarget(overPlus ? 1f : 0f);

        int bg = ColorUtil.lerp(theme.surfaceVariant, theme.surfaceHover, hover * 0.5f);
        if (!enabled) {
            bg = ColorUtil.multiplyAlpha(bg, 0.45f);
        }
        Render2D.fillRoundedRect(graphics, x, y, width, height, r, bg);

        // End-button hover fills, scissored so they keep the outer rounded corners
        drawZoneHighlight(graphics, x, btnW, minusHover.get(), r, theme);
        drawZoneHighlight(graphics, x + width - btnW, btnW, plusHover.get(), r, theme);

        int outlineColor = ColorUtil.lerp(theme.outline, theme.accent, focusT);
        Render2D.strokeRoundedRect(graphics, x, y, width, height, r, 1f + focusT * 0.5f, outlineColor);

        // Hairlines separating the button zones from the value area
        int hairline = ColorUtil.multiplyAlpha(theme.outline, 0.7f);
        Render2D.fillRect(graphics, x + btnW, y + 3, 1f, height - 6, hairline);
        Render2D.fillRect(graphics, x + width - btnW - 1, y + 3, 1f, height - 6, hairline);

        int glyphColor = enabled ? theme.text : ColorUtil.multiplyAlpha(theme.text, 0.45f);
        float cy = y + height / 2f;
        boolean atMin = value <= min;
        boolean atMax = value >= max;
        drawMinus(graphics, x + btnW / 2f, cy,
                atMin ? ColorUtil.multiplyAlpha(glyphColor, 0.35f)
                        : ColorUtil.lerp(glyphColor, theme.accentHover, minusHover.get()));
        drawPlus(graphics, x + width - btnW / 2f, cy,
                atMax ? ColorUtil.multiplyAlpha(glyphColor, 0.35f)
                        : ColorUtil.lerp(glyphColor, theme.accentHover, plusHover.get()));

        // Value (or the edit buffer with selection highlight and blinking caret)
        String text = editing ? editText : displayText();
        float textW = Text2D.width(text);
        float textX = x + btnW + (width - btnW * 2 - textW) / 2f;
        float textY = y + (height - Text2D.lineHeight()) / 2f + 0.5f;

        Render2D.pushScissor(graphics, x + btnW + 1, y, width - btnW * 2 - 2, height);
        if (editing && editSelectAll && !editText.isEmpty()) {
            Render2D.fillRect(graphics, textX - 1, textY - 1.5f, textW + 2, Text2D.lineHeight() + 2.5f,
                    ColorUtil.withAlpha(theme.accent, 0.35f));
        }
        Text2D.draw(graphics, text, textX, textY, enabled ? theme.text : theme.textMuted);
        if (editing && focused && (Util.getMillis() - lastInteraction) % 1000 < 530) {
            Render2D.fillRect(graphics, textX + textW + 0.5f, textY - 1.5f, 1f,
                    Text2D.lineHeight() + 2.5f, theme.text);
        }
        Render2D.popScissor(graphics);
    }

    private void drawZoneHighlight(GuiGraphics graphics, float zoneX, float zoneW, float strength,
                                   float radius, Theme theme) {
        if (strength <= 0.02f) {
            return;
        }
        Render2D.pushScissor(graphics, zoneX, y, zoneW, height);
        Render2D.fillRoundedRect(graphics, x, y, width, height, radius,
                ColorUtil.multiplyAlpha(theme.surfaceHover, strength));
        Render2D.popScissor(graphics);
    }

    private void drawMinus(GuiGraphics graphics, float cx, float cy, int color) {
        Render2D.fillRect(graphics, cx - 3f, cy - 0.6f, 6f, 1.2f, color);
    }

    private void drawPlus(GuiGraphics graphics, float cx, float cy, int color) {
        Render2D.fillRect(graphics, cx - 3f, cy - 0.6f, 6f, 1.2f, color);
        Render2D.fillRect(graphics, cx - 0.6f, cy - 3f, 1.2f, 6f, color);
    }

    // ------------------------------------------------------------------
    // Inline editing
    // ------------------------------------------------------------------

    private void beginEdit() {
        editing = true;
        editSelectAll = true;
        editText = plainFormat(value);
        lastInteraction = Util.getMillis();
        requestFocus();
    }

    private void commitEdit() {
        if (!editing) {
            return;
        }
        editing = false;
        try {
            applyValue(Double.parseDouble(editText));
        } catch (NumberFormatException ignored) {
            // Unparseable input: keep the old value
        }
    }

    private void cancelEdit() {
        editing = false;
    }

    @Override
    public void setFocused(boolean focused) {
        if (!focused) {
            commitEdit();
        }
        super.setFocused(focused);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || button != 0 || !contains(mouseX, mouseY)) {
            return false;
        }
        float btnW = buttonWidth();
        if (mouseX < x + btnW) {
            commitEdit();
            applyValue(value - step);
            return true;
        }
        if (mouseX >= x + width - btnW) {
            commitEdit();
            applyValue(value + step);
            return true;
        }
        scrubbing = true;
        scrubMoved = false;
        scrubStartX = mouseX;
        scrubStartValue = value;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!scrubbing || button != 0) {
            return false;
        }
        double dx = mouseX - scrubStartX;
        if (!scrubMoved && Math.abs(dx) > 3) {
            scrubMoved = true;
            cancelEdit();
        }
        if (scrubMoved) {
            applyValue(scrubStartValue + dx / SCRUB_PIXELS_PER_STEP * step);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (scrubbing && button == 0) {
            scrubbing = false;
            if (!scrubMoved) {
                beginEdit();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!enabled || !isHovered() || scrollY == 0) {
            return false;
        }
        commitEdit();
        applyValue(value + (scrollY > 0 ? step : -step));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused || !editing) {
            return false;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (editSelectAll) {
                    editText = "";
                    editSelectAll = false;
                } else if (!editText.isEmpty()) {
                    editText = editText.substring(0, editText.length() - 1);
                }
                lastInteraction = Util.getMillis();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                commitEdit();
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                cancelEdit();
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                commitEdit();
                applyValue(value + step);
                beginEdit();
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                commitEdit();
                applyValue(value - step);
                beginEdit();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (!focused || !editing) {
            return false;
        }
        boolean digit = chr >= '0' && chr <= '9';
        boolean sign = chr == '-' && (editSelectAll || editText.isEmpty()) && min < 0;
        boolean dot = chr == '.' && decimals() > 0 && (editSelectAll || !editText.contains("."));
        if (!digit && !sign && !dot) {
            return false;
        }
        if (editSelectAll) {
            editText = "";
            editSelectAll = false;
        }
        if (editText.length() < MAX_EDIT_LENGTH) {
            editText += chr;
            lastInteraction = Util.getMillis();
        }
        return true;
    }
}
