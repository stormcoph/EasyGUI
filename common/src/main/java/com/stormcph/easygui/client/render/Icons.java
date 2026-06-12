package com.stormcph.easygui.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Built-in vector icons. These are drawn procedurally (anti-aliased polylines, polygons
 * and circles), so they stay crisp at any size and GUI scale and can be tinted freely —
 * no texture assets required.
 *
 * <p>All shapes are built so that no geometry overlaps: paths with shared joints use
 * {@link Render2D#polyline} (single mitered mesh), crossing strokes are filled polygons,
 * and lines that meet other shapes use butt caps that join flush. This keeps icons clean
 * even when tinted with translucent colors or faded in/out.</p>
 */
@Environment(EnvType.CLIENT)
public final class Icons {
    private Icons() {
    }

    public static final Icon CLOSE = (g, x, y, s, c) ->
            cross(g, x + s * 0.5f, y + s * 0.5f, s * 0.34f, stroke(s) / 2f, 45f, c);

    public static final Icon PLUS = (g, x, y, s, c) ->
            cross(g, x + s * 0.5f, y + s * 0.5f, s * 0.30f, stroke(s) / 2f, 0f, c);

    public static final Icon MINUS = (g, x, y, s, c) ->
            Render2D.line(g, x + s * 0.2f, y + s * 0.5f, x + s * 0.8f, y + s * 0.5f, stroke(s), c);

    public static final Icon CHECK = (g, x, y, s, c) ->
            Render2D.polyline(g, new float[]{
                    x + s * 0.22f, y + s * 0.55f,
                    x + s * 0.42f, y + s * 0.72f,
                    x + s * 0.78f, y + s * 0.30f
            }, stroke(s), c, true);

    public static final Icon CHEVRON_DOWN = (g, x, y, s, c) ->
            Render2D.polyline(g, new float[]{
                    x + s * 0.25f, y + s * 0.4f,
                    x + s * 0.5f, y + s * 0.65f,
                    x + s * 0.75f, y + s * 0.4f
            }, stroke(s), c, true);

    public static final Icon CHEVRON_UP = (g, x, y, s, c) ->
            Render2D.polyline(g, new float[]{
                    x + s * 0.25f, y + s * 0.6f,
                    x + s * 0.5f, y + s * 0.35f,
                    x + s * 0.75f, y + s * 0.6f
            }, stroke(s), c, true);

    public static final Icon CHEVRON_LEFT = (g, x, y, s, c) ->
            Render2D.polyline(g, new float[]{
                    x + s * 0.6f, y + s * 0.25f,
                    x + s * 0.35f, y + s * 0.5f,
                    x + s * 0.6f, y + s * 0.75f
            }, stroke(s), c, true);

    public static final Icon CHEVRON_RIGHT = (g, x, y, s, c) ->
            Render2D.polyline(g, new float[]{
                    x + s * 0.4f, y + s * 0.25f,
                    x + s * 0.65f, y + s * 0.5f,
                    x + s * 0.4f, y + s * 0.75f
            }, stroke(s), c, true);

    public static final Icon SEARCH = (g, x, y, s, c) -> {
        float w = stroke(s);
        float cx = x + s * 0.42f;
        float cy = y + s * 0.42f;
        float r = s * 0.24f;
        Render2D.strokeCircle(g, cx, cy, r, w, c);
        // Handle starts flush at the ring's outer edge (butt cap), round cap at the end
        float ux = 0.70711f;
        Render2D.line(g, cx + ux * r, cy + ux * r, x + s * 0.84f, y + s * 0.84f, w, c, false, true);
    };

    public static final Icon GEAR = (g, x, y, s, c) -> {
        float cx = x + s * 0.5f;
        float cy = y + s * 0.5f;
        float w = stroke(s);
        float ringRadius = s * 0.28f;
        Render2D.strokeCircle(g, cx, cy, ringRadius, w, c);
        Render2D.fillCircle(g, cx, cy, s * 0.10f, c);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            float dx = (float) Math.cos(a);
            float dy = (float) Math.sin(a);
            // Teeth start flush at the ring's outer edge
            Render2D.line(g, cx + dx * ringRadius, cy + dy * ringRadius,
                    cx + dx * s * 0.44f, cy + dy * s * 0.44f, w, c, false, true);
        }
    };

    public static final Icon INFO = (g, x, y, s, c) -> {
        float cx = x + s * 0.5f;
        float w = stroke(s);
        Render2D.strokeCircle(g, cx, y + s * 0.5f, s * 0.36f, w, c);
        Render2D.fillCircle(g, cx, y + s * 0.34f, w * 0.7f, c);
        Render2D.line(g, cx, y + s * 0.48f, cx, y + s * 0.68f, w, c);
    };

    public static final Icon WARNING = (g, x, y, s, c) -> {
        float w = stroke(s);
        Render2D.polylineClosed(g, new float[]{
                x + s * 0.5f, y + s * 0.18f,
                x + s * 0.88f, y + s * 0.82f,
                x + s * 0.12f, y + s * 0.82f
        }, w, c);
        Render2D.line(g, x + s * 0.5f, y + s * 0.42f, x + s * 0.5f, y + s * 0.58f, w, c);
        Render2D.fillCircle(g, x + s * 0.5f, y + s * 0.72f, w * 0.7f, c);
    };

    public static final Icon MENU = (g, x, y, s, c) -> {
        float w = stroke(s);
        Render2D.line(g, x + s * 0.2f, y + s * 0.3f, x + s * 0.8f, y + s * 0.3f, w, c);
        Render2D.line(g, x + s * 0.2f, y + s * 0.5f, x + s * 0.8f, y + s * 0.5f, w, c);
        Render2D.line(g, x + s * 0.2f, y + s * 0.7f, x + s * 0.8f, y + s * 0.7f, w, c);
    };

    public static final Icon COPY = (g, x, y, s, c) -> {
        float w = stroke(s);
        Render2D.strokeRoundedRect(g, x + s * 0.32f, y + s * 0.32f, s * 0.48f, s * 0.48f, s * 0.08f, w, c);
        Render2D.polyline(g, new float[]{
                x + s * 0.22f, y + s * 0.62f,
                x + s * 0.18f, y + s * 0.62f,
                x + s * 0.18f, y + s * 0.18f,
                x + s * 0.62f, y + s * 0.18f,
                x + s * 0.62f, y + s * 0.22f
        }, w, c, true);
    };

    public static final Icon USER = (g, x, y, s, c) -> {
        Render2D.fillCircle(g, x + s * 0.5f, y + s * 0.34f, s * 0.18f, c);
        Render2D.drawArc(g, x + s * 0.5f, y + s * 0.95f, s * 0.38f, s * 0.20f, -65f, 65f, c);
    };

    public static final Icon DOT = (g, x, y, s, c) ->
            Render2D.fillCircle(g, x + s * 0.5f, y + s * 0.5f, s * 0.25f, c);

    public static final Icon FOLDER = (g, x, y, s, c) -> {
        float w = stroke(s);
        Render2D.strokeRoundedRect(g, x + s * 0.15f, y + s * 0.3f, s * 0.7f, s * 0.45f, s * 0.08f, w, c);
        // Filled tab sitting flush on the body's top edge
        Render2D.fillPolygon(g, new float[]{
                x + s * 0.24f, y + s * 0.30f,
                x + s * 0.30f, y + s * 0.18f,
                x + s * 0.50f, y + s * 0.18f,
                x + s * 0.56f, y + s * 0.30f
        }, c);
    };

    public static final Icon ARROW_RIGHT = (g, x, y, s, c) -> {
        float w = stroke(s);
        // Shaft butts flush against the arrowhead's base
        Render2D.line(g, x + s * 0.2f, y + s * 0.5f, x + s * 0.58f, y + s * 0.5f, w, c, true, false);
        Render2D.fillPolygon(g, new float[]{
                x + s * 0.58f, y + s * 0.28f,
                x + s * 0.82f, y + s * 0.5f,
                x + s * 0.58f, y + s * 0.72f
        }, c);
    };

    /** A plus shape as a single filled 12-gon (rotated 45° for an X) — no crossing strokes. */
    private static void cross(GuiGraphics g, float cx, float cy, float arm, float halfThickness,
                              float rotationDeg, int c) {
        float t = halfThickness;
        float l = arm;
        float[] base = {
                -t, -l, t, -l, t, -t, l, -t, l, t, t, t,
                t, l, -t, l, -t, t, -l, t, -l, -t, -t, -t
        };
        double rad = Math.toRadians(rotationDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float[] pts = new float[base.length];
        for (int i = 0; i < base.length; i += 2) {
            pts[i] = cx + base[i] * cos - base[i + 1] * sin;
            pts[i + 1] = cy + base[i] * sin + base[i + 1] * cos;
        }
        Render2D.fillPolygon(g, pts, c);
    }

    private static float stroke(float size) {
        return Math.max(1f, size / 9f);
    }
}
