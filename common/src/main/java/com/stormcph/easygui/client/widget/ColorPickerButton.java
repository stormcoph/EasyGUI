package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.function.Consumer;

/**
 * The popup variant of {@link ColorPicker}: a row with a label and a color swatch chip
 * that opens the full picker on the screen's popup layer. Changes apply live while
 * picking.
 */
@Environment(EnvType.CLIENT)
public class ColorPickerButton extends Widget {
    private static final float POPUP_GAP = 4f;
    private static final float POPUP_PADDING = 10f;
    private static final float PICKER_WIDTH = 170f;
    private static final float PICKER_HEIGHT = 104f;

    private String label;
    private final ColorPicker picker;
    private boolean open;

    private final SmoothValue openAnim = new SmoothValue(0f, 18f);

    public ColorPickerButton(String label, int initialArgb, Consumer<Integer> onChange) {
        this.label = label;
        this.picker = new ColorPicker(initialArgb, onChange);
        this.height = 20f;
    }

    /** Hides the alpha strip in the popup picker. */
    public ColorPickerButton setShowAlpha(boolean showAlpha) {
        picker.setShowAlpha(showAlpha);
        return this;
    }

    public int getColor() {
        return picker.getColor();
    }

    public ColorPickerButton setColor(int argb) {
        picker.setColor(argb);
        return this;
    }

    private float popupWidth() {
        return PICKER_WIDTH + POPUP_PADDING * 2;
    }

    private float popupHeight() {
        return PICKER_HEIGHT + POPUP_PADDING * 2;
    }

    private float popupX() {
        EasyScreen screen = getScreen();
        float px = x + width - popupWidth(); // right-align to the control
        float limit = screen != null ? screen.width - popupWidth() - 4 : px;
        return Mth.clamp(px, 4, Math.max(4, limit));
    }

    /** Popup rect, flipped above the control when there is no room below. */
    private float popupY() {
        EasyScreen screen = getScreen();
        float below = y + height + POPUP_GAP;
        if (screen != null && below + popupHeight() > screen.height - 4
                && y - POPUP_GAP - popupHeight() > 4) {
            return y - POPUP_GAP - popupHeight();
        }
        return below;
    }

    private void setOpen(boolean newOpen) {
        open = newOpen;
        EasyScreen screen = getScreen();
        if (screen == null) {
            return;
        }
        if (open) {
            picker.bindScreen(screen); // the picker lives outside the tree; route focus/theme
            screen.openPopup(this);
        } else {
            screen.closePopup(this);
            if (screen.getFocusedWidget() == picker) {
                screen.setFocusedWidget(null);
            }
        }
    }

    @Override
    public void dismissPopup() {
        open = false;
        EasyScreen screen = getScreen();
        if (screen != null && screen.getFocusedWidget() == picker) {
            screen.setFocusedWidget(null); // commits a pending hex edit
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float hover = hoverAmount();
        openAnim.setTarget(open ? 1f : 0f);
        float openT = openAnim.get();

        if (label != null && !label.isEmpty()) {
            Text2D.drawVerticallyCentered(graphics, label, x, y, height,
                    enabled ? theme.text : theme.textMuted);
        }

        float chipW = 36f;
        float chipX = x + width - chipW;
        float r = theme.radiusSmall;
        int color = picker.getColor();
        if (ColorUtil.alpha(color) < 255) {
            Render2D.pushScissor(graphics, chipX, y, chipW, height);
            Render2D.fillRect(graphics, chipX, y, chipW, height, 0xFFB9B9C0);
            Render2D.popScissor(graphics);
        }
        Render2D.fillRoundedRect(graphics, chipX, y, chipW, height, r, color);
        int outline = ColorUtil.lerp(theme.outline, theme.accent, Math.max(hover * 0.5f, openT));
        Render2D.strokeRoundedRect(graphics, chipX, y, chipW, height, r, 1f + openT * 0.5f, outline);
    }

    @Override
    public void renderTop(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        float openT = openAnim.get();
        if (openT < 0.01f) {
            return;
        }
        Theme theme = theme();
        float px = popupX();
        float py = popupY();
        float pw = popupWidth();
        float ph = popupHeight();

        picker.setBounds(px + POPUP_PADDING, py + POPUP_PADDING, PICKER_WIDTH, PICKER_HEIGHT);

        Render2D.pushAlpha(openT);
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, (1f - openT) * -6f * (py > y ? 1f : -1f), 0);

        Render2D.dropShadow(graphics, px, py, pw, ph, theme.radius, 7f, theme.shadow);
        Render2D.fillRoundedRect(graphics, px, py, pw, ph, theme.radius, theme.surface);
        Render2D.strokeRoundedRect(graphics, px, py, pw, ph, theme.radius, 1f, theme.outline);

        picker.render(graphics, mouseX, mouseY, delta);

        pose.popPose();
        Render2D.popAlpha();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || button != 0 || !contains(mouseX, mouseY)) {
            return false;
        }
        setOpen(!open);
        return true;
    }

    @Override
    public boolean popupMouseClicked(double mouseX, double mouseY, int button) {
        if (!open) {
            return false;
        }
        float px = popupX();
        float py = popupY();
        if (mouseX >= px && mouseX < px + popupWidth() && mouseY >= py && mouseY < py + popupHeight()) {
            picker.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (contains(mouseX, mouseY)) {
            setOpen(false);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return open && picker.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return open && picker.mouseReleased(mouseX, mouseY, button);
    }
}
