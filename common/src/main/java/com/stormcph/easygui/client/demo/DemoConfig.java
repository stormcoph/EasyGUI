package com.stormcph.easygui.client.demo;

import com.stormcph.easygui.client.EasyGuiClient;
import com.stormcph.easygui.client.config.ConfigValue;
import com.stormcph.easygui.client.config.EasyConfig;
import com.stormcph.easygui.client.font.Fonts;
import com.stormcph.easygui.client.font.TrueTypeFont;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Persisted settings for the demo screen and overlay ({@code config/easygui-demo.json}).
 * Doubles as example code for {@link EasyConfig}: preferences (theme, font, frosted
 * glass), a numeric value (the slider), and pure UI state (the scroll position you left
 * the list at) all survive restarts.
 */
@Environment(EnvType.CLIENT)
public final class DemoConfig {
    public enum ThemeChoice {DARK, LIGHT}

    public static final EasyConfig CONFIG = EasyConfig.of("easygui-demo");

    public static final ConfigValue<ThemeChoice> THEME =
            CONFIG.defineEnum("appearance.theme", ThemeChoice.DARK);
    public static final ConfigValue<Boolean> FROSTED_CARD =
            CONFIG.defineBool("appearance.frosted_card", true);
    public static final ConfigValue<Boolean> INTER_FONT =
            CONFIG.defineBool("appearance.inter_font", false);
    public static final ConfigValue<Boolean> HUD_OVERLAY =
            CONFIG.defineBool("overlay.visible", false);
    public static final ConfigValue<Double> DEMO_PROGRESS =
            CONFIG.defineDouble("state.progress", 0.65, 0.0, 1.0);
    public static final ConfigValue<Double> LIST_SCROLL =
            CONFIG.defineDouble("state.list_scroll", 0.0);

    private DemoConfig() {
    }

    /** Applies persisted preferences that don't need game resources. Called at client init. */
    public static void applyStartup() {
        Theme.setDefault(THEME.get() == ThemeChoice.LIGHT ? Theme.light() : Theme.dark());
        EasyGuiClient.DEMO_OVERLAY.setVisible(HUD_OVERLAY.get());
    }

    /** Applies the persisted font preference. Called once resources are loaded. */
    public static void applyFont() {
        if (INTER_FONT.get()) {
            TrueTypeFont inter = Fonts.inter();
            if (inter != null) {
                Text2D.setUiFont(inter, 9f);
            }
        }
    }
}
