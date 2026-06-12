package com.stormcph.easygui.client.widget;

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

/**
 * An inline HSV color picker: saturation/value box, hue strip, optional alpha strip,
 * and an editable hex field with a live swatch. Emits packed ARGB ints, so it binds
 * straight to {@code EasyConfig.defineColor} values ({@code new ColorPicker(C.get(), C)}).
 * For a compact control that opens this in a popup, use {@link ColorPickerButton}.
 *
 * <p>Hex input accepts {@code RRGGBB} (keeps the current alpha) or {@code AARRGGBB}.</p>
 */
@Environment(EnvType.CLIENT)
public class ColorPicker extends Widget {
    private static final float STRIP_WIDTH = 10f;
    private static final float GAP = 6f;
    private static final float HEX_ROW_HEIGHT = 18f;
    private static final int[] HUE_STOPS = {
            0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000,
    };

    private enum DragZone {NONE, SV, HUE, ALPHA}

    private float hue;
    private float saturation;
    private float value;
    private float alpha;
    private Consumer<Integer> onChange;
    private boolean showAlpha = true;

    private DragZone dragZone = DragZone.NONE;
    private boolean editingHex;
    private boolean hexSelectAll;
    private String hexText = "";
    private long lastInteraction;

    public ColorPicker(int initialArgb, Consumer<Integer> onChange) {
        this.onChange = onChange;
        this.height = 110f;
        setColor(initialArgb);
    }

    /** Hides the alpha strip and forces emitted colors opaque. */
    public ColorPicker setShowAlpha(boolean showAlpha) {
        this.showAlpha = showAlpha;
        if (!showAlpha) {
            alpha = 1f;
        }
        return this;
    }

    public int getColor() {
        return ColorUtil.withAlpha(ColorUtil.hsv(hue, saturation, value), alpha);
    }

    public ColorPicker setColor(int argb) {
        float[] hsv = ColorUtil.toHsv(argb);
        // Gray colors carry no hue information; keep the current hue so the
        // SV cursor doesn't jump to red when dragging through the gray edge.
        if (hsv[1] > 0f) {
            hue = hsv[0];
        }
        saturation = hsv[1];
        value = hsv[2];
        alpha = showAlpha ? ColorUtil.alpha(argb) / 255f : 1f;
        return this;
    }

