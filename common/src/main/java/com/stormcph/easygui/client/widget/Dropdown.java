package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Icons;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * A dropdown/select control. The option list opens on the screen's popup layer with a
 * fade+slide animation, renders above everything else, and captures input until dismissed.
 */
@Environment(EnvType.CLIENT)
public class Dropdown extends Widget {
    private static final float OPTION_HEIGHT = 18f;
    private static final float POPUP_GAP = 4f;

    private final List<String> options;
    private int selected;
    private IntConsumer onSelect;
    private boolean open;
    /** Keyboard-highlighted row while the popup is open (-1 = none). */
    private int highlight = -1;

    private final SmoothValue openAnim = new SmoothValue(0f, 18f);

    public Dropdown(List<String> options, int initialIndex, IntConsumer onSelect) {
        this.options = options;
        this.selected = Math.max(0, Math.min(options.size() - 1, initialIndex));
        this.onSelect = onSelect;
        this.height = 20f;
    }

    public int getSelectedIndex() {
        return selected;
    }

    public String getSelectedOption() {
        return options.isEmpty() ? "" : options.get(selected);
    }

    public Dropdown setSelectedIndex(int index) {
        this.selected = Math.max(0, Math.min(options.size() - 1, index));
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float hover = hoverAmount();
        openAnim.setTarget(open ? 1f : 0f);
        float openT = openAnim.get();

        float r = theme.radiusSmall;
        int bg = ColorUtil.lerp(theme.surfaceVariant, theme.surfaceHover, Math.max(hover, openT));
        Render2D.fillRoundedRect(graphics, x, y, width, height, r, bg);
        int outlineColor = ColorUtil.lerp(theme.outline, theme.accent, openT);
        Render2D.strokeRoundedRect(graphics, x, y, width, height, r, 1f, outlineColor);

        String label = Text2D.truncate(getSelectedOption(), (int) (width - 26));
        Text2D.drawVerticallyCentered(graphics, label, x + 8, y, height, theme.text);

        // Chevron flips as the popup opens
        float iconSize = 10f;
        float cx = x + width - iconSize - 6;
        float cy = y + (height - iconSize) / 2f;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(cx + iconSize / 2f, cy + iconSize / 2f, 0);
        pose.scale(1f, 1f - openT * 2f, 1f);
        pose.translate(-(cx + iconSize / 2f), -(cy + iconSize / 2f), 0);
        Icons.CHEVRON_DOWN.render(graphics, cx, cy, iconSize, theme.textMuted);
        pose.popPose();

        // While open the control's own outline is already accent, so no extra ring
        if (focused && !open) {
            drawFocusRing(graphics, x, y, width, height, r);
        }
    }

    private float popupHeight() {
        return options.size() * OPTION_HEIGHT + 8;
    }

    /** Popup rect, flipped above the control when there is no room below. */
    private float popupY() {
        EasyScreen screen = getScreen();
        float below = y + height + POPUP_GAP;
        if (screen != null && below + popupHeight() > screen.height - 4 && y - POPUP_GAP - popupHeight() > 4) {
            return y - POPUP_GAP - popupHeight();
        }
        return below;
    }

    @Override
    public void renderTop(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        float openT = openAnim.get();
        if (openT < 0.01f) {
            return;
        }
        Theme theme = theme();
        float px = x;
        float py = popupY();
        float pw = width;
        float ph = popupHeight();

        Render2D.pushAlpha(openT);
        var pose = graphics.pose();
        pose.pushPose();
        // Slide in from the control
        pose.translate(0, (1f - openT) * -6f * (py > y ? 1f : -1f), 0);

        Render2D.dropShadow(graphics, px, py, pw, ph, theme.radiusSmall, 6f, theme.shadow);
        Render2D.fillRoundedRect(graphics, px, py, pw, ph, theme.radiusSmall, theme.surface);
        Render2D.strokeRoundedRect(graphics, px, py, pw, ph, theme.radiusSmall, 1f, theme.outline);

        for (int i = 0; i < options.size(); i++) {
            float oy = py + 4 + i * OPTION_HEIGHT;
            boolean hoveredOption = open && mouseX >= px && mouseX < px + pw
                    && mouseY >= oy && mouseY < oy + OPTION_HEIGHT;
            // The keyboard-highlighted row reads exactly like a hovered one
            if (hoveredOption || (open && i == highlight)) {
                Render2D.fillRoundedRect(graphics, px + 3, oy, pw - 6, OPTION_HEIGHT, 4f, theme.surfaceHover);
            }
            int color = i == selected ? theme.accent : theme.text;
            Text2D.drawVerticallyCentered(graphics, Text2D.truncate(options.get(i), (int) (pw - 30)),
                    px + 8, oy, OPTION_HEIGHT, color);
            if (i == selected) {
                Icons.CHECK.render(graphics, px + pw - 16, oy + (OPTION_HEIGHT - 10) / 2f, 10f, theme.accent);
            }
        }

        pose.popPose();
        Render2D.popAlpha();
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
        setOpen(!open);
        if (open) {
            // Take focus so the arrow keys steer the popup even after a mouse open
            requestFocus();
        }
        return true;
    }

    private void setOpen(boolean newOpen) {
        open = newOpen;
        highlight = open ? selected : -1;
        EasyScreen screen = getScreen();
        if (screen != null) {
            if (open) {
                screen.openPopup(this);
            } else {
                screen.closePopup(this);
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused || !enabled) {
            return false;
        }
        if (!open) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                    || keyCode == GLFW.GLFW_KEY_SPACE) {
                setOpen(true);
                return true;
            }
            return false;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> {
                if (!options.isEmpty()) {
                    highlight = Math.floorMod((highlight < 0 ? selected : highlight) - 1, options.size());
                }
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (!options.isEmpty()) {
                    highlight = Math.floorMod((highlight < 0 ? selected : highlight) + 1, options.size());
                }
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (highlight >= 0 && highlight < options.size()) {
                    selected = highlight;
                    if (onSelect != null) {
                        onSelect.accept(selected);
                    }
                }
                setOpen(false);
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                setOpen(false);
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                // Swallow Tab so focus can't wander away underneath the open popup
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean popupMouseClicked(double mouseX, double mouseY, int button) {
        if (!open) {
            return false;
        }
        float px = x;
        float py = popupY();
        if (mouseX >= px && mouseX < px + width && mouseY >= py && mouseY < py + popupHeight()) {
            int index = (int) ((mouseY - py - 4) / OPTION_HEIGHT);
            if (index >= 0 && index < options.size()) {
                selected = index;
                if (onSelect != null) {
                    onSelect.accept(index);
                }
            }
            setOpen(false);
            return true;
        }
        if (contains(mouseX, mouseY)) {
            setOpen(false);
            return true;
        }
        return false;
    }

    @Override
    public void dismissPopup() {
        open = false;
        highlight = -1;
    }
}
