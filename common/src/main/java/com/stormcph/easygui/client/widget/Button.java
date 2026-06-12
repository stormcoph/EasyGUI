package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.Animation;
import com.stormcph.easygui.client.animation.Easing;
import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Icon;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * A clean rounded button with hover color blending, press scale, and a click ripple.
 * Supports a text label, an icon, or both. Variants map onto the theme palette.
 */
@Environment(EnvType.CLIENT)
public class Button extends Widget {
    public enum Variant {PRIMARY, SECONDARY, GHOST, DANGER}

    private String label;
    private Icon icon;
    private Runnable onClick;
    private Variant variant = Variant.PRIMARY;
    private boolean playSound = true;

    private final SmoothValue pressAnim = new SmoothValue(0f, 22f);
    private final Animation ripple = new Animation(420, Easing.CUBIC_OUT);
    private float rippleX;
    private float rippleY;
    private boolean pressed;

    public Button(String label, Runnable onClick) {
        this.label = label;
        this.onClick = onClick;
    }

    public Button(Icon icon, Runnable onClick) {
        this.icon = icon;
        this.onClick = onClick;
    }

    public Button(String label, Icon icon, Runnable onClick) {
        this.label = label;
        this.icon = icon;
        this.onClick = onClick;
    }

    public Button setVariant(Variant variant) {
        this.variant = variant;
        return this;
    }

    public Button setLabel(String label) {
        this.label = label;
        return this;
    }

    public Button setIcon(Icon icon) {
        this.icon = icon;
        return this;
    }

    public Button setOnClick(Runnable onClick) {
        this.onClick = onClick;
        return this;
    }

    public Button setPlaySound(boolean playSound) {
        this.playSound = playSound;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float hover = hoverAmount();
        pressAnim.setTarget(pressed ? 1f : 0f);
        float press = pressAnim.get();

        int base;
        int hovered;
        int content;
        switch (variant) {
            case PRIMARY -> {
                base = theme.accent;
                hovered = theme.accentHover;
                content = theme.onAccent;
            }
            case DANGER -> {
                base = theme.danger;
                hovered = theme.dangerHover;
                content = theme.onAccent;
            }
            case GHOST -> {
                base = ColorUtil.withAlpha(theme.surfaceHover, 0);
                hovered = theme.surfaceHover;
                content = theme.text;
            }
            default -> {
                base = theme.surfaceVariant;
                hovered = theme.surfaceHover;
                content = theme.text;
            }
        }
        int bg = ColorUtil.lerp(base, hovered, hover);
        if (!enabled) {
            bg = ColorUtil.multiplyAlpha(bg, 0.45f);
            content = ColorUtil.multiplyAlpha(content, 0.45f);
        }

        var pose = graphics.pose();
        pose.pushPose();
        float scale = 1f - press * 0.04f;
        pose.translate(x + width / 2f, y + height / 2f, 0);
        pose.scale(scale, scale, 1f);
        pose.translate(-(x + width / 2f), -(y + height / 2f), 0);

        float r = theme.radiusSmall;
        Render2D.fillRoundedRect(graphics, x, y, width, height, r, bg);
        if (variant == Variant.SECONDARY) {
            Render2D.strokeRoundedRect(graphics, x, y, width, height, r, 1f, theme.outline);
        }

        if (ripple.isRunning()) {
            float p = ripple.value();
            float maxRadius = Math.max(width, height) * 1.1f;
            int rippleColor = ColorUtil.withAlpha(0xFFFFFFFF, (1f - p) * 0.18f);
            // Clipped to the rounded silhouette (and follows the press-scale transform,
            // which a scissor region would not)
            Render2D.fillCircleInRoundedRect(graphics, rippleX, rippleY, maxRadius * p,
                    x, y, width, height, r, rippleColor);
        }

        float iconSize = Math.min(height - 8, 12f);
        boolean hasLabel = label != null && !label.isEmpty();
        if (icon != null && hasLabel) {
            float totalW = iconSize + 4 + Text2D.width(label);
            float startX = x + (width - totalW) / 2f;
            icon.render(graphics, startX, y + (height - iconSize) / 2f, iconSize, content);
            Text2D.drawVerticallyCentered(graphics, label, startX + iconSize + 4, y, height, content);
        } else if (icon != null) {
            icon.render(graphics, x + (width - iconSize) / 2f, y + (height - iconSize) / 2f, iconSize, content);
        } else if (hasLabel) {
            Text2D.draw(graphics, label, x + (width - Text2D.width(label)) / 2f,
                    y + (height - Text2D.lineHeight()) / 2f + 0.5f, content);
        }

        pose.popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || button != 0 || !contains(mouseX, mouseY)) {
            return false;
        }
        pressed = true;
        rippleX = (float) mouseX;
        rippleY = (float) mouseY;
        ripple.start();
        if (playSound) {
            Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
        if (onClick != null) {
            onClick.run();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (pressed && button == 0) {
            pressed = false;
            return true;
        }
        return false;
    }
}
