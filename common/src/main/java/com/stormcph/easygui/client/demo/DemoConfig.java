package com.stormcph.easygui.client.demo;

import com.stormcph.easygui.client.EasyGuiClient;
import com.stormcph.easygui.client.config.ConfigValue;
import com.stormcph.easygui.client.config.EasyConfig;
import com.stormcph.easygui.client.font.Fonts;
import com.stormcph.easygui.client.font.TrueTypeFont;
import com.stormcph.easygui.client.render.ColorUtil;
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
    public static final ConfigValue<Integer> ACCENT =
            CONFIG.defineColor("appearance.accent", 0xFF5B8CFF);
    public static final ConfigValue<Boolean> HUD_OVERLAY =
            CONFIG.defineBool("overlay.visible", false);
    public static final ConfigValue<Boolean> HUD_TEXT =
            CONFIG.defineBool("overlay.info_text", false);
    public static final ConfigValue<Boolean> HUD_MODULES =
            CONFIG.defineBool("overlay.module_list", false);
    public static final ConfigValue<Boolean> HUD_CHART =
            CONFIG.defineBool("overlay.fps_chart", false);
    public static final ConfigValue<Double> DEMO_PROGRESS =
            CONFIG.defineDouble("state.progress", 0.65, 0.0, 1.0);
    public static final ConfigValue<Integer> LAST_TAB =
            CONFIG.defineInt("state.last_tab", 0);
    public static final ConfigValue<Boolean> SECTION_OPEN =
            CONFIG.defineBool("state.demo_section_open", true);

    private DemoConfig() {
    }

    /** Applies persisted preferences that don't need game resources. Called at client init. */
    public static void applyStartup() {
        Theme.setDefault(THEME.get() == ThemeChoice.LIGHT ? Theme.light() : Theme.dark());
        applyAccent(Theme.getDefault());
        EasyGuiClient.DEMO_OVERLAY.setVisible(HUD_OVERLAY.get());
    }

    /** Applies the persisted accent color to {@code theme}, deriving the hover shade. */
    public static void applyAccent(Theme theme) {
        theme.accent = ACCENT.get();
        theme.accentHover = ColorUtil.shift(theme.accent, 0.18f);
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
