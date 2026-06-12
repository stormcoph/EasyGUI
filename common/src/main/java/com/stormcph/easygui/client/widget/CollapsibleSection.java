package com.stormcph.easygui.client.widget;

import com.mojang.math.Axis;
import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.config.ConfigValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Icons;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;

/**
 * A collapsible section ("accordion") for grouping settings under a clickable header.
 *
 * <p>The header row shows the section title and a chevron that rotates smoothly between
 * pointing right (collapsed) and down (expanded); clicking it toggles the section.
 * Widgets {@link #add added} after construction belong to the content region below the
 * header and use absolute GUI coordinates like everywhere else in EasyGUI — lay them out
 * starting at {@code section.getY() + headerHeight}.</p>
 *
 * <p><strong>Animated height contract:</strong> {@link #getHeight()} reports
 * {@code headerHeight + t * contentHeight()}, where {@code t} is the 0..1 expansion
 * {@link SmoothValue} and {@link #contentHeight()} is derived from the children's bounds
 * (max child bottom − content top + a small bottom pad). Because the reported height
 * glides every frame while toggling, a parent layout container (e.g. a LinearLayout that
 * re-positions its children from {@code getHeight()} each frame) reflows the siblings
 * below this section smoothly for free. While partially collapsed the content is
 * scissored under the header and faded with a global-alpha push, so children clip and
 * dim instead of popping.</p>
 *
 * <p>Moving the section (via {@link #setPosition} / {@link #setBounds}) shifts its direct
 * children by the same delta, so a layout container can re-position the whole section
 * without breaking the content's absolute coordinates. Content children receive input
 * only while the section is (effectively) expanded; the header always responds. Collapsing
 * also releases keyboard focus held by any descendant, since the screen routes key events
 * to the focused widget directly.</p>
 */
@Environment(EnvType.CLIENT)
public class CollapsibleSection extends Panel {
    private static final float CONTENT_BOTTOM_PAD = 6f;
    private static final float ICON_SIZE = 10f;

    private String title;
    private float headerHeight = 20f;
    private boolean expanded;
    private Consumer<Boolean> onToggle;
    private ConfigValue<Boolean> persistence;

    private final SmoothValue expandAnim;
    private final SmoothValue headerHover = new SmoothValue(0f, 14f);

    public CollapsibleSection(String title) {
        this(title, true);
    }