    private void fireChange() {
        if (onChange != null) {
            onChange.accept(getColor());
        }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private float mainHeight() {
        return height - HEX_ROW_HEIGHT - GAP;
    }

    private float svWidth() {
        float strips = STRIP_WIDTH + GAP + (showAlpha ? STRIP_WIDTH + GAP : 0);
        return width - strips;
    }

    private float hueX() {
        return x + svWidth() + GAP;
    }

    private float alphaX() {
        return hueX() + STRIP_WIDTH + GAP;
    }

    private float hexRowY() {
        return y + mainHeight() + GAP;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float svW = svWidth();
        float mainH = mainHeight();

        // Saturation/value box: white -> hue horizontally, transparent -> black vertically
        int hueColor = ColorUtil.hsv(hue, 1f, 1f);
        Render2D.fillRoundedRectGradientH(graphics, x, y, svW, mainH, 4f, 0xFFFFFFFF, hueColor);
        Render2D.fillRoundedRectGradient(graphics, x, y, svW, mainH, 4f, 0x00000000, 0xFF000000);
        Render2D.strokeRoundedRect(graphics, x, y, svW, mainH, 4f, 1f, theme.outline);

        float svCursorX = x + saturation * svW;
        float svCursorY = y + (1f - value) * mainH;
        Render2D.strokeCircle(graphics, svCursorX, svCursorY, 4.5f, 1.2f, ColorUtil.withAlpha(0xFF000000, 0.4f));
        Render2D.strokeCircle(graphics, svCursorX, svCursorY, 3.5f, 1.5f, 0xFFFFFFFF);

        // Hue strip
        float hx = hueX();
        float segH = mainH / (HUE_STOPS.length - 1);
        Render2D.pushScissor(graphics, hx, y, STRIP_WIDTH, mainH);
        for (int i = 0; i < HUE_STOPS.length - 1; i++) {
            Render2D.fillRoundedRectGradient(graphics, hx, y + i * segH, STRIP_WIDTH, segH + 0.5f, 0f,
                    HUE_STOPS[i], HUE_STOPS[i + 1]);
        }
        Render2D.popScissor(graphics);
        Render2D.strokeRoundedRect(graphics, hx, y, STRIP_WIDTH, mainH, 3f, 1f, theme.outline);
        drawStripThumb(graphics, hx, y + hue * mainH);

        // Alpha strip
        if (showAlpha) {
            float ax = alphaX();
            int current = ColorUtil.hsv(hue, saturation, value);
            checkerboard(graphics, ax, y, STRIP_WIDTH, mainH);
            Render2D.fillRoundedRectGradient(graphics, ax, y, STRIP_WIDTH, mainH, 0f,
                    ColorUtil.withAlpha(current, 255), ColorUtil.withAlpha(current, 0));
            Render2D.strokeRoundedRect(graphics, ax, y, STRIP_WIDTH, mainH, 3f, 1f, theme.outline);
            drawStripThumb(graphics, ax, y + (1f - alpha) * mainH);
        }

        renderHexRow(graphics, theme);
    }

    private void drawStripThumb(GuiGraphics graphics, float stripX, float thumbY) {
        thumbY = Mth.clamp(thumbY, y + 1.5f, y + mainHeight() - 1.5f);
        Render2D.fillRoundedRect(graphics, stripX - 1.5f, thumbY - 2f, STRIP_WIDTH + 3f, 4f, 2f, 0xFFFFFFFF);
        Render2D.strokeRoundedRect(graphics, stripX - 1.5f, thumbY - 2f, STRIP_WIDTH + 3f, 4f, 2f, 1f,
                ColorUtil.withAlpha(0xFF000000, 0.35f));
    }

    private void checkerboard(GuiGraphics graphics, float cx, float cy, float w, float h) {
        Render2D.pushScissor(graphics, cx, cy, w, h);
        Render2D.fillRect(graphics, cx, cy, w, h, 0xFFB9B9C0);
        float cell = 3f;
        for (int row = 0; row * cell < h; row++) {
            for (int col = row % 2; col * cell < w; col += 2) {
                Render2D.fillRect(graphics, cx + col * cell, cy + row * cell,
                        Math.min(cell, w - col * cell), Math.min(cell, h - row * cell), 0xFF8C8C94);
            }
        }
        Render2D.popScissor(graphics);
    }

    private String formatHex() {
        int color = getColor();
        return showAlpha ? String.format(Locale.ROOT, "%08X", color)
                : String.format(Locale.ROOT, "%06X", color & 0xFFFFFF);
    }

    private void renderHexRow(GuiGraphics graphics, Theme theme) {
        float rowY = hexRowY();
        float swatchW = 22f;

        if (showAlpha && alpha < 1f) {
            checkerboard(graphics, x, rowY, swatchW, HEX_ROW_HEIGHT);
        }
        Render2D.fillRoundedRect(graphics, x, rowY, swatchW, HEX_ROW_HEIGHT, 4f, getColor());
        Render2D.strokeRoundedRect(graphics, x, rowY, swatchW, HEX_ROW_HEIGHT, 4f, 1f, theme.outline);

        float fieldX = x + swatchW + GAP;
        float fieldW = width - swatchW - GAP;
        int bg = theme.surfaceVariant;
        int outline = editingHex && focused ? theme.accent : theme.outline;
        Render2D.fillRoundedRect(graphics, fieldX, rowY, fieldW, HEX_ROW_HEIGHT, 4f, bg);
        Render2D.strokeRoundedRect(graphics, fieldX, rowY, fieldW, HEX_ROW_HEIGHT, 4f, 1f, outline);

        String shown = "#" + (editingHex ? hexText : formatHex());
        float textX = fieldX + 7f;
        float textY = rowY + (HEX_ROW_HEIGHT - Text2D.lineHeight()) / 2f + 0.5f;
        if (editingHex && hexSelectAll && !hexText.isEmpty()) {
            Render2D.fillRect(graphics, textX - 1, textY - 1.5f, Text2D.width(shown) + 2,
                    Text2D.lineHeight() + 2.5f, ColorUtil.withAlpha(theme.accent, 0.35f));
        }
        Text2D.draw(graphics, shown, textX, textY, theme.text);
        if (editingHex && focused && (Util.getMillis() - lastInteraction) % 1000 < 530) {
            Render2D.fillRect(graphics, textX + Text2D.width(shown) + 0.5f, textY - 1.5f, 1f,
                    Text2D.lineHeight() + 2.5f, theme.text);
        }
    }

    // ------------------------------------------------------------------
    // Hex editing
    // ------------------------------------------------------------------

    private void beginHexEdit() {
        editingHex = true;
        hexSelectAll = true;
        hexText = formatHex();
        lastInteraction = Util.getMillis();
        requestFocus();
    }

    private void commitHexEdit() {
        if (!editingHex) {
            return;
        }
        editingHex = false;
        String hex = hexText.trim();
        if (hex.length() != 6 && hex.length() != 8) {
            return;
        }
        try {
            long parsed = Long.parseLong(hex, 16);
            int argb;
            if (hex.length() == 6) {
                // RGB only: keep the current alpha
                argb = ColorUtil.withAlpha((int) parsed | 0xFF000000, alpha);
            } else {
                argb = (int) parsed;
            }
            float keptAlpha = showAlpha ? ColorUtil.alpha(argb) / 255f : 1f;
            setColor(argb);
            alpha = keptAlpha;
            fireChange();
        } catch (NumberFormatException ignored) {
        }
    }

    @Override
    public void setFocused(boolean focused) {
        if (!focused) {
            commitHexEdit();
        }
        super.setFocused(focused);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private void updateFromMouse(double mouseX, double mouseY) {
        float mainH = mainHeight();
        switch (dragZone) {
            case SV -> {
                saturation = Mth.clamp((float) (mouseX - x) / svWidth(), 0f, 1f);
                value = 1f - Mth.clamp((float) (mouseY - y) / mainH, 0f, 1f);
                fireChange();
            }
            case HUE -> {
                hue = Mth.clamp((float) (mouseY - y) / mainH, 0f, 0.9999f);
                fireChange();
            }
            case ALPHA -> {
                alpha = 1f - Mth.clamp((float) (mouseY - y) / mainH, 0f, 1f);
                fireChange();
            }
            default -> {
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || button != 0 || !contains(mouseX, mouseY)) {
            return false;
        }
        float mainH = mainHeight();
        if (mouseY < y + mainH) {
            if (mouseX < x + svWidth()) {
                dragZone = DragZone.SV;
            } else if (mouseX < hueX() + STRIP_WIDTH + GAP / 2f) {
                dragZone = DragZone.HUE;
            } else if (showAlpha) {
                dragZone = DragZone.ALPHA;
            }
            if (dragZone != DragZone.NONE) {
                commitHexEdit();
                updateFromMouse(mouseX, mouseY);
            }
            return true;
        }
        if (mouseY >= hexRowY() && mouseX >= x + 22f + GAP) {
            beginHexEdit();
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragZone != DragZone.NONE && button == 0) {
            updateFromMouse(mouseX, mouseY);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragZone != DragZone.NONE && button == 0) {
            dragZone = DragZone.NONE;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused || !editingHex) {
            return false;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (hexSelectAll) {
                    hexText = "";
                    hexSelectAll = false;
                } else if (!hexText.isEmpty()) {
                    hexText = hexText.substring(0, hexText.length() - 1);
                }
                lastInteraction = Util.getMillis();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                commitHexEdit();
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                editingHex = false;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (!focused || !editingHex) {
            return false;
        }
        boolean hexDigit = (chr >= '0' && chr <= '9') || (chr >= 'a' && chr <= 'f') || (chr >= 'A' && chr <= 'F');
        if (chr == '#') {
            return true; // swallow, the prefix is drawn automatically
        }
        if (!hexDigit) {
            return false;
        }
        if (hexSelectAll) {
            hexText = "";
            hexSelectAll = false;
        }
        if (hexText.length() < 8) {
            hexText += Character.toUpperCase(chr);
            lastInteraction = Util.getMillis();
        }
        return true;
    }
}
