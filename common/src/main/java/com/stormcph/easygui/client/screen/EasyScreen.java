package com.stormcph.easygui.client.screen;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import com.stormcph.easygui.client.widget.Panel;
import com.stormcph.easygui.client.widget.Widget;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * Base class for EasyGUI screens.
 *
 * <p>Build your UI in {@link #build(Panel)} by adding widgets to the root panel.
 * The screen handles open/close fade+scale animations, the background dim (and vanilla's
 * menu blur when in a world), focus management, popup routing (e.g. dropdown lists),
 * and tooltip rendering.</p>
 *
 * <pre>{@code
 * public class MyScreen extends EasyScreen {
 *     public MyScreen() { super(Component.literal("My Screen")); }
 *
 *     @Override
 *     protected void build(Panel root) {
 *         Panel card = root.add(new Panel().setCard(true));
 *         card.setBounds(width / 2f - 100, height / 2f - 60, 200, 120);
 *         card.add(new Button("Hello", () -> {}))
 *             .setBounds(card.getX() + 16, card.getY() + 16, 100, 22);
 *     }
 * }
 * }</pre>
 */
@Environment(EnvType.CLIENT)
public abstract class EasyScreen extends Screen {
    private final Panel root = new Panel();
    private final SmoothValue openAnim = new SmoothValue(0f, 11f);

    private Theme theme = Theme.getDefault();
    private Screen parentScreen;
    private Widget focusedWidget;
    private Widget popupWidget;
    private boolean closing;
    private boolean backgroundBlur = true;

    private String tooltipText;
    private float tooltipX;
    private float tooltipY;

    protected EasyScreen(Component title) {
        super(title);
    }

    /** Screen to return to when this one closes. */
    public EasyScreen setParentScreen(Screen parent) {
        this.parentScreen = parent;
        return this;
    }

    public Theme getTheme() {
        return theme;
    }

    public EasyScreen setTheme(Theme theme) {
        this.theme = theme;
        return this;
    }

    /** Whether to apply vanilla's menu blur to the world behind this screen. */
    public EasyScreen setBackgroundBlur(boolean blur) {
        this.backgroundBlur = blur;
        return this;
    }

    public Panel getRoot() {
        return root;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        root.bindScreen(this);
        root.setBounds(0, 0, width, height);
        root.clearChildren();
        focusedWidget = null;
        popupWidget = null;
        openAnim.setTarget(1f);
        build(root);
    }

    /** Populate {@code root} with widgets. Called on open and on every resize. */
    protected abstract void build(Panel root);

    /** Plays the close animation, then returns to the parent screen. */
    public void closeWithAnimation() {
        closing = true;
        openAnim.setTarget(0f);
    }

    @Override
    public void onClose() {
        if (!closing) {
            closeWithAnimation();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float open = openAnim.get();
        if (closing && open < 0.02f) {
            if (minecraft != null) {
                minecraft.setScreen(parentScreen);
            }
            return;
        }

        if (backgroundBlur && minecraft != null && minecraft.level != null) {
            renderBlurredBackground(partialTick);
        }
        graphics.fill(0, 0, width, height,
                ColorUtil.multiplyAlpha(theme.screenDim, open));

        // Pop-in: subtle scale from the screen center plus a global alpha fade
        float scale = 0.94f + 0.06f * open;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(width / 2f * (1 - scale), height / 2f * (1 - scale), 0);
        pose.scale(scale, scale, 1f);
        Render2D.pushAlpha(open);

        tooltipText = null;
        boolean popupOpen = popupWidget != null;
        // While a popup layer is open, the main tree gets an off-screen cursor so nothing
        // behind the popup shows hover states or tooltips.
        double mx = popupOpen ? -1.0E7 : mouseX;
        double my = popupOpen ? -1.0E7 : mouseY;
        root.render(graphics, mx, my, partialTick);
        root.renderTop(graphics, mouseX, mouseY, partialTick);

        renderTooltip(graphics);

        Render2D.popAlpha();
        pose.popPose();
    }

    private void renderTooltip(GuiGraphics graphics) {
        if (tooltipText == null) {
            return;
        }
        float padX = 7f;
        float padY = 5f;
        float w = Text2D.width(tooltipText) + padX * 2;
        float h = Text2D.lineHeight() + padY * 2;
        float tx = Math.min(tooltipX + 10, width - w - 4);
        float ty = Math.min(tooltipY - h - 6, height - h - 4);
        if (ty < 4) {
            ty = tooltipY + 14;
        }
        Render2D.dropShadow(graphics, tx, ty, w, h, 5f, 5f, theme.shadow);
        Render2D.fillRoundedRect(graphics, tx, ty, w, h, 5f, theme.tooltipBackground);
        Render2D.strokeRoundedRect(graphics, tx, ty, w, h, 5f, 1f, theme.outline);
        Text2D.draw(graphics, tooltipText, tx + padX, ty + padY + 0.5f, theme.text);
    }

    /** Widgets call this during render to show a tooltip this frame. */
    public void requestTooltip(String text, float mouseX, float mouseY) {
        this.tooltipText = text;
        this.tooltipX = mouseX;
        this.tooltipY = mouseY;
    }

    // ------------------------------------------------------------------
    // Focus & popups
    // ------------------------------------------------------------------

    public void setFocusedWidget(Widget widget) {
        if (focusedWidget == widget) {
            return;
        }
        if (focusedWidget != null) {
            focusedWidget.setFocused(false);
        }
        focusedWidget = widget;
        if (widget != null) {
            widget.setFocused(true);
        }
    }

    public Widget getFocusedWidget() {
        return focusedWidget;
    }

    /** Gives {@code widget} the popup layer (rendered on top, receives input first). */
    public void openPopup(Widget widget) {
        popupWidget = widget;
    }

    public void closePopup(Widget widget) {
        if (popupWidget == widget) {
            popupWidget = null;
        }
    }

    public boolean isPopupOpen() {
        return popupWidget != null;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closing) {
            return false;
        }
        if (popupWidget != null) {
            if (popupWidget.popupMouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            popupWidget.dismissPopup();
            popupWidget = null;
            return true;
        }
        if (root.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        setFocusedWidget(null);
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return root.mouseReleased(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return root.mouseDragged(mouseX, mouseY, button, dragX, dragY)
                || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (popupWidget != null && popupWidget.popupMouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        return root.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (focusedWidget != null && focusedWidget.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (popupWidget != null) {
                popupWidget.dismissPopup();
                popupWidget = null;
                return true;
            }
            if (focusedWidget != null) {
                setFocusedWidget(null);
                return true;
            }
            closeWithAnimation();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (focusedWidget != null && focusedWidget.charTyped(chr, modifiers)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }
}
