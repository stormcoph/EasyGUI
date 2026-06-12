package com.stormcph.easygui.client.overlay;

import com.stormcph.easygui.client.widget.Panel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A {@link HudOverlay} that hosts a regular EasyGUI widget tree on the in-game HUD.
 *
 * <p>Build the tree under {@link #getRoot()} exactly as you would on a screen, but
 * position children in <em>local</em> coordinates — {@code (0, 0)} is the overlay's
 * content origin, and the anchor/offset system moves the whole tree as one block.
 * Since chart widgets (the {@code chart} package) are regular widgets, this is also
 * the way to put live charts on the HUD:</p>
 *
 * <pre>{@code
 * WidgetHostOverlay hud = new WidgetHostOverlay(120, 40);
 * hud.getRoot().add(new Sparkline().setSeries(Metrics.fps().series()))
 *         .setBounds(0, 0, 120, 40);
 * OverlayManager.register(hud);
 * }</pre>
 *
 * <p>The HUD is display-only: widgets render but receive no input, and the mouse is
 * reported far off-screen so no hover states ever fire. Outside a screen, widgets
 * automatically read {@link com.stormcph.easygui.client.theme.Theme#getDefault()},
 * so the overlay re-skins live with the rest of the UI.</p>
 */
@Environment(EnvType.CLIENT)
public class WidgetHostOverlay extends HudOverlay {
    /** Mouse position handed to the tree — far enough away that nothing ever hovers. */
    private static final double OFF_SCREEN_MOUSE = -1.0E7;

    private final Panel root = new Panel();
    private float width;
    private float height;

    public WidgetHostOverlay(float width, float height) {
        this.width = width;
        this.height = height;
        root.setBounds(0f, 0f, width, height);
    }

    /**
     * The root panel to add widgets to. Children use local coordinates: {@code (0, 0)}
     * is the overlay's content origin (inside any style padding).
     */
    public Panel getRoot() {
        return root;
    }

    /** Resizes the overlay's content box (and the root panel with it). */
    public WidgetHostOverlay setSize(float width, float height) {
        this.width = width;
        this.height = height;
        root.setSize(width, height);
        return this;
    }

    @Override
    public float getWidth() {
        return width;
    }

    @Override
    public float getHeight() {
        return height;
    }

    @Override
    public void render(GuiGraphics graphics, float x, float y, float partialTick) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0f);
        root.render(graphics, OFF_SCREEN_MOUSE, OFF_SCREEN_MOUSE, partialTick);
        graphics.pose().popPose();
    }
}
