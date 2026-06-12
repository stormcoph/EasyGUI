package com.stormcph.easygui.client.overlay;

import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A HUD overlay that renders an animated vertical stack of text rows — the classic
 * "module list" look, but generic enough for any live list (active effects, keybinds,
 * party members, …).
 *
 * <p>Rows are identified by id. {@link #addEntry(String, Supplier)} animates a new row
 * in (slide from the anchored screen edge + fade + height grow);
 * {@link #removeEntry(String)} plays the same animation in reverse — slide out, fade,
 * height collapse — and only drops the row once the exit has finished, so neighbors
 * glide into the gap instead of snapping. {@link #updateFromCollection(Collection)} is
 * the convenience for "render exactly these labels": it diffs the collection against
 * the current rows, animating additions in and removals out automatically.</p>
 *
 * <p>The list is anchor-aware: rows align left/right/center to match
 * {@link Anchor#horizontalAlign()}, the slide-in comes from the anchored edge, and with
 * a bottom anchor the list grows <em>upward</em>. The convention is that row 0 — the
 * first row in sort order — always sits nearest the anchored edge: at the top for top
 * anchors, at the bottom for bottom anchors. Reorders (sort mode changes, width
 * changes under {@link SortMode#WIDTH_DESC}) animate via a per-row smoothed offset
 * rather than teleporting.</p>
 *
 * <p>{@link #getWidth()} is the widest row, smoothed so the styled background plate
 * doesn't jitter as labels change; {@link #getHeight()} is the sum of the animated row
 * heights, so the plate collapses with the rows.</p>
 */
@Environment(EnvType.CLIENT)
public class AnimatedListOverlay extends HudOverlay {
    private static final float ROW_PADDING_X = 4f;
    private static final float ROW_PADDING_Y = 2f;
    private static final float ROW_RADIUS = 3f;
    private static final float SLIDE_DISTANCE = 12f;

    /** How rows are ordered, top row (row 0) nearest the anchored edge. */
    public enum SortMode {
        /** Rows keep the order they were added in (the default). */
        INSERT_ORDER,
        /** Widest row first — the classic module-list cascade. */
        WIDTH_DESC,
        /** Case-insensitive label order. */
        ALPHABETICAL
    }

    /** Per-row color hook: visual row index (0 = nearest the anchored edge) and label. */
    @FunctionalInterface
    public interface RowColorProvider {
        int color(int index, String label);
    }

    private static final class Entry {
        final String id;
        final long seq;
        Supplier<String> text;
        String label = "";
        float width;
        /** 0..1 in/out progress: alpha, edge slide, and height collapse all read this. */
        final SmoothValue appear = new SmoothValue(0f, 10f);
        /** Smoothed distance from the anchored edge to this row's near edge. */
        final SmoothValue slot = new SmoothValue(0f, 14f);
        boolean slotInitialized;
        boolean removing;

        Entry(String id, long seq, Supplier<String> text) {
            this.id = id;
            this.seq = seq;
            this.text = text;
            this.appear.setTarget(1f);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> ordered = new ArrayList<>();
    private final SmoothValue boxWidth = new SmoothValue(0f, 14f);
    private boolean widthInitialized;
    private long seqCounter;
    private long lastRefreshMillis = -1L;

    private SortMode sortMode = SortMode.INSERT_ORDER;
    private Comparator<String> comparator;
    private RowColorProvider colorProvider;
    private boolean rowBackground;

    // ------------------------------------------------------------------
    // Entries
    // ------------------------------------------------------------------

    /**
     * Adds a row whose label is re-read from {@code text} every frame (live values are
     * fine — width changes are absorbed by the smoothed box width). If a row with this
     * id already exists — even one currently animating out — it is updated/revived in
     * place, keeping its animation state.
     */
    public AnimatedListOverlay addEntry(String id, Supplier<String> text) {
        Supplier<String> safe = text != null ? text : () -> "";
        Entry existing = find(id);
        if (existing != null) {
            existing.text = safe;
            existing.removing = false;
            existing.appear.setTarget(1f);
            return this;
        }
        entries.add(new Entry(id, seqCounter++, safe));
        return this;
    }

    /** Adds a row with a fixed label; same revive/update semantics as the supplier overload. */
    public AnimatedListOverlay addEntry(String id, String fixed) {
        String label = fixed != null ? fixed : "";
        return addEntry(id, () -> label);
    }

    /**
     * Starts the exit animation for the row with this id: it slides toward the anchored
     * edge, fades, and collapses its height; the entry is dropped only once the exit
     * settles, so the rows around it close the gap smoothly. Unknown ids are ignored.
     */
    public AnimatedListOverlay removeEntry(String id) {
        Entry entry = find(id);
        if (entry != null && !entry.removing) {
            entry.removing = true;
            entry.appear.setTarget(0f);
        }
        return this;
    }

    /**
     * Diffs {@code labels} against the current rows, using each label as its own id:
     * labels without a row are {@link #addEntry(String, String) added} (animating in,
     * reviving rows that were mid-exit), and rows whose label is no longer in the
     * collection are {@link #removeEntry(String) removed} (animating out). Call this
     * whenever your source list changes — or every frame; it is cheap when nothing
     * changed.
     */
    public AnimatedListOverlay updateFromCollection(Collection<String> labels) {
        Set<String> wanted = new LinkedHashSet<>(labels);
        for (String label : wanted) {
            Entry existing = find(label);
            if (existing == null || existing.removing) {
                addEntry(label, label);
            }
        }
        for (Entry entry : entries) {
            if (!entry.removing && !wanted.contains(entry.id)) {
                removeEntry(entry.id);
            }
        }
        return this;
    }

    private Entry find(String id) {
        for (Entry entry : entries) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** How rows are sorted (default {@link SortMode#INSERT_ORDER}); reorders animate. */
    public AnimatedListOverlay setSortMode(SortMode sortMode) {
        this.sortMode = sortMode != null ? sortMode : SortMode.INSERT_ORDER;
        return this;
    }

    /**
     * Custom label ordering; while set it overrides the {@link #setSortMode(SortMode)
     * sort mode}. Ties fall back to insertion order. Pass {@code null} to clear.
     */
    public AnimatedListOverlay setComparator(Comparator<String> comparator) {
        this.comparator = comparator;
        return this;
    }

    /**
     * Per-row text color hook (e.g. a rainbow cascade by index, or category colors by
     * label). Defaults to the theme text color; pass {@code null} to restore that.
     */
    public AnimatedListOverlay setColorProvider(RowColorProvider colorProvider) {
        this.colorProvider = colorProvider;
        return this;
    }

    /**
     * Draws a rounded fill behind each row, sized to that row's own text width — the
     * classic stair-stepped module-list look. Off by default (use the overlay's
     * {@link HudStyle} background for one plate behind the whole list instead).
     */
    public AnimatedListOverlay setRowBackground(boolean rowBackground) {
        this.rowBackground = rowBackground;
        return this;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private float rowHeight() {
        return Text2D.lineHeight() + ROW_PADDING_Y * 2f;
    }

    private float entryWidth(String label) {
        return Text2D.width(label) + (rowBackground ? ROW_PADDING_X * 2f : 0f);
    }

    /** Once-per-frame bookkeeping: labels, exits, sort order, slot and width targets. */
    private void refresh() {
        long now = Util.getMillis();
        if (now == lastRefreshMillis) {
            return;
        }
        lastRefreshMillis = now;

        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (entry.removing && entry.appear.get() <= 0.01f) {
                it.remove();
                continue;
            }
            String label = entry.text.get();
            entry.label = label != null ? label : "";
            entry.width = entryWidth(entry.label);
        }

        ordered.clear();
        ordered.addAll(entries);
        ordered.sort(orderComparator());

        // Slot targets accumulate the *animated* heights, so a collapsing row pulls
        // the rows behind it along smoothly. Row 0 sits nearest the anchored edge.
        float rowH = rowHeight();
        float distance = 0f;
        for (Entry entry : ordered) {
            if (entry.slotInitialized) {
                entry.slot.setTarget(distance);
            } else {
                entry.slot.setInstant(distance);
                entry.slotInitialized = true;
            }
            distance += rowH * entry.appear.get();
        }

        float maxWidth = 0f;
        for (Entry entry : entries) {
            if (!entry.removing) {
                maxWidth = Math.max(maxWidth, entry.width);
            }
        }
        if (widthInitialized) {
            boxWidth.setTarget(maxWidth);
        } else {
            boxWidth.setInstant(maxWidth);
            widthInitialized = true;
        }
    }

    private Comparator<Entry> orderComparator() {
        Comparator<Entry> base;
        if (comparator != null) {
            base = (a, b) -> comparator.compare(a.label, b.label);
        } else {
            base = switch (sortMode) {
                case INSERT_ORDER -> Comparator.comparingLong((Entry e) -> e.seq);
                case WIDTH_DESC -> (a, b) -> Float.compare(b.width, a.width);
                case ALPHABETICAL -> (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
            };
        }
        return base.thenComparingLong((Entry e) -> e.seq);
    }

    @Override
    public float getWidth() {
        refresh();
        return boxWidth.get();
    }

    @Override
    public float getHeight() {
        refresh();
        float rowH = rowHeight();
        float total = 0f;
        for (Entry entry : entries) {
            total += rowH * entry.appear.get();
        }
        return total;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, float x, float y, float partialTick) {
        refresh();
        Theme theme = theme();
        boolean textShadow = getStyle().isTextShadow();
        float boxW = getWidth();
        float totalH = getHeight();
        float rowH = rowHeight();
        boolean upward = anchor.growsUpward();
        int align = anchor.horizontalAlign();

        int index = 0;
        for (Entry entry : ordered) {
            float appear = entry.appear.get();
            if (appear <= 0.01f) {
                index++;
                continue;
            }
            float height = rowH * appear;
            float distance = entry.slot.get();
            float top = upward ? y + totalH - distance - height : y + distance;
            float w = entry.width;
            float rowX = align == 1 ? x + boxW - w
                    : align == 0 ? x + (boxW - w) / 2f
                    : x;

            // Slide in from the anchored screen edge (vertical for centered-X anchors)
            float slide = (1f - appear) * SLIDE_DISTANCE;
            float dx = 0f;
            float dy = 0f;
            if (align != 0) {
                dx = align * slide;
            } else if (anchor.isTop()) {
                dy = -slide;
            } else if (anchor.isBottom()) {
                dy = slide;
            }

            Render2D.pushAlpha(appear);
            if (rowBackground) {
                Render2D.fillRoundedRect(graphics, rowX + dx, top + dy, w, height, ROW_RADIUS,
                        ColorUtil.withAlpha(theme.surface, 0.78f));
            }
            int color = colorProvider != null ? colorProvider.color(index, entry.label) : theme.text;
            float textX = rowX + dx + (rowBackground ? ROW_PADDING_X : 0f);
            Text2D.draw(graphics, entry.label, textX,
                    top + dy + (height - Text2D.lineHeight()) / 2f + 0.5f, color, textShadow);
            Render2D.popAlpha();
            index++;
        }
    }
}
