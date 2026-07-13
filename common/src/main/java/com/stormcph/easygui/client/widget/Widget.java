package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Base class for all EasyGUI widgets.
 *
 * <p>Widgets use absolute GUI coordinates and form a tree under an {@link EasyScreen}'s
 * root {@link Panel}. Mouse/keyboard handlers return {@code true} when the event was
 * consumed. Every widget gets a built-in smoothed hover animation
 * ({@link #hoverAmount()}), which most subclasses use to blend colors.</p>
 */
@Environment(EnvType.CLIENT)
public abstract class Widget {
    protected float x;
    protected float y;
    protected float width;
    protected float height;

    Panel parent;
    EasyScreen screen;

    protected boolean visible = true;
    protected boolean enabled = true;
    protected boolean focused;
    protected String tooltip;
    protected String id;

    private final SmoothValue hoverAnim = new SmoothValue(0f, 14f);
    private boolean hovered;

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    public Widget setBounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        return this;
    }

    public Widget setPosition(float x, float y) {
        this.x = x;
        this.y = y;
        return this;
    }

    public Widget setSize(float width, float height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    public boolean isVisible() {
        return visible;
    }

    public Widget setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Widget setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public Widget setTooltip(String tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    /**
     * Optional stable identifier for tooling: widget-tree dumps include it, and automation
     * scripts can target the widget with {@code click #id}. Not used by rendering.
     */
    public Widget setId(String id) {
        this.id = id;
        return this;
    }

    public String getId() {
        return id;
    }

    public boolean isHovered() {
        return hovered;
    }

    /** Smoothed 0..1 hover progress, for color/size blending. */
    public float hoverAmount() {
        return hoverAnim.get();
    }

    public boolean isFocused() {
        return focused;
    }

    /**
     * Whether Tab traversal can land on this widget. Defaults to {@code false};
     * interactive widgets opt in so keyboard users can reach and activate them.
     */
    public boolean isFocusable() {
        return false;
    }

    public void requestFocus() {
        EasyScreen s = getScreen();
        if (s != null) {
            s.setFocusedWidget(this);
        }
    }

    /** Called by the screen when focus is gained or lost. */
    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    public Panel getParent() {
        return parent;
    }

    public EasyScreen getScreen() {
        if (screen != null) {
            return screen;
        }
        return parent != null ? parent.getScreen() : null;
    }

    /** Internal: binds a root panel directly to its screen. Called by {@link EasyScreen}. */
    public void bindScreen(EasyScreen screen) {
        this.screen = screen;
    }

    /** The active theme (the screen's theme, or the global default outside a screen). */
    public Theme theme() {
        EasyScreen s = getScreen();
        return s != null ? s.getTheme() : Theme.getDefault();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** Renders this widget. Mouse coordinates may be far off-screen when input is captured elsewhere. */
    public final void render(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        if (!visible) {
            return;
        }
        hovered = enabled && contains(mouseX, mouseY);
        hoverAnim.setTarget(hovered ? 1f : 0f);
        renderWidget(graphics, mouseX, mouseY, delta);
        if (hovered && tooltip != null) {
            EasyScreen s = getScreen();
            if (s != null) {
                s.requestTooltip(tooltip, (float) mouseX, (float) mouseY);
            }
        }
    }

    protected abstract void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta);

    /**
     * Strokes the shared keyboard-focus indicator: a subtle accent outline 2px outside the
     * given rect. Focusable widgets call this from their render pass while focused. Inputs
     * whose own outline already turns accent on focus (e.g. {@link TextField}) skip it so
     * they don't show a double ring.
     */
    protected void drawFocusRing(GuiGraphics graphics, float x, float y, float w, float h, float radius) {
        Render2D.strokeRoundedRect(graphics, x - 2f, y - 2f, w + 4f, h + 4f, radius + 2f, 1.5f,
                ColorUtil.multiplyAlpha(theme().accent, 0.8f));
    }

    /**
     * Second render pass, drawn after the whole main tree (for popups, dropdown lists, etc.).
     * Always receives the real mouse position.
     */
    public void renderTop(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
    }

    // ------------------------------------------------------------------
    // Input (return true to consume)
    // ------------------------------------------------------------------

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        return false;
    }

    // ------------------------------------------------------------------
    // Popup support (used by widgets like Dropdown that capture input on a top layer)
    // ------------------------------------------------------------------

    /** Mouse click routed to this widget while it owns the screen's popup layer. */
    public boolean popupMouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean popupMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return false;
    }

    /** Called when a click lands outside the popup or Escape is pressed; close the popup here. */
    public void dismissPopup() {
    }
}
