package com.stormcph.easygui.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * An {@link Icon} backed by a texture (optionally a sub-region of an atlas).
 * The texture is tinted with the icon color, so white/grayscale icon textures work best.
 */
@Environment(EnvType.CLIENT)
public final class TextureIcon implements Icon {
    private final ResourceLocation texture;
    private final float u0;
    private final float v0;
    private final float u1;
    private final float v1;

    /** Icon spanning the entire texture. */
    public TextureIcon(ResourceLocation texture) {
        this(texture, 0f, 0f, 1f, 1f);
    }

    /** Icon from a UV sub-region (0..1) of the texture. */
    public TextureIcon(ResourceLocation texture, float u0, float v0, float u1, float v1) {
        this.texture = texture;
        this.u0 = u0;
        this.v0 = v0;
        this.u1 = u1;
        this.v1 = v1;
    }

    /** Icon from a pixel region of an atlas of the given dimensions. */
    public static TextureIcon ofAtlas(ResourceLocation texture, int x, int y, int width, int height,
                                      int atlasWidth, int atlasHeight) {
        return new TextureIcon(texture,
                x / (float) atlasWidth, y / (float) atlasHeight,
                (x + width) / (float) atlasWidth, (y + height) / (float) atlasHeight);
    }

    @Override
    public void render(GuiGraphics graphics, float x, float y, float size, int color) {
        Render2D.texturedRect(graphics, texture, x, y, size, size, u0, v0, u1, v1, color);
    }
}
