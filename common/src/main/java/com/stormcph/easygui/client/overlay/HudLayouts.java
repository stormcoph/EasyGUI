package com.stormcph.easygui.client.overlay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import com.stormcph.easygui.client.config.EasyConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Named HUD layout profiles: full snapshots of every persistable overlay's anchor,
 * offsets, visibility, and {@link HudStyle} that can be saved, re-applied, and deleted
 * by name — e.g. a compact "PvP" arrangement next to a roomy "Building" one.
 *
 * <p>Only overlays with a {@link HudOverlay#setPersistId persist id} participate; an
 * overlay that joins later simply keeps its current state when an older profile is
 * loaded. {@link #loadProfile} applies a profile to the live overlays (styles are
 * updated in place, so widgets bound to a style keep working) and persists the result
 * exactly like the HUD editor does, so the loaded layout survives restarts.</p>
 *
 * <p>Profiles live under a {@code "profiles"} object in {@code config/easygui.json},
 * next to the per-overlay {@code "overlays"} section, keyed by the display name:</p>
 *
 * <pre>{@code
 * "profiles": {
 *   "PvP": {
 *     "demo_watermark": { "anchor": "top_right", "x": 6.0, "y": 6.0, "scale": 0.75, ... }
 *   }
 * }
 * }</pre>
 *
 * <p>All access must happen on the client thread (the {@link HudEditScreen} toolbar is
 * the intended caller).</p>
 */
@Environment(EnvType.CLIENT)
public final class HudLayouts {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String CONFIG_NAME = "easygui";
    private static final String PROFILES_KEY = "profiles";

    private HudLayouts() {
    }

    /** All saved profile names, sorted case-insensitively. */
    public static List<String> listProfiles() {
        List<String> names = new ArrayList<>(readProfiles().keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /**
     * Snapshots the current anchor/offsets/visibility/style of every overlay with a
     * persist id under the given name (overwriting an existing profile of that name).
     * Returns {@code false} for a blank name.
     */
    public static boolean saveProfile(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        JsonObject snapshot = new JsonObject();
        for (HudOverlay overlay : OverlayManager.getOverlays()) {
            String id = overlay.getPersistId();
            if (id != null) {
                snapshot.add(OverlayEditor.persistKey(id), snapshotOverlay(overlay));
            }
        }
        mutateProfiles(profiles -> profiles.add(trimmed, snapshot));
        return true;
    }

    /**
     * Applies the named profile to all registered overlays with a persist id and
     * persists the result (so the layout also survives restarts). Overlays the profile
     * doesn't know about are left untouched. Returns {@code false} if no such profile.
     */
    public static boolean loadProfile(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (!(readProfiles().get(trimmed) instanceof JsonObject profile)) {
            return false;
        }
        for (HudOverlay overlay : OverlayManager.getOverlays()) {
            String id = overlay.getPersistId();
            if (id == null) {
                continue;
            }
            if (profile.get(OverlayEditor.persistKey(id)) instanceof JsonObject entry) {
                applyTo(overlay, entry);
                OverlayEditor.persistAll(overlay);
            }
        }
        return true;
    }

    /** Removes the named profile; returns whether it existed. */
    public static boolean deleteProfile(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (!readProfiles().has(trimmed)) {
            return false;
        }
        mutateProfiles(profiles -> profiles.remove(trimmed));
        return true;
    }

    // ------------------------------------------------------------------
    // Snapshots
    // ------------------------------------------------------------------

    private static JsonObject snapshotOverlay(HudOverlay overlay) {
        HudStyle style = overlay.getStyle();
        JsonObject entry = new JsonObject();
        entry.addProperty("anchor", overlay.getAnchor().name().toLowerCase(Locale.ROOT));
        entry.addProperty("x", round4(overlay.getOffsetX()));
        entry.addProperty("y", round4(overlay.getOffsetY()));
        entry.addProperty("visible", overlay.isVisible());
        entry.addProperty("scale", round4(style.getScale()));
        entry.addProperty("opacity", round4(style.getOpacity()));
        entry.addProperty("padding", round4(style.getPadding()));
        entry.addProperty("background", style.getBackground().name().toLowerCase(Locale.ROOT));
        entry.addProperty("background_color", String.format(Locale.ROOT, "#%08X", style.getBackgroundColor()));
        entry.addProperty("radius", round4(style.getRadius()));
        entry.addProperty("outline", style.isOutline());
        entry.addProperty("shadow", style.isShadow());
        entry.addProperty("text_shadow", style.isTextShadow());
        return entry;
    }

    /** Applies one profile entry; missing or malformed keys keep the overlay's current value. */
    private static void applyTo(HudOverlay overlay, JsonObject entry) {
        overlay.setAnchor(getEnum(entry, "anchor", overlay.getAnchor()));
        overlay.setOffsets(getFloat(entry, "x", overlay.getOffsetX()),
                getFloat(entry, "y", overlay.getOffsetY()));
        overlay.setVisible(getBool(entry, "visible", overlay.isVisible()));
        HudStyle style = overlay.getStyle();
        style.setScale(getFloat(entry, "scale", style.getScale()))
                .setOpacity(getFloat(entry, "opacity", style.getOpacity()))
                .setPadding(getFloat(entry, "padding", style.getPadding()))
                .setBackground(getEnum(entry, "background", style.getBackground()))
                .setBackgroundColor(getColor(entry, "background_color", style.getBackgroundColor()))
                .setRadius(getFloat(entry, "radius", style.getRadius()))
                .setOutline(getBool(entry, "outline", style.isOutline()))
                .setShadow(getBool(entry, "shadow", style.isShadow()))
                .setTextShadow(getBool(entry, "text_shadow", style.isTextShadow()));
    }

    // ------------------------------------------------------------------
    // Storage (the "profiles" object inside config/easygui.json)
    // ------------------------------------------------------------------

    /** The current profiles object as stored on disk (never {@code null}). */
    private static JsonObject readProfiles() {
        JsonObject root = parseFile(EasyConfig.of(CONFIG_NAME).getFile());
        return root.get(PROFILES_KEY) instanceof JsonObject profiles ? profiles : new JsonObject();
    }

    /**
     * Rewrites the {@code "profiles"} object inside the shared config file. The owning
     * {@link EasyConfig} is flushed first (so its pending values aren't lost in the
     * rewrite) and reloaded afterwards (so its in-memory copy of the file — which
     * preserves unknown keys like ours on every save — picks the new profiles up).
     */
    private static void mutateProfiles(Consumer<JsonObject> mutator) {
        EasyConfig config = EasyConfig.of(CONFIG_NAME);
        config.save();
        Path file = config.getFile();
        JsonObject root = parseFile(file);
        JsonObject profiles = root.get(PROFILES_KEY) instanceof JsonObject existing
                ? existing : new JsonObject();
        mutator.accept(profiles);
        root.add(PROFILES_KEY, profiles);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("EasyGUI: failed to save HUD layout profiles to '{}'", file, e);
        }
        config.reload();
    }

    private static JsonObject parseFile(Path file) {
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (Exception e) {
            LOGGER.error("EasyGUI: could not read HUD layout profiles from '{}'", file, e);
        }
        return new JsonObject();
    }

    // ------------------------------------------------------------------
    // JSON helpers
    // ------------------------------------------------------------------

    private static double round4(float value) {
        return Math.round(value * 10000f) / 10000.0;
    }

    private static float getFloat(JsonObject entry, String key, float fallback) {
        return entry.get(key) instanceof JsonPrimitive p && p.isNumber() ? p.getAsFloat() : fallback;
    }

    private static boolean getBool(JsonObject entry, String key, boolean fallback) {
        return entry.get(key) instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : fallback;
    }

    private static <E extends Enum<E>> E getEnum(JsonObject entry, String key, E fallback) {
        if (!(entry.get(key) instanceof JsonPrimitive p) || !p.isString()) {
            return fallback;
        }
        for (E constant : fallback.getDeclaringClass().getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(p.getAsString())) {
                return constant;
            }
        }
        return fallback;
    }

    /** Packed ARGB from a number or a {@code "#AARRGGBB"} string (alpha digits optional). */
    private static int getColor(JsonObject entry, String key, int fallback) {
        if (!(entry.get(key) instanceof JsonPrimitive p)) {
            return fallback;
        }
        if (p.isNumber()) {
            return p.getAsInt();
        }
        if (p.isString()) {
            String s = p.getAsString().trim();
            if (s.startsWith("#")) {
                s = s.substring(1);
            }
            try {
                long parsed = Long.parseLong(s, 16);
                if (s.length() <= 6) {
                    parsed |= 0xFF000000L;
                }
                return (int) parsed;
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
