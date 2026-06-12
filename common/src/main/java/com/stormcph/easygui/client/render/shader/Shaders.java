package com.stormcph.easygui.client.render.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.architectury.registry.ReloadListenerRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.util.Unit;

import java.util.function.Consumer;

/**
 * Shader bookkeeping: hooks resource reloads so every {@link EasyShader} recompiles on
 * F3+T, and hosts the built-in decorative shaders.
 */
@Environment(EnvType.CLIENT)
public final class Shaders {
    /**
     * Built-in animated aurora gradient ({@code POSITION_TEX_COLOR}). Drawn with
     * {@code Render2D.shadedRect}/{@code shadedRoundedRect}; the vertex tint multiplies
     * the effect, so alpha fades work as usual.
     */
    public static final EasyShader AURORA = EasyShader.of(
            ResourceLocation.fromNamespaceAndPath("easygui", "easygui_aurora"),
            DefaultVertexFormat.POSITION_TEX_COLOR);

    /**
     * Built-in animated liquid (domain-warped fractal noise — a slowly churning fluid
     * surface). Tunable uniforms: {@code ColorA} (deep), {@code ColorB} (body),
     * {@code ColorC} (highlight), {@code Speed}, {@code Scale} — see
     * {@link #liquidColors(int, int, int)} for an easy palette swap. Defaults to a deep
     * blue "water" look.
     */
    public static final EasyShader LIQUID = EasyShader.of(
            ResourceLocation.fromNamespaceAndPath("easygui", "easygui_liquid"),
            DefaultVertexFormat.POSITION_TEX_COLOR);

    private static boolean bootstrapped;

    private Shaders() {
    }

    /** Registers the resource-reload hook. Called once from EasyGUI's client init. */
    public static void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        ReloadListenerRegistry.register(PackType.CLIENT_RESOURCES,
                (barrier, manager, prepProfiler, applyProfiler, backgroundExecutor, gameExecutor) ->
                        barrier.wait(Unit.INSTANCE).thenRunAsync(EasyShader::invalidateAll, gameExecutor),
                ResourceLocation.fromNamespaceAndPath("easygui", "shaders"));
    }

    /**
     * Wall-clock seconds for the auto-filled {@code Time} uniform (wraps hourly to keep
     * float precision). Unlike vanilla's {@code GameTime}, this also advances in menus.
     */
    public static float timeSeconds() {
        return (Util.getMillis() % 3_600_000L) / 1000f;
    }

    /**
     * Uniform setter for {@link #LIQUID}'s palette (alpha channels are ignored — fade via
     * the draw tint instead). Example presets:
     * <pre>{@code
     * Shaders.liquidColors(0xFF03102A, 0xFF5B8CFF, 0xFFD6ECFF)  // water (default-ish)
     * Shaders.liquidColors(0xFF3D0E02, 0xFFE25822, 0xFFFFC74D)  // lava
     * Shaders.liquidColors(0xFF5C3A00, 0xFFE8A317, 0xFFFFE9A8)  // honey
     * Shaders.liquidColors(0xFF0B2B14, 0xFF46C26E, 0xFFB6F5CB)  // slime
     * }</pre>
     */
    public static Consumer<ShaderInstance> liquidColors(int deep, int body, int highlight) {
        return shader -> {
            setColor3(shader, "ColorA", deep);
            setColor3(shader, "ColorB", body);
            setColor3(shader, "ColorC", highlight);
        };
    }

    /** Sets a {@code vec3} color uniform from a packed RGB(A) int. */
    public static void setColor3(ShaderInstance shader, String uniform, int rgb) {
        shader.safeGetUniform(uniform).set(
                ((rgb >> 16) & 0xFF) / 255f,
                ((rgb >> 8) & 0xFF) / 255f,
                (rgb & 0xFF) / 255f);
    }
}
