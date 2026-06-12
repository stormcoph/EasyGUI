package com.stormcph.easygui.client.widget;

import com.mojang.blaze3d.platform.InputConstants;
import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A keybind row: label on the left, key chip on the right. Click the chip, then press
 * any key or mouse button to bind it; Escape cancels, right-click clears the binding.
 * The chip turns red when another {@code KeybindButton} on the same screen (or the
 * optional {@link #setConflictDetector external detector}) holds the same key.
 */
@Environment(EnvType.CLIENT)
public class KeybindButton extends Widget {
    private String label;
    private InputConstants.Key key;
    private Consumer<InputConstants.Key> onChange;
    private Predicate<InputConstants.Key> conflictDetector;
    private boolean listening;

    private final SmoothValue listenAnim = new SmoothValue(0f, 14f);

    public KeybindButton(String label, InputConstants.Key initial, Consumer<InputConstants.Key> onChange) {
        this.label = label;
        this.key = initial == null ? InputConstants.UNKNOWN : initial;
        this.onChange = onChange;
        this.height = 20f;
    }

    /** Convenience constructor for a GLFW keyboard key code. */
    public KeybindButton(String label, int keyCode, Consumer<InputConstants.Key> onChange) {
        this(label, InputConstants.Type.KEYSYM.getOrCreate(keyCode), onChange);
    }

    public InputConstants.Key getKey() {
        return key;
    }

    public KeybindButton setKey(InputConstants.Key newKey) {
        this.key = newKey == null ? InputConstants.UNKNOWN : newKey;
        return this;
    }

    public boolean isListening() {
        return listening;
    }

    /**
     * Extra conflict source beyond sibling {@code KeybindButton}s — return {@code true}
     * if the candidate key collides with a binding owned elsewhere (e.g. vanilla keymaps).
     */
    public KeybindButton setConflictDetector(Predicate<InputConstants.Key> conflictDetector) {
        this.conflictDetector = conflictDetector;
        return this;
    }

    private boolean hasConflict() {
        if (key.equals(InputConstants.UNKNOWN)) {
            return false;
        }
        if (conflictDetector != null && conflictDetector.test(key)) {
            return true;
        }
        EasyScreen screen = getScreen();
        return screen != null && conflictsWithin(screen.getRoot());
    }

    private boolean conflictsWithin(Widget widget) {
        if (widget instanceof KeybindButton other && other != this && other.key.equals(key)) {
            return true;
        }
        if (widget instanceof Panel panel) {
            for (Widget child : panel.getChildren()) {
                if (conflictsWithin(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String chipText() {
        if (listening) {
            return "...";
        }
        if (key.equals(InputConstants.UNKNOWN)) {
            return "None";
        }
        return key.getDisplayName().getString();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float hover = hoverAmount();
        listenAnim.setTarget(listening ? 1f : 0f);
        float listenT = listenAnim.get();
        boolean conflict = !listening && hasConflict();

        if (label != null && !label.isEmpty()) {
            Text2D.drawVerticallyCentered(graphics, label, x, y, height,
                    enabled ? theme.text : theme.textMuted);
        }

        String chip = chipText();
        float chipW = Math.min(width, Math.max(48f, Text2D.width(chip) + 18f));
        float chipX = x + width - chipW;
        float r = theme.radiusSmall;

        int bg = ColorUtil.lerp(theme.surfaceVariant, theme.surfaceHover, hover);
        int outline = ColorUtil.lerp(theme.outline, theme.accent, listenT);
        int textColor = ColorUtil.lerp(theme.text, theme.accent, listenT);
        if (conflict) {
            outline = theme.danger;
            textColor = theme.danger;
        }
        if (!enabled) {
            bg = ColorUtil.multiplyAlpha(bg, 0.45f);
            textColor = ColorUtil.multiplyAlpha(textColor, 0.45f);
        }

        Render2D.fillRoundedRect(graphics, chipX, y, chipW, height, r, bg);
        Render2D.strokeRoundedRect(graphics, chipX, y, chipW, height, r, 1f + listenT * 0.5f, outline);
        Text2D.draw(graphics, chip, chipX + (chipW - Text2D.width(chip)) / 2f,
                y + (height - Text2D.lineHeight()) / 2f + 0.5f, textColor);
    }

    private void bind(InputConstants.Key newKey) {
        listening = false;
        if (!key.equals(newKey)) {
            key = newKey;
            if (onChange != null) {
                onChange.accept(key);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || !contains(mouseX, mouseY)) {
            return false;
        }
        if (listening) {
            bind(InputConstants.Type.MOUSE.getOrCreate(button));
            return true;
        }
        if (button == 0) {
            listening = true;
            requestFocus();
            return true;
        }
        if (button == 1) {
            bind(InputConstants.UNKNOWN);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!listening) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            listening = false;
            return true;
        }
        bind(InputConstants.getKey(keyCode, scanCode));
        return true;
    }

    @Override
    public void setFocused(boolean focused) {
        if (!focused) {
            listening = false;
        }
        super.setFocused(focused);
    }
}
