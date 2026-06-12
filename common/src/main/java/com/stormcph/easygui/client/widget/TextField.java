package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * A single-line text input with cursor, selection, clipboard support, word jumping,
 * placeholder text, horizontal scrolling and an animated focus outline.
 */
@Environment(EnvType.CLIENT)
public class TextField extends Widget {
    private static final float PADDING = 7f;

    private String text = "";
    private String placeholder = "";
    private int maxLength = 256;
    private Consumer<String> onChange;
    private Consumer<String> onSubmit;

    private int cursor;
    private int selectionAnchor;
    private float scrollOffset;
    private long lastInteraction;
    private boolean selectingWithMouse;

    private final SmoothValue focusAnim = new SmoothValue(0f, 14f);

    public TextField(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
        this.height = 20f;
    }

    public TextField setOnChange(Consumer<String> onChange) {
        this.onChange = onChange;
        return this;
    }

    /** Called when the user presses Enter. */
    public TextField setOnSubmit(Consumer<String> onSubmit) {
        this.onSubmit = onSubmit;
        return this;
    }

    public TextField setMaxLength(int maxLength) {
        this.maxLength = Math.max(0, maxLength);
        return this;
    }

    public String getText() {
        return text;
    }

    public TextField setText(String newText) {
        this.text = newText == null ? "" : newText;
        if (this.text.length() > maxLength) {
            this.text = this.text.substring(0, maxLength);
        }
        cursor = Mth.clamp(cursor, 0, text.length());
        selectionAnchor = cursor;
        return this;
    }

    private boolean hasSelection() {
        return selectionAnchor != cursor;
    }

    private int selectionStart() {
        return Math.min(selectionAnchor, cursor);
    }

    private int selectionEnd() {
        return Math.max(selectionAnchor, cursor);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        focusAnim.setTarget(focused ? 1f : 0f);
        float focusT = focusAnim.get();
        float hover = hoverAmount();

        float r = theme.radiusSmall;
        int bg = ColorUtil.lerp(theme.surfaceVariant, theme.surfaceHover, hover * 0.5f);
        Render2D.fillRoundedRect(graphics, x, y, width, height, r, bg);
        int outlineColor = ColorUtil.lerp(theme.outline, theme.accent, focusT);
        Render2D.strokeRoundedRect(graphics, x, y, width, height, r, 1f + focusT * 0.5f, outlineColor);

        float innerX = x + PADDING;
        float innerW = width - PADDING * 2;
        float textY = y + (height - Text2D.lineHeight()) / 2f + 0.5f;

        ensureCursorVisible(innerW);

        Render2D.pushScissor(graphics, x + 2, y, width - 4, height);

        if (text.isEmpty() && !placeholder.isEmpty()) {
            Text2D.draw(graphics, placeholder, innerX, textY, theme.textMuted);
        } else {
            // Selection highlight behind the text
            if (hasSelection() && focused) {
                float selX1 = innerX - scrollOffset + Text2D.width(text.substring(0, selectionStart()));
                float selX2 = innerX - scrollOffset + Text2D.width(text.substring(0, selectionEnd()));
                Render2D.fillRect(graphics, selX1, textY - 1.5f, selX2 - selX1, Text2D.lineHeight() + 2.5f,
                        ColorUtil.withAlpha(theme.accent, 0.35f));
            }
            Text2D.draw(graphics, text, innerX - scrollOffset, textY, theme.text);
        }

        // Blinking cursor
        if (focused) {
            boolean blinkOn = (Util.getMillis() - lastInteraction) % 1000 < 530;
            if (blinkOn) {
                float cursorX = innerX - scrollOffset + Text2D.width(text.substring(0, cursor));
                Render2D.fillRect(graphics, cursorX, textY - 1.5f, 1f, Text2D.lineHeight() + 2.5f, theme.text);
            }
        }

        Render2D.popScissor(graphics);
    }

