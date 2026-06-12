package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * A vertically scrolling container with smooth (eased) wheel scrolling and a slim
 * auto-fading scrollbar with a draggable thumb.
 *
 * <p>Lay out children as if the panel showed everything from its top edge. While
 * scrolling, the panel physically shifts the children's positions (rather than just
 * translating the pose), so hover states, tooltips, and popup layers (e.g. a
 * {@link Dropdown} inside the list) stay aligned with what's on screen.</p>
 */
@Environment(EnvType.CLIENT)
public class ScrollPanel extends Panel {
    private static final float BAR_WIDTH = 3f;
    private static final float BAR_MARGIN = 2f;

    private final SmoothValue scroll = new SmoothValue(0f, 13f);
    private final SmoothValue barFade = new SmoothValue(0f, 8f);
    private float scrollStep = 24f;
    private float contentPadding = 6f;
    private float appliedScroll;
    private boolean draggingThumb;
    private double dragStartY;
    private float dragStartScroll;

    public ScrollPanel setScrollStep(float step) {
        this.scrollStep = step;
        return this;
    }

    public ScrollPanel setContentPadding(float padding) {
        this.contentPadding = padding;
        return this;
    }

    /** Total height of the laid-out content, in unscrolled space. */
    public float contentHeight() {
        float maxBottom = y;
        for (Widget child : children) {
            maxBottom = Math.max(maxBottom, child.getY() + child.getHeight());
        }
        return maxBottom + appliedScroll - y + contentPadding;
    }

    public float maxScroll() {
        return Math.max(0, contentHeight() - height);
    }

    public float getScrollAmount() {
        return scroll.get();
    }

    public void scrollTo(float target) {
        scroll.setTarget(Mth.clamp(target, 0, maxScroll()));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        drawBackground(graphics);

        float scrollAmount = Mth.clamp(scroll.get(), 0, maxScroll());
        applyScroll(scrollAmount);
        boolean mouseInside = contains(mouseX, mouseY);
        barFade.setTarget(mouseInside || draggingThumb || !scroll.isSettled(0.5f) ? 1f : 0f);

        Render2D.pushScissor(graphics, x, y, width, height);
        // Suppress hover entirely while the cursor is outside the clip area.
        double childMouseX = mouseInside ? mouseX : -1.0E7;
        double childMouseY = mouseInside ? mouseY : -1.0E7;
        for (Widget child : children) {
            // Skip children fully outside the visible window
            if (child.getY() + child.getHeight() < y - 5 || child.getY() > y + height + 5) {
                continue;
            }
            child.render(graphics, childMouseX, childMouseY, delta);
        }
        Render2D.popScissor(graphics);

        drawScrollbar(graphics, scrollAmount);
    }

    /** Shifts children so their on-screen positions reflect the current scroll amount. */
    private void applyScroll(float scrollAmount) {
        float delta = scrollAmount - appliedScroll;
        if (delta == 0f) {
            return;
        }
        for (Widget child : children) {
            child.setPosition(child.getX(), child.getY() - delta);
        }
        appliedScroll = scrollAmount;
    }

    /**
     * How far the scrollbar track is inset from the panel's top/bottom edges so the bar
     * never pokes outside the rounded background. Derived from the corner radius and the
     * bar's horizontal position within the corner curve.
     */
    private float trackInset() {
        float r = backgroundRadius();
        float dx = r - BAR_MARGIN;
        if (r <= 0 || dx <= 0) {
            return 2f;
        }
        float intrusion = r - (float) Math.sqrt(Math.max(0f, r * r - dx * dx));
        return Math.max(2f, intrusion + 1f);
    }

    private float trackHeight() {
        return height - trackInset() * 2;
    }

    private float thumbHeight() {
        float track = trackHeight();
        return Math.min(track, Math.max(16f, track * (height / contentHeight())));
    }

    private void drawScrollbar(GuiGraphics graphics, float scrollAmount) {
        float max = maxScroll();
        float fade = barFade.get();
        if (max <= 0 || fade < 0.02f) {
            return;
        }
        Theme theme = theme();
        float trackX = x + width - BAR_WIDTH - BAR_MARGIN;
        float trackY = y + trackInset();
        float track = trackHeight();
        float thumb = thumbHeight();
        float thumbY = trackY + (track - thumb) * (scrollAmount / max);

        Render2D.fillRoundedRect(graphics, trackX, trackY, BAR_WIDTH, track, BAR_WIDTH / 2f,
                ColorUtil.multiplyAlpha(theme.outline, fade * 0.6f));
        Render2D.fillRoundedRect(graphics, trackX, thumbY, BAR_WIDTH, thumb, BAR_WIDTH / 2f,
                ColorUtil.multiplyAlpha(theme.textMuted, fade * 0.9f));
    }

    private boolean overScrollbar(double mouseX, double mouseY) {
        return maxScroll() > 0
                && mouseX >= x + width - BAR_WIDTH - BAR_MARGIN * 2 && mouseX <= x + width
                && mouseY >= y && mouseY <= y + height;
    }

    // ------------------------------------------------------------------
    // Input (children sit at their real on-screen positions; no coordinate shifting)
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        if (button == 0 && overScrollbar(mouseX, mouseY)) {
            draggingThumb = true;
            dragStartY = mouseY;
            dragStartScroll = Mth.clamp(scroll.get(), 0, maxScroll());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingThumb && button == 0) {
            float trackRange = trackHeight() - thumbHeight();
            if (trackRange > 0) {
                float scrollPerPixel = maxScroll() / trackRange;
                float target = dragStartScroll + (float) (mouseY - dragStartY) * scrollPerPixel;
                scroll.setInstant(Mth.clamp(target, 0, maxScroll()));
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean wasDragging = draggingThumb;
        if (button == 0) {
            draggingThumb = false;
        }
        return super.mouseReleased(mouseX, mouseY, button) || wasDragging;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        if (maxScroll() <= 0) {
            return false;
        }
        scroll.setTarget(Mth.clamp(scroll.getTarget() - (float) scrollY * scrollStep, 0, maxScroll()));
        return true;
    }
}
