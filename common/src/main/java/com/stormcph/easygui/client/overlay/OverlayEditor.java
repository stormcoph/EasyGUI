package com.stormcph.easygui.client.overlay;

import com.stormcph.easygui.client.config.ConfigValue;
import com.stormcph.easygui.client.config.EasyConfig;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.render.Text2D;
import com.stormcph.easygui.client.theme.Theme;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared drag-to-move logic for HUD overlays, used by the {@link HudEditScreen} and by
 * "move while chat is open" mode ({@link OverlayManager#setMoveInChat}).
 *
 * <p>While dragging, overlays snap (4 GUI px) to the screen edges, the screen center
 * lines, and the edges/centers of every other overlay — with accent-colored guide lines
 * showing what snapped. On release the overlay re-anchors to the closest third of the
 * screen (so it keeps hugging its corner across resolutions) and, if the overlay has a
 * {@link HudOverlay#setPersistId persist id}, the position is saved to
 * {@code config/easygui.json}. Right-click resets an overlay to its code-defined default;
 * arrow keys nudge the selected overlay by one pixel in the editor screen.</p>
 */
@Environment(EnvType.CLIENT)
public final class OverlayEditor {
    private static final float SNAP_RANGE = 4f;

    private record DefaultPos(Anchor anchor, float x, float y) {
    }

    private record Persisted(ConfigValue<Anchor> anchor, ConfigValue<Double> x, ConfigValue<Double> y) {
    }

    private static final Map<HudOverlay, DefaultPos> DEFAULTS = new HashMap<>();
    private static final Map<HudOverlay, Persisted> PERSISTED = new HashMap<>();

    private static HudOverlay dragging;
    private static HudOverlay selected;
    private static float grabX;
    private static float grabY;
    private static final FloatList guidesX = new FloatArrayList();
    private static final FloatList guidesY = new FloatArrayList();

    private OverlayEditor() {
    }

    /** Captures defaults and applies any persisted position. Called by {@link OverlayManager#register}. */
    static void onRegister(HudOverlay overlay) {
        DEFAULTS.putIfAbsent(overlay, new DefaultPos(overlay.anchor, overlay.offsetX, overlay.offsetY));
        String id = overlay.getPersistId();
        if (id == null || PERSISTED.containsKey(overlay)) {
            return;
        }
        String key = "overlays." + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        EasyConfig config = EasyConfig.of("easygui");
        Persisted persisted = new Persisted(
                config.defineEnum(key + ".anchor", overlay.anchor),
                config.defineDouble(key + ".x", overlay.offsetX),
                config.defineDouble(key + ".y", overlay.offsetY));
        PERSISTED.put(overlay, persisted);
        overlay.setAnchor(persisted.anchor.get());
        overlay.setOffsets((float) (double) persisted.x.get(), (float) (double) persisted.y.get());
    }

    public static boolean isDragging() {
        return dragging != null;
    }

    public static HudOverlay getSelected() {
        return selected;
    }

    public static float resolveX(HudOverlay overlay) {
        return overlay.anchor.resolveX(guiWidth(), overlay.getWidth(), overlay.offsetX);
    }

    public static float resolveY(HudOverlay overlay) {
        return overlay.anchor.resolveY(guiHeight(), overlay.getHeight(), overlay.offsetY);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /** Left-click starts a drag, right-click resets; returns whether the click hit an overlay. */
    public static boolean mouseClicked(double mouseX, double mouseY, int button, boolean includeHidden) {
        HudOverlay hit = pick(mouseX, mouseY, includeHidden);
        if (hit == null) {
            selected = null;
            return false;
        }
        selected = hit;
        if (button == 1) {
            reset(hit);
            return true;
        }
        if (button != 0) {
            return false;
        }
        dragging = hit;
        grabX = (float) mouseX - resolveX(hit);
        grabY = (float) mouseY - resolveY(hit);
        return true;
    }

    public static boolean mouseDragged(double mouseX, double mouseY, boolean includeHidden) {
        if (dragging == null) {
            return false;
        }
        float screenW = guiWidth();
        float screenH = guiHeight();
        float w = dragging.getWidth();
        float h = dragging.getHeight();
        float x = (float) mouseX - grabX;
        float y = (float) mouseY - grabY;

        guidesX.clear();
        guidesY.clear();
        x += snapAxis(x, w, collectTargets(true, screenW, screenH, includeHidden), guidesX);
        y += snapAxis(y, h, collectTargets(false, screenW, screenH, includeHidden), guidesY);
        x = Mth.clamp(x, 0f, Math.max(0f, screenW - w));
        y = Mth.clamp(y, 0f, Math.max(0f, screenH - h));

        applyPosition(dragging, x, y, screenW, screenH);
        return true;
    }

    /** Ends a drag and persists the new position; returns whether a drag was active. */
    public static boolean mouseReleased(int button) {
        if (button != 0 || dragging == null) {
            return false;
        }
        finishDrag();
        return true;
    }

    /** Force-ends a drag (screen switched away mid-drag, editor closed, …). */
    public static void finishDrag() {
        if (dragging != null) {
            persist(dragging);
            dragging = null;
        }
        guidesX.clear();
        guidesY.clear();
    }

    /** Moves the selected overlay by whole pixels (arrow keys in the editor). */
    public static boolean nudge(float dx, float dy) {
        if (selected == null) {
            return false;
        }
        float screenW = guiWidth();
        float screenH = guiHeight();
        float x = Mth.clamp(resolveX(selected) + dx, 0f, Math.max(0f, screenW - selected.getWidth()));
        float y = Mth.clamp(resolveY(selected) + dy, 0f, Math.max(0f, screenH - selected.getHeight()));
        applyPosition(selected, x, y, screenW, screenH);
        persist(selected);
        return true;
    }

    /** Restores the overlay's code-defined anchor/offsets (and persists the reset). */
    public static void reset(HudOverlay overlay) {
        Persisted persisted = PERSISTED.get(overlay);
        if (persisted != null) {
            persisted.anchor.reset();
            persisted.x.reset();
            persisted.y.reset();
            overlay.setAnchor(persisted.anchor.get());
            overlay.setOffsets((float) (double) persisted.x.get(), (float) (double) persisted.y.get());
            return;
        }
        DefaultPos defaults = DEFAULTS.get(overlay);
        if (defaults != null) {
            overlay.setAnchor(defaults.anchor());
            overlay.setOffsets(defaults.x(), defaults.y());
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** Draws selection boxes, labels, and any active snap guides. */
    public static void renderChrome(GuiGraphics graphics, double mouseX, double mouseY, boolean includeHidden) {
        Theme theme = Theme.getDefault();
        for (HudOverlay overlay : OverlayManager.overlays()) {
            if (!includeHidden && !overlay.isVisible()) {
                continue;
            }
            float x = resolveX(overlay);
            float y = resolveY(overlay);
            float w = overlay.getWidth();
            float h = overlay.getHeight();
            boolean active = overlay == dragging
                    || (dragging == null && contains(x, y, w, h, mouseX, mouseY))
                    || overlay == selected;
            int color = active ? theme.accent : ColorUtil.withAlpha(theme.text, 0.35f);
            if (active) {
                Render2D.fillRoundedRect(graphics, x - 2, y - 2, w + 4, h + 4, 4f,
                        ColorUtil.withAlpha(theme.accent, 0.10f));
            }
            Render2D.strokeRoundedRect(graphics, x - 2, y - 2, w + 4, h + 4, 4f, 1f, color);
            if (active) {
                String label = overlay.getPersistId() != null
                        ? overlay.getPersistId() : overlay.getClass().getSimpleName();
                if (!overlay.isVisible()) {
                    label += " (hidden)";
                }
                float labelY = y - 13 < 2 ? y + h + 5 : y - 13;
                Text2D.draw(graphics, label, x, labelY, theme.textMuted);
            }
        }
        int guide = ColorUtil.withAlpha(theme.accent, 0.85f);
        for (int i = 0; i < guidesX.size(); i++) {
            Render2D.fillRect(graphics, guidesX.getFloat(i) - 0.5f, 0, 1f, guiHeight(), guide);
        }
        for (int i = 0; i < guidesY.size(); i++) {
            Render2D.fillRect(graphics, 0, guidesY.getFloat(i) - 0.5f, guiWidth(), 1f, guide);
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static HudOverlay pick(double mouseX, double mouseY, boolean includeHidden) {
        List<HudOverlay> overlays = OverlayManager.overlays();
        for (int i = overlays.size() - 1; i >= 0; i--) {
            HudOverlay overlay = overlays.get(i);
            if (!includeHidden && !overlay.isVisible()) {
                continue;
            }
            if (contains(resolveX(overlay), resolveY(overlay), overlay.getWidth(), overlay.getHeight(),
                    mouseX, mouseY)) {
                return overlay;
            }
        }
        return null;
    }

    private static boolean contains(float x, float y, float w, float h, double mouseX, double mouseY) {
        return mouseX >= x - 2 && mouseX < x + w + 2 && mouseY >= y - 2 && mouseY < y + h + 2;
    }

    /** Screen edges, screen center, and every other overlay's edges/center on one axis. */
    private static FloatList collectTargets(boolean xAxis, float screenW, float screenH, boolean includeHidden) {
        FloatList targets = new FloatArrayList();
        float screen = xAxis ? screenW : screenH;
        targets.add(0f);
        targets.add(screen / 2f);
        targets.add(screen);
        for (HudOverlay other : OverlayManager.overlays()) {
            if (other == dragging || (!includeHidden && !other.isVisible())) {
                continue;
            }
            float start = xAxis ? resolveX(other) : resolveY(other);
            float size = xAxis ? other.getWidth() : other.getHeight();
            targets.add(start);
            targets.add(start + size / 2f);
            targets.add(start + size);
        }
        return targets;
    }

    /**
     * Finds the smallest in-range correction that aligns the overlay's start/center/end
     * with a target line; records the matched line for guide rendering.
     */
    private static float snapAxis(float pos, float size, FloatList targets, FloatList guidesOut) {
        float bestDelta = Float.MAX_VALUE;
        float bestLine = 0f;
        for (int i = 0; i < targets.size(); i++) {
            float target = targets.getFloat(i);
            for (float own : new float[]{0f, size / 2f, size}) {
                float delta = target - (pos + own);
                if (Math.abs(delta) < Math.abs(bestDelta)) {
                    bestDelta = delta;
                    bestLine = target;
                }
            }
        }
        if (Math.abs(bestDelta) <= SNAP_RANGE) {
            guidesOut.add(bestLine);
            return bestDelta;
        }
        return 0f;
    }

    /**
     * Moves the overlay to an absolute position, re-anchoring it to the closest third of
     * the screen so it stays attached to the right edge/corner across resolutions. The
     * conversion is lossless — the on-screen position doesn't change.
     */
    private static void applyPosition(HudOverlay overlay, float x, float y, float screenW, float screenH) {
        float w = overlay.getWidth();
        float h = overlay.getHeight();
        float centerX = x + w / 2f;
        float centerY = y + h / 2f;
        float factorX = centerX < screenW / 3f ? 0f : centerX > screenW * 2f / 3f ? 1f : 0.5f;
        float factorY = centerY < screenH / 3f ? 0f : centerY > screenH * 2f / 3f ? 1f : 0.5f;
        Anchor anchor = Anchor.of(factorX, factorY);
        overlay.setAnchor(anchor);
        overlay.setOffsets(anchor.offsetForX(screenW, w, x), anchor.offsetForY(screenH, h, y));
    }

    private static void persist(HudOverlay overlay) {
        Persisted persisted = PERSISTED.get(overlay);
        if (persisted != null) {
            persisted.anchor.set(overlay.anchor);
            persisted.x.set((double) overlay.offsetX);
            persisted.y.set((double) overlay.offsetY);
        }
    }

    private static float guiWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    private static float guiHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }
}
