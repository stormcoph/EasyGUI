package com.stormcph.easygui.client.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.opengl.GL11;

import java.util.function.Supplier;

/**
 * Displays a living entity — by default the local player — like the model in the
 * vanilla inventory screen.
 *
 * <p>The entity is supplied live each frame, scales with the widget height (an entity is
 * about 1.8 blocks tall, so the render scale is roughly {@code height / 2.4}) and is
 * clipped to the widget bounds by the vanilla helper's scissor. With
 * {@link #setFollowMouse(boolean) follow-mouse} on (the default) the entity tracks the
 * cursor like in the inventory; off, it holds a fixed pleasant three-quarter pose. When
 * no entity is available (e.g. on the title screen) a muted placeholder silhouette and
 * message are drawn instead. An optional card background frames the model.</p>
 *
 * <p>Entity rendering writes GUI depth well above the z=0 plane the rest of EasyGUI
 * draws on; after drawing, the widget rewrites the depth under its bounds back to z=0
 * so overlapping tooltips/popups composite normally.</p>
 */
@Environment(EnvType.CLIENT)
public class PlayerView extends Widget {
    /** Vertical centering tweak used by the vanilla inventory screen. */
    private static final float Y_OFFSET = 0.0625f;

    private Supplier<LivingEntity> entity = () -> Minecraft.getInstance().player;
    private boolean followMouse = true;
    private boolean cardBackground;

    /** Shows the local player (null-safe; a placeholder is drawn while there is none). */
    public PlayerView() {
        this.width = 60f;
        this.height = 80f;
    }

    /** Shows a custom entity, re-queried every frame. */
    public PlayerView(Supplier<LivingEntity> entity) {
        this();
        this.entity = entity;
    }

    /** Replaces the entity supplier ({@code null} suppliers/results fall back to the placeholder). */
    public PlayerView setEntity(Supplier<LivingEntity> entity) {
        this.entity = entity;
        return this;
    }

    /**
     * Whether the entity turns to look at the cursor (default {@code true}). When off it
     * holds a fixed three-quarter angle: body yaw ≈ 19°, head yaw ≈ 38°, pitch ≈ 12° up.
     */
    public PlayerView setFollowMouse(boolean followMouse) {
        this.followMouse = followMouse;
        return this;
    }

    /** Draws a rounded card background (surface fill + hairline outline) behind the model. */
    public PlayerView setCardBackground(boolean cardBackground) {
        this.cardBackground = cardBackground;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        if (cardBackground) {
            float r = theme.radius;
            Render2D.fillRoundedRect(graphics, x, y, width, height, r,
                    ColorUtil.lerp(theme.surfaceVariant, theme.surfaceHover, hoverAmount() * 0.4f));
            Render2D.strokeRoundedRect(graphics, x, y, width, height, r, 1f, theme.outline);
        }
        if (width <= 0f || height <= 0f) {
            return;
        }

        LivingEntity target = entity != null ? entity.get() : null;
        if (target == null) {
            drawPlaceholder(graphics, theme);
            return;
        }

        float cx = x + width / 2f;
        float cy = y + height / 2f;
        float lookX;
        float lookY;
        if (followMouse) {
            // Clamp so an off-screen cursor (popup open, input captured) can't snap the
            // model to an extreme angle.
            lookX = Mth.clamp((float) mouseX, cx - 160f, cx + 160f);
            lookY = Mth.clamp((float) mouseY, cy - 160f, cy + 160f);
        } else {
            // Fixed fake cursor up-left of center: the vanilla helper turns this into the
            // three-quarter pose documented on setFollowMouse.
            lookX = cx - 55f;
            lookY = cy - 28f;
        }

        int scale = Math.max(1, Math.round(height / 2.4f));
        InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,
                Math.round(x), Math.round(y), Math.round(x + width), Math.round(y + height),
                scale, Y_OFFSET, lookX, lookY, target);

        resetDepth(graphics, x, y, width, height);
    }

    /** Muted head-and-shoulders silhouette with a hint, shown when no entity is available. */
    private void drawPlaceholder(GuiGraphics graphics, Theme theme) {
        float cx = x + width / 2f;
        int silhouette = ColorUtil.multiplyAlpha(theme.textMuted, 0.35f);
        float headRadius = Math.min(width, height) * 0.11f;
        float headCy = y + height * 0.34f;
        Render2D.fillCircle(graphics, cx, headCy, headRadius, silhouette);
        float shouldersWidth = headRadius * 3.6f;
        float shouldersHeight = headRadius * 2f;
        float shouldersY = headCy + headRadius * 1.35f;
        Render2D.fillRoundedRect(graphics, cx - shouldersWidth / 2f, shouldersY,
                shouldersWidth, shouldersHeight, headRadius, headRadius, 0f, 0f, silhouette);
        Text2D.drawCentered(graphics, Text2D.truncate("No entity", (int) Math.max(0f, width - 8f)),
                cx, shouldersY + shouldersHeight + 6f, theme.textMuted);
    }

    /**
     * Rewrites the depth buffer under the model back to the z=0 UI plane (color writes off,
     * depth func ALWAYS), so tooltips/popups drawn later aren't occluded by the 3D entity.
     */
    private static void resetDepth(GuiGraphics graphics, float x, float y, float width, float height) {
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        Render2D.fillRect(graphics, x, y, width, height, 0xFFFFFFFF);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableDepthTest();
        RenderSystem.colorMask(true, true, true, true);
    }
}
