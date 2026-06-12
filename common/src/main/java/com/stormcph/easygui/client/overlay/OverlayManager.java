package com.stormcph.easygui.client.overlay;

import dev.architectury.event.events.client.ClientGuiEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry and render loop for {@link HudOverlay}s. Overlays render during the vanilla
 * HUD pass (after vanilla elements) in registration order, on both Fabric and NeoForge.
 */
@Environment(EnvType.CLIENT)
public final class OverlayManager {
    private static final List<HudOverlay> OVERLAYS = new CopyOnWriteArrayList<>();
    private static boolean initialized;

    private OverlayManager() {
    }

    public static void register(HudOverlay overlay) {
        if (!OVERLAYS.contains(overlay)) {
            OVERLAYS.add(overlay);
        }
    }

    public static void unregister(HudOverlay overlay) {
        OVERLAYS.remove(overlay);
    }

    public static List<HudOverlay> getOverlays() {
        return List.copyOf(OVERLAYS);
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
        });
    }
}
