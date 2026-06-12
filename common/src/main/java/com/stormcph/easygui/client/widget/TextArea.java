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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A multi-line text input with soft word wrapping, cursor and selection across wrapped
 * lines, clipboard support, vertical scrolling, and the same focus styling as
 * {@link TextField}. Enter inserts a newline.
 */
@Environment(EnvType.CLIENT)
public class TextArea extends Widget {
    private static final float PAD_X = 7f;
    private static final float PAD_Y = 5f;
    private static final float LINE_SPACING = 2f;

    private String text = "";
    private String placeholder = "";
    private int maxLength = 4096;
    private Consumer<String> onChange;

    private int cursor;
    private int selectionAnchor;
    private float scrollY;
    private long lastInteraction;
    private boolean selectingWithMouse;

    private final SmoothValue focusAnim = new SmoothValue(0f, 14f);

    /** Wrapped visual lines as {@code [start, end)} ranges into {@link #text}. */
    private final List<int[]> lines = new ArrayList<>();
    private String wrappedText;
    private float wrappedWidth = -1f;

    public TextArea(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
        this.height = 48f;
    }

    public TextArea setOnChange(Consumer<String> onChange) {
        this.onChange = onChange;
        return this;
    }

    public TextArea setMaxLength(int maxLength) {
        this.maxLength = Math.max(0, maxLength);
        return this;
    }

    public String getText() {
        return text;
    }

