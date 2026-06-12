package com.stormcph.easygui.client.render.shader;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * How a shader's square 0..1 UV pattern maps onto a non-square rectangle. Without
 * correction, a wide-and-thin fill (like a HUD accent strip) would stretch the pattern
 * into streaks; these modes keep it looking right at any aspect ratio.
 */
@Environment(EnvType.CLIENT)
public enum ShaderFit {
    /** UVs span 0..1 across the rectangle; the pattern distorts with the aspect ratio. */
    STRETCH,
    /**
     * The pattern keeps its natural scale (one pattern unit = the rectangle's short side)
     * and simply continues along the long side — procedural shaders generate more of the
     * field, seamlessly.
     */
    TILE,
    /**
     * "Zoom to fill" (the default): the pattern is scaled uniformly until it covers the
     * rectangle and the overflow is cropped. No distortion; features get bigger the more
     * elongated the rectangle is.
     */
    COVER;

    /**
     * The UV rectangle {@code {u0, v0, u1, v1}} this mode assigns to a {@code width × height}
     * fill.
     */
    public float[] uvRect(float width, float height) {
        switch (this) {
            case TILE:
                if (width >= height) {
                    return new float[]{0f, 0f, width / height, 1f};
                }
                return new float[]{0f, 0f, 1f, height / width};
            case COVER:
                if (width >= height) {
                    float half = height / width / 2f;
                    return new float[]{0f, 0.5f - half, 1f, 0.5f + half};
                }
                float half = width / height / 2f;
                return new float[]{0.5f - half, 0f, 0.5f + half, 1f};
            default:
                return new float[]{0f, 0f, 1f, 1f};
        }
    }
}
