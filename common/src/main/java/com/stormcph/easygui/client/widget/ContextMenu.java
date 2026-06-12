package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Icon;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A right-click context menu that lives on the screen's popup layer.
 *
 * <p>Build the menu fluently, then call {@link #open(EasyScreen, float, float)} with the
 * click position: the menu adds itself to the screen's root panel, claims the popup layer,
 * and pops in at that point (flipping leftwards or sliding up when it would leave the
 * screen). Clicking an item runs its action and closes the menu; clicking anywhere else,
 * or pressing Escape, dismisses it. While open, Up/Down move a keyboard selection and
 * Enter activates it. The menu removes itself from the root panel when it closes, so a
 * fresh instance per right-click is the intended usage.</p>
 *
 * <p>Triggering is the caller's job — open it from your own right-click handler:</p>
 *
 * <pre>{@code
 * @Override
 * public boolean mouseClicked(double mouseX, double mouseY, int button) {
 *     if (button == 1 && contains(mouseX, mouseY)) {
 *         new ContextMenu()
 *                 .addItem("Copy", Icons.COPY, this::copyEntry)
 *                 .addItem("Rename", this::renameEntry)
 *                 .addDivider()
 *                 .addDisabledItem("Share (soon)")
 *                 .addDangerItem("Delete", this::deleteEntry)
 *                 .open(getScreen(), (float) mouseX, (float) mouseY);
 *         return true;
 *     }
 *     return false;
 * }
 * }</pre>
 */
@Environment(EnvType.CLIENT)
public class ContextMenu extends Widget {
    private static final float ITEM_HEIGHT = 18f;
    private static final float DIVIDER_HEIGHT = 7f;
    private static final float MIN_WIDTH = 110f;
    private static final float MAX_WIDTH = 240f;
    private static final float PAD_X = 8f;
    private static final float PAD_Y = 4f;
    private static final float ICON_SIZE = 10f;
    private static final float ICON_GAP = 6f;
    private static final float SCREEN_MARGIN = 4f;

    private final List<Item> items = new ArrayList<>();
    private final SmoothValue openAnim = new SmoothValue(0f, 20f);
    private boolean hasIcons;

    /** The spawn point the open animation scales out from (clamped into the menu rect). */
    private float anchorX;
    private float anchorY;
    /** Highlighted row: synced to the hovered row, or driven by Up/Down. -1 = none. */
    private int keyIndex = -1;

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    public ContextMenu addItem(String label, Runnable action) {
        return addItem(label, null, action);
    }

    public ContextMenu addItem(String label, Icon icon, Runnable action) {
        items.add(new Item(label, icon, action, false, true, false));
        if (icon != null) {
            hasIcons = true;
        }
        return this;
    }

    /** A destructive action, rendered in the theme's danger color. */
    public ContextMenu addDangerItem(String label, Runnable action) {
        items.add(new Item(label, null, action, true, true, false));
        return this;
    }

    /** A dimmed, unclickable entry (e.g. an action that isn't available right now). */
    public ContextMenu addDisabledItem(String label) {
        items.add(new Item(label, null, null, false, false, false));
        return this;
    }

    /** A hairline separator between item groups. */
    public ContextMenu addDivider() {
        items.add(new Item(null, null, null, false, false, true));
        return this;
    }

    // ------------------------------------------------------------------
    // Opening / closing
    // ------------------------------------------------------------------

    /**
     * Shows the menu at ({@code spawnX}, {@code spawnY}) — typically the right-click
     * position. The menu adds itself to {@code screen}'s root panel, takes over the
     * popup layer, and stays fully on screen: it opens leftwards when too close to the
     * right edge and slides up when it would overflow the bottom.
     */
    public ContextMenu open(EasyScreen screen, float spawnX, float spawnY) {
        if (screen == null || items.isEmpty()) {
            return this;
        }
        if (getParent() != null) {
            getParent().remove(this);
        }

        float iconColumn = hasIcons ? ICON_SIZE + ICON_GAP : 0f;
        float maxText = 0f;
        float h = PAD_Y * 2;
        for (Item item : items) {
            if (item.divider) {
                h += DIVIDER_HEIGHT;
                continue;
            }
            h += ITEM_HEIGHT;
            maxText = Math.max(maxText, Text2D.width(item.label));
        }
        float w = Mth.clamp(PAD_X + iconColumn + maxText + PAD_X, MIN_WIDTH, MAX_WIDTH);

        float mx = spawnX;
        float my = spawnY;
        if (mx + w > screen.width - SCREEN_MARGIN) {
            mx = spawnX - w; // open leftwards from the click point
        }
        mx = Math.max(SCREEN_MARGIN, mx);
        if (my + h > screen.height - SCREEN_MARGIN) {
            my = screen.height - SCREEN_MARGIN - h; // slide up to fit
        }
        my = Math.max(SCREEN_MARGIN, my);
        setBounds(mx, my, w, h);
        anchorX = Mth.clamp(spawnX, mx, mx + w);
        anchorY = Mth.clamp(spawnY, my, my + h);

        keyIndex = -1;
        openAnim.setInstant(0f).setTarget(1f);
        screen.getRoot().add(this);
        screen.openPopup(this);
        requestFocus();
        return this;
    }

    private void close() {
        EasyScreen screen = getScreen();
        if (screen != null) {
            screen.closePopup(this);
            if (screen.getFocusedWidget() == this) {
                screen.setFocusedWidget(null);
            }
        }
        if (getParent() != null) {
            getParent().remove(this);
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        // Everything draws on the popup layer (renderTop), above the main tree.
    }

    @Override
    public void renderTop(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        float openT = openAnim.get();
        if (openT < 0.01f) {
            return;
        }
        Theme theme = theme();

        // The mouse drives the same selection the keyboard does, so there is always a
        // single highlighted row and Enter activates whatever is highlighted.
        int hoverIndex = rowIndexAt(mouseX, mouseY);
        if (hoverIndex >= 0 && isSelectable(items.get(hoverIndex))) {
            keyIndex = hoverIndex;
        }

        // Pop-in: fade + scale from 0.95 anchored at the spawn point
        var pose = graphics.pose();
        pose.pushPose();
        float scale = 0.95f + 0.05f * openT;
        pose.translate(anchorX, anchorY, 0);
        pose.scale(scale, scale, 1f);
        pose.translate(-anchorX, -anchorY, 0);
        Render2D.pushAlpha(openT);

        float r = theme.radiusSmall;
        Render2D.dropShadow(graphics, x, y, width, height, r, 6f, theme.shadow);
        Render2D.fillRoundedRect(graphics, x, y, width, height, r, theme.surface);
        Render2D.strokeRoundedRect(graphics, x, y, width, height, r, 1f, theme.outline);

        float textX = x + PAD_X + (hasIcons ? ICON_SIZE + ICON_GAP : 0f);
        int textBudget = (int) (x + width - PAD_X - textX);
        float ry = y + PAD_Y;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.divider) {
                Render2D.fillRect(graphics, x + 6, ry + (DIVIDER_HEIGHT - 1f) / 2f,
                        width - 12, 1f, theme.outline);
                ry += DIVIDER_HEIGHT;
                continue;
            }
            boolean active = i == keyIndex && isSelectable(item);
            if (active) {
                Render2D.fillRoundedRect(graphics, x + 3, ry, width - 6, ITEM_HEIGHT, 4f, theme.surfaceHover);
            }
            int color;
            if (!item.enabled) {
                color = ColorUtil.multiplyAlpha(theme.text, 0.45f);
            } else if (item.danger) {
                color = active ? theme.dangerHover : theme.danger;
            } else {
                color = theme.text;
            }
            if (item.icon != null) {
                item.icon.render(graphics, x + PAD_X, ry + (ITEM_HEIGHT - ICON_SIZE) / 2f, ICON_SIZE, color);
            }
            Text2D.drawVerticallyCentered(graphics, Text2D.truncate(item.label, textBudget),
                    textX, ry, ITEM_HEIGHT, color);
            ry += ITEM_HEIGHT;
        }

        Render2D.popAlpha();
        pose.popPose();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean popupMouseClicked(double mouseX, double mouseY, int button) {
        if (!contains(mouseX, mouseY)) {
            // Let the screen dismiss us — clicking elsewhere is how a context menu closes.
            return false;
        }
        if (button == 0) {
            int index = rowIndexAt(mouseX, mouseY);
            if (index >= 0) {
                Item item = items.get(index);
                if (isSelectable(item)) {
                    close();
                    item.action.run();
                }
            }
        }
        return true; // clicks inside the menu never fall through to the tree below
    }

    @Override
    public boolean popupMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Swallow scrolling while open so the content under the menu can't shift away.
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            moveSelection(1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            moveSelection(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (keyIndex >= 0 && keyIndex < items.size()) {
                Item item = items.get(keyIndex);
                if (isSelectable(item)) {
                    close();
                    item.action.run();
                }
            }
            return true;
        }
        return false; // Escape falls through to the screen, which dismisses the popup
    }

    @Override
    public void dismissPopup() {
        close();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Moves the keyboard selection to the next/previous actionable row, wrapping around. */
    private void moveSelection(int direction) {
        int n = items.size();
        if (n == 0) {
            return;
        }
        int i = keyIndex >= 0 ? keyIndex : (direction > 0 ? -1 : n);
        for (int step = 0; step < n; step++) {
            i = (i + direction + n) % n;
            if (isSelectable(items.get(i))) {
                keyIndex = i;
                return;
            }
        }
    }

    /** Index of the row under the mouse (dividers included), or -1 when outside all rows. */
    private int rowIndexAt(double mouseX, double mouseY) {
        if (mouseX < x || mouseX >= x + width || mouseY < y + PAD_Y) {
            return -1;
        }
        float ry = y + PAD_Y;
        for (int i = 0; i < items.size(); i++) {
            float rh = items.get(i).divider ? DIVIDER_HEIGHT : ITEM_HEIGHT;
            if (mouseY < ry + rh) {
                return i;
            }
            ry += rh;
        }
        return -1;
    }

    private static boolean isSelectable(Item item) {
        return !item.divider && item.enabled && item.action != null;
    }

    private static final class Item {
        final String label;
        final Icon icon;
        final Runnable action;
        final boolean danger;
        final boolean enabled;
        final boolean divider;

        Item(String label, Icon icon, Runnable action, boolean danger, boolean enabled, boolean divider) {
            this.label = label;
            this.icon = icon;
            this.action = action;
            this.danger = danger;
            this.enabled = enabled;
            this.divider = divider;
        }
    }
}
