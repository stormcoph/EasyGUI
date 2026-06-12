package com.stormcph.easygui.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import dev.architectury.event.events.client.ClientLifecycleEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.platform.Platform;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * A small JSON config system for GUI mods: typed values with defaults, human-editable
 * files in the standard {@code config/} folder, automatic debounced saving, and direct
 * widget binding through {@link ConfigValue}. Use it for settings, preferences, and UI
 * state (last selected tab, scroll positions, overlay placement…).
 *
 * <pre>{@code
 * public final class MyConfig {
 *     public static final EasyConfig CONFIG = EasyConfig.of("mymod");
 *
 *     public static final ConfigValue<Boolean> SHOW_HUD   = CONFIG.defineBool("hud.show", true);
 *     public static final ConfigValue<Double>  HUD_SCALE  = CONFIG.defineDouble("hud.scale", 1.0, 0.5, 2.0);
 *     public static final ConfigValue<Integer> ACCENT     = CONFIG.defineColor("theme.accent", 0xFF5B8CFF);
 *     public static final ConfigValue<Mode>    MODE       = CONFIG.defineEnum("general.mode", Mode.SIMPLE);
 *     public static final ConfigValue<Integer> LAST_TAB   = CONFIG.defineInt("state.last_tab", 0);
 * }
 * }</pre>
 *
 * <p>Dotted keys become nested JSON objects ({@code "hud.show"} →
 * {@code {"hud": {"show": true}}}). Changes save automatically ~2 seconds after the last
 * write and on game shutdown; defaults are written out on first run so the file is easy
 * to discover and edit. Unknown keys in the file are preserved. A corrupt file is backed
 * up to {@code <name>.json.bak} and replaced with defaults instead of crashing.
 * All access must happen on the client thread.</p>
 */
