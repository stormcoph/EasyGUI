package com.stormcph.easygui.client.widget;

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

import java.util.List;
import java.util.function.Consumer;

/**
 * An exclusive-choice radio group rendered as equal-width segments with an accent pill
 * that slides to the selected option. Best for 2-4 short options; for longer lists use
 * {@link Dropdown}.
 */
@Environment(EnvType.CLIENT)
public class SegmentedControl extends Widget {
    private static final float INSET = 2f;

    private final List<String> options;
    private int selected;
    private Consumer<Integer> onChange;
    private boolean playSound = true;

    private final SmoothValue pillAnim;

    public SegmentedControl(List<String> options, int initial, Consumer<Integer> onChange) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("SegmentedControl needs at least one option");
        }
        this.options = List.copyOf(options);
        this.selected = Mth.clamp(initial, 0, options.size() - 1);
        this.onChange = onChange;
        this.pillAnim = new SmoothValue(this.selected, 18f);
        this.height = 20f;
    }

    public int getSelected() {
        return selected;
    }

    public SegmentedControl setSelected(int index) {
        this.selected = Mth.clamp(index, 0, options.size() - 1);
        pillAnim.setTarget(this.selected);
        return this;
    }

    public SegmentedControl setPlaySound(boolean playSound) {
        this.playSound = playSound;
        return this;
    }

    private float segmentWidth() {
        return (width - INSET * 2) / options.size();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float hover = hoverAmount();
        float r = theme.radiusSmall;
        float segW = segmentWidth();

        int bg = enabled ? theme.surfaceVariant : ColorUtil.multiplyAlpha(theme.surfaceVariant, 0.45f);
        Render2D.fillRoundedRect(graphics, x, y, width, height, r, bg);
        Render2D.strokeRoundedRect(graphics, x, y, width, height, r, 1f, theme.outline);

        pillAnim.setTarget(selected);
        float pos = pillAnim.get();
        int hoveredSegment = isHovered() ? segmentAt(mouseX) : -1;

        int pillColor = ColorUtil.lerp(theme.accent, theme.accentHover,
                hoveredSegment == selected ? hover : 0f);
        if (!enabled) {
            pillColor = ColorUtil.multiplyAlpha(pillColor, 0.45f);
        }
        Render2D.fillRoundedRect(graphics, x + INSET + segW * pos, y + INSET,
                segW, height - INSET * 2, Math.max(2f, r - 2f), pillColor);

        float textY = y + (height - Text2D.lineHeight()) / 2f + 0.5f;
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i);
            // Text blends toward onAccent as the pill slides under it
            float proximity = Mth.clamp(1f - Math.abs(i - pos), 0f, 1f);
            int idle = i == hoveredSegment ? theme.text : theme.textMuted;
            int color = ColorUtil.lerp(idle, theme.onAccent, proximity);
            if (!enabled) {
                color = ColorUtil.multiplyAlpha(color, 0.45f);
            }
            float centerX = x + INSET + segW * i + segW / 2f;
            Text2D.draw(graphics, option, centerX - Text2D.width(option) / 2f, textY, color);
        }
    }

    private int segmentAt(double mouseX) {
        return Mth.clamp((int) ((mouseX - x - INSET) / segmentWidth()), 0, options.size() - 1);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || button != 0 || !contains(mouseX, mouseY)) {
            return false;
        }
        int clicked = segmentAt(mouseX);
        if (clicked != selected) {
            selected = clicked;
            pillAnim.setTarget(selected);
            if (playSound) {
                Minecraft.getInstance().getSoundManager()
                        .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
            }
            if (onChange != null) {
                onChange.accept(selected);
            }
        }
        return true;
    }
}
