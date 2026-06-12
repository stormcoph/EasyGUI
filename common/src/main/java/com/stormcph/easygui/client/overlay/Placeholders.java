package com.stormcph.easygui.client.overlay;

import com.stormcph.easygui.client.stat.Metrics;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The global placeholder registry behind {@link TextElement} templates: every
 * {@code {token}} in a template is resolved here, once per frame.
 *
 * <p>Register your own with {@link #register(String, Supplier)} — keys are
 * case-insensitive and shared by every text element. Suppliers must be cheap (they run
 * during HUD rendering); a {@code null} or throwing supplier resolves to {@code ""}
 * rather than breaking the HUD. All world-dependent built-ins are null-safe and return
 * {@code ""} outside a world.</p>
 *
 * <p>Built-ins: {@code fps}, {@code ping}, {@code tps}, {@code memory} (used heap,
 * MiB), {@code speed} (blocks/s), {@code cps} — terse formats of the
 * {@link Metrics} singletons (accessing one starts its sampling) — plus {@code x},
 * {@code y}, {@code z}, {@code coords} (block coordinates), {@code biome} (registry
 * path), {@code dimension} (registry path), {@code facing} (N/NE/E/SE/S/SW/W/NW from
 * yaw), {@code clock} (system time, HH:mm) and {@code day} (in-game day number).</p>
 */
@Environment(EnvType.CLIENT)
public final class Placeholders {
    private static final Map<String, Supplier<String>> REGISTRY = new ConcurrentHashMap<>();
    /** Eight compass points, indexed by wrapped yaw / 45° (yaw 0 = south, clockwise). */
    private static final String[] FACINGS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    static {
        register("fps", () -> Integer.toString((int) Math.round(Metrics.fps().value())));
        register("ping", () -> Integer.toString((int) Math.round(Metrics.ping().value())));
        register("tps", () -> String.format(Locale.ROOT, "%.1f", Metrics.tps().value()));
        register("memory", () -> Integer.toString((int) Math.round(Metrics.memory().value())));
        register("speed", () -> String.format(Locale.ROOT, "%.1f", Metrics.speed().smoothedValue()));
        register("cps", () -> String.format(Locale.ROOT, "%.1f", Metrics.cps().value()));

        register("x", () -> withPlayer(p -> Integer.toString(Mth.floor(p.getX()))));
        register("y", () -> withPlayer(p -> Integer.toString(Mth.floor(p.getY()))));
        register("z", () -> withPlayer(p -> Integer.toString(Mth.floor(p.getZ()))));
        register("coords", () -> withPlayer(p ->
                Mth.floor(p.getX()) + " " + Mth.floor(p.getY()) + " " + Mth.floor(p.getZ())));
        register("biome", () -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.level == null) {
                return "";
            }
            return minecraft.level.getBiome(minecraft.player.blockPosition()).unwrapKey()
                    .map(key -> key.location().getPath()).orElse("");
        });
        register("dimension", () -> withLevel(level -> level.dimension().location().getPath()));
        register("facing", () -> withPlayer(p ->
                FACINGS[Math.floorMod(Math.round(Mth.wrapDegrees(p.getYRot()) / 45f), 8)]));
        register("clock", () -> CLOCK.format(LocalTime.now()));
        register("day", () -> withLevel(level -> Long.toString(level.getDayTime() / 24000L)));
    }

    private Placeholders() {
    }

    /** Registers (or replaces) a placeholder. Keys are trimmed and case-insensitive. */
    public static void register(String key, Supplier<String> value) {
        Objects.requireNonNull(value, "value");
        REGISTRY.put(normalize(key), value);
    }

    /** Removes a placeholder; templates using it render the raw {@code {token}} again. */
    public static void unregister(String key) {
        REGISTRY.remove(normalize(key));
    }

    public static boolean has(String key) {
        return REGISTRY.containsKey(normalize(key));
    }

    /**
     * Resolves a placeholder: the supplier's value ({@code ""} when it returns
     * {@code null} or throws), or {@code null} when the key isn't registered — letting
     * callers render unknown tokens literally so typos stay visible.
     */
    public static String resolve(String key) {
        Supplier<String> supplier = REGISTRY.get(normalize(key));
        if (supplier == null) {
            return null;
        }
        try {
            String value = supplier.get();
            return value != null ? value : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private static String withPlayer(Function<LocalPlayer, String> value) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null ? value.apply(player) : "";
    }

    private static String withLevel(Function<ClientLevel, String> value) {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null ? value.apply(level) : "";
    }
}
