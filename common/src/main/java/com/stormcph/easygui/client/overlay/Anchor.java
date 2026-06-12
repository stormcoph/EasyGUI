package com.stormcph.easygui.client.overlay;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Screen-relative anchor points for HUD overlays. Offsets are applied inward from the
 * anchored edge (on centered axes they shift right/down from the center), so an overlay
 * stays a fixed margin from its anchor at any resolution.
 */
@Environment(EnvType.CLIENT)
public enum Anchor {
    TOP_LEFT(0f, 0f),
    TOP_CENTER(0.5f, 0f),
    TOP_RIGHT(1f, 0f),
    CENTER_LEFT(0f, 0.5f),
    CENTER(0.5f, 0.5f),
    CENTER_RIGHT(1f, 0.5f),
    BOTTOM_LEFT(0f, 1f),
    BOTTOM_CENTER(0.5f, 1f),
    BOTTOM_RIGHT(1f, 1f);

    public final float factorX;
    public final float factorY;

    Anchor(float factorX, float factorY) {
        this.factorX = factorX;
        this.factorY = factorY;
    }

    /** The anchor whose axis factors (0, 0.5, 1) match — e.g. {@code of(1, 0)} = TOP_RIGHT. */
    public static Anchor of(float factorX, float factorY) {
        for (Anchor anchor : values()) {
            if (anchor.factorX == factorX && anchor.factorY == factorY) {
                return anchor;
            }
        }
        return TOP_LEFT;
    }

    public float resolveX(float screenWidth, float width, float offsetX) {
        float base = (screenWidth - width) * factorX;
        if (factorX == 1f) return base - offsetX;
        return base + offsetX;
    }

    public float resolveY(float screenHeight, float height, float offsetY) {
        float base = (screenHeight - height) * factorY;
        if (factorY == 1f) return base - offsetY;
        return base + offsetY;
    }

    /** Inverse of {@link #resolveX}: the offset that puts an overlay's left edge at {@code x}. */
    public float offsetForX(float screenWidth, float width, float x) {
        float base = (screenWidth - width) * factorX;
        if (factorX == 1f) return base - x;
        return x - base;
    }

    /** Inverse of {@link #resolveY}: the offset that puts an overlay's top edge at {@code y}. */
    public float offsetForY(float screenHeight, float height, float y) {
        float base = (screenHeight - height) * factorY;
        if (factorY == 1f) return base - y;
        return y - base;
    }

    // ------------------------------------------------------------------
    // Semantic helpers (alignment-aware overlays read these)
    // ------------------------------------------------------------------

    /** True for the three left-edge anchors ({@code factorX == 0}). */
    public boolean isLeft() {
        return factorX == 0f;
    }

    /** True for the three right-edge anchors ({@code factorX == 1}). */
    public boolean isRight() {
        return factorX == 1f;
    }

    /** True for the three horizontally centered anchors ({@code factorX == 0.5}). */
    public boolean isCenterX() {
        return factorX == 0.5f;
    }

    /** True for the three top-edge anchors ({@code factorY == 0}). */
    public boolean isTop() {
        return factorY == 0f;
    }

    /** True for the three bottom-edge anchors ({@code factorY == 1}). */
    public boolean isBottom() {
        return factorY == 1f;
    }

    /** True for the three vertically centered anchors ({@code factorY == 0.5}). */
    public boolean isCenterY() {
        return factorY == 0.5f;
    }

    /**
     * Horizontal alignment as a sign: {@code -1} left, {@code 0} center, {@code +1} right —
     * e.g. for right-aligning text inside right-anchored overlays.
     */
    public int horizontalAlign() {
        return factorX == 0f ? -1 : factorX == 1f ? 1 : 0;
    }

    /** Whether stacked content should grow upward (bottom anchors keep their lower edge pinned). */
    public boolean growsUpward() {
        return isBottom();
    }
}
