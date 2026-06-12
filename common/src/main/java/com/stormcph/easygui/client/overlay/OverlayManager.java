package com.stormcph.easygui.client.overlay;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientScreenInputEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry and render loop for {@link HudOverlay}s. Overlays render during the vanilla
 * HUD pass (after vanilla elements) in registration order, on both Fabric and NeoForge.
 *
 * <p>Two ways to let players rearrange overlays (both with snap guides, right-click
 * reset, and automatic persistence for overlays with a {@link HudOverlay#setPersistId
 * persist id}):</p>
 * <ul>
 *   <li>open the dedicated editor — {@code minecraft.setScreen(new HudEditScreen(parent))}
 *       (wire it to a button in your settings screen), or</li>
 *   <li>{@link #setMoveInChat(boolean) setMoveInChat(true)} — overlays become draggable
 *       whenever the chat screen is open.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class OverlayManager {
    private static final List<HudOverlay> OVERLAYS = new CopyOnWriteArrayList<>();
    private static boolean initialized;
    private static boolean moveInChat;

    private OverlayManager() {
    }

    public static void register(HudOverlay overlay) {
        if (!OVERLAYS.contains(overlay)) {
            OVERLAYS.add(overlay);
            OverlayEditor.onRegister(overlay);
        }
    }

    public static void unregister(HudOverlay overlay) {
        OVERLAYS.remove(overlay);
    }

    public static List<HudOverlay> getOverlays() {
        return List.copyOf(OVERLAYS);
    }

    /** Internal live view for the editor. */
    static List<HudOverlay> overlays() {
        return OVERLAYS;
    }

    /** When enabled, overlays can be dragged around while the chat screen is open. */
    public static void setMoveInChat(boolean enabled) {
        moveInChat = enabled;
    }

    public static boolean isMoveInChat() {
        return moveInChat;
    }

    private static boolean chatEditActive(Minecraft minecraft) {
        return moveInChat && minecraft.screen instanceof ChatScreen;
    }

    /** Called once from EasyGUI's client init. */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientGuiEvent.RENDER_HUD.register((graphics, tickDelta) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.options.hideGui) {
                return;
            }
            if (minecraft.screen instanceof HudEditScreen) {
                return; // the editor renders (and ghosts) the overlays itself
            }
            float screenWidth = graphics.guiWidth();
            float screenHeight = graphics.guiHeight();
            float partialTick = tickDelta.getGameTimeDeltaPartialTick(true);
            for (HudOverlay overlay : OVERLAYS) {
                if (!overlay.isVisible()) {
                    continue;
                }
                float x = overlay.getAnchor().resolveX(screenWidth, overlay.getWidth(), overlay.offsetX);
                float y = overlay.getAnchor().resolveY(screenHeight, overlay.getHeight(), overlay.offsetY);
                overlay.render(graphics, x, y, partialTick);
            }
            if (chatEditActive(minecraft)) {
                var window = minecraft.getWindow();
                double mouseX = minecraft.mouseHandler.xpos()
                        * window.getGuiScaledWidth() / Math.max(1, window.getScreenWidth());
                double mouseY = minecraft.mouseHandler.ypos()
                        * window.getGuiScaledHeight() / Math.max(1, window.getScreenHeight());
                OverlayEditor.renderChrome(graphics, mouseX, mouseY, false);
            } else if (OverlayEditor.isDragging()) {
                OverlayEditor.finishDrag(); // chat closed mid-drag
            }
        });

        // Chat-screen editing: intercept the chat screen's mouse input while enabled.
        ClientScreenInputEvent.MOUSE_CLICKED_PRE.register((client, screen, mouseX, mouseY, button) ->
                chatEditActive(client) && OverlayEditor.mouseClicked(mouseX, mouseY, button, false)
                        ? EventResult.interruptTrue() : EventResult.pass());
        ClientScreenInputEvent.MOUSE_DRAGGED_PRE.register((client, screen, mouseX, mouseY, button, dragX, dragY) ->
                chatEditActive(client) && OverlayEditor.mouseDragged(mouseX, mouseY, false)
                        ? EventResult.interruptTrue() : EventResult.pass());
        ClientScreenInputEvent.MOUSE_RELEASED_PRE.register((client, screen, mouseX, mouseY, button) ->
                chatEditActive(client) && OverlayEditor.mouseReleased(button)
                        ? EventResult.interruptTrue() : EventResult.pass());
    }
}
