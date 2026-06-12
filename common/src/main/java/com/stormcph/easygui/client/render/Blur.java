package com.stormcph.easygui.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stormcph.easygui.client.render.shader.EasyShader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.opengl.GL11;

/**
 * Real gaussian background blur ("frosted glass"), implemented as a shader post-pass over
 * the current framebuffer.
 *
 * <p>{@link #capture} grabs whatever has been rendered so far (world, HUD, earlier GUI
 * layers — anything behind the element being drawn), downsamples it to half resolution and
 * runs separable gaussian passes through two ping-pong render targets. The blurred result
 * is then composited by {@code Render2D.fillRoundedRectBlurred(...)} using a shader that
 * samples it at {@code gl_FragCoord}, so blurred fills respect pose transforms and get the
 * same feathered anti-aliased edges as every other EasyGUI shape.</p>
 */
@Environment(EnvType.CLIENT)
public final class Blur {
    static final EasyShader BLUR = EasyShader.of(
            ResourceLocation.fromNamespaceAndPath("easygui", "easygui_blur"),
            DefaultVertexFormat.POSITION);
    static final EasyShader FILL = EasyShader.of(
            ResourceLocation.fromNamespaceAndPath("easygui", "easygui_blur_fill"),
            DefaultVertexFormat.POSITION_COLOR);

    private static RenderTarget targetA;
    private static RenderTarget targetB;

    private Blur() {
    }

    /** Whether the blur shaders compiled. When {@code false}, blurred fills draw nothing. */
    public static boolean isAvailable() {
        return BLUR.get() != null && FILL.get() != null;
    }

    /**
     * Blurs the current main framebuffer content and returns the GL texture id holding the
     * result (or {@code -1} if the shaders are unavailable). Leaves the main render target
     * bound for writing again. {@code radius} is the approximate blur radius in GUI pixels.
     */
    static int capture(float radius) {
        ShaderInstance blur = BLUR.get();
        if (blur == null || FILL.get() == null) {
            return -1;
        }
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();

        // glClear and our fullscreen passes must not be clipped by an active scissor region
        // (e.g. when a frosted element is drawn inside a ScrollPanel).
        boolean hadScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (hadScissor) {
            GlStateManager._disableScissorTest();
        }

        int halfW = Math.max(1, main.width / 2);
        int halfH = Math.max(1, main.height / 2);
        ensureTargets(halfW, halfH);

        // Targets are half resolution, so one texel covers two physical pixels. The 5-tap
        // linear gaussian in the shader has sigma ~2 texels at offset scale 1; stack passes
        // until the requested radius is reached.
        float radiusTexels = Math.max(0.25f, radius * Render2D.guiScale() / 2f);
        int iterations = Mth.clamp((int) Math.ceil(radiusTexels / 4f), 1, 8);
        float offset = Mth.clamp(radiusTexels / (4f * iterations), 0.05f, 1.25f);

        RenderSystem.disableBlend();
        RenderSystem.disableCull();

        // Smooth downsample needs linear filtering on the main color texture; restore the
        // vanilla nearest filter afterwards.
        setTextureFilter(main.getColorTextureId(), GL11.GL_LINEAR);
        pass(blur, main.getColorTextureId(), main.width, main.height, targetA, offset, 0f);
        setTextureFilter(main.getColorTextureId(), GL11.GL_NEAREST);
        pass(blur, targetA.getColorTextureId(), halfW, halfH, targetB, 0f, offset);
        for (int i = 1; i < iterations; i++) {
            pass(blur, targetB.getColorTextureId(), halfW, halfH, targetA, offset, 0f);
            pass(blur, targetA.getColorTextureId(), halfW, halfH, targetB, 0f, offset);
        }

        main.bindWrite(true);
        RenderSystem.enableCull();
        if (hadScissor) {
            GlStateManager._enableScissorTest();
        }
        return targetB.getColorTextureId();
    }

    private static void pass(ShaderInstance shader, int sourceTexture, int sourceWidth, int sourceHeight,
                             RenderTarget destination, float dirX, float dirY) {
        destination.bindWrite(true);
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, sourceTexture);
        shader.safeGetUniform("BlurDir").set(dirX, dirY);
        shader.safeGetUniform("SrcSize").set((float) sourceWidth, (float) sourceHeight);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buffer.addVertex(-1f, -1f, 0f);
        buffer.addVertex(1f, -1f, 0f);
        buffer.addVertex(1f, 1f, 0f);
        buffer.addVertex(-1f, 1f, 0f);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private static void ensureTargets(int width, int height) {
        if (targetA != null && targetA.width == width && targetA.height == height) {
            return;
        }
        if (targetA != null) {
            targetA.destroyBuffers();
            targetB.destroyBuffers();
        }
        targetA = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        targetB = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        targetA.setFilterMode(GL11.GL_LINEAR);
        targetB.setFilterMode(GL11.GL_LINEAR);
    }

    private static void setTextureFilter(int texture, int filter) {
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
    }
}