    public CollapsibleSection(String title, boolean initiallyExpanded) {
        this.title = title;
        this.expanded = initiallyExpanded;
        this.expandAnim = new SmoothValue(initiallyExpanded ? 1f : 0f, 14f);
        this.height = headerHeight;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    public String getTitle() {
        return title;
    }

    public CollapsibleSection setTitle(String title) {
        this.title = title;
        return this;
    }

    public float getHeaderHeight() {
        return headerHeight;
    }

    public CollapsibleSection setHeaderHeight(float headerHeight) {
        this.headerHeight = headerHeight;
        return this;
    }

    public boolean isExpanded() {
        return expanded;
    }

    /**
     * Expands or collapses the section programmatically (animated). Writes through to a
     * bound {@link #persistTo config value} but does <em>not</em> fire the
     * {@link #setOnToggle toggle callback} — that fires only on user clicks.
     */
    public CollapsibleSection setExpanded(boolean expanded) {
        if (this.expanded == expanded) {
            return this;
        }
        this.expanded = expanded;
        expandAnim.setTarget(expanded ? 1f : 0f);
        if (!expanded) {
            releaseFocusInContent();
        }
        if (persistence != null) {
            persistence.set(expanded);
        }
        return this;
    }

    /** Called with the new expanded state whenever the user toggles the header. */
    public CollapsibleSection setOnToggle(Consumer<Boolean> onToggle) {
        this.onToggle = onToggle;
        return this;
    }

    /**
     * Binds the expanded state to a config value: the current value is adopted immediately
     * (without animation), and every toggle writes back, so the section reopens the way the
     * user left it.
     */
    public CollapsibleSection persistTo(ConfigValue<Boolean> value) {
        this.persistence = value;
        if (value != null) {
            this.expanded = Boolean.TRUE.equals(value.get());
            expandAnim.setInstant(expanded ? 1f : 0f);
        }
        return this;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /** Top edge of the content region (just below the header). */
    public float contentTop() {
        return y + headerHeight;
    }

    /** Full (expanded) height of the content region, derived from the children's bounds. */
    public float contentHeight() {
        float top = contentTop();
        float maxBottom = top;
        for (Widget child : children) {
            maxBottom = Math.max(maxBottom, child.getY() + child.getHeight());
        }
        return maxBottom > top ? maxBottom - top + CONTENT_BOTTOM_PAD : 0f;
    }

    /**
     * The animated height: {@code headerHeight + t * contentHeight()}. Parent layouts that
     * re-position children from this value each frame get smooth sibling reflow while the
     * section opens or closes.
     */
    @Override
    public float getHeight() {
        return headerHeight + expandAnim.get() * contentHeight();
    }

    @Override
    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + getHeight();
    }

    @Override
    public Widget setPosition(float x, float y) {
        moveChildren(x - this.x, y - this.y);
        return super.setPosition(x, y);
    }

    @Override
    public Widget setBounds(float x, float y, float width, float height) {
        moveChildren(x - this.x, y - this.y);
        return super.setBounds(x, y, width, height);
    }

    /** Keeps the absolutely-positioned content attached to the header when the section moves. */
    private void moveChildren(float dx, float dy) {
        if (dx == 0f && dy == 0f) {
            return;
        }
        for (Widget child : children) {
            child.setPosition(child.getX() + dx, child.getY() + dy);
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        expandAnim.setTarget(expanded ? 1f : 0f);
        float t = expandAnim.get();
        float ch = contentHeight();
        // Keep the inherited field in sync so the optional Panel card background and any
        // direct field readers see the animated height.
        this.height = headerHeight + t * ch;

        drawBackground(graphics);

        // Header: subtle rounded hover wash, title, rotating chevron
        boolean overHeader = enabled && headerContains(mouseX, mouseY);
        headerHover.setTarget(overHeader ? 1f : 0f);
        float hh = headerHover.get();
        if (hh > 0.01f) {
            Render2D.fillRoundedRect(graphics, x, y, width, headerHeight, theme.radiusSmall,
                    ColorUtil.multiplyAlpha(theme.surfaceHover, hh * 0.7f));
        }

        int titleColor = enabled ? theme.text : ColorUtil.multiplyAlpha(theme.text, 0.45f);
        int chevronColor = ColorUtil.lerp(theme.textMuted, theme.text, hh);
        if (!enabled) {
            chevronColor = ColorUtil.multiplyAlpha(theme.textMuted, 0.45f);
        }
        Text2D.drawVerticallyCentered(graphics, Text2D.truncate(title, (int) (width - ICON_SIZE - 20)),
                x + 8, y, headerHeight, titleColor);

        // Chevron rotates from pointing right (collapsed) to down (expanded)
        float ix = x + width - ICON_SIZE - 6f;
        float iy = y + (headerHeight - ICON_SIZE) / 2f;
        float cx = ix + ICON_SIZE / 2f;
        float cy = iy + ICON_SIZE / 2f;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(-90f * (1f - t)));
        pose.translate(-cx, -cy, 0);
        Icons.CHEVRON_DOWN.render(graphics, ix, iy, ICON_SIZE, chevronColor);
        pose.popPose();

        // Content, clipped under the header and faded while partially collapsed
        if (t <= 0.004f || ch <= 0f) {
            return;
        }
        float top = contentTop();
        float visible = t * ch;
        boolean interactive = contentInteractive();
        boolean mouseInContent = interactive
                && mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < top + visible;
        // Off-screen cursor suppresses hover states/tooltips on clipped or inert content.
        double cmx = mouseInContent ? mouseX : -1.0E7;
        double cmy = mouseInContent ? mouseY : -1.0E7;

        Render2D.pushScissor(graphics, x, top, width, visible);
        Render2D.pushAlpha(t);
        for (Widget child : children) {
            child.render(graphics, cmx, cmy, delta);
        }
        Render2D.popAlpha();
        Render2D.popScissor(graphics);
    }

    @Override
    public void renderTop(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        // No popup layers (dropdown lists, etc.) from a collapsed section
        if (expandAnim.get() > 0.01f) {
            super.renderTop(graphics, mouseX, mouseY, delta);
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private boolean headerContains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + headerHeight;
    }

    /** Content accepts input only when fully (or nearly fully) expanded. */
    private boolean contentInteractive() {
        return expanded || expandAnim.get() > 0.95f;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled) {
            return false;
        }
        if (button == 0 && headerContains(mouseX, mouseY)) {
            setExpanded(!expanded);
            if (onToggle != null) {
                onToggle.accept(expanded);
            }
            return true;
        }
        return contentInteractive() && super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return contentInteractive() && super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return contentInteractive() && super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return contentInteractive() && super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * If the screen's focused widget lives inside this section, drop the focus — the screen
     * routes key/char events straight to the focused widget, so a hidden child would
     * otherwise keep typing.
     */
    private void releaseFocusInContent() {
        EasyScreen s = getScreen();
        if (s == null) {
            return;
        }
        for (Widget w = s.getFocusedWidget(); w != null; w = w.getParent()) {
            if (w == this) {
                s.setFocusedWidget(null);
                return;
            }
        }
    }
}
