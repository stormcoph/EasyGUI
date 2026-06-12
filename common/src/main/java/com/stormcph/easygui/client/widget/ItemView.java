package com.stormcph.easygui.client.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.opengl.GL11;

import java.util.function.Supplier;

/**
 * Displays an {@link ItemStack}, scaled to fill the widget bounds.
 *
 * <p>Vanilla draws items as fixed 16×16 sprites at integer coordinates, so this widget
 * wraps the draw in a pose translate+scale: the stack fills a centered square of
 * {@code min(width, height)} pixels. The stack can be fixed or supplied live each frame
 * (e.g. the player's main-hand item). Optional extras: vanilla count/durability
 * decorations, a subtle slot-style background, and a tooltip showing the item's hover
 * name. An empty stack renders nothing (the slot background, if enabled, still shows).</p>
 *
 * <p>3D items write into the GUI depth buffer well above the z=0 plane the rest of
 * EasyGUI draws on, which would let them poke through tooltips and popups rendered later.
 * After drawing, the widget rewrites the depth under its item square back to z=0, so
 * overlapping top-layer UI composites normally.</p>
 */
@Environment(EnvType.CLIENT)
public class ItemView extends Widget {
    private final Supplier<ItemStack> stackSupplier;
    private boolean showDecorations = true;
    private boolean tooltipFromItem;
    private boolean slotBackground;

    /** Shows a fixed stack. */
    public ItemView(ItemStack stack) {
        this(() -> stack);
    }

    /** Shows a live stack, re-queried every frame (e.g. {@code () -> player.getMainHandItem()}). */
    public ItemView(Supplier<ItemStack> stack) {
        this.stackSupplier = stack;
        this.width = 18f;
        this.height = 18f;
    }

    /** The stack currently displayed (never {@code null}; empty when the supplier yields nothing). */
    public ItemStack getStack() {
        ItemStack stack = stackSupplier != null ? stackSupplier.get() : null;
        return stack != null ? stack : ItemStack.EMPTY;
    }

    /** Whether to draw the vanilla count/durability decorations (default {@code true}). */
    public ItemView setShowDecorations(boolean showDecorations) {
        this.showDecorations = showDecorations;
        return this;
    }

    /**
     * When hovered, shows the stack's hover name as a tooltip. A static
     * {@link #setTooltip(String) tooltip} takes precedence if both are set.
     */
    public ItemView setTooltipFromItem(boolean tooltipFromItem) {
        this.tooltipFromItem = tooltipFromItem;
        return this;
    }

    /** Draws a subtle rounded slot background (surface fill + hairline outline) behind the item. */
    public ItemView setSlotBackground(boolean slotBackground) {
        this.slotBackground = slotBackground;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float side = Math.min(width, height);
        float ix = x + (width - side) / 2f;
        float iy = y + (height - side) / 2f;

        if (slotBackground) {
            float r = theme.radiusSmall;
            int bg = ColorUtil.lerp(theme.surfaceVariant, theme.surfaceHover, hoverAmount() * 0.6f);
            Render2D.fillRoundedRect(graphics, ix, iy, side, side, r, bg);
            Render2D.strokeRoundedRect(graphics, ix, iy, side, side, r, 1f, theme.outline);
        }

        ItemStack stack = getStack();
        if (stack.isEmpty() || side <= 0f) {
            return;
        }

        float scale = side / 16f;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(ix, iy, 0);
        pose.scale(scale, scale, 1f);
        graphics.renderItem(stack, 0, 0);
        if (showDecorations) {
            // Same pose transform, so the count text and durability bar scale with the item.
            graphics.renderItemDecorations(Minecraft.getInstance().font, stack, 0, 0);
        }
        pose.popPose();

        resetDepth(graphics, ix, iy, side, side);

        if (tooltipFromItem && isHovered()) {
            EasyScreen screen = getScreen();
            if (screen != null) {
                screen.requestTooltip(stack.getHoverName().getString(), (float) mouseX, (float) mouseY);
            }
        }
    }

    /**
     * Rewrites the depth buffer under the item back to the z=0 UI plane (color writes off,
     * depth func ALWAYS), so tooltips/popups drawn later aren't occluded by the 3D item.
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
