package com.stormcph.easygui.client.stat;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientRawInputEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.function.DoubleSupplier;

/**
 * Built-in {@link Metric}s for the stats everyone wants — FPS, ping, server TPS,
 * heap memory, movement speed, and clicks per second.
 *
 * <p>Each metric is a lazy singleton: nothing is created (and no tick work happens)
 * until the first accessor call, after which the metric samples continuously for the
 * rest of the session. Grab one and hand its {@link Metric#series()} to a chart:</p>
 *
 * <pre>{@code
 * sparkline.setSeries(Metrics.fps().series());
 * float worstPing = Metrics.ping().series().max();
 * }</pre>
 *
 * <p>Do not {@link Metric#dispose() dispose} these — they are shared. Client thread
 * only.</p>
 */
@Environment(EnvType.CLIENT)
public final class Metrics {
    private static Metric fps;
    private static Metric ping;
    private static Metric tps;
    private static Metric memory;
    private static Metric speed;
    private static Metric cps;

    private Metrics() {
    }

    /** Frames per second, as reported by {@code Minecraft.getFps()}. Sampled 10×/s. */
    public static synchronized Metric fps() {
        if (fps == null) {
            fps = Metric.gauge(() -> Minecraft.getInstance().getFps(), 10);
        }
        return fps;
    }

    /**
     * The local player's connection latency in milliseconds, from the server's player
     * list. {@code 0} when not connected (or before the server has sent latency info).
     * Sampled 2×/s.
     */
    public static synchronized Metric ping() {
        if (ping == null) {
            ping = Metric.gauge(Metrics::currentPing, 2);
        }
        return ping;
    }

    /**
     * <em>Estimated</em> server TPS, clamped to {@code 0..20}; {@code 0} while not in
     * a world.
     *
     * <p>Caveat: the client cannot measure server tick speed directly (wall-clock time
     * between <em>client</em> ticks only measures the client, which always ticks at 20).
     * Instead this compares how fast the world's game time ({@code level.getGameTime()},
     * incremented once per server tick) advances against wall-clock time. The estimate
     * needs a couple of seconds to settle after joining a world, and can dip briefly on
     * network hiccups or dimension changes even when the server itself is healthy.</p>
     */
    public static synchronized Metric tps() {
        if (tps == null) {
            tps = Metric.gauge(new TpsEstimator(), 1);
        }
        return tps;
    }

    /** Used JVM heap in MiB ({@code totalMemory - freeMemory}). Sampled 2×/s. */
    public static synchronized Metric memory() {
        if (memory == null) {
            memory = Metric.gauge(() -> {
                Runtime runtime = Runtime.getRuntime();
                return (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);
            }, 2);
        }
        return memory;
    }

    /**
     * The player's horizontal speed in blocks per second, from position deltas each
     * tick; {@code 0} without a player. The series holds the raw per-tick samples
     * (teleports show up as one large spike); {@link Metric#smoothedValue()} is
     * pre-configured to give a steadier readout.
     */
    public static synchronized Metric speed() {
        if (speed == null) {
            speed = Metric.gauge(new SpeedTracker(), 20).smooth(0.3f);
        }
        return speed;
    }

    /**
     * Clicks per second: a {@link Metric#counter() counter} fed by raw left-mouse
     * presses ({@code ClientRawInputEvent.MOUSE_CLICKED_PRE}), so it counts clicks
     * both in-game and inside screens. Read {@link Metric#perSecond()}.
     */
    public static synchronized Metric cps() {
        if (cps == null) {
            cps = Metric.counter();
            ClientRawInputEvent.MOUSE_CLICKED_PRE.register((client, button, action, mods) -> {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && action == GLFW.GLFW_PRESS) {
                    cps.add(1.0);
                }
                return EventResult.pass();
            });
        }
        return cps;
    }

    // ------------------------------------------------------------------
    // Suppliers
    // ------------------------------------------------------------------

    private static double currentPing() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        LocalPlayer player = minecraft.player;
        if (connection == null || player == null) {
            return 0.0;
        }
        PlayerInfo info = connection.getPlayerInfo(player.getUUID());
        return info != null ? info.getLatency() : 0.0;
    }

    /** Derives TPS from game-time deltas vs wall clock; see {@link #tps()} for caveats. */
    private static final class TpsEstimator implements DoubleSupplier {
        private long lastGameTime = -1L;
        private long lastNanos;
        private double tps = 20.0;

        @Override
        public double getAsDouble() {
            ClientLevel level = Minecraft.getInstance().level;
            long now = System.nanoTime();
            if (level == null) {
                lastGameTime = -1L;
                tps = 20.0;
                return 0.0;
            }
            long gameTime = level.getGameTime();
            if (lastGameTime < 0L) {
                lastGameTime = gameTime;
                lastNanos = now;
                return tps;
            }
            double seconds = (now - lastNanos) / 1.0E9;
            if (seconds <= 0.0) {
                return tps;
            }
            double estimate = Mth.clamp((gameTime - lastGameTime) / seconds, 0.0, 20.0);
            lastGameTime = gameTime;
            lastNanos = now;
            // Blend half-way each poll: jitter-free without lagging multiple seconds behind.
            tps += (estimate - tps) * 0.5;
            return tps;
        }
    }

    /** Horizontal blocks-per-second from per-tick position deltas; see {@link #speed()}. */
    private static final class SpeedTracker implements DoubleSupplier {
        private double lastX;
        private double lastZ;
        private long lastNanos;
        private boolean tracking;

        @Override
        public double getAsDouble() {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                tracking = false;
                return 0.0;
            }
            double x = player.getX();
            double z = player.getZ();
            long now = System.nanoTime();
            if (!tracking) {
                tracking = true;
                lastX = x;
                lastZ = z;
                lastNanos = now;
                return 0.0;
            }
            double seconds = (now - lastNanos) / 1.0E9;
            double dx = x - lastX;
            double dz = z - lastZ;
            lastX = x;
            lastZ = z;
            lastNanos = now;
            if (seconds <= 0.0) {
                return 0.0;
            }
            return Math.sqrt(dx * dx + dz * dz) / seconds;
        }
    }
}
