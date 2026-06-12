package com.stormcph.easygui.client.overlay;

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
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Toast notifications: small cards that slide in over the HUD, stack, count down a
 * progress sliver, and slide back out. Fire one from anywhere:
 *
 * <pre>{@code
 * Toasts.show(Toast.success("Saved").withBody("Settings written to disk."));
 * }</pre>
 *
 * <p>The first {@link #show(Toast)} lazily registers a single internal {@link HudOverlay}
 * (persist id {@code "easygui.toasts"}, default anchor {@link Anchor#TOP_RIGHT}) with the
 * {@link OverlayManager}. That makes the stack a regular overlay: players can drag and
 * re-anchor it in the {@link HudEditScreen HUD editor} (it shows a sample card there so
 * there is something to grab), its position persists, and the slide-in direction plus
 * stacking growth follow the anchor — bottom anchors grow upward, right anchors slide in
 * from the right, and the accent strip hugs the anchored side of each card. The newest
 * toast always lands nearest the anchored edge and pushes older ones away. At most
 * {@value #MAX_VISIBLE} cards are visible; further toasts queue and appear (with a full
 * timer) as slots free up. {@link #clear()} dismisses everything.</p>
 *
 * <p>Implementation note: this is a bespoke stacking overlay rather than a subclass of
 * {@link AnimatedListOverlay} — that overlay's rows are single-line text with a fixed
 * row height, so its hooks cannot host multi-line cards with strips, icons, and progress
 * bars. It does reuse the exact same animation idioms: a per-card 0..1 {@code appear}
 * {@link SmoothValue} driving fade + edge slide + height collapse, and a smoothed
 * {@code slot} distance so neighbors glide when a card enters or leaves.</p>
 *
 * <p>Toasts render during the vanilla HUD pass, so they appear <em>under</em> any open
 * screen (chat, inventory, menus) and their timers keep counting there; they are not a
 * screen-level notification system. Hiding the overlay in the HUD editor suppresses the
 * whole stack like any other overlay. {@link #show(Toast)} and {@link #clear()} are safe
 * to call from any thread; card activation and animation always happen on the render
 * thread.</p>
 */
@Environment(EnvType.CLIENT)
public final class Toasts {
    /** Cards on screen at once before further toasts queue up. */
    private static final int MAX_VISIBLE = 5;

    private static final float CARD_WIDTH = 180f;
    private static final float STRIP_WIDTH = 3f;
    private static final float PAD_X = 7f;
    private static final float PAD_TOP = 6f;
    private static final float TITLE_ROW_HEIGHT = 10f;
    private static final float ICON_SIZE = 10f;
    private static final float ICON_GAP = 5f;
    private static final float BODY_GAP = 2f;
    /** Space below the content: gap, the 2px progress sliver, and a bottom inset. */
    private static final float BOTTOM_HEIGHT = 8f;
    private static final float PROGRESS_INSET_X = 5f;
    private static final float PROGRESS_HEIGHT = 2f;
    private static final float CARD_GAP = 4f;
    private static final float SLIDE_DISTANCE = 18f;
    private static final float SHADOW_SIZE = 4f;
    /** Text column width: card minus strip, horizontal padding, icon, and icon gap. */
    private static final float TEXT_WIDTH = CARD_WIDTH - STRIP_WIDTH - PAD_X * 2f - ICON_SIZE - ICON_GAP;

    /** Queued toasts beyond this are dropped oldest-first (e.g. while the overlay is hidden). */
    private static final int MAX_PENDING = 32;
    /** Queued toasts older than this are silently dropped at activation — no stale floods. */
    private static final long PENDING_MAX_AGE_MS = 30_000L;

    private record Queued(Toast toast, long queuedAtMillis) {
    }

    private static final Queue<Queued> PENDING = new ConcurrentLinkedQueue<>();
    private static volatile boolean clearRequested;
    private static ToastOverlay overlay;

    private Toasts() {
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Queues a toast. It appears immediately when fewer than {@value #MAX_VISIBLE} cards
     * are on screen, otherwise as soon as a slot frees up; its countdown starts when it
     * appears. Registers the toast overlay on first use. {@code null} is ignored.
     */
    public static void show(Toast toast) {
        if (toast == null) {
            return;
        }
        PENDING.add(new Queued(toast, Util.getMillis()));
        while (PENDING.size() > MAX_PENDING) {
            PENDING.poll(); // bounded even while the overlay is hidden
        }
        // First-use registration touches the overlay/config registries, which are
        // client-thread only; the queue itself is safe from any thread.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            overlay();
        } else {
            minecraft.execute(Toasts::overlay);
        }
    }

    /** Dismisses every visible toast (exit slide + fade) and drops the queued ones. */
    public static void clear() {
        PENDING.clear();
        clearRequested = true;
    }

    /**
     * The internal toast overlay, registering it if needed — for programmatic tweaks
     * like {@code Toasts.overlay().setAnchor(Anchor.BOTTOM_RIGHT)} or styling via
     * {@link HudOverlay#getStyle()}. Players can do the same interactively in the HUD
     * editor; with the persist id {@code "easygui.toasts"} their edits stick.
     */
    public static synchronized HudOverlay overlay() {
        if (overlay == null) {
            overlay = new ToastOverlay();
            OverlayManager.register(overlay);
        }
        return overlay;
    }

    // ------------------------------------------------------------------
    // Card: one activated toast with its layout and animation state
    // ------------------------------------------------------------------

    private static final class Card {
        final Toast toast;
        final String title;
        final List<String> bodyLines;
        final float height;
        final long durationMs;
        final long expireAt;
        /** 0..1 in/out progress: alpha, edge slide, and stack-extent collapse all read this. */
        final SmoothValue appear = new SmoothValue(0f, 10f);
        /** Smoothed distance from the anchored edge to this card's near edge. */
        final SmoothValue slot = new SmoothValue(0f, 14f);
        boolean slotInitialized;
        boolean removing;

        Card(Toast toast, long now) {
            this.toast = toast;
            this.title = Text2D.truncate(toast.getTitle(), (int) TEXT_WIDTH);
            this.bodyLines = wrapBody(toast.getBody(), (int) TEXT_WIDTH);
            float h = PAD_TOP + TITLE_ROW_HEIGHT;
            if (!bodyLines.isEmpty()) {
                h += BODY_GAP + bodyLines.size() * (Text2D.lineHeight() + 1f);
            }
            this.height = h + BOTTOM_HEIGHT;
            this.durationMs = Math.max(1L, Math.round(toast.getDurationSeconds() * 1000.0));
            this.expireAt = now + durationMs;
            this.appear.setTarget(1f);
        }

        /** Remaining lifetime fraction, 1 → just shown, 0 → expired. */
        float remainingFraction(long now) {
            return Mth.clamp((expireAt - now) / (float) durationMs, 0f, 1f);
        }
    }

    /**
     * Greedy word wrap to at most two lines of {@code maxWidth}; leftover text is folded
     * into the second line and ellipsized. Oversized single words are ellipsized too.
     */
    private static List<String> wrapBody(String body, int maxWidth) {
        List<String> lines = new ArrayList<>(2);
        if (body == null || body.isBlank()) {
            return lines;
        }
        String[] words = body.trim().split("\\s+");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String candidate = current.isEmpty() ? words[i] : current + " " + words[i];
            if (current.isEmpty() || Text2D.width(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (lines.isEmpty()) {
                lines.add(Text2D.truncate(current.toString(), maxWidth));
                current.setLength(0);
                current.append(words[i]);
            } else {
                // A third line would start here: fold the rest into line two and ellipsize.
                for (int j = i; j < words.length; j++) {
                    current.append(' ').append(words[j]);
                }
                lines.add(Text2D.truncate(current.toString(), maxWidth));
                return lines;
            }
        }
        if (!current.isEmpty()) {
            lines.add(Text2D.truncate(current.toString(), maxWidth));
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // The overlay
    // ------------------------------------------------------------------

    private static final class ToastOverlay extends HudOverlay {
        private final List<Card> cards = new ArrayList<>();
        private long lastRefreshMillis = -1L;
        private Card editorPreview;

        ToastOverlay() {
            setAnchor(Anchor.TOP_RIGHT);
            setOffsets(6f, 6f);
            setPersistId("easygui.toasts");
        }

        /** Once-per-frame bookkeeping: clear requests, expiries, exits, activation, slots. */
        private void refresh() {
            long now = Util.getMillis();
            if (now == lastRefreshMillis) {
                return;
            }
            lastRefreshMillis = now;

            if (clearRequested) {
                clearRequested = false;
                for (Card card : cards) {
                    if (!card.removing) {
                        card.removing = true;
                        card.appear.setTarget(0f);
                    }
                }
            }

            int live = 0;
            for (Iterator<Card> it = cards.iterator(); it.hasNext(); ) {
                Card card = it.next();
                if (card.removing) {
                    if (card.appear.get() <= 0.01f) {
                        it.remove();
                    }
                    continue;
                }
                if (now >= card.expireAt) {
                    card.removing = true;
                    card.appear.setTarget(0f);
                    continue;
                }
                live++;
            }

            // Activate queued toasts into free slots; the countdown starts now, not at
            // show(). Entries that sat queued too long (hidden overlay) are dropped.
            while (live < MAX_VISIBLE) {
                Queued next = PENDING.poll();
                if (next == null) {
                    break;
                }
                if (now - next.queuedAtMillis() > PENDING_MAX_AGE_MS) {
                    continue;
                }
                cards.add(0, new Card(next.toast(), now)); // index 0 = newest, nearest the anchored edge
                live++;
            }

            // Slot targets accumulate *animated* extents, so an exiting card pulls the
            // cards behind it along smoothly and a new card pushes them away as it grows.
            float distance = 0f;
            for (Card card : cards) {
                if (card.slotInitialized) {
                    card.slot.setTarget(distance);
                } else {
                    card.slot.setInstant(distance);
                    card.slotInitialized = true;
                }
                distance += (card.height + CARD_GAP) * card.appear.get();
            }
        }

        private boolean inEditor() {
            return Minecraft.getInstance().screen instanceof HudEditScreen;
        }

        /** Sample card shown only in the HUD editor, so an idle stack can be grabbed. */
        private Card editorPreview() {
            if (editorPreview == null) {
                editorPreview = new Card(Toast.info("Toasts appear here")
                        .withBody("Drag and re-anchor this stack like any overlay."), Util.getMillis());
            }
            return editorPreview;
        }

        @Override
        public float getWidth() {
            return CARD_WIDTH;
        }

        @Override
        public float getHeight() {
            refresh();
            if (cards.isEmpty()) {
                return inEditor() ? editorPreview().height : 0f;
            }
            float total = 0f;
            for (Card card : cards) {
                total += (card.height + CARD_GAP) * card.appear.get();
            }
            return total;
        }

        @Override
        public void render(GuiGraphics graphics, float x, float y, float partialTick) {
            refresh();
            Theme theme = theme();
            int align = anchor.horizontalAlign();

            if (cards.isEmpty()) {
                if (inEditor()) {
                    drawCard(graphics, theme, editorPreview(), x, y, align, 0.65f);
                }
                return;
            }

            long now = Util.getMillis();
            float totalH = getHeight();
            boolean upward = anchor.growsUpward();

            for (Card card : cards) {
                float appear = card.appear.get();
                if (appear <= 0.01f) {
                    continue;
                }
                // The card pins its near edge to its slot and is drawn at full height;
                // only its reserved extent in the stack collapses, masked by the fade.
                float distance = card.slot.get();
                float top = upward ? y + totalH - distance - card.height : y + distance;

                // Slide in from the anchored screen edge (vertical for centered-X
                // top/bottom anchors; pure CENTER fades only).
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
                drawCard(graphics, theme, card, x + dx, top + dy, align, card.remainingFraction(now));
                Render2D.popAlpha();
            }
        }

        /**
         * Draws one full card. The accent strip is realized as an accent-colored underlay
         * with the surface plate inset 3px on the anchored side — a thin rounded rect of
         * its own would have its corner radii clamped to half its width and poke past the
         * card's larger corner curve.
         */
        private void drawCard(GuiGraphics graphics, Theme theme, Card card,
                              float x, float y, int align, float progress) {
            float w = CARD_WIDTH;
            float h = card.height;
            float r = theme.radiusSmall;
            int accent = card.toast.accentColor(theme);
            boolean stripRight = align == 1;
            boolean textShadow = getStyle().isTextShadow();

            Render2D.dropShadow(graphics, x, y, w, h, r, SHADOW_SIZE,
                    ColorUtil.multiplyAlpha(theme.shadow, 0.7f));
            Render2D.fillRoundedRect(graphics, x, y, w, h, r, accent);
            if (stripRight) {
                Render2D.fillRoundedRect(graphics, x, y, w - STRIP_WIDTH, h, r, 0f, 0f, r, theme.surface);
            } else {
                Render2D.fillRoundedRect(graphics, x + STRIP_WIDTH, y, w - STRIP_WIDTH, h, 0f, r, r, 0f,
                        theme.surface);
            }
            Render2D.strokeRoundedRect(graphics, x, y, w, h, r, 1f, theme.outline);

            float contentX = stripRight ? x + PAD_X : x + STRIP_WIDTH + PAD_X;
            card.toast.icon().render(graphics, contentX,
                    y + PAD_TOP + (TITLE_ROW_HEIGHT - ICON_SIZE) / 2f, ICON_SIZE, accent);

            float textX = contentX + ICON_SIZE + ICON_GAP;
            Text2D.draw(graphics, card.title, textX,
                    y + PAD_TOP + (TITLE_ROW_HEIGHT - Text2D.lineHeight()) / 2f + 0.5f,
                    theme.text, textShadow);

            float lineY = y + PAD_TOP + TITLE_ROW_HEIGHT + BODY_GAP;
            for (String line : card.bodyLines) {
                Text2D.draw(graphics, line, textX, lineY, theme.textMuted, textShadow);
                lineY += Text2D.lineHeight() + 1f;
            }

            // Progress sliver: shrinks toward the anchored side as the timer runs down.
            float barMax = w - PROGRESS_INSET_X * 2f;
            float barWidth = barMax * progress;
            if (barWidth > 1f) {
                float barX = stripRight ? x + w - PROGRESS_INSET_X - barWidth : x + PROGRESS_INSET_X;
                Render2D.fillRoundedRect(graphics, barX, y + h - 2f - PROGRESS_HEIGHT,
                        barWidth, PROGRESS_HEIGHT, PROGRESS_HEIGHT / 2f, accent);
            }
        }
    }
}
