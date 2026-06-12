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
 * <p>While dragging, overlays snap to the screen edges and center lines (4 GUI px) and to
 * the edges/centers of every other overlay (6 GUI px) — with accent-colored guide lines
 * showing only the snaps that are active. On release the overlay re-anchors to the closest
 * third of the screen (so it keeps hugging its corner across resolutions). A hovered or
 * selected overlay also shows a corner grip at the bottom-right of its bounding box;
 * dragging it rescales the overlay ({@link HudStyle#setScale}, snapped to 0.05 steps)
 * while the top-left corner stays pinned.</p>
 *
 * <p>For overlays with a {@link HudOverlay#setPersistId persist id}, the anchor/offsets
 * <em>and</em> the full {@link HudStyle} (plus visibility) are saved to
 * {@code config/easygui.json} whenever the editor changes them; missing style keys in
 * older files simply fall back to the code-defined defaults. Right-click resets an
 * overlay in chat-move mode (the editor screen routes right-clicks to a context menu
 * instead); arrow keys nudge the selected overlay by one pixel in the editor screen.</p>
 */
@Environment(EnvType.CLIENT)
public final class OverlayEditor {
    private static final float SNAP_RANGE_SCREEN = 4f;
    private static final float SNAP_RANGE_ELEMENT = 6f;
    /** Gap between an overlay's styled box and its chrome rectangle. */
    private static final float CHROME_PAD = 2f;
    private static final float GRIP_SIZE = 6f;
    private static final float GRIP_HIT_RANGE = 5f;
    private static final float SCALE_SNAP = 0.05f;

    private record DefaultPos(Anchor anchor, float x, float y) {
    }

    private record Persisted(ConfigValue<Anchor> anchor, ConfigValue<Double> x, ConfigValue<Double> y,
                             ConfigValue<Boolean> visible, ConfigValue<Double> scale,
                             ConfigValue<Double> opacity, ConfigValue<Double> padding,
                             ConfigValue<HudStyle.Background> background, ConfigValue<Integer> backgroundColor,
                             ConfigValue<Double> radius, ConfigValue<Boolean> outline,
                             ConfigValue<Boolean> shadow, ConfigValue<Boolean> textShadow) {
    }

    private static final Map<HudOverlay, DefaultPos> DEFAULTS = new HashMap<>();
    private static final Map<HudOverlay, Persisted> PERSISTED = new HashMap<>();

    private static HudOverlay dragging;
    private static HudOverlay scaling;
    private static HudOverlay selected;
    private static float grabX;
    private static float grabY;
    /** Pinned top-left of the overlay while its corner grip is being dragged. */
    private static float scaleOriginX;
    private static float scaleOriginY;
    private static final FloatList guidesX = new FloatArrayList();
    private static final FloatList guidesY = new FloatArrayList();

    private OverlayEditor() {
    }

    /** Captures defaults and applies any persisted position/style. Called by {@link OverlayManager#register}. */
    static void onRegister(HudOverlay overlay) {
        DEFAULTS.putIfAbsent(overlay, new DefaultPos(overlay.anchor, overlay.offsetX, overlay.offsetY));
        String id = overlay.getPersistId();
        if (id == null || PERSISTED.containsKey(overlay)) {
            return;
        }
        String key = "overlays." + persistKey(id);
        EasyConfig config = EasyConfig.of("easygui");
        HudStyle style = overlay.getStyle();
        Persisted persisted = new Persisted(
                config.defineEnum(key + ".anchor", overlay.anchor),
                config.defineDouble(key + ".x", overlay.offsetX),
                config.defineDouble(key + ".y", overlay.offsetY),
                config.defineBool(key + ".visible", overlay.isVisible()),
                config.defineDouble(key + ".style.scale", style.getScale(), 0.5, 3.0),
                config.defineDouble(key + ".style.opacity", style.getOpacity(), 0.0, 1.0),
                config.defineDouble(key + ".style.padding", style.getPadding()),
                config.defineEnum(key + ".style.background", style.getBackground()),
                config.defineColor(key + ".style.background_color", style.getBackgroundColor()),
                config.defineDouble(key + ".style.radius", style.getRadius()),
                config.defineBool(key + ".style.outline", style.isOutline()),
                config.defineBool(key + ".style.shadow", style.isShadow()),
                config.defineBool(key + ".style.text_shadow", style.isTextShadow()));
        PERSISTED.put(overlay, persisted);
        overlay.setAnchor(persisted.anchor.get());
        overlay.setOffsets((float) (double) persisted.x.get(), (float) (double) persisted.y.get());
        overlay.setVisible(persisted.visible.get());
        style.setScale((float) (double) persisted.scale.get())
                .setOpacity((float) (double) persisted.opacity.get())
                .setPadding((float) (double) persisted.padding.get())
                .setBackground(persisted.background.get())
                .setBackgroundColor(persisted.backgroundColor.get())
                .setRadius((float) (double) persisted.radius.get())
                .setOutline(persisted.outline.get())
                .setShadow(persisted.shadow.get())
                .setTextShadow(persisted.textShadow.get());
    }

    /** The config-key-safe form of a persist id (shared with {@link HudLayouts} profiles). */
    static String persistKey(String id) {
        return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
    }

    public static boolean isDragging() {
        return dragging != null || scaling != null;
    }

    public static HudOverlay getSelected() {
        return selected;
    }

    /** Marks an overlay as the keyboard/nudge selection (the editor screen's context menu uses this). */
    public static void setSelected(HudOverlay overlay) {
        selected = overlay;
    }

    /** The topmost overlay whose chrome box contains the given point, or {@code null}. */
    public static HudOverlay pickOverlay(double mouseX, double mouseY, boolean includeHidden) {
        return pick(mouseX, mouseY, includeHidden);
    }

    public static float resolveX(HudOverlay overlay) {
        return overlay.anchor.resolveX(guiWidth(), overlay.styledWidth(), overlay.offsetX);
    }

    public static float resolveY(HudOverlay overlay) {
        return overlay.anchor.resolveY(guiHeight(), overlay.styledHeight(), overlay.offsetY);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Left-click starts a drag (or a scale-grip drag when the click lands on a corner
     * grip), right-click resets; returns whether the click hit an overlay. The editor
     * screen routes right-clicks to its context menu before calling this.
     */
    public static boolean mouseClicked(double mouseX, double mouseY, int button, boolean includeHidden) {
        if (button == 0) {
            HudOverlay grip = pickGrip(mouseX, mouseY, includeHidden);
            if (grip != null) {
                selected = grip;
                scaling = grip;
                scaleOriginX = resolveX(grip);
                scaleOriginY = resolveY(grip);
                return true;
            }
        }
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
        if (scaling != null) {
            dragScale(mouseX, mouseY);
            return true;
        }
        if (dragging == null) {
            return false;
        }
        float screenW = guiWidth();
        float screenH = guiHeight();
        float w = dragging.styledWidth();
        float h = dragging.styledHeight();
        float x = (float) mouseX - grabX;
        float y = (float) mouseY - grabY;

        guidesX.clear();
        guidesY.clear();
        x += snapAxis(x, w, true, screenW, screenH, includeHidden, guidesX);
        y += snapAxis(y, h, false, screenW, screenH, includeHidden, guidesY);
        x = Mth.clamp(x, 0f, Math.max(0f, screenW - w));
        y = Mth.clamp(y, 0f, Math.max(0f, screenH - h));

        applyPosition(dragging, x, y, screenW, screenH);
        return true;
    }

    /** Ends a move or scale drag and persists the changes; returns whether one was active. */
    public static boolean mouseReleased(int button) {
        if (button != 0 || (dragging == null && scaling == null)) {
            return false;
        }
        finishDrag();
        return true;
    }

    /** Force-ends any drag (screen switched away mid-drag, editor closed, …). */
    public static void finishDrag() {
        if (dragging != null) {
            persist(dragging);
            dragging = null;
        }
        if (scaling != null) {
            persistAll(scaling);
            scaling = null;
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
        float x = Mth.clamp(resolveX(selected) + dx, 0f, Math.max(0f, screenW - selected.styledWidth()));
        float y = Mth.clamp(resolveY(selected) + dy, 0f, Math.max(0f, screenH - selected.styledHeight()));
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

    /** Draws selection boxes, scale grips, labels, and any active snap guides. */
    public static void renderChrome(GuiGraphics graphics, double mouseX, double mouseY, boolean includeHidden) {
        Theme theme = Theme.getDefault();
        for (HudOverlay overlay : OverlayManager.overlays()) {
            if (!includeHidden && !overlay.isVisible()) {
                continue;
            }
            float x = resolveX(overlay);
            float y = resolveY(overlay);
            float w = overlay.styledWidth();
            float h = overlay.styledHeight();
            boolean active = overlay == dragging || overlay == scaling
                    || (dragging == null && scaling == null && contains(x, y, w, h, mouseX, mouseY))
                    || overlay == selected;
            int color = active ? theme.accent : ColorUtil.withAlpha(theme.text, 0.35f);
            if (active) {
                Render2D.fillRoundedRect(graphics, x - CHROME_PAD, y - CHROME_PAD,
                        w + CHROME_PAD * 2, h + CHROME_PAD * 2, 4f,
                        ColorUtil.withAlpha(theme.accent, 0.10f));
            }
            Render2D.strokeRoundedRect(graphics, x - CHROME_PAD, y - CHROME_PAD,
                    w + CHROME_PAD * 2, h + CHROME_PAD * 2, 4f, 1f, color);
            if (active) {
                // Corner grip: drag to rescale (white knob with an accent ring, like a slider thumb)
                float gx = x + w + CHROME_PAD;
                float gy = y + h + CHROME_PAD;
                Render2D.fillRoundedRect(graphics, gx - GRIP_SIZE / 2f, gy - GRIP_SIZE / 2f,
                        GRIP_SIZE, GRIP_SIZE, 2f, 0xFFFFFFFF);
                Render2D.strokeRoundedRect(graphics, gx - GRIP_SIZE / 2f, gy - GRIP_SIZE / 2f,
                        GRIP_SIZE, GRIP_SIZE, 2f, 1f, theme.accent);

                String label = overlay.getPersistId() != null
                        ? overlay.getPersistId() : overlay.getClass().getSimpleName();
                if (!overlay.isVisible()) {
                    label += " (hidden)";
                }
                if (overlay == scaling) {
                    label += String.format(Locale.ROOT, " — %d%%",
                            Math.round(overlay.getStyle().getScale() * 100f));
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
            if (contains(resolveX(overlay), resolveY(overlay), overlay.styledWidth(), overlay.styledHeight(),
                    mouseX, mouseY)) {
                return overlay;
            }
        }
        return null;
    }

    /** The topmost overlay whose bottom-right scale grip is under the mouse, or {@code null}. */
    private static HudOverlay pickGrip(double mouseX, double mouseY, boolean includeHidden) {
        List<HudOverlay> overlays = OverlayManager.overlays();
        for (int i = overlays.size() - 1; i >= 0; i--) {
            HudOverlay overlay = overlays.get(i);
            if (!includeHidden && !overlay.isVisible()) {
                continue;
            }
            float gx = resolveX(overlay) + overlay.styledWidth() + CHROME_PAD;
            float gy = resolveY(overlay) + overlay.styledHeight() + CHROME_PAD;
            if (Math.abs(mouseX - gx) <= GRIP_HIT_RANGE && Math.abs(mouseY - gy) <= GRIP_HIT_RANGE) {
                return overlay;
            }
        }
        return null;
    }

    private static boolean contains(float x, float y, float w, float h, double mouseX, double mouseY) {
        return mouseX >= x - 2 && mouseX < x + w + 2 && mouseY >= y - 2 && mouseY < y + h + 2;
    }

    /**
     * Rescales the overlay under the corner grip: the scale follows the diagonal from the
     * pinned top-left corner to the mouse, snapped to 0.05 steps and clamped by
     * {@link HudStyle#setScale} (0.5–3). The top-left corner stays where it was when the
     * grip was grabbed, so the box grows toward the grip like in any canvas editor.
     */
    private static void dragScale(double mouseX, double mouseY) {
        HudStyle style = scaling.getStyle();
        float baseW = scaling.getWidth() + style.getPadding() * 2f;
        float baseH = scaling.getHeight() + style.getPadding() * 2f;
        float baseDiagonal = (float) Math.hypot(baseW, baseH);
        if (baseDiagonal < 0.001f) {
            return;
        }
        float distance = (float) Math.hypot(mouseX - scaleOriginX, mouseY - scaleOriginY);
        style.setScale(Math.round(distance / baseDiagonal / SCALE_SNAP) * SCALE_SNAP);

        float screenW = guiWidth();
        float screenH = guiHeight();
        float x = Mth.clamp(scaleOriginX, 0f, Math.max(0f, screenW - scaling.styledWidth()));
        float y = Mth.clamp(scaleOriginY, 0f, Math.max(0f, screenH - scaling.styledHeight()));
        applyPosition(scaling, x, y, screenW, screenH);
    }

    /**
     * Finds the smallest in-range correction that aligns the dragged overlay's
     * start/center/end with a snap line on one axis — screen edges and center within
     * {@link #SNAP_RANGE_SCREEN}, other overlays' edges/centers within
     * {@link #SNAP_RANGE_ELEMENT} — and records the matched line for guide rendering.
     */
    private static float snapAxis(float pos, float size, boolean xAxis, float screenW, float screenH,
                                  boolean includeHidden, FloatList guidesOut) {
        float screen = xAxis ? screenW : screenH;
        float[] own = {0f, size / 2f, size};
        float bestDelta = Float.MAX_VALUE;
        float bestLine = 0f;

        for (float target : new float[]{0f, screen / 2f, screen}) {
            for (float edge : own) {
                float delta = target - (pos + edge);
                if (Math.abs(delta) <= SNAP_RANGE_SCREEN && Math.abs(delta) < Math.abs(bestDelta)) {
                    bestDelta = delta;
                    bestLine = target;
                }
            }
        }
        for (HudOverlay other : OverlayManager.overlays()) {
            if (other == dragging || (!includeHidden && !other.isVisible())) {
                continue;
            }
            float start = xAxis ? resolveX(other) : resolveY(other);
            float otherSize = xAxis ? other.styledWidth() : other.styledHeight();
            for (float target : new float[]{start, start + otherSize / 2f, start + otherSize}) {
                for (float edge : own) {
                    float delta = target - (pos + edge);
                    if (Math.abs(delta) <= SNAP_RANGE_ELEMENT && Math.abs(delta) < Math.abs(bestDelta)) {
                        bestDelta = delta;
                        bestLine = target;
                    }
                }
            }
        }
        if (bestDelta != Float.MAX_VALUE) {
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
        float w = overlay.styledWidth();
        float h = overlay.styledHeight();
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

    /**
     * Persists position, visibility, and every {@link HudStyle} field — called whenever
     * the editor changes more than the position (scale grip, settings popup, hide/show,
     * profile loads). No-op for overlays without a persist id.
     */
    static void persistAll(HudOverlay overlay) {
        persist(overlay);
        Persisted persisted = PERSISTED.get(overlay);
        if (persisted == null) {
            return;
        }
        HudStyle style = overlay.getStyle();
        persisted.visible.set(overlay.isVisible());
        persisted.scale.set(round4(style.getScale()));
        persisted.opacity.set(round4(style.getOpacity()));
        persisted.padding.set(round4(style.getPadding()));
        persisted.background.set(style.getBackground());
        persisted.backgroundColor.set(style.getBackgroundColor());
        persisted.radius.set(round4(style.getRadius()));
        persisted.outline.set(style.isOutline());
        persisted.shadow.set(style.isShadow());
        persisted.textShadow.set(style.isTextShadow());
    }

    /** Keeps float-to-double noise (1.1500000476…) out of the saved JSON. */
    private static double round4(float value) {
        return Math.round(value * 10000f) / 10000.0;
    }

    private static float guiWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    private static float guiHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }
}
