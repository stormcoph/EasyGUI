package com.stormcph.easygui.client.theme;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Color palette and shape metrics shared by all EasyGUI widgets.
 *
 * <p>Widgets read the theme each frame, so swapping {@link #setDefault(Theme)} (or a
 * screen's theme) re-skins the UI live. Start from {@link #dark()} or {@link #light()}
 * and tweak fields, e.g. {@code Theme.dark().accent = 0xFFFF6B81}.</p>
 */
@Environment(EnvType.CLIENT)
public class Theme {
    /** Full-screen dim behind screens. */
    public int screenDim;
    /** Card / panel background. */
    public int surface;
    /** Slightly raised surface (rows, inputs). */
    public int surfaceVariant;
    /** Hovered surface. */
    public int surfaceHover;
    /** Primary accent (buttons, toggles, focus). */
    public int accent;
    /** Hovered/active accent. */
    public int accentHover;
    /** Text/icons placed on top of accent-colored fills. */
    public int onAccent;
    /** Primary text. */
    public int text;
    /** Secondary/muted text. */
    public int textMuted;
    /** Hairline outlines. */
    public int outline;
    /** Destructive actions. */
    public int danger;
    public int dangerHover;
    /** Positive/confirmation. */
    public int success;
    /** Drop shadow color. */
    public int shadow;
    /** Tooltip background. */
    public int tooltipBackground;

    /** Default corner radius for cards/panels. */
    public float radius = 10f;
    /** Corner radius for small controls (buttons, inputs). */
    public float radiusSmall = 6f;
    /** Drop shadow size for cards. */
    public float shadowSize = 8f;

    private static Theme defaultTheme = dark();

    public static Theme getDefault() {
        return defaultTheme;
    }

    public static void setDefault(Theme theme) {
        defaultTheme = theme;
    }

    public static Theme dark() {
        Theme t = new Theme();
        t.screenDim = 0xA50C0C10;
        t.surface = 0xFF15151C;
        t.surfaceVariant = 0xFF1E1E27;
        t.surfaceHover = 0xFF2A2A36;
        t.accent = 0xFF5B8CFF;
        t.accentHover = 0xFF7AA2FF;
        t.onAccent = 0xFFFFFFFF;
        t.text = 0xFFECECF1;
        t.textMuted = 0xFF9A9AA8;
        t.outline = 0x28FFFFFF;
        t.danger = 0xFFE5484D;
        t.dangerHover = 0xFFFF6369;
        t.success = 0xFF46C26E;
        t.shadow = 0x66000000;
        t.tooltipBackground = 0xF2070709;
        return t;
    }

    public static Theme light() {
        Theme t = new Theme();
        t.screenDim = 0x73101018;
        t.surface = 0xFFF7F7FA;
        t.surfaceVariant = 0xFFECECF1;
        t.surfaceHover = 0xFFDFDFE8;
        t.accent = 0xFF3D6DE0;
        t.accentHover = 0xFF5B8CFF;
        t.onAccent = 0xFFFFFFFF;
        t.text = 0xFF1B1B22;
        t.textMuted = 0xFF6E6E7C;
        t.outline = 0x22000000;
        t.danger = 0xFFD93036;
        t.dangerHover = 0xFFE5484D;
        t.success = 0xFF2E9E56;
        t.shadow = 0x40202030;
        t.tooltipBackground = 0xF2FFFFFF;
        return t;
    }
}