@Environment(EnvType.CLIENT)
public final class EasyConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_\\-]+(/[a-z0-9_\\-]+)*");
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-zA-Z0-9_\\-]+(\\.[a-zA-Z0-9_\\-]+)*");
    private static final int SAVE_DELAY_TICKS = 40;

    private static final Map<String, EasyConfig> CONFIGS = new LinkedHashMap<>();
    private static boolean bootstrapped;

    private final String name;
    private final Path file;
    private final Map<String, ConfigValue<?>> values = new LinkedHashMap<>();
    private JsonObject root;
    /** -1 when clean; otherwise ticks since the last write, counting toward the save delay. */
    private int dirtyTicks = -1;

    private EasyConfig(String name) {
        this.name = name;
        this.file = Platform.getConfigFolder().resolve(name + ".json");
        this.root = readRoot();
    }

    /**
     * Gets (or creates and loads) the config stored at {@code config/<name>.json}.
     * {@code name} may contain {@code /} for subfolders (e.g. {@code "mymod/client"}).
     */
    public static synchronized EasyConfig of(String name) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid config name '" + name
                    + "' (use lowercase letters, digits, -, _, and / for subfolders)");
        }
        return CONFIGS.computeIfAbsent(name, EasyConfig::new);
    }

    /** Registers the auto-save hooks. Called once from EasyGUI's client init. */
    public static void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        ClientTickEvent.CLIENT_POST.register(minecraft -> tickAll());
        ClientLifecycleEvent.CLIENT_STOPPING.register(minecraft -> saveAll());
    }

    public String getName() {
        return name;
    }

    public Path getFile() {
        return file;
    }

    // ------------------------------------------------------------------
    // Value definitions
    // ------------------------------------------------------------------

    public ConfigValue<Boolean> defineBool(String key, boolean defaultValue) {
        return define(key, defaultValue, UnaryOperator.identity(),
                e -> isPrimitive(e) && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : null,
                JsonPrimitive::new);
    }

    public ConfigValue<Integer> defineInt(String key, int defaultValue) {
        return define(key, defaultValue, UnaryOperator.identity(), EasyConfig::readInt, JsonPrimitive::new);
    }

    /** Integer clamped to {@code [min, max]} on load and set. */
    public ConfigValue<Integer> defineInt(String key, int defaultValue, int min, int max) {
        return define(key, defaultValue, v -> Mth.clamp(v, min, max), EasyConfig::readInt, JsonPrimitive::new);
    }

    public ConfigValue<Double> defineDouble(String key, double defaultValue) {
        return define(key, defaultValue, UnaryOperator.identity(), EasyConfig::readDouble, JsonPrimitive::new);
    }

    /** Double clamped to {@code [min, max]} on load and set. */
    public ConfigValue<Double> defineDouble(String key, double defaultValue, double min, double max) {
        return define(key, defaultValue, v -> Mth.clamp(v, min, max), EasyConfig::readDouble, JsonPrimitive::new);
    }

    public ConfigValue<String> defineString(String key, String defaultValue) {
        return define(key, defaultValue, UnaryOperator.identity(),
                e -> isPrimitive(e) && e.getAsJsonPrimitive().isString() ? e.getAsString() : null,
                JsonPrimitive::new);
    }

    /** Packed ARGB color, stored human-editable as {@code "#AARRGGBB"} (alpha optional when editing). */
    public ConfigValue<Integer> defineColor(String key, int defaultValue) {
        return define(key, defaultValue, UnaryOperator.identity(), EasyConfig::readColor,
                v -> new JsonPrimitive(String.format(Locale.ROOT, "#%08X", v)));
    }

    /** Enum stored by name (case-insensitive in the file). */
    public <E extends Enum<E>> ConfigValue<E> defineEnum(String key, E defaultValue) {
        @SuppressWarnings("unchecked")
        Class<E> type = (Class<E>) defaultValue.getDeclaringClass();
        return define(key, defaultValue, UnaryOperator.identity(),
                e -> readEnum(e, type),
                v -> new JsonPrimitive(v.name().toLowerCase(Locale.ROOT)));
    }

    private <T> ConfigValue<T> define(String key, T defaultValue, UnaryOperator<T> sanitizer,
                                      Function<JsonElement, T> reader, Function<T, JsonElement> writer) {
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid config key '" + key + "'");
        }
        ConfigValue<?> existing = values.get(key);
        if (existing != null) {
            // Same key must always be defined with the same type and default.
            @SuppressWarnings("unchecked")
            ConfigValue<T> cast = (ConfigValue<T>) existing;
            return cast;
        }
        ConfigValue<T> value = new ConfigValue<>(this, key, defaultValue, sanitizer, reader, writer);
        JsonElement element = getPath(root, key);
        T parsed = element == null ? null : reader.apply(element);
        if (parsed == null) {
            value.initialize(defaultValue);
            markDirty(); // write the default out so the file is discoverable/editable
        } else {
            value.initialize(parsed);
        }
        values.put(key, value);
        return value;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    void markDirty() {
        dirtyTicks = 0;
    }

    /** Writes the config to disk now (normally unnecessary — saving is automatic). */
    public void save() {
        for (ConfigValue<?> value : values.values()) {
            writeValue(value);
        }
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
            LOGGER.error("EasyGUI: failed to save config '{}'", name, e);
        }
        dirtyTicks = -1;
    }

    /**
     * Re-reads the file from disk and updates all defined values ({@link ConfigValue#onChange}
     * listeners fire for anything that changed). Useful after external edits.
     */
    public void reload() {
        root = readRoot();
        for (ConfigValue<?> value : values.values()) {
            reloadValue(value);
        }
        dirtyTicks = -1;
    }

    private <T> void writeValue(ConfigValue<T> value) {
        setPath(root, value.getKey(), value.writer.apply(value.get()));
    }

    private <T> void reloadValue(ConfigValue<T> value) {
        JsonElement element = getPath(root, value.getKey());
        T parsed = element == null ? null : value.reader.apply(element);
        value.setLoaded(parsed != null ? parsed : value.getDefault());
    }

    private JsonObject readRoot() {
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
            throw new IOException("root is not a JSON object");
        } catch (Exception e) {
            LOGGER.error("EasyGUI: config '{}' is corrupt; backing it up and using defaults", name, e);
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            return new JsonObject();
        }
    }

    private static void tickAll() {
        for (EasyConfig config : new ArrayList<>(CONFIGS.values())) {
            if (config.dirtyTicks >= 0 && ++config.dirtyTicks >= SAVE_DELAY_TICKS) {
                config.save();
            }
        }
    }

    private static void saveAll() {
        for (EasyConfig config : new ArrayList<>(CONFIGS.values())) {
            if (config.dirtyTicks >= 0) {
                config.save();
            }
        }
    }

    // ------------------------------------------------------------------
    // JSON helpers
    // ------------------------------------------------------------------

    private static JsonElement getPath(JsonObject root, String key) {
        String[] parts = key.split("\\.");
        JsonObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!(current.get(parts[i]) instanceof JsonObject next)) {
                return null;
            }
            current = next;
        }
        return current.get(parts[parts.length - 1]);
    }

    private static void setPath(JsonObject root, String key, JsonElement value) {
        String[] parts = key.split("\\.");
        JsonObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (current.get(parts[i]) instanceof JsonObject next) {
                current = next;
            } else {
                JsonObject next = new JsonObject();
                current.add(parts[i], next);
                current = next;
            }
        }
        current.add(parts[parts.length - 1], value);
    }

    private static boolean isPrimitive(JsonElement e) {
        return e != null && e.isJsonPrimitive();
    }

    private static Integer readInt(JsonElement e) {
        return isPrimitive(e) && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : null;
    }

    private static Double readDouble(JsonElement e) {
        return isPrimitive(e) && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : null;
    }

    private static Integer readColor(JsonElement e) {
        if (!isPrimitive(e)) {
            return null;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
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
                    parsed |= 0xFF000000L; // no alpha digits → opaque
                }
                return (int) parsed;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static <E extends Enum<E>> E readEnum(JsonElement e, Class<E> type) {
        if (!isPrimitive(e) || !e.getAsJsonPrimitive().isString()) {
            return null;
        }
        String s = e.getAsString();
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(s)) {
                return constant;
            }
        }
        return null;
    }
}
