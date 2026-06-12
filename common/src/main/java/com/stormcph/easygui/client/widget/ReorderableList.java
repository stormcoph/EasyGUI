package com.stormcph.easygui.client.widget;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A scrolling list whose rows can be reordered by dragging.
 *
 * <p>Add rows with {@link #addItem(Widget)}; the list owns every row's position from then
 * on, stacking them vertically ({@link #setRowGap}, {@link #setRowPadding}) and animating
 * each row toward its slot with a per-row {@link SmoothValue}, so insertions and reorders
 * glide instead of snapping. Children inside a row keep their offsets relative to wherever
 * the row sat when it was added — the list moves whole row subtrees together.</p>
 *
 * <p>Pressing a row only begins a drag if nothing inside the row consumed the click
 * (buttons, sliders, etc. inside rows keep working). After ~4px of vertical movement the
 * row "lifts": it renders on top with a drop shadow and a slight scale, follows the cursor
 * (clamped to the list), and the other rows animate aside live. Dragging near the top or
 * bottom edge auto-scrolls, ramping faster toward the edge. Releasing commits the new
 * order and fires {@link #setOnReorder}; Escape cancels and the rows settle back.</p>
 *
 * <pre>{@code
 * ReorderableList list = root.add(new ReorderableList());
 * list.setCard(true).setBounds(20, 20, 220, 180);
 * list.setOnReorder((from, to) -> save(list.getItems()));
 * Panel row = new Panel().setCard(true);
 * row.setSize(0, 24); // height is what matters; the list owns x/y/width
 * list.addItem(row);
 * }</pre>
 */
@Environment(EnvType.CLIENT)
public class ReorderableList extends ScrollPanel {
    /** Vertical travel before a pressed row lifts into a drag. */
    private static final float LIFT_DISTANCE = 4f;
    /** Extra scale applied to the lifted row at full lift. */
    private static final float LIFT_SCALE = 0.02f;
    /** Height of the auto-scroll zones at the list's top and bottom edges. */
    private static final float EDGE_ZONE = 12f;
    /** Auto-scroll speed (px/s) when the cursor is at or beyond the edge. */
    private static final float AUTO_SCROLL_SPEED = 220f;
    private static final float ROW_ANIM_SPEED = 16f;

    private final List<Widget> rows = new ArrayList<>();
    private final Map<Widget, RowState> states = new HashMap<>();
    private float rowGap = 4f;
    private float rowPadding = 6f;
    private BiConsumer<Integer, Integer> onReorder;

    // Drag state
    private Widget pressedRow;
    private Widget liftedRow;
    private boolean dragging;
    private double pressY;
    private double dragMouseY;
    private float grabOffset;
    private int dragInsertIndex;
    private final SmoothValue liftAnim = new SmoothValue(0f, 18f);
    private float autoScrollTarget;
    private long lastAutoScrollNanos = -1L;

    /**
     * Mirror of the scroll offset the parent panel last applied to its children, used to
     * pre-compensate row positions so {@code ScrollPanel}'s scroll shift lands them (and
     * their unshifted descendants) exactly where this frame's layout wants them.
     */
    private float appliedScrollMirror;

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** Vertical gap between consecutive rows (default 4). */
    public ReorderableList setRowGap(float gap) {
        this.rowGap = gap;
        return this;
    }

    /** Inset between the list edges and the rows, on all sides (default 6). */
    public ReorderableList setRowPadding(float padding) {
        this.rowPadding = padding;
        return this;
    }

    /** Called after a drop commits a new order, with the row's old and new index. */
    public ReorderableList setOnReorder(BiConsumer<Integer, Integer> onReorder) {
        this.onReorder = onReorder;
        return this;
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    /**
     * Adds a row. Its height is kept; x, y, and width belong to the list from now on.
     * Children already inside the row keep their offsets relative to the row's current
     * position, so build the row fully (or position children relative to it) before adding.
     */
    public <T extends Widget> T addItem(T row) {
        float slot = rowPadding;
        for (Widget existing : rows) {
            slot += existing.getHeight() + rowGap;
        }
        rows.add(row);
        states.put(row, new RowState(new SmoothValue(slot, ROW_ANIM_SPEED), row.getX(), row.getY()));
        super.add(row);
        return row;
    }

    /** Removes a row (cancelling any drag it is involved in). */
    public ReorderableList removeItem(Widget row) {
        if (pressedRow == row) {
            pressedRow = null;
            dragging = false;
            lastAutoScrollNanos = -1L;
            liftAnim.setInstant(0f);
            releaseFocus();
        }
        if (liftedRow == row) {
            liftedRow = null;
        }
        rows.remove(row);
        states.remove(row);
        super.remove(row);
        return this;
    }

    /** The rows in their current (committed) order. */
    public List<Widget> getItems() {
        return List.copyOf(rows);
    }

    /** True while a row is lifted and following the cursor. */
    public boolean isDragging() {
        return dragging;
    }

    @Override
    public boolean movesChildrenWithSelf() {
        return true; // layoutRows re-resolves every row subtree from the list's own bounds
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    /** Content height derived from the row stack (independent of in-flight animations). */
    @Override
    public float contentHeight() {
        float h = rowPadding;
        for (int i = 0; i < rows.size(); i++) {
            h += rows.get(i).getHeight();
            if (i < rows.size() - 1) {
                h += rowGap;
            }
        }
        return h + rowPadding;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        if (dragging) {
            autoScroll();
        }
        float scrollAmount = Mth.clamp(getScrollAmount(), 0f, maxScroll());
        if (dragging) {
            updateDragIndex(scrollAmount);
        }
        layoutRows(scrollAmount);

        // Return a settled row to the normal layer here (not in renderTop), so it is
        // drawn by the main pass on the same frame it stops rendering on top.
        if (!dragging && liftedRow != null && liftAnim.get() < 0.03f) {
            liftedRow = null;
        }

        // The lifted row is drawn on the top pass instead of inside the normal child loop.
        Widget hidden = liftedRow;
        if (hidden != null) {
            hidden.setVisible(false);
        }
        super.renderWidget(graphics, mouseX, mouseY, delta);
        if (hidden != null) {
            hidden.setVisible(true);
        }
        appliedScrollMirror = scrollAmount;
    }

    /**
     * Positions every row for this frame. Each row's descendants are shifted to the row's
     * on-screen render position; the row itself is offset by the scroll delta the parent
     * panel is about to apply, so after {@code ScrollPanel} shifts its direct children the
     * whole subtree lines up again.
     */
    private void layoutRows(float scrollAmount) {
        float preShift = scrollAmount - appliedScrollMirror;
        float innerX = x + rowPadding;
        float innerW = Math.max(0f, width - rowPadding * 2f);

        List<Widget> order = rows;
        if (dragging && pressedRow != null) {
            order = new ArrayList<>(rows);
            order.remove(pressedRow);
            order.add(Math.min(dragInsertIndex, order.size()), pressedRow);
        }

        float off = rowPadding;
        for (Widget row : order) {
            RowState state = states.get(row);
            if (state == null) {
                continue;
            }
            state.anim.setTarget(off);
            float renderY = dragging && row == pressedRow
                    ? dragRenderY(row)
                    : y + state.anim.get() - scrollAmount;
            boolean selfMoving = row instanceof Panel p && p.movesChildrenWithSelf();
            if (!selfMoving) {
                // Plain rows: the row frame moves via setBounds but its descendants do
                // not, so shift them to the render position ourselves (anchor-tracked).
                float dx = innerX - state.anchorX;
                float dy = renderY - state.anchorY;
                if ((dx != 0f || dy != 0f) && row instanceof Panel panel) {
                    shiftDescendants(panel, dx, dy);
                }
                state.anchorX = innerX;
                state.anchorY = renderY;
            }
            // Self-moving rows carry their subtree through setBounds (and through the
            // parent ScrollPanel's scroll shift), so they need no anchor compensation.
            row.setBounds(innerX, renderY + preShift, innerW, row.getHeight());
            off += row.getHeight() + rowGap;
        }
    }

    /** The lifted row's on-screen y: cursor minus grab point, clamped inside the list. */
    private float dragRenderY(Widget row) {
        float top = y + rowPadding;
        float bottom = y + height - rowPadding - row.getHeight();
        return Mth.clamp((float) (dragMouseY - grabOffset), top, Math.max(top, bottom));
    }

    /** Recomputes where the lifted row would drop, from its center against the other rows. */
    private void updateDragIndex(float scrollAmount) {
        Widget dragged = pressedRow;
        if (dragged == null) {
            return;
        }
        float center = dragRenderY(dragged) + dragged.getHeight() / 2f - y + scrollAmount;
        int index = 0;
        float off = rowPadding;
        for (Widget row : rows) {
            if (row == dragged) {
                continue;
            }
            if (center > off + row.getHeight() / 2f) {
                index++;
            }
            off += row.getHeight() + rowGap;
        }
        dragInsertIndex = index;
    }

    /** Scrolls while the cursor sits in the edge zones, ramping faster toward the edge. */
    private void autoScroll() {
        long now = System.nanoTime();
        float dt = lastAutoScrollNanos > 0 ? Math.min((now - lastAutoScrollNanos) / 1_000_000_000f, 0.1f) : 0f;
        lastAutoScrollNanos = now;
        if (dt <= 0f) {
            return;
        }
        float fromTop = (float) (dragMouseY - y);
        float fromBottom = (float) (y + height - dragMouseY);
        float velocity = 0f;
        if (fromTop < EDGE_ZONE) {
            velocity = -AUTO_SCROLL_SPEED * (1f - Math.max(0f, fromTop) / EDGE_ZONE);
        } else if (fromBottom < EDGE_ZONE) {
            velocity = AUTO_SCROLL_SPEED * (1f - Math.max(0f, fromBottom) / EDGE_ZONE);
        }
        if (velocity == 0f) {
            autoScrollTarget = Mth.clamp(getScrollAmount(), 0f, maxScroll());
            return;
        }
        autoScrollTarget = Mth.clamp(autoScrollTarget + velocity * dt, 0f, maxScroll());
        scrollTo(autoScrollTarget);
    }

    // ------------------------------------------------------------------
    // Lifted row (drawn on the top pass, above the scissored list)
    // ------------------------------------------------------------------

    @Override
    public void renderTop(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        super.renderTop(graphics, mouseX, mouseY, delta);
        Widget row = liftedRow;
        if (row == null) {
            return;
        }
        float lift = liftAnim.get();
        if (!dragging && lift < 0.03f) {
            return; // renderWidget clears liftedRow next frame
        }
        Theme theme = theme();
        float scale = 1f + LIFT_SCALE * lift;
        float cx = row.getX() + row.getWidth() / 2f;
        float cy = row.getY() + row.getHeight() / 2f;

        Render2D.pushScissor(graphics, x, y, width, height);
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0);
        pose.scale(scale, scale, 1f);
        pose.translate(-cx, -cy, 0);
        Render2D.dropShadow(graphics, row.getX(), row.getY(), row.getWidth(), row.getHeight(),
                theme.radiusSmall, 6f, ColorUtil.multiplyAlpha(theme.shadow, lift));
        row.render(graphics, mouseX, mouseY, delta);
        pose.popPose();
        Render2D.popScissor(graphics);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (dragging) {
            return true; // a drag gesture owns the mouse
        }
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        // Scrollbar thumb and widgets inside rows get the click first; a consumed click
        // never starts a drag, so buttons inside rows keep working.
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (enabled && button == 0) {
            Widget row = rowAt(mouseX, mouseY);
            if (row != null) {
                pressedRow = row;
                pressY = mouseY;
                dragMouseY = mouseY;
                grabOffset = (float) (mouseY - row.getY());
                return true;
            }
        }
        return false;
    }

    private Widget rowAt(double mouseX, double mouseY) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            Widget row = rows.get(i);
            if (row.isVisible() && row.contains(mouseX, mouseY)) {
                return row;
            }
        }
        return null;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && pressedRow != null) {
            dragMouseY = mouseY;
            if (!dragging && Math.abs(mouseY - pressY) >= LIFT_DISTANCE) {
                startDrag();
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && pressedRow != null) {
            if (dragging) {
                commitDrag();
            } else {
                pressedRow = null;
            }
            super.mouseReleased(mouseX, mouseY, button);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (dragging) {
            return true; // auto-scroll owns the scroll position during a drag
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (dragging && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            cancelDrag();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------
    // Drag lifecycle
    // ------------------------------------------------------------------

    private void startDrag() {
        dragging = true;
        liftedRow = pressedRow;
        // Re-derive the grab point from the row's current position, in case the list
        // scrolled under the cursor between the press and the lift.
        grabOffset = Mth.clamp((float) (dragMouseY - pressedRow.getY()), 0f, pressedRow.getHeight());
        dragInsertIndex = rows.indexOf(pressedRow);
        liftAnim.setTarget(1f);
        autoScrollTarget = Mth.clamp(getScrollAmount(), 0f, maxScroll());
        lastAutoScrollNanos = -1L;
        requestFocus(); // so Escape reaches us while dragging
    }

    private void commitDrag() {
        Widget row = pressedRow;
        RowState state = states.get(row);
        if (state != null) {
            // Settle from wherever the row visually is right now.
            state.anim.setInstant(row.getY() - y + appliedScrollMirror);
        }
        int from = rows.indexOf(row);
        rows.remove(row);
        int to = Mth.clamp(dragInsertIndex, 0, rows.size());
        rows.add(to, row);

        // Keep the children list in the same order so render order and input routing match.
        children.remove(row);
        int childIndex = children.size();
        if (to + 1 < rows.size()) {
            int next = children.indexOf(rows.get(to + 1));
            if (next >= 0) {
                childIndex = next;
            }
        }
        children.add(childIndex, row);

        finishDrag();
        if (onReorder != null && from != to) {
            onReorder.accept(from, to);
        }
    }

    private void cancelDrag() {
        Widget row = pressedRow;
        RowState state = states.get(row);
        if (state != null) {
            state.anim.setInstant(row.getY() - y + appliedScrollMirror);
        }
        finishDrag();
    }

    private void finishDrag() {
        pressedRow = null;
        dragging = false;
        liftAnim.setTarget(0f);
        lastAutoScrollNanos = -1L;
        releaseFocus();
        // liftedRow stays set until the lift animation fades out on the top pass.
    }

    private void releaseFocus() {
        EasyScreen screen = getScreen();
        if (screen != null && screen.getFocusedWidget() == this) {
            screen.setFocusedWidget(null);
        }
    }

    /** Per-row animation state plus the subtree-consistent anchor the row was last placed at. */
    private static final class RowState {
        final SmoothValue anim;
        float anchorX;
        float anchorY;

        RowState(SmoothValue anim, float anchorX, float anchorY) {
            this.anim = anim;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
        }
    }
}
