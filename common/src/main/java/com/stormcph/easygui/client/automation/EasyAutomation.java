package com.stormcph.easygui.client.automation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import com.stormcph.easygui.client.widget.Widget;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.platform.Platform;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * File-driven UI automation for agents and scripts: drive screens, simulate input, and
 * capture screenshots plus widget-tree dumps without a human at the keyboard.
 *
 * <p>Active in development environments, or anywhere when the {@code easygui.automation}
 * system property is set. While active, the game watches {@code <gameDir>/easygui-automation/}
 * for a {@code script.txt}; when one appears it is consumed and executed one command per
 * client tick (so every state change gets at least one rendered frame before the next
 * command), then a {@code result.json} manifest is written next to it. See
 * {@code AUTOMATION.md} in the repository root for the command reference.</p>
 *
 * <p>Screens are opened by name; register yours with {@link #registerScreen} during client
 * init. Hover states are driven through a virtual mouse ({@code move x y}) that overrides
 * the cursor position {@link EasyScreen} renders with.</p>
 */
@Environment(EnvType.CLIENT)
public final class EasyAutomation {
    private static final String DIR_NAME = "easygui-automation";
    private static final String SCRIPT_FILE = "script.txt";
    private static final String RESULT_FILE = "result.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Supplier<Screen>> SCREENS = new HashMap<>();
    private static final Map<String, Integer> KEYS = buildKeyMap();

    private static boolean initialized;
    private static Path dir;

    // Script execution state
    private static List<String> commands;
    private static int index;
    private static int waitTicks;
    private static JsonArray results;
    private static final AtomicInteger pendingShots = new AtomicInteger();

    // Virtual mouse
    private static boolean mouseOverride;
    private static float mouseX;
    private static float mouseY;

    private EasyAutomation() {
    }

    /** Whether automation is allowed to run at all in this launch. */
    public static boolean isEnabled() {
        return Platform.isDevelopmentEnvironment() || Boolean.getBoolean("easygui.automation");
    }

    /** Makes a screen openable via the {@code open <name>} command. */
    public static void registerScreen(String name, Supplier<Screen> factory) {
        SCREENS.put(name.toLowerCase(Locale.ROOT), factory);
    }

    /** Hooks the tick-driven script runner. Call once from client init; no-op when disabled. */
    public static void init() {
        if (initialized || !isEnabled()) {
            return;
        }
        initialized = true;
        ClientTickEvent.CLIENT_POST.register(EasyAutomation::tick);
    }

    // ------------------------------------------------------------------
    // Virtual mouse (consulted by EasyScreen.render)
    // ------------------------------------------------------------------

    /** Whether the virtual mouse should replace the real cursor position this frame. */
    public static boolean overridesMouse() {
        return mouseOverride;
    }

    public static int mouseXInt() {
        return Math.round(mouseX);
    }

    public static int mouseYInt() {
        return Math.round(mouseY);
    }

    // ------------------------------------------------------------------
    // Script runner
    // ------------------------------------------------------------------

    private static void tick(Minecraft minecraft) {
        if (minecraft.getOverlay() != null) {
            return; // still loading resources
        }
        if (dir == null) {
            dir = minecraft.gameDirectory.toPath().resolve(DIR_NAME);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                return; // retried next tick
            }
        }
        if (commands == null) {
            pollForScript();
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (index >= commands.size()) {
            // All commands done; hold until async screenshot writes have flushed.
            if (pendingShots.get() == 0) {
                finish(minecraft);
            }
            return;
        }
        String line = commands.get(index++).trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("index", index - 1);
        entry.addProperty("command", line);
        try {
            String detail = execute(minecraft, line);
            entry.addProperty("status", "ok");
            if (detail != null) {
                entry.addProperty("file", detail);
            }
        } catch (Exception e) {
            entry.addProperty("status", "error");
            entry.addProperty("message", e.getMessage() != null ? e.getMessage() : e.toString());
        }
        results.add(entry);
    }

    private static void pollForScript() {
        Path script = dir.resolve(SCRIPT_FILE);
        if (!Files.isRegularFile(script)) {
            return;
        }
        try {
            commands = new ArrayList<>(Files.readAllLines(script));
            Files.deleteIfExists(script);
            Files.deleteIfExists(dir.resolve(RESULT_FILE));
        } catch (IOException e) {
            commands = null;
            return;
        }
        index = 0;
        waitTicks = 0;
        results = new JsonArray();
    }

    private static void finish(Minecraft minecraft) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("status", "completed");
        manifest.addProperty("screen", minecraft.screen != null
                ? minecraft.screen.getClass().getName() : null);
        manifest.add("commands", results);
        try {
            Files.writeString(dir.resolve(RESULT_FILE), GSON.toJson(manifest));
        } catch (IOException ignored) {
        }
        commands = null;
        results = null;
        mouseOverride = false;
    }

    /** Runs one command. Returns a produced file path (for the manifest) or {@code null}. */
    private static String execute(Minecraft minecraft, String line) throws Exception {
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        Screen screen = minecraft.screen;
        switch (cmd) {
            case "open" -> {
                Supplier<Screen> factory = SCREENS.get(arg(parts, 1).toLowerCase(Locale.ROOT));
                if (factory == null) {
                    throw new IllegalArgumentException("Unknown screen '" + arg(parts, 1)
                            + "'. Registered: " + SCREENS.keySet());
                }
                minecraft.setScreen(factory.get());
            }
            case "close" -> {
                if (screen != null) {
                    screen.onClose();
                }
            }
            case "theme" -> {
                switch (arg(parts, 1).toLowerCase(Locale.ROOT)) {
                    case "dark" -> Theme.setDefault(Theme.dark());
                    case "light" -> Theme.setDefault(Theme.light());
                    default -> throw new IllegalArgumentException("theme expects dark|light");
                }
                if (screen instanceof EasyScreen easy) {
                    easy.setTheme(Theme.getDefault());
                }
            }
            case "move" -> {
                setMouse(Float.parseFloat(arg(parts, 1)), Float.parseFloat(arg(parts, 2)));
                if (screen != null) {
                    screen.mouseMoved(mouseX, mouseY);
                }
            }
            case "click" -> {
                requireScreen(screen);
                int button = 0;
                if (parts.length >= 2 && parts[1].startsWith("#")) {
                    Widget target = findWidget(screen, parts[1].substring(1));
                    setMouse(target.getX() + target.getWidth() / 2f,
                            target.getY() + target.getHeight() / 2f);
                    button = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
                } else if (parts.length >= 3) {
                    setMouse(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
                    button = parts.length >= 4 ? Integer.parseInt(parts[3]) : 0;
                }
                screen.mouseClicked(mouseX, mouseY, button);
                screen.mouseReleased(mouseX, mouseY, button);
            }
            case "drag" -> {
                requireScreen(screen);
                float x1 = Float.parseFloat(arg(parts, 1));
                float y1 = Float.parseFloat(arg(parts, 2));
                float x2 = Float.parseFloat(arg(parts, 3));
                float y2 = Float.parseFloat(arg(parts, 4));
                int button = parts.length >= 6 ? Integer.parseInt(parts[5]) : 0;
                screen.mouseClicked(x1, y1, button);
                int steps = 8;
                for (int i = 1; i <= steps; i++) {
                    float t = (float) i / steps;
                    screen.mouseDragged(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, button,
                            (x2 - x1) / steps, (y2 - y1) / steps);
                }
                screen.mouseReleased(x2, y2, button);
                setMouse(x2, y2);
            }
            case "scroll" -> {
                requireScreen(screen);
                screen.mouseScrolled(mouseX, mouseY, 0, Double.parseDouble(arg(parts, 1)));
            }
            case "key" -> {
                requireScreen(screen);
                int code = keyCode(arg(parts, 1));
                screen.keyPressed(code, 0, 0);
                screen.keyReleased(code, 0, 0);
            }
            case "type" -> {
                requireScreen(screen);
                String text = line.substring(line.indexOf(' ') + 1);
                for (int i = 0; i < text.length(); i++) {
                    screen.charTyped(text.charAt(i), 0);
                }
            }
            case "wait" -> waitTicks = Integer.parseInt(arg(parts, 1));
            case "guiscale" -> {
                minecraft.options.guiScale().set(Integer.parseInt(arg(parts, 1)));
                minecraft.resizeDisplay();
            }
            case "screenshot" -> {
                String name = arg(parts, 1);
                if (!name.endsWith(".png")) {
                    name += ".png";
                }
                pendingShots.incrementAndGet();
                Screenshot.grab(dir.toFile(), name, minecraft.getMainRenderTarget(),
                        component -> pendingShots.decrementAndGet());
                return dir.resolve("screenshots").resolve(name).toString();
            }
            case "dump" -> {
                String name = arg(parts, 1);
                if (!name.endsWith(".json")) {
                    name += ".json";
                }
                Path out = dir.resolve(name);
                Files.writeString(out, GSON.toJson(WidgetTreeDump.dump(screen)));
                return out.toString();
            }
            default -> throw new IllegalArgumentException("Unknown command '" + cmd + "'");
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void setMouse(float x, float y) {
        mouseX = x;
        mouseY = y;
        mouseOverride = true;
    }

    private static String arg(String[] parts, int i) {
        if (i >= parts.length) {
            throw new IllegalArgumentException("Missing argument " + i + " for '" + parts[0] + "'");
        }
        return parts[i];
    }

    private static void requireScreen(Screen screen) {
        if (screen == null) {
            throw new IllegalStateException("No screen is open");
        }
    }

    private static Widget findWidget(Screen screen, String id) {
        if (!(screen instanceof EasyScreen easy)) {
            throw new IllegalStateException("Widget ids require an EasyScreen");
        }
        Widget found = WidgetTreeDump.findById(easy.getRoot(), id);
        if (found == null) {
            throw new IllegalArgumentException("No widget with id '" + id + "'");
        }
        return found;
    }

    private static int keyCode(String name) {
        Integer mapped = KEYS.get(name.toLowerCase(Locale.ROOT));
        if (mapped != null) {
            return mapped;
        }
        if (name.length() == 1) {
            char c = Character.toUpperCase(name.charAt(0));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                return c;
            }
        }
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unknown key '" + name + "'");
        }
    }

    private static Map<String, Integer> buildKeyMap() {
        Map<String, Integer> keys = new HashMap<>();
        keys.put("enter", GLFW.GLFW_KEY_ENTER);
        keys.put("escape", GLFW.GLFW_KEY_ESCAPE);
        keys.put("esc", GLFW.GLFW_KEY_ESCAPE);
        keys.put("tab", GLFW.GLFW_KEY_TAB);
        keys.put("backspace", GLFW.GLFW_KEY_BACKSPACE);
        keys.put("delete", GLFW.GLFW_KEY_DELETE);
        keys.put("space", GLFW.GLFW_KEY_SPACE);
        keys.put("up", GLFW.GLFW_KEY_UP);
        keys.put("down", GLFW.GLFW_KEY_DOWN);
        keys.put("left", GLFW.GLFW_KEY_LEFT);
        keys.put("right", GLFW.GLFW_KEY_RIGHT);
        keys.put("home", GLFW.GLFW_KEY_HOME);
        keys.put("end", GLFW.GLFW_KEY_END);
        keys.put("pageup", GLFW.GLFW_KEY_PAGE_UP);
        keys.put("pagedown", GLFW.GLFW_KEY_PAGE_DOWN);
        return keys;
    }
}
