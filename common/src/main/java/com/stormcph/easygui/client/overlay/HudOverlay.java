package com.stormcph.easygui.client.overlay;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.BooleanSupplier;

/**
 * An in-game HUD element rendered every frame while playing (not in a screen).
 * Register instances with {@link OverlayManager#register(HudOverlay)}.
 *
 * <p>Size is reported by {@link #getWidth()}/{@link #getHeight()}; position is derived
 * from the {@link Anchor} plus offsets, so overlays adapt to window resizes and GUI
 * scale changes automatically. Draw with {@code Render2D}/{@code Text2D} as usual.</p>
 *
 * <p>Every overlay also carries a {@link HudStyle} ({@link #getStyle()}) — scale,
 * opacity, padding, and an optional background plate drawn by the manager around the
 * content. The manager positions overlays by their <em>styled</em> footprint
 * ({@link #styledWidth()}/{@link #styledHeight()}) and enters through
 * {@link #renderStyled}, which finally calls the abstract {@link #render} with the
 * content origin, so subclasses keep drawing exactly as before. Visibility changes
 * (manual {@link #setVisible} or a {@link #setVisibleWhen conditional}) fade and slide
 * smoothly instead of cutting.</p>
 */
@Environment(EnvType.CLIENT)
public abstract class HudOverlay {
    private static final float FROST_BLUR_RADIUS = 5f;
    private static final float SHADOW_SIZE = 4f;
    private static final float SLIDE_DISTANCE = 4f;

    protected Anchor anchor = Anchor.TOP_LEFT;
    protected float offsetX = 6f;
    protected float offsetY = 6f;
    protected boolean visible = true;
    private String persistId;
    private HudStyle style = new HudStyle();
    private BooleanSupplier visibleWhen;
    private final SmoothValue showFade = new SmoothValue(1f, 12f);
    private boolean fadeInitialized;

    public HudOverlay setAnchor(Anchor anchor) {
        this.anchor = anchor;
        return this;
    }

