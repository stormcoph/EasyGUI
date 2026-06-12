package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Icons;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * A combo box: closed it looks like a {@link Dropdown}, open it becomes a search field
 * that filters the option list as you type. Arrow keys move the highlight, Enter picks,
 * Escape closes. Long lists scroll inside the popup. {@code onSelect} receives the index
 * into the <em>original</em> options list.
 */
@Environment(EnvType.CLIENT)
public class SearchableDropdown extends Widget {
    private static final float OPTION_HEIGHT = 18f;
    private static final float POPUP_GAP = 4f;
    private static final int MAX_VISIBLE = 6;

    private final List<String> options;
    private int selected;
    private IntConsumer onSelect;
    private boolean open;

    private String query = "";
    private final List<Integer> filtered = new ArrayList<>();
    private int highlighted;
    private float listScroll;
    private long lastInteraction;

    private final SmoothValue openAnim = new SmoothValue(0f, 18f);

    public SearchableDropdown(List<String> options, int initialIndex, IntConsumer onSelect) {
        this.options = List.copyOf(options);
        this.selected = Mth.clamp(initialIndex, 0, Math.max(0, options.size() - 1));
        this.onSelect = onSelect;
        this.height = 20f;
        refilter();
    }

    public int getSelectedIndex() {
        return selected;
    }

    public String getSelectedOption() {
        return options.isEmpty() ? "" : options.get(selected);
    }

    public SearchableDropdown setSelectedIndex(int index) {
        this.selected = Mth.clamp(index, 0, Math.max(0, options.size() - 1));
        return this;
    }

    private void refilter() {
        filtered.clear();
        String needle = query.toLowerCase(Locale.ROOT);
        for (int i = 0; i < options.size(); i++) {
            if (needle.isEmpty() || options.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                filtered.add(i);
            }
        }
        highlighted = Mth.clamp(highlighted, 0, Math.max(0, filtered.size() - 1));
        listScroll = Mth.clamp(listScroll, 0f, maxListScroll());
    }

    private float maxListScroll() {
        return Math.max(0f, (filtered.size() - MAX_VISIBLE) * OPTION_HEIGHT);
    }

    private void scrollHighlightIntoView() {
        float top = highlighted * OPTION_HEIGHT;
        if (top < listScroll) {
            listScroll = top;
        } else if (top + OPTION_HEIGHT > listScroll + MAX_VISIBLE * OPTION_HEIGHT) {
            listScroll = top + OPTION_HEIGHT - MAX_VISIBLE * OPTION_HEIGHT;
        }
        listScroll = Mth.clamp(listScroll, 0f, maxListScroll());
    }

    // ------------------------------------------------------------------
    // Open/close
    // ------------------------------------------------------------------

    private void setOpen(boolean newOpen) {
        open = newOpen;
        EasyScreen screen = getScreen();
        if (open) {
            query = "";
            refilter();
            highlighted = Math.max(0, filtered.indexOf(selected));
            scrollHighlightIntoView();
            lastInteraction = Util.getMillis();
            if (screen != null) {
                screen.openPopup(this);
            }
            requestFocus();
        } else if (screen != null) {
            screen.closePopup(this);
            if (screen.getFocusedWidget() == this) {
                screen.setFocusedWidget(null);
            }
        }
    }

    @Override
    public void dismissPopup() {
        open = false;
        EasyScreen screen = getScreen();
        if (screen != null && screen.getFocusedWidget() == this) {
            screen.setFocusedWidget(null);
        }
    }

    private void select(int originalIndex) {
        selected = originalIndex;
        if (onSelect != null) {
            onSelect.accept(originalIndex);
        }
        setOpen(false);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        Theme theme = theme();
        float hover = hoverAmount();
        openAnim.setTarget(open ? 1f : 0f);
        float openT = openAnim.get();

        float r = theme.radiusSmall;
        int bg = ColorUtil.lerp(theme.surfaceVariant, theme.surfaceHover, Math.max(hover, openT));
        Render2D.fillRoundedRect(graphics, x, y, width, height, r, bg);
        int outlineColor = ColorUtil.lerp(theme.outline, theme.accent, openT);
        Render2D.strokeRoundedRect(graphics, x, y, width, height, r, 1f, outlineColor);

        if (open) {
            // The control is the search field while the popup is open
            float textX = x + 8;
            if (query.isEmpty()) {
                Text2D.drawVerticallyCentered(graphics, "Type to filter…", textX, y, height, theme.textMuted);
            } else {
                Text2D.drawVerticallyCentered(graphics,
                        Text2D.truncate(query, (int) (width - 30)), textX, y, height, theme.text);
            }
            if ((Util.getMillis() - lastInteraction) % 1000 < 530) {
                float caretX = textX + Math.min(Text2D.width(query), width - 30);
                Render2D.fillRect(graphics, caretX + 0.5f, y + (height - Text2D.lineHeight()) / 2f - 1f,
                        1f, Text2D.lineHeight() + 2f, theme.text);
            }
        } else {
            Text2D.drawVerticallyCentered(graphics,
                    Text2D.truncate(getSelectedOption(), (int) (width - 26)), x + 8, y, height, theme.text);
        }

        // Magnifier while open, chevron otherwise
        float iconSize = 10f;
        float cx = x + width - iconSize - 6;
        float cy = y + (height - iconSize) / 2f;
        if (openT > 0.5f) {
            Icons.SEARCH.render(graphics, cx, cy, iconSize, theme.textMuted);
        } else {
            Icons.CHEVRON_DOWN.render(graphics, cx, cy, iconSize, theme.textMuted);
        }
    }

