package com.stormcph.easygui.client.overlay;

import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.widget.Button;
import com.stormcph.easygui.client.widget.Label;
import com.stormcph.easygui.client.widget.Panel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The HUD layout editor: every registered {@link HudOverlay} is shown in place (hidden
 * ones ghosted) and can be dragged around with snap guides, right-clicked to reset, or
 * nudged pixel-by-pixel with the arrow keys. Positions persist automatically for
 * overlays with a {@link HudOverlay#setPersistId persist id}.
 *
 * <p>Open it from anywhere — e.g. an "Edit HUD" button in your settings screen:</p>
 * <pre>{@code
 * card.add(new Button("Edit HUD", () -> minecraft.setScreen(new HudEditScreen(this))));
 * }</pre>
 */
@Environment(EnvType.CLIENT)
public class HudEditScreen extends EasyScreen {
    public HudEditScreen() {
        this(null);
    }

    /** @param parent screen to return to when the editor closes */
    public HudEditScreen(Screen parent) {
        super(Component.literal("HUD Editor"));
        setParentScreen(parent);
        setBackgroundBlur(false);
        setBackgroundDim(false);
    }

    @Override
    protected void build(Panel root) {
        float barW = 332;
        float barH = 44;
        float barX = (width - barW) / 2f;
        Panel bar = root.add(new Panel().setCard(true).setFrosted(true));
        bar.setBounds(barX, 8, barW, barH);
        bar.add(new Label("HUD editor").setScale(1.1f))
                .setBounds(barX + 14, 15, 100, 11);
        bar.add(new Label("Drag to move • Right-click to reset • Arrows to nudge").setMuted(true))
                .setBounds(barX + 14, 28, 250, 10);
        bar.add(new Button("Done", this::closeWithAnimation))
                .setBounds(barX + barW - 58, 17, 44, 18);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The edit canvas: all overlays at their real positions (manager rendering is
        // suspended while this screen is open), hidden ones ghosted so they're placeable.
        for (HudOverlay overlay : OverlayManager.overlays()) {
            float x = OverlayEditor.resolveX(overlay);
            float y = OverlayEditor.resolveY(overlay);
            if (!overlay.isVisible()) {
                Render2D.pushAlpha(0.35f);
                overlay.render(graphics, x, y, partialTick);
                Render2D.popAlpha();
            } else {
                overlay.render(graphics, x, y, partialTick);
            }
        }
        OverlayEditor.renderChrome(graphics, mouseX, mouseY, true);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return OverlayEditor.mouseClicked(mouseX, mouseY, button, true);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (OverlayEditor.mouseDragged(mouseX, mouseY, true)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean dragged = OverlayEditor.mouseReleased(button);
        return super.mouseReleased(mouseX, mouseY, button) || dragged;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        float step = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? 5f : 1f;
        boolean handled = switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> OverlayEditor.nudge(-step, 0);
            case GLFW.GLFW_KEY_RIGHT -> OverlayEditor.nudge(step, 0);
            case GLFW.GLFW_KEY_UP -> OverlayEditor.nudge(0, -step);
            case GLFW.GLFW_KEY_DOWN -> OverlayEditor.nudge(0, step);
            default -> false;
        };
        return handled || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        OverlayEditor.finishDrag();
        super.removed();
    }
}
