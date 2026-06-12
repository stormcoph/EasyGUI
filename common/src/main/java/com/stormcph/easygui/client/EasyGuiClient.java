package com.stormcph.easygui.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import com.stormcph.easygui.client.demo.DemoOverlay;
import com.stormcph.easygui.client.demo.DemoScreen;
import com.stormcph.easygui.client.overlay.OverlayManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side initialization: hooks the HUD overlay renderer and registers the
 * demo screen keybinding (F8 by default).
 */
@Environment(EnvType.CLIENT)
public final class EasyGuiClient {
    public static final DemoOverlay DEMO_OVERLAY = new DemoOverlay();

    private static KeyMapping openDemoKey;

    private EasyGuiClient() {
    }

    public static void init() {
        OverlayManager.init();
        OverlayManager.register(DEMO_OVERLAY);
        DEMO_OVERLAY.setVisible(false);

        openDemoKey = new KeyMapping("key.easygui.open_demo", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8, "key.categories.easygui");
        KeyMappingRegistry.register(openDemoKey);

        ClientTickEvent.CLIENT_POST.register(minecraft -> {
            while (openDemoKey.consumeClick()) {
                if (minecraft.screen == null) {
                    minecraft.setScreen(new DemoScreen());
                }
            }
        });
    }
}
