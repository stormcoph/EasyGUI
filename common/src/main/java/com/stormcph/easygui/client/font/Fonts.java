package com.stormcph.easygui.client.font;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Loads and caches {@link TrueTypeFont}s from mod/resource-pack assets, files on disk, or
 * raw bytes. Loaders return {@code null} (with one logged error) instead of throwing, so a
 * missing font degrades to the vanilla font rather than crashing.
 *
 * <pre>{@code
 * TrueTypeFont font = Fonts.fromResource(
 *         ResourceLocation.fromNamespaceAndPath("mymod", "fonts/myfont.ttf"));
 * if (font != null) {
 *     Text2D.setUiFont(font, 9f); // every EasyGUI widget now uses it
 * }
 * }</pre>
 */
@Environment(EnvType.CLIENT)
public final class Fonts {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, TrueTypeFont> CACHE = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();

    /** EasyGUI's bundled UI font ({@code Inter}, SIL OFL 1.1 — license in assets/easygui/fonts). */
    public static final ResourceLocation INTER =
            ResourceLocation.fromNamespaceAndPath("easygui", "fonts/inter.ttf");

    private Fonts() {
    }

    /** The bundled Inter font, EasyGUI's recommended default UI font. */
    public static TrueTypeFont inter() {
        return fromResource(INTER);
    }

    /** Loads a TTF shipped in mod assets or a resource pack. Cached; {@code null} on failure. */
    public static TrueTypeFont fromResource(ResourceLocation location) {
        return cached("res:" + location, () -> {
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(location)) {
                return in.readAllBytes();
            }
        }, location.toString());
    }

    /** Loads a TTF from disk (e.g. a system font). Cached; {@code null} on failure. */
    public static TrueTypeFont fromFile(Path path) {
        return cached("file:" + path.toAbsolutePath(), () -> Files.readAllBytes(path), path.toString());
    }

    /** Wraps raw TTF bytes. Not cached — hold on to the returned instance. */
    public static TrueTypeFont fromBytes(byte[] ttf, String name) {
        try {
            return new TrueTypeFont(ttf, name);
        } catch (Exception e) {
            LOGGER.error("EasyGUI: could not load font '{}'", name, e);
            return null;
        }
    }

    private interface ByteSource {
        byte[] read() throws IOException;
    }

    private static TrueTypeFont cached(String key, ByteSource source, String name) {
        TrueTypeFont font = CACHE.get(key);
        if (font != null) {
            return font;
        }
        if (FAILED.contains(key)) {
            return null;
        }
        try {
            font = new TrueTypeFont(source.read(), name);
            CACHE.put(key, font);
            return font;
        } catch (Exception e) {
            FAILED.add(key);
            LOGGER.error("EasyGUI: could not load font '{}'", name, e);
            return null;
        }
    }
}
