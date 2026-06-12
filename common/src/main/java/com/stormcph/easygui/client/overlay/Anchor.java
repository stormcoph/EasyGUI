package com.stormcph.easygui.client.overlay;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Screen-relative anchor points for HUD overlays. Offsets are applied inward from the
 * anchored edge (and ignored on centered axes), so an overlay stays a fixed margin from
 * its corner at any resolution.
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

    public float resolveX(float screenWidth, float width, float offsetX) {
        float base = (screenWidth - width) * factorX;
        if (factorX == 0f) return base + offsetX;
        if (factorX == 1f) return base - offsetX;
        return base;
    }

    public float resolveY(float screenHeight, float height, float offsetY) {
        float base = (screenHeight - height) * factorY;
        if (factorY == 0f) return base + offsetY;
        if (factorY == 1f) return base - offsetY;
        return base;
    }
}
