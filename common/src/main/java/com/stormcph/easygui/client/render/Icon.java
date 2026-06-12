package com.stormcph.easygui.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A scalable, tintable icon. Built-in vector icons live in {@link Icons};
 * texture-backed icons can be created with {@link TextureIcon}.
 */
@Environment(EnvType.CLIENT)
@FunctionalInterface
public interface Icon {
    /**
     * Renders the icon inside the square from ({@code x}, {@code y}) to
     * ({@code x + size}, {@code y + size}), tinted with {@code color}.
     */
    void render(GuiGraphics graphics, float x, float y, float size, int color);
}
