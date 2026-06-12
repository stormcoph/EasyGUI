package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * A container widget. Optionally draws a rounded "card" background with a drop shadow
 * and hairline outline. Children are rendered in add-order; input is routed in reverse
 * (topmost first).
 */
@Environment(EnvType.CLIENT)
public class Panel extends Widget {
    protected final List<Widget> children = new ArrayList<>();

    private boolean card;
    private int backgroundColor;
    private boolean hasCustomBackground;
    private float radius = -1f;
    private boolean shadow;
    private boolean outline;

    /** Makes this panel draw a themed card background (surface color, shadow, outline). */
    public Panel setCard(boolean card) {
        this.card = card;
        this.shadow = card;
        this.outline = card;
        return this;
    }

    public Panel setBackgroundColor(int color) {
        this.backgroundColor = color;
        this.hasCustomBackground = true;
        this.card = true;
        return this;
    }

    public Panel setRadius(float radius) {
        this.radius = radius;
        return this;
    }

    public Panel setShadow(boolean shadow) {
        this.shadow = shadow;
        return this;
    }

    public Panel setOutline(boolean outline) {
        this.outline = outline;
        return this;
    }

    // ------------------------------------------------------------------
    // Children
    // ------------------------------------------------------------------

    public <T extends Widget> T add(T child) {
        child.parent = this;
        children.add(child);
        return child;
    }

    public void remove(Widget child) {
        if (children.remove(child)) {
            child.parent = null;
        }
    }

    public void clearChildren() {
        for (Widget child : children) {
            child.parent = null;
        }
        children.clear();
    }

    public List<Widget> getChildren() {
        return children;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        drawBackground(graphics);
        for (Widget child : children) {
            child.render(graphics, mouseX, mouseY, delta);
        }
    }

    /** The corner radius the background card is drawn with (0 when there is no card background). */
    public float backgroundRadius() {
        if (!card) {
            return 0f;
        }
        return radius >= 0 ? radius : theme().radius;
    }

    protected void drawBackground(GuiGraphics graphics) {
        if (!card) {
            return;
        }
        Theme theme = theme();
        float r = backgroundRadius();
        int bg = hasCustomBackground ? backgroundColor : theme.surface;
        if (shadow) {
            Render2D.dropShadow(graphics, x, y, width, height, r, theme.shadowSize, theme.shadow);
        }
        Render2D.fillRoundedRect(graphics, x, y, width, height, r, bg);
        if (outline) {
            Render2D.strokeRoundedRect(graphics, x, y, width, height, r, 1f, theme.outline);
        }
    }

    @Override
    public void renderTop(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        for (Widget child : children) {
            if (child.isVisible()) {
                child.renderTop(graphics, mouseX, mouseY, delta);
            }
        }
    }

    // ------------------------------------------------------------------
    // Input routing
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (child.isVisible() && child.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean consumed = false;
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (child.isVisible() && child.mouseReleased(mouseX, mouseY, button)) {
                consumed = true;
            }
        }
        return consumed;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (child.isVisible() && child.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (child.isVisible() && child.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
                return true;
            }
        }
        return false;
    }
}
