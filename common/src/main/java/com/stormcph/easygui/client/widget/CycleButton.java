package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.Animation;
import com.stormcph.easygui.client.animation.Easing;
import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A button that cycles through a fixed option list on click ("Mode: Fancy &rarr; Fast
 * &rarr; Off"). Left click advances, right click goes back; the option text crossfades
 * with a small vertical slide. {@link #ofEnum} builds one straight from an enum.
 */
@Environment(EnvType.CLIENT)
public class CycleButton extends Widget {
    private final String label;
    private final List<String> options;
    private int index;
    private Consumer<Integer> onChange;
    private boolean playSound = true;

    private final SmoothValue pressAnim = new SmoothValue(0f, 22f);
    private final Animation swapAnim = new Animation(170, Easing.CUBIC_OUT);
    private String previousOption;
    private int swapDirection = 1;
    private boolean pressed;

    public CycleButton(String label, List<String> options, int initial, Consumer<Integer> onChange) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("CycleButton needs at least one option");
        }
        this.label = label;
        this.options = List.copyOf(options);
        this.index = Mth.clamp(initial, 0, options.size() - 1);
        this.onChange = onChange;
        this.height = 20f;
    }

    /** Builds a cycle button over all constants of {@code enumClass}, prettifying their names. */
    public static <E extends Enum<E>> CycleButton ofEnum(String label, Class<E> enumClass, E initial,
                                                         Consumer<E> onSelect) {
        E[] constants = enumClass.getEnumConstants();
        List<String> names = new ArrayList<>(constants.length);
        for (E constant : constants) {
            names.add(prettify(constant.name()));
        }
        return new CycleButton(label, names, initial.ordinal(),
                i -> onSelect.accept(constants[i]));
    }

    /** "FAST_RENDER" &rarr; "Fast render". */
    private static String prettify(String constantName) {
        String lower = constantName.toLowerCase().replace('_', ' ');
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public int getIndex() {
        return index;
    }

    public String getOption() {
        return options.get(index);
    }

    public CycleButton setIndex(int newIndex) {
        this.index = Mth.clamp(newIndex, 0, options.size() - 1);
        return this;
    }

    public CycleButton setPlaySound(boolean playSound) {
        this.playSound = playSound;
        return this;
    }

    private void cycle(int direction) {
        previousOption = options.get(index);
        swapDirection = direction;
        index = Math.floorMod(index + direction, options.size());
        swapAnim.start();
        if (playSound) {
            Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
        if (onChange != null) {
            onChange.accept(index);
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float hover = hoverAmount();
        pressAnim.setTarget(pressed ? 1f : 0f);
        float press = pressAnim.get();

        int bg = ColorUtil.lerp(theme.surfaceVariant, theme.surfaceHover, hover);
        int labelColor = theme.textMuted;
        int optionColor = theme.text;
        if (!enabled) {
            bg = ColorUtil.multiplyAlpha(bg, 0.45f);
            labelColor = ColorUtil.multiplyAlpha(labelColor, 0.45f);
            optionColor = ColorUtil.multiplyAlpha(optionColor, 0.45f);
        }

        var pose = graphics.pose();
        pose.pushPose();
        float scale = 1f - press * 0.04f;
        pose.translate(x + width / 2f, y + height / 2f, 0);
        pose.scale(scale, scale, 1f);
        pose.translate(-(x + width / 2f), -(y + height / 2f), 0);

        float r = theme.radiusSmall;
        Render2D.fillRoundedRect(graphics, x, y, width, height, r, bg);
        Render2D.strokeRoundedRect(graphics, x, y, width, height, r, 1f, theme.outline);

        String prefix = label == null || label.isEmpty() ? "" : label + ": ";
        String option = options.get(index);
        float totalW = Text2D.width(prefix) + Text2D.width(option);
        float startX = x + (width - totalW) / 2f;
        float textY = y + (height - Text2D.lineHeight()) / 2f + 0.5f;

        if (!prefix.isEmpty()) {
            Text2D.draw(graphics, prefix, startX, textY, labelColor);
        }
        float optionX = startX + Text2D.width(prefix);
        if (swapAnim.isRunning() && previousOption != null) {
            // Old option slides out one way, new option slides in from the other
            float p = swapAnim.value();
            float slide = 5f * swapDirection;
            Text2D.draw(graphics, previousOption, optionX, textY - slide * p,
                    ColorUtil.multiplyAlpha(labelColor, 1f - p));
            Text2D.draw(graphics, option, optionX, textY + slide * (1f - p),
                    ColorUtil.multiplyAlpha(optionColor, p));
        } else {
            Text2D.draw(graphics, option, optionX, textY, optionColor);
        }

        pose.popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || (button != 0 && button != 1) || !contains(mouseX, mouseY)) {
            return false;
        }
        pressed = true;
        cycle(button == 0 ? 1 : -1);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (pressed && (button == 0 || button == 1)) {
            pressed = false;
            return true;
        }
        return false;
    }
}