    private float popupHeight() {
        int rows = Math.max(1, Math.min(filtered.size(), MAX_VISIBLE));
        return rows * OPTION_HEIGHT + 8;
    }

    /** Popup rect, flipped above the control when there is no room below. */
    private float popupY() {
        EasyScreen screen = getScreen();
        float below = y + height + POPUP_GAP;
        if (screen != null && below + popupHeight() > screen.height - 4 && y - POPUP_GAP - popupHeight() > 4) {
            return y - POPUP_GAP - popupHeight();
        }
        return below;
    }

    @Override
    public void renderTop(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        float openT = openAnim.get();
        if (openT < 0.01f) {
            return;
        }
        Theme theme = theme();
        float px = x;
        float py = popupY();
        float pw = width;
        float ph = popupHeight();

        Render2D.pushAlpha(openT);
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, (1f - openT) * -6f * (py > y ? 1f : -1f), 0);

        Render2D.dropShadow(graphics, px, py, pw, ph, theme.radiusSmall, 6f, theme.shadow);
        Render2D.fillRoundedRect(graphics, px, py, pw, ph, theme.radiusSmall, theme.surface);
        Render2D.strokeRoundedRect(graphics, px, py, pw, ph, theme.radiusSmall, 1f, theme.outline);

        if (filtered.isEmpty()) {
            Text2D.drawVerticallyCentered(graphics, "No matches", px + 8, py + 4, OPTION_HEIGHT, theme.textMuted);
        } else {
            Render2D.pushScissor(graphics, px + 1, py + 4, pw - 2, ph - 8);
            for (int row = 0; row < filtered.size(); row++) {
                float oy = py + 4 + row * OPTION_HEIGHT - listScroll;
                if (oy + OPTION_HEIGHT < py || oy > py + ph) {
                    continue;
                }
                int original = filtered.get(row);
                boolean hoveredRow = open && mouseX >= px && mouseX < px + pw
                        && mouseY >= oy && mouseY < oy + OPTION_HEIGHT;
                if (hoveredRow) {
                    highlighted = row;
                }
                if (row == highlighted) {
                    Render2D.fillRoundedRect(graphics, px + 3, oy, pw - 6, OPTION_HEIGHT, 4f, theme.surfaceHover);
                }
                int color = original == selected ? theme.accent : theme.text;
                Text2D.drawVerticallyCentered(graphics,
                        Text2D.truncate(options.get(original), (int) (pw - 30)), px + 8, oy, OPTION_HEIGHT, color);
                if (original == selected) {
                    Icons.CHECK.render(graphics, px + pw - 16, oy + (OPTION_HEIGHT - 10) / 2f, 10f, theme.accent);
                }
            }
            Render2D.popScissor(graphics);

            float max = maxListScroll();
            if (max > 0f) {
                float trackH = ph - 8f;
                float thumbH = Math.max(10f, trackH * MAX_VISIBLE / filtered.size());
                float thumbY = py + 4f + (trackH - thumbH) * (listScroll / max);
                Render2D.fillRoundedRect(graphics, px + pw - 4f, thumbY, 2f, thumbH, 1f,
                        ColorUtil.multiplyAlpha(theme.textMuted, 0.5f));
            }
        }

        pose.popPose();
        Render2D.popAlpha();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || button != 0 || !contains(mouseX, mouseY)) {
            return false;
        }
        setOpen(!open);
        return true;
    }

    @Override
    public boolean popupMouseClicked(double mouseX, double mouseY, int button) {
        if (!open) {
            return false;
        }
        float px = x;
        float py = popupY();
        if (mouseX >= px && mouseX < px + width && mouseY >= py && mouseY < py + popupHeight()) {
            int row = (int) ((mouseY - py - 4 + listScroll) / OPTION_HEIGHT);
            if (row >= 0 && row < filtered.size()) {
                select(filtered.get(row));
            }
            return true;
        }
        // Clicking the control keeps the search field active
        return contains(mouseX, mouseY);
    }

    @Override
    public boolean popupMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!open) {
            return false;
        }
        listScroll = Mth.clamp(listScroll - (float) scrollY * OPTION_HEIGHT, 0f, maxListScroll());
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused || !open) {
            return false;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_DOWN -> {
                highlighted = Math.min(highlighted + 1, filtered.size() - 1);
                scrollHighlightIntoView();
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                highlighted = Math.max(highlighted - 1, 0);
                scrollHighlightIntoView();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (highlighted >= 0 && highlighted < filtered.size()) {
                    select(filtered.get(highlighted));
                }
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                setOpen(false);
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!query.isEmpty()) {
                    query = query.substring(0, query.length() - 1);
                    refilter();
                }
                lastInteraction = Util.getMillis();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (!focused || !open || !StringUtil.isAllowedChatCharacter(chr)) {
            return false;
        }
        query += chr;
        highlighted = 0;
        listScroll = 0f;
        refilter();
        lastInteraction = Util.getMillis();
        return true;
    }
}
