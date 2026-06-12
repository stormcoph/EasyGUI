package com.stormcph.easygui.client.widget;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A {@link Panel} that automatically positions its children in a single row or column,
 * killing the manual {@code rowY += 22} math. Children keep whatever width/height they
 * were given (via {@code setSize}/{@code setBounds}); the layout owns their positions
 * and re-resolves them whenever its own bounds change, children are added or removed,
 * or a child's size/visibility changes. Layouts nest cleanly (a horizontal row inside a
 * vertical column resolves in one pass) and work inside {@link ScrollPanel}.
 *
 * <p>The main axis is the flow direction (vertical = top to bottom); {@link Align}
 * controls the cross axis, with {@link Align#STRETCH} overriding each child's
 * cross-axis size. {@link Spacer} children soak up leftover main-axis space when the
 * layout has a fixed size. Invisible children are skipped entirely (no gap).</p>
 *
 * <pre>{@code
 * // Before: settingsPanel.add(new Slider(...)).setBounds(x, rowY, 180, 16); rowY += 22; ...
 * // After:
 * LinearLayout column = panel.add(LinearLayout.vertical()
 *         .setGap(6f)
 *         .setPadding(10f)
 *         .setAlign(LinearLayout.Align.STRETCH));
 * column.setBounds(20, 20, 200, 160);
 * column.add(new Label("Render"));
 * column.add(new Slider(0, 100, 1, 50, v -> {}));          // width comes from STRETCH
 * column.add(new Spacer());                                // pushes the button down
 * column.add(new Button("Apply", () -> {})).setSize(0, 20);
 * }</pre>
 */
@Environment(EnvType.CLIENT)
public class LinearLayout extends Panel {
    /** Cross-axis alignment. {@link #STRETCH} overrides each child's cross-axis size. */
    public enum Align {START, CENTER, END, STRETCH}

    private final boolean vertical;
    private float gap = 4f;
    private float padding = 0f;
    private Align align = Align.START;
    private boolean autoSize;

    private boolean dirty = true;
    private int lastChildHash;
    private boolean layingOut;

    protected LinearLayout(boolean vertical) {
        this.vertical = vertical;
    }

    /** A top-to-bottom column. */
    public static LinearLayout vertical() {
        return new LinearLayout(true);
    }

    /** A left-to-right row. */
    public static LinearLayout horizontal() {
        return new LinearLayout(false);
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** Spacing between consecutive children on the main axis (default 4). */
    public LinearLayout setGap(float gap) {
        this.gap = gap;
        relayout();
        return this;
    }

    /** Inset between the layout's edges and its content, on all sides (default 0). */
    public LinearLayout setPadding(float padding) {
        this.padding = padding;
        relayout();
        return this;
    }

    /** Cross-axis alignment of children (default {@link Align#START}). */
    public LinearLayout setAlign(Align align) {
        this.align = align;
        relayout();
        return this;
    }

    /**
     * When enabled the layout sizes itself to its content: main axis = sum of child main
     * sizes plus gaps plus {@code 2 * padding}; cross axis = largest child cross size plus
     * {@code 2 * padding}. Any size set by the user is overwritten, and {@link Spacer}s
     * collapse to zero (there is no leftover space to distribute).
     */
    public LinearLayout setAutoSize(boolean autoSize) {
        this.autoSize = autoSize;
        relayout();
        return this;
    }

    public float getGap() {
        return gap;
    }

    public float getPadding() {
        return padding;
    }

    public Align getAlign() {
        return align;
    }

    public boolean isAutoSize() {
        return autoSize;
    }

    public boolean isVertical() {
        return vertical;
    }

    /** Defers a relayout to the start of the next frame (rarely needed; sizes are watched). */
    public LinearLayout requestLayout() {
        this.dirty = true;
        return this;
    }

    // ------------------------------------------------------------------
    // Geometry — the layout re-resolves immediately so nested layouts settle in one pass
    // ------------------------------------------------------------------

    @Override
    public LinearLayout setBounds(float x, float y, float width, float height) {
        super.setBounds(x, y, width, height);
        relayout();
        return this;
    }

    @Override
    public LinearLayout setPosition(float x, float y) {
        super.setPosition(x, y);
        relayout();
        return this;
    }

    @Override
    public LinearLayout setSize(float width, float height) {
        super.setSize(width, height);
        relayout();
        return this;
    }

    // ------------------------------------------------------------------
    // Children
    // ------------------------------------------------------------------

    @Override
    public <T extends Widget> T add(T child) {
        T added = super.add(child);
        relayout();
        return added;
    }

    @Override
    public void remove(Widget child) {
        super.remove(child);
        relayout();
    }

    @Override
    public void clearChildren() {
        super.clearChildren();
        relayout();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    /** Recomputes the layout's own size (when auto-sizing) and every child position now. */
    public void relayout() {
        if (layingOut) {
            return;
        }
        layingOut = true;
        try {
            layoutChildren();
        } finally {
            layingOut = false;
            dirty = false;
            lastChildHash = childStateHash();
        }
    }

    private void layoutChildren() {
        // Measure: main-axis space taken by regular children, the widest cross size,
        // total spacer weight, and how many slots (and therefore gaps) the flow has.
        float fixedSum = 0f;
        float maxCross = 0f;
        float totalWeight = 0f;
        int placed = 0;
        for (Widget child : children) {
            if (!child.isVisible()) {
                continue;
            }
            if (child instanceof Spacer spacer) {
                if (autoSize) {
                    continue; // spacers collapse: no size, no gap
                }
                totalWeight += Math.max(0f, spacer.getWeight());
                placed++;
            } else {
                fixedSum += vertical ? child.getHeight() : child.getWidth();
                maxCross = Math.max(maxCross, vertical ? child.getWidth() : child.getHeight());
                placed++;
            }
        }
        float gaps = placed > 1 ? gap * (placed - 1) : 0f;

        if (autoSize) {
            float main = fixedSum + gaps + padding * 2f;
            float cross = maxCross + padding * 2f;
            // Write the fields directly; going through setSize would recurse into relayout.
            if (vertical) {
                this.width = cross;
                this.height = main;
            } else {
                this.width = main;
                this.height = cross;
            }
        }

        float innerMain = (vertical ? height : width) - padding * 2f;
        float innerCross = (vertical ? width : height) - padding * 2f;
        float leftover = Math.max(0f, innerMain - fixedSum - gaps);

        // Place: walk the main axis, resolving spacer shares and cross alignment.
        float mainPos = (vertical ? y : x) + padding;
        float crossStart = (vertical ? x : y) + padding;
        boolean first = true;
        for (Widget child : children) {
            if (!child.isVisible()) {
                continue;
            }
            if (child instanceof Spacer spacer) {
                float share = !autoSize && totalWeight > 0f
                        ? leftover * Math.max(0f, spacer.getWeight()) / totalWeight
                        : 0f;
                float spacerCross = align == Align.STRETCH ? Math.max(0f, innerCross)
                        : (vertical ? spacer.getWidth() : spacer.getHeight());
                resizeChild(spacer, vertical ? spacerCross : share, vertical ? share : spacerCross);
                if (autoSize) {
                    // Collapsed: park it at the cursor without advancing or adding a gap.
                    placeChild(spacer, vertical ? crossStart : mainPos, vertical ? mainPos : crossStart);
                    continue;
                }
            } else if (align == Align.STRETCH) {
                float stretched = Math.max(0f, innerCross);
                if (vertical) {
                    resizeChild(child, stretched, child.getHeight());
                } else {
                    resizeChild(child, child.getWidth(), stretched);
                }
            }

            if (!first) {
                mainPos += gap;
            }
            float childCross = vertical ? child.getWidth() : child.getHeight();
            float crossPos = switch (align) {
                case START, STRETCH -> crossStart;
                case CENTER -> crossStart + (innerCross - childCross) / 2f;
                case END -> crossStart + innerCross - childCross;
            };
            placeChild(child, vertical ? crossPos : mainPos, vertical ? mainPos : crossPos);
            mainPos += vertical ? child.getHeight() : child.getWidth();
            first = false;
        }
    }

    /**
     * Moves a child to its resolved position. Nested {@link LinearLayout}s reposition
     * their own subtree when their position is set; plain containers get their whole
     * subtree translated by the same delta so absolute child coordinates stay aligned.
     */
    private void placeChild(Widget child, float newX, float newY) {
        float dx = newX - child.getX();
        float dy = newY - child.getY();
        if (dx == 0f && dy == 0f) {
            return;
        }
        child.setPosition(newX, newY);
        if (child instanceof Panel panel && !(child instanceof LinearLayout)) {
            offsetDescendants(panel, dx, dy);
        }
    }

    private static void offsetDescendants(Panel panel, float dx, float dy) {
        for (Widget child : panel.getChildren()) {
            child.setPosition(child.getX() + dx, child.getY() + dy);
            if (child instanceof Panel nested && !(child instanceof LinearLayout)) {
                offsetDescendants(nested, dx, dy);
            }
        }
    }

    private static void resizeChild(Widget child, float width, float height) {
        if (child.getWidth() != width || child.getHeight() != height) {
            child.setSize(width, height);
        }
    }

    /**
     * Cheap fingerprint of everything (besides our own bounds and settings, which relayout
     * eagerly) that affects layout: child sizes, visibility, order, and spacer weights.
     */
    private int childStateHash() {
        int h = 1;
        for (Widget child : children) {
            h = 31 * h + Float.hashCode(child.getWidth());
            h = 31 * h + Float.hashCode(child.getHeight());
            h = 31 * h + (child.isVisible() ? 1 : 0);
            if (child instanceof Spacer spacer) {
                h = 31 * h + Float.hashCode(spacer.getWeight());
            }
        }
        return h;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        // Defensive pass: catches children resized/toggled directly since the last layout
        // (e.g. a nested auto-sizing layout that grew, or a widget shown via setVisible).
        if (dirty || childStateHash() != lastChildHash) {
            relayout();
        }
        super.renderWidget(graphics, mouseX, mouseY, delta);
    }
}