    public TextArea setText(String newText) {
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
    // Wrapping
    // ------------------------------------------------------------------

    private float innerWidth() {
        return width - PAD_X * 2;
    }

    private float viewHeight() {
        return height - PAD_Y * 2;
    }

    private float lineHeight() {
        return Text2D.lineHeight() + LINE_SPACING;
    }

    private void ensureWrapped() {
        float innerW = innerWidth();
        if (text.equals(wrappedText) && innerW == wrappedWidth) {
            return;
        }
        lines.clear();
        int hardStart = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                wrapSegment(hardStart, i, innerW);
                hardStart = i + 1;
            }
        }
        wrappedText = text;
        wrappedWidth = innerW;
    }

    /** Greedily wraps {@code [start, end)} (one hard line) into visual lines. */
    private void wrapSegment(int start, int end, float maxWidth) {
        if (start >= end) {
            lines.add(new int[]{start, end});
            return;
        }
        int s = start;
        while (s < end) {
            int fit = fitChars(s, end, maxWidth);
            if (s + fit >= end) {
                lines.add(new int[]{s, end});
                return;
            }
            int breakAt = -1;
            for (int j = s + fit; j > s; j--) {
                if (text.charAt(j - 1) == ' ') {
                    breakAt = j; // break after the space; it stays on this line
                    break;
                }
            }
            if (breakAt <= s) {
                breakAt = s + fit; // single unbreakable word: hard-break mid-word
            }
            lines.add(new int[]{s, breakAt});
            s = breakAt;
        }
    }

    /** Max characters of {@code text[from, to)} fitting in {@code maxWidth} (at least 1). */
    private int fitChars(int from, int to, float maxWidth) {
        int lo = 1;
        int hi = to - from;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (Text2D.width(text.substring(from, from + mid)) <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** The visual line holding {@code index}; a soft-wrap boundary belongs to the next line. */
    private int lineOf(int index) {
        for (int i = 0; i < lines.size(); i++) {
            int[] line = lines.get(i);
            boolean lastLine = i == lines.size() - 1;
            if (index < line[1] || (index == line[1] && (lastLine || lines.get(i + 1)[0] != line[1]))) {
                return i;
            }
        }
        return Math.max(0, lines.size() - 1);
    }

    private float offsetInLine(int lineIndex, int index) {
        int[] line = lines.get(lineIndex);
        return Text2D.width(text.substring(line[0], Mth.clamp(index, line[0], line[1])));
    }

    /** Character index within visual line {@code lineIndex} closest to {@code xOffset}. */
    private int indexAtOffset(int lineIndex, float xOffset) {
        int[] line = lines.get(lineIndex);
        float consumed = 0f;
        for (int i = line[0]; i < line[1]; i++) {
            float charW = Text2D.width(text.substring(line[0], i + 1)) - consumed;
            if (consumed + charW / 2f > xOffset) {
                return i;
            }
            consumed += charW;
        }
        return line[1];
    }

    private int indexAt(double mouseX, double mouseY) {
        ensureWrapped();
        int lineIndex = Mth.clamp((int) ((mouseY - (y + PAD_Y) + scrollY) / lineHeight()), 0, lines.size() - 1);
        return indexAtOffset(lineIndex, (float) (mouseX - (x + PAD_X)));
    }

    private float maxScroll() {
        return Math.max(0f, lines.size() * lineHeight() - viewHeight());
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        ensureWrapped();
        focusAnim.setTarget(focused ? 1f : 0f);
        float focusT = focusAnim.get();
        float hover = hoverAmount();
        float lineH = lineHeight();

        float r = theme.radiusSmall;
        int bg = ColorUtil.lerp(theme.surfaceVariant, theme.surfaceHover, hover * 0.5f);
        Render2D.fillRoundedRect(graphics, x, y, width, height, r, bg);
        int outlineColor = ColorUtil.lerp(theme.outline, theme.accent, focusT);
        Render2D.strokeRoundedRect(graphics, x, y, width, height, r, 1f + focusT * 0.5f, outlineColor);

        if (focused) {
            // Keep the caret line inside the viewport
            float caretTop = lineOf(cursor) * lineH;
            if (caretTop - scrollY < 0) {
                scrollY = caretTop;
            } else if (caretTop + lineH - scrollY > viewHeight()) {
                scrollY = caretTop + lineH - viewHeight();
            }
        }
        scrollY = Mth.clamp(scrollY, 0f, maxScroll());

        float innerX = x + PAD_X;
        float topY = y + PAD_Y;
        Render2D.pushScissor(graphics, x + 2, y + 2, width - 4, height - 4);

        if (text.isEmpty() && !placeholder.isEmpty()) {
            Text2D.draw(graphics, placeholder, innerX, topY + 0.5f, theme.textMuted);
        } else {
            int selStart = selectionStart();
            int selEnd = selectionEnd();
            int firstLine = Math.max(0, (int) (scrollY / lineH));
            int lastLine = Math.min(lines.size() - 1, (int) ((scrollY + viewHeight()) / lineH) + 1);
            for (int i = firstLine; i <= lastLine; i++) {
                int[] line = lines.get(i);
                float lineY = topY + i * lineH - scrollY;
                if (focused && hasSelection() && selStart < line[1] + 1 && selEnd > line[0]) {
                    float x1 = innerX + offsetInLine(i, Math.max(selStart, line[0]));
                    float x2 = innerX + offsetInLine(i, Math.min(selEnd, line[1]));
                    if (selEnd > line[1]) {
                        x2 += 3f; // hint that the selection continues past the wrap/newline
                    }
                    if (x2 > x1) {
                        Render2D.fillRect(graphics, x1, lineY - 1f, x2 - x1, Text2D.lineHeight() + 2f,
                                ColorUtil.withAlpha(theme.accent, 0.35f));
                    }
                }
                Text2D.draw(graphics, text.substring(line[0], line[1]), innerX, lineY + 0.5f, theme.text);
            }
        }

        if (focused && (Util.getMillis() - lastInteraction) % 1000 < 530) {
            int caretLine = lineOf(cursor);
            float caretX = innerX + offsetInLine(caretLine, cursor);
            float caretY = topY + caretLine * lineHeight() - scrollY;
            Render2D.fillRect(graphics, caretX, caretY - 1f, 1f, Text2D.lineHeight() + 2f, theme.text);
        }

        // Slim scroll indicator when the content overflows
        float max = maxScroll();
        if (max > 0f) {
            float trackH = height - 8f;
            float thumbH = Math.max(12f, trackH * viewHeight() / (lines.size() * lineH));
            float thumbY = y + 4f + (trackH - thumbH) * (scrollY / max);
            Render2D.fillRoundedRect(graphics, x + width - 5f, thumbY, 2f, thumbH, 1f,
                    ColorUtil.multiplyAlpha(theme.textMuted, 0.5f));
        }

        Render2D.popScissor(graphics);
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    private void insert(String insertion) {
        StringBuilder filtered = new StringBuilder();
        for (char c : insertion.toCharArray()) {
            if (c == '\n' || StringUtil.isAllowedChatCharacter(c)) {
                filtered.append(c);
            } else if (c == '\r') {
                filtered.append('\n');
            }
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

    private void moveCursor(int newPos, boolean extendSelection) {
        cursor = Mth.clamp(newPos, 0, text.length());
        if (!extendSelection) {
            selectionAnchor = cursor;
        }
        lastInteraction = Util.getMillis();
    }

    private void moveCursorVertically(int direction, boolean extendSelection) {
        ensureWrapped();
        int lineIndex = lineOf(cursor);
        int target = lineIndex + direction;
        if (target < 0) {
            moveCursor(0, extendSelection);
            return;
        }
        if (target >= lines.size()) {
            moveCursor(text.length(), extendSelection);
            return;
        }
        float xOffset = offsetInLine(lineIndex, cursor);
        moveCursor(indexAtOffset(target, xOffset), extendSelection);
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
        moveCursor(indexAt(mouseX, mouseY), Screen.hasShiftDown());
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (selectingWithMouse && button == 0) {
            moveCursor(indexAt(mouseX, mouseY), true);
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isHovered() || maxScroll() <= 0f) {
            return false;
        }
        this.scrollY = Mth.clamp(this.scrollY - (float) scrollY * lineHeight() * 2f, 0f, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) {
            return false;
        }
        boolean shift = Screen.hasShiftDown();
        boolean ctrl = Screen.hasControlDown();
        ensureWrapped();

        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                moveCursor(cursor - 1, shift);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                moveCursor(cursor + 1, shift);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                moveCursorVertically(-1, shift);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                moveCursorVertically(1, shift);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                moveCursor(ctrl ? 0 : lines.get(lineOf(cursor))[0], shift);
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                moveCursor(ctrl ? text.length() : lines.get(lineOf(cursor))[1], shift);
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                deleteSelectionOr(-1);
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                deleteSelectionOr(1);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                insert("\n");
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
