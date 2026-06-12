package com.stormcph.easygui.client.render.shader;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * A custom core shader loaded from {@code assets/<namespace>/shaders/core/<name>.json}
 * (the vanilla core-shader format: a JSON program definition referencing {@code .vsh} /
 * {@code .fsh} files next to it).
 *
 * <p>Compilation is lazy (first {@link #get()}) and happens on the render thread; shaders
 * recompile automatically after a resource reload (F3+T). Because this class implements
 * {@link Supplier}, an {@code EasyShader} can be passed straight to
 * {@code RenderSystem.setShader(shader)} — but check {@link #get()} for {@code null} first:
 * a shader that failed to compile stays {@code null} (with one error logged) instead of
 * crashing the game.</p>
 *
 * <p>All vanilla JSON-declared uniforms work, and the standard ones (ModelViewMat, ProjMat,
 * ColorModulator, ScreenSize, GameTime, Sampler0…11 from {@code RenderSystem.setShaderTexture})
 * are filled in automatically by the vanilla draw path. Shader <em>names are a global
 * namespace</em> shared with vanilla and other mods — always prefix them with your modid
 * (e.g. {@code mymod_glow}).</p>
 *
 * <pre>{@code
 * public static final EasyShader GLOW =
 *         EasyShader.of(ResourceLocation.fromNamespaceAndPath("mymod", "mymod_glow"),
 *                       DefaultVertexFormat.POSITION_TEX_COLOR);
 *
 * // in render code:
 * Render2D.shadedRoundedRect(graphics, GLOW, x, y, w, h, 8f, 0xFFFFFFFF,
 *         shader -> shader.safeGetUniform("Intensity").set(1.5f));
 * }</pre>
 */
@Environment(EnvType.CLIENT)
public final class EasyShader implements Supplier<ShaderInstance> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<EasyShader> ALL = new CopyOnWriteArrayList<>();

    private final String namespace;
    private final String name;
    private final VertexFormat format;

    private ShaderInstance instance;
    private boolean failed;

    private EasyShader(String namespace, String name, VertexFormat format) {
        this.namespace = namespace;
        this.name = name;
        this.format = format;
    }

    /**
     * Creates (or returns the already-created) shader for {@code id}. The namespace is where
     * shader resources are looked up first (falling back to {@code minecraft}, so
     * {@code #moj_import <fog.glsl>} etc. keep working); the path is the core shader name,
     * resolved to {@code assets/<namespace>/shaders/core/<path>.json}.
     */
    public static EasyShader of(ResourceLocation id, VertexFormat format) {
        for (EasyShader shader : ALL) {
            if (shader.namespace.equals(id.getNamespace()) && shader.name.equals(id.getPath())) {
                return shader;
            }
        }
        EasyShader shader = new EasyShader(id.getNamespace(), id.getPath(), format);
        ALL.add(shader);
        return shader;
    }

    /** The compiled shader, or {@code null} if compilation failed (logged once). */
    @Override
    public ShaderInstance get() {
        if (instance == null && !failed) {
            compile();
        }
        return instance;
    }

    public String getName() {
        return name;
    }

    public VertexFormat getFormat() {
        return format;
    }

    private void compile() {
        ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
        // ShaderInstance always looks resources up in the "minecraft" namespace; redirect
        // every lookup to our namespace first so mod shaders need no loader-specific
        // registration, while vanilla includes still resolve.
        ResourceProvider provider = location -> {
            ResourceLocation remapped = ResourceLocation.fromNamespaceAndPath(namespace, location.getPath());
            Optional<Resource> resource = resourceManager.getResource(remapped);
            return resource.isPresent() ? resource : resourceManager.getResource(location);
        };
        try {
            instance = new ShaderInstance(provider, name, format);
        } catch (Exception e) {
            failed = true;
            LOGGER.error("EasyGUI: failed to load shader '{}:{}'", namespace, name, e);
        }
    }

    /** Closes the compiled program; it recompiles lazily on next use. */
    public void invalidate() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
        failed = false;
    }

    /** Called on resource reload so edited shader sources take effect. */
    public static void invalidateAll() {
        for (EasyShader shader : ALL) {
            shader.invalidate();
        }
    }
}