    private void ensureCursorVisible(float innerWidth) {
        float cursorX = Text2D.width(text.substring(0, cursor));
        if (cursorX - scrollOffset < 0) {
            scrollOffset = cursorX;
        } else if (cursorX - scrollOffset > innerWidth) {
            scrollOffset = cursorX - innerWidth;
        }
        float maxScroll = Math.max(0, Text2D.width(text) - innerWidth);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll + 2);
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    private void insert(String insertion) {
        StringBuilder filtered = new StringBuilder();
        for (char c : insertion.toCharArray()) {
            if (StringUtil.isAllowedChatCharacter(c)) {
                filtered.append(c);
            }
        }
        if (filtered.isEmpty() && !insertion.isEmpty() && hasSelection()) {
            return;
        }
        String before = text.substring(0, selectionStart());
        String after = text.substring(selectionEnd());
        String inserted = filtered.toString();
        int room = maxLength - before.length() - after.length();
        if (inserted.length() > room) {
            inserted = inserted.substring(0, Math.max(0, room));
        }
        text = before + inserted + after;
        cursor = before.length() + inserted.length();
        selectionAnchor = cursor;
        notifyChange();
    }

    private void deleteSelectionOr(int direction) {
        if (hasSelection()) {
            text = text.substring(0, selectionStart()) + text.substring(selectionEnd());
            cursor = selectionStart();
            selectionAnchor = cursor;
            notifyChange();
            return;
        }
        if (direction < 0 && cursor > 0) {
            text = text.substring(0, cursor - 1) + text.substring(cursor);
            cursor--;
            selectionAnchor = cursor;
            notifyChange();
        } else if (direction > 0 && cursor < text.length()) {
            text = text.substring(0, cursor) + text.substring(cursor + 1);
            selectionAnchor = cursor;
            notifyChange();
        }
    }

    private void notifyChange() {
        lastInteraction = Util.getMillis();
        if (onChange != null) {
            onChange.accept(text);
        }
    }

    private int wordBoundary(int from, int direction) {
        int pos = from;
        if (direction < 0) {
            while (pos > 0 && text.charAt(pos - 1) == ' ') pos--;
            while (pos > 0 && text.charAt(pos - 1) != ' ') pos--;
        } else {
            while (pos < text.length() && text.charAt(pos) != ' ') pos++;
            while (pos < text.length() && text.charAt(pos) == ' ') pos++;
        }
        return pos;
    }

    private void moveCursor(int newPos, boolean extendSelection) {
        cursor = Mth.clamp(newPos, 0, text.length());
        if (!extendSelection) {
            selectionAnchor = cursor;
        }
        lastInteraction = Util.getMillis();
    }

    private int cursorFromMouseX(double mouseX) {
        float relX = (float) (mouseX - (x + PADDING)) + scrollOffset;
        if (relX <= 0) {
            return 0;
        }
        return Text2D.font().plainSubstrByWidth(text, (int) relX).length();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /** Focusable for Tab traversal; the animated accent outline doubles as the focus ring. */
    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || button != 0 || !contains(mouseX, mouseY)) {
            return false;
        }
        requestFocus();
        selectingWithMouse = true;
        moveCursor(cursorFromMouseX(mouseX), Screen.hasShiftDown());
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (selectingWithMouse && button == 0) {
            moveCursor(cursorFromMouseX(mouseX), true);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (selectingWithMouse && button == 0) {
            selectingWithMouse = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) {
            return false;
        }
        boolean shift = Screen.hasShiftDown();
        boolean ctrl = Screen.hasControlDown();

        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                moveCursor(ctrl ? wordBoundary(cursor, -1) : cursor - 1, shift);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                moveCursor(ctrl ? wordBoundary(cursor, 1) : cursor + 1, shift);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                moveCursor(0, shift);
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                moveCursor(text.length(), shift);
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (ctrl && !hasSelection()) {
                    int boundary = wordBoundary(cursor, -1);
                    selectionAnchor = boundary;
                }
                deleteSelectionOr(-1);
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                deleteSelectionOr(1);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (onSubmit != null) {
                    onSubmit.accept(text);
                }
                return true;
            }
        }

        if (ctrl) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_A -> {
                    selectionAnchor = 0;
                    cursor = text.length();
                    return true;
                }
                case GLFW.GLFW_KEY_C -> {
                    if (hasSelection()) {
                        Minecraft.getInstance().keyboardHandler
                                .setClipboard(text.substring(selectionStart(), selectionEnd()));
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_X -> {
                    if (hasSelection()) {
                        Minecraft.getInstance().keyboardHandler
                                .setClipboard(text.substring(selectionStart(), selectionEnd()));
                        deleteSelectionOr(0);
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_V -> {
                    insert(Minecraft.getInstance().keyboardHandler.getClipboard());
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (!focused || !StringUtil.isAllowedChatCharacter(chr)) {
            return false;
        }
        insert(String.valueOf(chr));
        return true;
    }
}
