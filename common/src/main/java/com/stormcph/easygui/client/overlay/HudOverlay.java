package com.stormcph.easygui.client.overlay;

import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

/**
 * An in-game HUD element rendered every frame while playing (not in a screen).
 * Register instances with {@link OverlayManager#register(HudOverlay)}.
 *
 * <p>Size is reported by {@link #getWidth()}/{@link #getHeight()}; position is derived
 * from the {@link Anchor} plus offsets, so overlays adapt to window resizes and GUI
 * scale changes automatically. Draw with {@code Render2D}/{@code Text2D} as usual.</p>
 */
@Environment(EnvType.CLIENT)
public abstract class HudOverlay {
    protected Anchor anchor = Anchor.TOP_LEFT;
    protected float offsetX = 6f;
    protected float offsetY = 6f;
    protected boolean visible = true;
    private String persistId;

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

    public Anchor getAnchor() {
        return anchor;
    }

    public Theme theme() {
        return Theme.getDefault();
    }

    public abstract float getWidth();

    public abstract float getHeight();

    /**
     * Renders the overlay. {@code x}/{@code y} are the resolved top-left corner
     * based on the anchor, offsets, and current GUI size.
     */
    public abstract void render(GuiGraphics graphics, float x, float y, float partialTick);
}
