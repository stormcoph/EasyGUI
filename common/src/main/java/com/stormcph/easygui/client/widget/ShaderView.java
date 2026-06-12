package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.shader.EasyShader;
import com.stormcph.easygui.client.render.shader.ShaderFit;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.function.Consumer;

/**
 * A widget whose surface is drawn by a custom {@link EasyShader} (vertex format
 * {@code POSITION_TEX_COLOR}; UVs span 0..1 across the bounds, a {@code Time} uniform is
 * fed automatically). Use it for animated gradients, glows, plasma headers, visualizers —
 * anything a fragment shader can dream up.
 *
 * <pre>{@code
 * card.add(new ShaderView(Shaders.AURORA).setRadius(2f))
 *     .setBounds(x, y, 200, 4);
 * }</pre>
 */
@Environment(EnvType.CLIENT)
public class ShaderView extends Widget {
    private final EasyShader shader;
    private float radius;
    private int tint = 0xFFFFFFFF;
    private Consumer<ShaderInstance> uniforms;
    private ShaderFit fit = ShaderFit.COVER;

    public ShaderView(EasyShader shader) {
        this.shader = shader;
    }

    /** Corner radius; {@code 0} draws a plain quad. */
    public ShaderView setRadius(float radius) {
        this.radius = radius;
        return this;
    }

    /** How the shader pattern maps onto a non-square widget (default {@link ShaderFit#COVER}). */
    public ShaderView setFit(ShaderFit fit) {
        this.fit = fit;
        return this;
    }

    /** Multiplied into the shader output as the vertex color (default white). */
    public ShaderView setTint(int tint) {
        this.tint = tint;
        return this;
    }

    /** Called every frame before drawing, for custom uniforms. */
    public ShaderView setUniforms(Consumer<ShaderInstance> uniforms) {
        this.uniforms = uniforms;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        if (radius > 0f) {
            Render2D.shadedRoundedRect(graphics, shader, x, y, width, height, radius, tint, uniforms, fit);
        } else {
            Render2D.shadedRect(graphics, shader, x, y, width, height, tint, uniforms, fit);
        }
    }
}