    public HudOverlay setOffsets(float offsetX, float offsetY) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        return this;
    }

    public float getOffsetX() {
        return offsetX;
    }

    public float getOffsetY() {
        return offsetY;
    }

    /**
     * Opts this overlay into position persistence: with an id set <em>before registering</em>,
     * its anchor/offsets load from {@code config/easygui.json} and any change made in the
     * HUD editor (see {@link HudEditScreen}) is saved automatically.
     */
    public HudOverlay setPersistId(String persistId) {
        this.persistId = persistId;
        return this;
    }

    public String getPersistId() {
        return persistId;
    }

    public boolean isVisible() {
        return visible;
    }

    public HudOverlay setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /**
     * Conditional visibility: the overlay only shows while {@code condition} returns
     * {@code true} (evaluated every frame) <em>and</em> it is {@link #setVisible(boolean)
     * visible}. Transitions fade smoothly. Pass {@code null} to clear the condition.
     */
    public HudOverlay setVisibleWhen(BooleanSupplier condition) {
        this.visibleWhen = condition;
        return this;
    }

    /** Manual visibility combined with the {@link #setVisibleWhen(BooleanSupplier) condition}, if any. */
    public final boolean isEffectivelyVisible() {
        return visible && (visibleWhen == null || visibleWhen.getAsBoolean());
    }

    public Anchor getAnchor() {
        return anchor;
    }

    public Theme theme() {
        return Theme.getDefault();
    }

    // ------------------------------------------------------------------
    // Style
    // ------------------------------------------------------------------

    /** The live, mutable style — tweak it directly: {@code overlay.getStyle().setScale(1.5f)}. */
    public HudStyle getStyle() {
        return style;
    }

    /** Replaces the whole style instance (e.g. when applying a profile). Ignores {@code null}. */
    public HudOverlay setStyle(HudStyle style) {
        if (style != null) {
            this.style = style;
        }
        return this;
    }

    /**
     * The overlay's true on-screen width: content width plus style padding on both sides,
     * multiplied by the style scale. Anchor resolution, stacking, and editor hitboxes all
     * use this rather than {@link #getWidth()}.
     */
    public final float styledWidth() {
        return (getWidth() + style.getPadding() * 2f) * style.getScale();
    }

    /** Styled on-screen height; see {@link #styledWidth()}. */
    public final float styledHeight() {
        return (getHeight() + style.getPadding() * 2f) * style.getScale();
    }

    // ------------------------------------------------------------------
    // Show/hide fade
    // ------------------------------------------------------------------

    /**
     * Advances the show/hide fade toward the current {@link #isEffectivelyVisible()
     * effective visibility} and returns it (0 = fully hidden, 1 = fully shown). The
     * manager calls this once per frame and keeps rendering while the fade is above
     * ~0.01, which turns visibility changes into a fade + slide instead of a hard cut.
     * The very first call snaps to the current state so overlays never flash on startup.
     */
    public final float updateFade() {
        boolean shown = isEffectivelyVisible();
        if (!fadeInitialized) {
            fadeInitialized = true;
            showFade.setInstant(shown ? 1f : 0f);
        }
        showFade.setTarget(shown ? 1f : 0f);
        return showFade.get();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * The manager's render entry point. {@code x}/{@code y} are the resolved top-left of
     * the <em>styled</em> box ({@link #styledWidth()} × {@link #styledHeight()}). Applies
     * the show/hide fade (alpha plus a small slide toward the anchored edge), draws the
     * style background, pose-scales around the origin, then calls {@link #render} with
     * the content origin inside the padding.
     */
    public final void renderStyled(GuiGraphics graphics, float x, float y, float partialTick) {
        float fade = showFade.get();
        if (fade <= 0.01f) {
            return;
        }
        float slide = (1f - fade) * SLIDE_DISTANCE;
        if (slide > 0f) {
            x += anchor.horizontalAlign() * slide;
            y += anchor.isTop() ? -slide : anchor.isBottom() ? slide : 0f;
        }
        renderWithStyle(graphics, x, y, partialTick, fade * style.getOpacity());
    }

    /**
     * Full styled rendering with no show/hide fade or slide — used by the HUD editor,
     * which must show every overlay (even hidden ones, ghosted via
     * {@code Render2D.pushAlpha}) at its styled size and position.
     */
    public final void renderStyledForEditor(GuiGraphics graphics, float x, float y, float partialTick) {
        renderWithStyle(graphics, x, y, partialTick, style.getOpacity());
    }

    private void renderWithStyle(GuiGraphics graphics, float x, float y, float partialTick, float alpha) {
        if (alpha <= 0.004f) {
            return;
        }
        float scale = style.getScale();
        float padding = style.getPadding();
        boolean scaled = scale != 1f;
        Render2D.pushAlpha(alpha);
        if (scaled) {
            // Scale around the resolved origin: content drawn at (x + u, y + v) lands at
            // (x + scale*u, y + scale*v), so the styled box fills exactly styledWidth/Height.
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale(scale, scale, 1f);
            graphics.pose().translate(-x, -y, 0);
        }
        drawStyleBackground(graphics, x, y, getWidth() + padding * 2f, getHeight() + padding * 2f);
        render(graphics, x + padding, y + padding, partialTick);
        if (scaled) {
            graphics.pose().popPose();
        }
        Render2D.popAlpha();
    }

    /** Draws the style's plate (shadow, frosted or solid fill, outline) behind the padded content box. */
    private void drawStyleBackground(GuiGraphics graphics, float x, float y, float w, float h) {
        HudStyle.Background mode = style.getBackground();
        if (mode == HudStyle.Background.NONE && !style.isOutline() && !style.isShadow()) {
            return;
        }
        Theme theme = theme();
        float r = style.getRadius();
        if (style.isShadow()) {
            // Solid plates include the area under themselves; translucent/absent plates
            // get only the outer halo so the world isn't darkened through them.
            Render2D.dropShadow(graphics, x, y, w, h, r, SHADOW_SIZE,
                    ColorUtil.multiplyAlpha(theme.shadow, 0.7f), mode == HudStyle.Background.SOLID);
        }
        if (mode == HudStyle.Background.FROSTED) {
            if (!Render2D.fillRoundedRectBlurred(graphics, x, y, w, h, r, FROST_BLUR_RADIUS,
                    style.getBackgroundColor())) {
                Render2D.fillRoundedRect(graphics, x, y, w, h, r, style.getBackgroundColor());
            }
        } else if (mode == HudStyle.Background.SOLID) {
            Render2D.fillRoundedRect(graphics, x, y, w, h, r, style.getBackgroundColor());
        }
        if (style.isOutline()) {
            Render2D.strokeRoundedRect(graphics, x, y, w, h, r, 1f, theme.outline);
        }
    }

    public abstract float getWidth();

    public abstract float getHeight();

    /**
     * Renders the overlay content. {@code x}/{@code y} are the resolved top-left corner
     * of the content area (inside any style padding) based on the anchor, offsets, and
     * current GUI size.
     */
    public abstract void render(GuiGraphics graphics, float x, float y, float partialTick);
}
