package com.stormcph.easygui.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The EasyGUI 2D rendering core.
 *
 * <p>Everything here renders with plain {@code position_color} / {@code position_tex_color}
 * shaders and triangle geometry, so it works identically on Fabric and NeoForge with no
 * custom shader pipeline. Curved shapes are tessellated with a sub-pixel "feather" ring
 * around their silhouette which fades to transparent, giving smooth anti-aliased edges
 * at any GUI scale.</p>
 *
 * <p>All coordinates are in GUI space (the same space {@link GuiGraphics} uses) and respect
 * the current pose transformations. Colors are packed ARGB.</p>
 */
@Environment(EnvType.CLIENT)
public final class Render2D {
    /** Global alpha multiplier stack, used by screens to fade their whole widget tree. */
    private static float globalAlpha = 1f;
    private static final float[] ALPHA_STACK = new float[16];
    private static int alphaStackSize = 0;

    private Render2D() {
    }

    // ------------------------------------------------------------------
    // Global alpha
    // ------------------------------------------------------------------

    /** Multiplies all subsequent EasyGUI draws (shapes and {@link Text2D} text) by {@code alpha}. */
    public static void pushAlpha(float alpha) {
        if (alphaStackSize < ALPHA_STACK.length) {
            ALPHA_STACK[alphaStackSize++] = globalAlpha;
        }
        globalAlpha *= Mth.clamp(alpha, 0f, 1f);
    }

    public static void popAlpha() {
        globalAlpha = alphaStackSize > 0 ? ALPHA_STACK[--alphaStackSize] : 1f;
    }

    public static float getGlobalAlpha() {
        return globalAlpha;
    }

    /** Applies the current global alpha multiplier to a packed ARGB color. */
    public static int applyGlobalAlpha(int color) {
        if (globalAlpha >= 1f) {
            return color;
        }
        return ColorUtil.multiplyAlpha(color, globalAlpha);
    }

    // ------------------------------------------------------------------
    // Rectangles
    // ------------------------------------------------------------------

    /** Axis-aligned filled rectangle (no anti-aliasing needed; edges are pixel-aligned). */
    public static void fillRect(GuiGraphics graphics, float x, float y, float width, float height, int color) {
        if (width <= 0 || height <= 0 || ColorUtil.alpha(applyGlobalAlpha(color)) == 0) {
            return;
        }
        Matrix4f mat = graphics.pose().last().pose();
        int c = applyGlobalAlpha(color);
        BufferBuilder buffer = beginColor();
        quad(buffer, mat, x, y, x + width, y + height, c, c, c, c);
        end(buffer);
    }

    /** Filled rounded rectangle with a uniform corner radius. */
    public static void fillRoundedRect(GuiGraphics graphics, float x, float y, float width, float height,
                                       float radius, int color) {
        fillRoundedRect(graphics, x, y, width, height, radius, radius, radius, radius, color);
    }

    /** Filled rounded rectangle with per-corner radii (top-left, top-right, bottom-right, bottom-left). */
    public static void fillRoundedRect(GuiGraphics graphics, float x, float y, float width, float height,
                                       float radiusTL, float radiusTR, float radiusBR, float radiusBL, int color) {
        int c = applyGlobalAlpha(color);
        if (width <= 0 || height <= 0 || ColorUtil.alpha(c) == 0) {
            return;
        }
        List<float[]> pts = roundedPerimeter(x, y, width, height, radiusTL, radiusTR, radiusBR, radiusBL);
        fillPerimeter(graphics, pts, x + width / 2f, y + height / 2f, (px, py) -> c);
    }

    /** Filled rounded rectangle with a vertical color gradient. */
    public static void fillRoundedRectGradient(GuiGraphics graphics, float x, float y, float width, float height,
                                               float radius, int colorTop, int colorBottom) {
        int top = applyGlobalAlpha(colorTop);
        int bottom = applyGlobalAlpha(colorBottom);
        if (width <= 0 || height <= 0 || (ColorUtil.alpha(top) == 0 && ColorUtil.alpha(bottom) == 0)) {
            return;
        }
        List<float[]> pts = roundedPerimeter(x, y, width, height, radius, radius, radius, radius);
        fillPerimeter(graphics, pts, x + width / 2f, y + height / 2f,
                (px, py) -> ColorUtil.lerp(top, bottom, (py - y) / height));
    }

    /** Filled rounded rectangle with a horizontal color gradient. */
    public static void fillRoundedRectGradientH(GuiGraphics graphics, float x, float y, float width, float height,
                                                float radius, int colorLeft, int colorRight) {
        int left = applyGlobalAlpha(colorLeft);
        int right = applyGlobalAlpha(colorRight);
        if (width <= 0 || height <= 0 || (ColorUtil.alpha(left) == 0 && ColorUtil.alpha(right) == 0)) {
            return;
        }
        List<float[]> pts = roundedPerimeter(x, y, width, height, radius, radius, radius, radius);
        fillPerimeter(graphics, pts, x + width / 2f, y + height / 2f,
                (px, py) -> ColorUtil.lerp(left, right, (px - x) / width));
    }

    /** Rounded rectangle outline. The stroke grows inward from the outer silhouette. */
    public static void strokeRoundedRect(GuiGraphics graphics, float x, float y, float width, float height,
                                         float radius, float thickness, int color) {
        int c = applyGlobalAlpha(color);
        if (width <= 0 || height <= 0 || thickness <= 0 || ColorUtil.alpha(c) == 0) {
            return;
        }
        List<float[]> pts = roundedPerimeter(x, y, width, height, radius, radius, radius, radius);
        strokePerimeter(graphics, pts, thickness, c, true);
    }

    // ------------------------------------------------------------------
    // Circles and arcs
    // ------------------------------------------------------------------

    public static void fillCircle(GuiGraphics graphics, float centerX, float centerY, float radius, int color) {
        int c = applyGlobalAlpha(color);
        if (radius <= 0 || ColorUtil.alpha(c) == 0) {
            return;
        }
        List<float[]> pts = arcPoints(centerX, centerY, radius, 0, 360);
        fillPerimeter(graphics, pts, centerX, centerY, (px, py) -> c);
    }

    /**
     * Fills the part of a circle that lies inside a rounded rectangle — for effects like
     * button ripples that must stay within their control's rounded silhouette (a scissor
     * region can only clip to a plain rectangle, and ignores pose transforms).
     */
    public static void fillCircleInRoundedRect(GuiGraphics graphics, float centerX, float centerY, float radius,
                                               float x, float y, float width, float height,
                                               float cornerRadius, int color) {
        int c = applyGlobalAlpha(color);
        if (radius <= 0 || width <= 0 || height <= 0 || ColorUtil.alpha(c) == 0) {
            return;
        }
        List<float[]> circle = arcPoints(centerX, centerY, radius, 0, 360);
        circle.remove(circle.size() - 1); // drop the duplicated closing point
        List<float[]> clipped = clipConvex(circle,
                roundedPerimeter(x, y, width, height, cornerRadius, cornerRadius, cornerRadius, cornerRadius));
        if (clipped.size() < 3) {
            return;
        }
        float cx = 0, cy = 0;
        for (float[] p : clipped) {
            cx += p[0];
            cy += p[1];
        }
        cx /= clipped.size();
        cy /= clipped.size();
        Matrix4f mat = graphics.pose().last().pose();
        BufferBuilder buffer = beginColor();
        int n = clipped.size();
        for (int i = 0; i < n; i++) {
            float[] a = clipped.get(i);
            float[] b = clipped.get((i + 1) % n);
            triangle(buffer, mat, cx, cy, c, a[0], a[1], c, b[0], b[1], c);
        }
        end(buffer);
    }

    /** Sutherland–Hodgman clip of one clockwise convex polygon by another. */
    private static List<float[]> clipConvex(List<float[]> subject, List<float[]> clip) {
        List<float[]> output = subject;
        int m = clip.size();
        for (int i = 0; i < m && !output.isEmpty(); i++) {
            float[] a = clip.get(i);
            float[] b = clip.get((i + 1) % m);
            float ex = b[0] - a[0];
            float ey = b[1] - a[1];
            if (Math.abs(ex) < 1.0E-6f && Math.abs(ey) < 1.0E-6f) {
                continue;
            }
            List<float[]> input = output;
            output = new ArrayList<>();
            int k = input.size();
            for (int j = 0; j < k; j++) {
                float[] p = input.get(j);
                float[] q = input.get((j + 1) % k);
                float sideP = ex * (p[1] - a[1]) - ey * (p[0] - a[0]);
                float sideQ = ex * (q[1] - a[1]) - ey * (q[0] - a[0]);
                if (sideP >= 0) {
                    output.add(p);
                }
                if ((sideP >= 0) != (sideQ >= 0)) {
                    float t = sideP / (sideP - sideQ);
                    output.add(new float[]{p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t});
                }
            }
        }
        return output;
    }

    public static void strokeCircle(GuiGraphics graphics, float centerX, float centerY, float radius,
                                    float thickness, int color) {
        int c = applyGlobalAlpha(color);
        if (radius <= 0 || thickness <= 0 || ColorUtil.alpha(c) == 0) {
            return;
        }
        List<float[]> pts = arcPoints(centerX, centerY, radius, 0, 360);
        strokePerimeter(graphics, pts, thickness, c, true);
    }

    /**
     * Draws a partial ring. Angles are in degrees, {@code 0} pointing up, increasing clockwise.
     * {@code radius} is the outer radius; the stroke grows inward by {@code thickness}.
     * Useful for loading spinners and radial progress indicators.
     */
    public static void drawArc(GuiGraphics graphics, float centerX, float centerY, float radius,
                               float thickness, float startDeg, float endDeg, int color) {
        int c = applyGlobalAlpha(color);
        if (radius <= 0 || thickness <= 0 || ColorUtil.alpha(c) == 0 || endDeg <= startDeg) {
            return;
        }
        List<float[]> pts = arcPoints(centerX, centerY, radius, startDeg, endDeg);
        strokePerimeter(graphics, pts, thickness, c, false);
    }

    // ------------------------------------------------------------------
    // Lines and triangles
    // ------------------------------------------------------------------

    /** Anti-aliased line with round caps on both ends. */
    public static void line(GuiGraphics graphics, float x1, float y1, float x2, float y2, float width, int color) {
        strokePath(graphics, new float[]{x1, y1, x2, y2}, width, color, false, true, true);
    }

    /**
     * Anti-aliased line with per-end cap control. Butt ends (cap {@code false}) stop exactly
     * at the endpoint, so a line can join another shape flush without double-blending.
     */
    public static void line(GuiGraphics graphics, float x1, float y1, float x2, float y2, float width, int color,
                            boolean capStart, boolean capEnd) {
        strokePath(graphics, new float[]{x1, y1, x2, y2}, width, color, false, capStart, capEnd);
    }

    /**
     * Anti-aliased open polyline through {@code points} ({@code [x0, y0, x1, y1, ...]}) with
     * mitered joints. Unlike chaining {@link #line} calls, joints are part of one continuous
     * mesh, so translucent strokes don't double-blend where segments meet.
     */
    public static void polyline(GuiGraphics graphics, float[] points, float width, int color, boolean roundCaps) {
        strokePath(graphics, points, width, color, false, roundCaps, roundCaps);
    }

    /** Anti-aliased closed outline loop through {@code points}, with mitered joints. */
    public static void polylineClosed(GuiGraphics graphics, float[] points, float width, int color) {
        strokePath(graphics, points, width, color, true, false, false);
    }

    private static void strokePath(GuiGraphics graphics, float[] pts, float width, int color,
                                   boolean closed, boolean capStart, boolean capEnd) {
        int n = pts.length / 2;
        int c = applyGlobalAlpha(color);
        if (n < 2 || width <= 0 || ColorUtil.alpha(c) == 0) {
            return;
        }
        float half = width / 2f;
        float f = feather();
        int c0 = c & 0x00FFFFFF;

        // Per-vertex miter directions (unit miter scaled by 1/cos(halfAngle), clamped)
        float[] offX = new float[n];
        float[] offY = new float[n];
        for (int i = 0; i < n; i++) {
            float n1x = 0, n1y = 0, n2x = 0, n2y = 0;
            boolean hasPrev = closed || i > 0;
            boolean hasNext = closed || i < n - 1;
            if (hasPrev) {
                int p = (i - 1 + n) % n;
                float dx = pts[i * 2] - pts[p * 2];
                float dy = pts[i * 2 + 1] - pts[p * 2 + 1];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 1.0E-5f) {
                    n1x = -dy / len;
                    n1y = dx / len;
                }
            }
            if (hasNext) {
                int q = (i + 1) % n;
                float dx = pts[q * 2] - pts[i * 2];
                float dy = pts[q * 2 + 1] - pts[i * 2 + 1];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 1.0E-5f) {
                    n2x = -dy / len;
                    n2y = dx / len;
                }
            }
            if (n1x == 0 && n1y == 0) {
                n1x = n2x;
                n1y = n2y;
            }
            if (n2x == 0 && n2y == 0) {
                n2x = n1x;
                n2y = n1y;
            }
            float mx = n1x + n2x;
            float my = n1y + n2y;
            float mlen = (float) Math.sqrt(mx * mx + my * my);
            if (mlen < 1.0E-4f) {
                // 180° turn; fall back to the segment normal
                mx = n1x;
                my = n1y;
            } else {
                mx /= mlen;
                my /= mlen;
            }
            float cos = mx * n1x + my * n1y;
            float scale = cos > 0.25f ? 1f / cos : 4f;
            offX[i] = mx * scale;
            offY[i] = my * scale;
        }

        Matrix4f mat = graphics.pose().last().pose();
        BufferBuilder buffer = beginColor();
        int segments = closed ? n : n - 1;
        for (int i = 0; i < segments; i++) {
            int j = (i + 1) % n;
            float ax = pts[i * 2], ay = pts[i * 2 + 1];
            float bx = pts[j * 2], by = pts[j * 2 + 1];
            float aLx = ax + offX[i] * half, aLy = ay + offY[i] * half;
            float aRx = ax - offX[i] * half, aRy = ay - offY[i] * half;
            float bLx = bx + offX[j] * half, bLy = by + offY[j] * half;
            float bRx = bx - offX[j] * half, bRy = by - offY[j] * half;
            // Core band
            triangle(buffer, mat, aLx, aLy, c, bLx, bLy, c, bRx, bRy, c);
            triangle(buffer, mat, aLx, aLy, c, bRx, bRy, c, aRx, aRy, c);
            // Feather on both sides
            float aLfx = ax + offX[i] * (half + f), aLfy = ay + offY[i] * (half + f);
            float bLfx = bx + offX[j] * (half + f), bLfy = by + offY[j] * (half + f);
            float aRfx = ax - offX[i] * (half + f), aRfy = ay - offY[i] * (half + f);
            float bRfx = bx - offX[j] * (half + f), bRfy = by - offY[j] * (half + f);
            triangle(buffer, mat, aLx, aLy, c, aLfx, aLfy, c0, bLfx, bLfy, c0);
            triangle(buffer, mat, aLx, aLy, c, bLfx, bLfy, c0, bLx, bLy, c);
            triangle(buffer, mat, aRx, aRy, c, bRfx, bRfy, c0, aRfx, aRfy, c0);
            triangle(buffer, mat, aRx, aRy, c, bRx, bRy, c, bRfx, bRfy, c0);
        }

        if (!closed) {
            if (capStart) {
                addRoundCap(buffer, mat, pts[0], pts[1], pts[0] - pts[2], pts[1] - pts[3],
                        offX[0], offY[0], half, c, c0);
            }
            if (capEnd) {
                int e = n - 1;
                addRoundCap(buffer, mat, pts[e * 2], pts[e * 2 + 1],
                        pts[e * 2] - pts[(e - 1) * 2], pts[e * 2 + 1] - pts[(e - 1) * 2 + 1],
                        offX[e], offY[e], half, c, c0);
            }
        }
        end(buffer);
    }

    /** Semicircular end cap fan, flush against the stroke's end edge (no overlap). */
    private static void addRoundCap(BufferBuilder buffer, Matrix4f mat, float cx, float cy,
                                    float dirX, float dirY, float nx, float ny,
                                    float half, int color, int colorTransparent) {
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (len < 1.0E-5f) {
            return;
        }
        dirX /= len;
        dirY /= len;
        int segs = Math.max(4, cornerSegments(half));
        float f = feather();
        float px = cx + nx * half, py = cy + ny * half;
        float pfx = cx + nx * (half + f), pfy = cy + ny * (half + f);
        for (int k = 1; k <= segs; k++) {
            double a = Math.PI * k / segs;
            float vx = (float) (nx * Math.cos(a) + dirX * Math.sin(a));
            float vy = (float) (ny * Math.cos(a) + dirY * Math.sin(a));
            float qx = cx + vx * half, qy = cy + vy * half;
            float qfx = cx + vx * (half + f), qfy = cy + vy * (half + f);
            triangle(buffer, mat, cx, cy, color, px, py, color, qx, qy, color);
            triangle(buffer, mat, px, py, color, pfx, pfy, colorTransparent, qfx, qfy, colorTransparent);
            triangle(buffer, mat, px, py, color, qfx, qfy, colorTransparent, qx, qy, color);
            px = qx;
            py = qy;
            pfx = qfx;
            pfy = qfy;
        }
    }

    public static void fillTriangle(GuiGraphics graphics, float x1, float y1, float x2, float y2,
                                    float x3, float y3, int color) {
        int c = applyGlobalAlpha(color);
        if (ColorUtil.alpha(c) == 0) {
            return;
        }
        Matrix4f mat = graphics.pose().last().pose();
        BufferBuilder buffer = beginColor();
        triangle(buffer, mat, x1, y1, c, x2, y2, c, x3, y3, c);
        end(buffer);
    }

    /**
     * Fills a polygon given as {@code [x0, y0, x1, y1, ...]} in clockwise order, with
     * anti-aliased edges. The polygon must be star-shaped with respect to its centroid
     * (every vertex visible from it) — covers convex shapes, crosses, arrows, stars.
     */
    public static void fillPolygon(GuiGraphics graphics, float[] points, int color) {
        int n = points.length / 2;
        int c = applyGlobalAlpha(color);
        if (n < 3 || ColorUtil.alpha(c) == 0) {
            return;
        }
        float cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            cx += points[i * 2];
            cy += points[i * 2 + 1];
        }
        cx /= n;
        cy /= n;
        float f = feather();
        int c0 = c & 0x00FFFFFF;
        Matrix4f mat = graphics.pose().last().pose();
        BufferBuilder buffer = beginColor();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float ax = points[i * 2], ay = points[i * 2 + 1];
            float bx = points[j * 2], by = points[j * 2 + 1];
            triangle(buffer, mat, cx, cy, c, ax, ay, c, bx, by, c);
            // Per-edge feather along the outward edge normal
            float dx = bx - ax, dy = by - ay;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1.0E-5f) {
                continue;
            }
            float nx = dy / len * f, ny = -dx / len * f;
            triangle(buffer, mat, ax, ay, c, ax + nx, ay + ny, c0, bx + nx, by + ny, c0);
            triangle(buffer, mat, ax, ay, c, bx + nx, by + ny, c0, bx, by, c);
        }
        end(buffer);
    }

    // ------------------------------------------------------------------
    // Shadows
    // ------------------------------------------------------------------

    /**
     * Soft drop shadow behind a rounded rectangle. {@code size} is how far the shadow
     * extends past the rectangle bounds, in GUI pixels. The shadow includes the area
     * under the rectangle itself, so draw it before the rectangle.
     */
    public static void dropShadow(GuiGraphics graphics, float x, float y, float width, float height,
                                  float radius, float size, int color) {
        int baseAlpha = ColorUtil.alpha(color);
        if (width <= 0 || height <= 0 || size <= 0 || baseAlpha == 0 || globalAlpha <= 0f) {
            return;
        }
        fillRoundedRect(graphics, x, y, width, height, radius, color);

        int layers = Mth.clamp((int) (size * guiScale() * 0.75f), 4, 20);
        float prev = 0f;
        for (int i = 1; i <= layers; i++) {
            float expand = size * i / layers;
            float t = (i - 0.5f) / layers;
            float falloff = (1f - t) * (1f - t);
            int layerColor = applyGlobalAlpha(ColorUtil.withAlpha(color, (int) (baseAlpha * falloff)));
            if (ColorUtil.alpha(layerColor) != 0) {
                float thickness = expand - prev;
                List<float[]> pts = roundedPerimeter(x - expand, y - expand,
                        width + expand * 2, height + expand * 2,
                        radius + expand, radius + expand, radius + expand, radius + expand);
                strokePerimeterRaw(graphics, pts, thickness, layerColor, true, false);
            }
            prev = expand;
        }
    }

    // ------------------------------------------------------------------
    // Textures
    // ------------------------------------------------------------------

    /** Textured quad with a tint color (use {@code 0xFFFFFFFF} for no tint). */
    public static void texturedRect(GuiGraphics graphics, ResourceLocation texture, float x, float y,
                                    float width, float height, int tint) {
        texturedRect(graphics, texture, x, y, width, height, 0f, 0f, 1f, 1f, tint);
    }

    /** Textured quad with explicit UVs (0..1) and a tint color. */
    public static void texturedRect(GuiGraphics graphics, ResourceLocation texture, float x, float y,
                                    float width, float height, float u0, float v0, float u1, float v1, int tint) {
        int c = applyGlobalAlpha(tint);
        if (width <= 0 || height <= 0 || ColorUtil.alpha(c) == 0) {
            return;
        }
        Matrix4f mat = graphics.pose().last().pose();
        BufferBuilder buffer = beginTextured(texture);
        buffer.addVertex(mat, x, y, 0).setUv(u0, v0).setColor(c);
        buffer.addVertex(mat, x, y + height, 0).setUv(u0, v1).setColor(c);
        buffer.addVertex(mat, x + width, y + height, 0).setUv(u1, v1).setColor(c);
        buffer.addVertex(mat, x, y, 0).setUv(u0, v0).setColor(c);
        buffer.addVertex(mat, x + width, y + height, 0).setUv(u1, v1).setColor(c);
        buffer.addVertex(mat, x + width, y, 0).setUv(u1, v0).setColor(c);
        end(buffer);
    }

    /**
     * Texture clipped to a rounded rectangle with anti-aliased corners
     * (e.g. for avatars, thumbnails, server icons).
     */
    public static void texturedRoundedRect(GuiGraphics graphics, ResourceLocation texture, float x, float y,
                                           float width, float height, float radius, int tint) {
        int c = applyGlobalAlpha(tint);
        if (width <= 0 || height <= 0 || ColorUtil.alpha(c) == 0) {
            return;
        }
        List<float[]> pts = roundedPerimeter(x, y, width, height, radius, radius, radius, radius);
        float cx = x + width / 2f;
        float cy = y + height / 2f;
        float f = feather();
        int c0 = c & 0x00FFFFFF;

        Matrix4f mat = graphics.pose().last().pose();
        BufferBuilder buffer = beginTextured(texture);
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            float[] a = pts.get(i);
            float[] b = pts.get((i + 1) % n);
            texVertex(buffer, mat, cx, cy, x, y, width, height, c);
            texVertex(buffer, mat, a[0], a[1], x, y, width, height, c);
            texVertex(buffer, mat, b[0], b[1], x, y, width, height, c);
        }
        // Feather ring (UVs clamped at the silhouette)
        for (int i = 0; i < n; i++) {
            float[] a = pts.get(i);
            float[] b = pts.get((i + 1) % n);
            float ax = a[0] + a[2] * f;
            float ay = a[1] + a[3] * f;
            float bx = b[0] + b[2] * f;
            float by = b[1] + b[3] * f;
            texVertexColored(buffer, mat, a[0], a[1], x, y, width, height, c);
            texVertexAt(buffer, mat, ax, ay, a[0], a[1], x, y, width, height, c0);
            texVertexAt(buffer, mat, bx, by, b[0], b[1], x, y, width, height, c0);
            texVertexColored(buffer, mat, a[0], a[1], x, y, width, height, c);
            texVertexAt(buffer, mat, bx, by, b[0], b[1], x, y, width, height, c0);
            texVertexColored(buffer, mat, b[0], b[1], x, y, width, height, c);
        }
        end(buffer);
    }

    private static void texVertex(BufferBuilder buffer, Matrix4f mat, float px, float py,
                                  float x, float y, float w, float h, int color) {
        buffer.addVertex(mat, px, py, 0).setUv((px - x) / w, (py - y) / h).setColor(color);
    }

    private static void texVertexColored(BufferBuilder buffer, Matrix4f mat, float px, float py,
                                         float x, float y, float w, float h, int color) {
        texVertex(buffer, mat, px, py, x, y, w, h, color);
    }

    private static void texVertexAt(BufferBuilder buffer, Matrix4f mat, float px, float py,
                                    float uvx, float uvy, float x, float y, float w, float h, int color) {
        buffer.addVertex(mat, px, py, 0).setUv((uvx - x) / w, (uvy - y) / h).setColor(color);
    }

    // ------------------------------------------------------------------
    // Scissor
    // ------------------------------------------------------------------

    /**
     * Pushes a scissor region in absolute GUI coordinates. Regions nest (they intersect with
     * any active region). Note that vanilla scissoring ignores pose transformations.
     */
    public static void pushScissor(GuiGraphics graphics, float x, float y, float width, float height) {
        graphics.enableScissor((int) Math.floor(x), (int) Math.floor(y),
                (int) Math.ceil(x + width), (int) Math.ceil(y + height));
    }

    public static void popScissor(GuiGraphics graphics) {
        graphics.disableScissor();
    }

    // ------------------------------------------------------------------
    // Pixel helpers
    // ------------------------------------------------------------------

    public static float guiScale() {
        return (float) Minecraft.getInstance().getWindow().getGuiScale();
    }

    /** Rounds a GUI-space coordinate to the nearest physical screen pixel. */
    public static float roundToPixel(float value) {
        float scale = Math.max(1f, guiScale());
        return Math.round(value * scale) / scale;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface ColorAt {
        int get(float x, float y);
    }

    private static float feather() {
        return 1f / Math.max(1f, guiScale());
    }

    private static BufferBuilder beginColor() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        return Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
    }

    private static BufferBuilder beginTextured(ResourceLocation texture) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        return Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    private static void end(BufferBuilder buffer) {
        MeshData mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void quad(BufferBuilder buffer, Matrix4f mat, float x1, float y1, float x2, float y2,
                             int cTL, int cTR, int cBR, int cBL) {
        buffer.addVertex(mat, x1, y1, 0).setColor(cTL);
        buffer.addVertex(mat, x1, y2, 0).setColor(cBL);
        buffer.addVertex(mat, x2, y2, 0).setColor(cBR);
        buffer.addVertex(mat, x1, y1, 0).setColor(cTL);
        buffer.addVertex(mat, x2, y2, 0).setColor(cBR);
        buffer.addVertex(mat, x2, y1, 0).setColor(cTR);
    }

    private static void triangle(BufferBuilder buffer, Matrix4f mat,
                                 float x1, float y1, int c1,
                                 float x2, float y2, int c2,
                                 float x3, float y3, int c3) {
        buffer.addVertex(mat, x1, y1, 0).setColor(c1);
        buffer.addVertex(mat, x2, y2, 0).setColor(c2);
        buffer.addVertex(mat, x3, y3, 0).setColor(c3);
    }

    /**
     * Builds the clockwise perimeter of a rounded rectangle.
     * Each entry is {@code {x, y, normalX, normalY}} with the normal pointing outward.
     */
    private static List<float[]> roundedPerimeter(float x, float y, float w, float h,
                                                  float rtl, float rtr, float rbr, float rbl) {
        float maxR = Math.min(w, h) / 2f;
        rtl = Mth.clamp(rtl, 0f, maxR);
        rtr = Mth.clamp(rtr, 0f, maxR);
        rbr = Mth.clamp(rbr, 0f, maxR);
        rbl = Mth.clamp(rbl, 0f, maxR);
        List<float[]> pts = new ArrayList<>();
        addCornerArc(pts, x + rtl, y + rtl, rtl, 180f, 270f);
        addCornerArc(pts, x + w - rtr, y + rtr, rtr, 270f, 360f);
        addCornerArc(pts, x + w - rbr, y + h - rbr, rbr, 0f, 90f);
        addCornerArc(pts, x + rbl, y + h - rbl, rbl, 90f, 180f);
        return pts;
    }

    private static void addCornerArc(List<float[]> pts, float cx, float cy, float r, float fromDeg, float toDeg) {
        if (r <= 0f) {
            float mid = (float) Math.toRadians((fromDeg + toDeg) / 2f);
            float nx = (float) Math.cos(mid);
            float ny = (float) Math.sin(mid);
            pts.add(new float[]{cx, cy, nx, ny});
            return;
        }
        int segments = cornerSegments(r);
        for (int i = 0; i <= segments; i++) {
            float a = (float) Math.toRadians(fromDeg + (toDeg - fromDeg) * i / segments);
            float nx = (float) Math.cos(a);
            float ny = (float) Math.sin(a);
            pts.add(new float[]{cx + nx * r, cy + ny * r, nx, ny});
        }
    }

    /**
     * Points along a circular arc. Angles in degrees, 0 = up, clockwise.
     * Normals point outward (radially).
     */
    private static List<float[]> arcPoints(float cx, float cy, float r, float startDeg, float endDeg) {
        float sweep = endDeg - startDeg;
        int segments = Math.max(4, (int) Math.ceil(cornerSegments(r) * sweep / 90f));
        List<float[]> pts = new ArrayList<>(segments + 1);
        for (int i = 0; i <= segments; i++) {
            float a = (float) Math.toRadians(startDeg + sweep * i / segments - 90f);
            float nx = (float) Math.cos(a);
            float ny = (float) Math.sin(a);
            pts.add(new float[]{cx + nx * r, cy + ny * r, nx, ny});
        }
        return pts;
    }

    private static int cornerSegments(float r) {
        return Mth.clamp((int) Math.ceil(r * guiScale() * 0.6f), 3, 32);
    }

    /** Fills a convex perimeter by fanning from a center point, then feathers the silhouette. */
    private static void fillPerimeter(GuiGraphics graphics, List<float[]> pts, float cx, float cy, ColorAt color) {
        Matrix4f mat = graphics.pose().last().pose();
        BufferBuilder buffer = beginColor();
        int n = pts.size();
        int centerColor = color.get(cx, cy);
        for (int i = 0; i < n; i++) {
            float[] a = pts.get(i);
            float[] b = pts.get((i + 1) % n);
            triangle(buffer, mat,
                    cx, cy, centerColor,
                    a[0], a[1], color.get(a[0], a[1]),
                    b[0], b[1], color.get(b[0], b[1]));
        }
        float f = feather();
        for (int i = 0; i < n; i++) {
            float[] a = pts.get(i);
            float[] b = pts.get((i + 1) % n);
            int ca = color.get(a[0], a[1]);
            int cb = color.get(b[0], b[1]);
            triangle(buffer, mat,
                    a[0], a[1], ca,
                    a[0] + a[2] * f, a[1] + a[3] * f, ca & 0x00FFFFFF,
                    b[0] + b[2] * f, b[1] + b[3] * f, cb & 0x00FFFFFF);
            triangle(buffer, mat,
                    a[0], a[1], ca,
                    b[0] + b[2] * f, b[1] + b[3] * f, cb & 0x00FFFFFF,
                    b[0], b[1], cb);
        }
        end(buffer);
    }

    private static void strokePerimeter(GuiGraphics graphics, List<float[]> pts, float thickness,
                                        int color, boolean closed) {
        strokePerimeterRaw(graphics, pts, thickness, color, closed, true);
    }

    /**
     * Strokes along a perimeter. The band spans from each point (outer edge) inward by
     * {@code thickness} along the inverted normal.
     */
    private static void strokePerimeterRaw(GuiGraphics graphics, List<float[]> pts, float thickness,
                                           int color, boolean closed, boolean feathered) {
        Matrix4f mat = graphics.pose().last().pose();
        BufferBuilder buffer = beginColor();
        float f = feather();
        int c0 = color & 0x00FFFFFF;
        int n = pts.size();
        int limit = closed ? n : n - 1;
        for (int i = 0; i < limit; i++) {
            float[] a = pts.get(i);
            float[] b = pts.get((i + 1) % n);
            float aix = a[0] - a[2] * thickness;
            float aiy = a[1] - a[3] * thickness;
            float bix = b[0] - b[2] * thickness;
            float biy = b[1] - b[3] * thickness;
            // Band
            triangle(buffer, mat, a[0], a[1], color, b[0], b[1], color, bix, biy, color);
            triangle(buffer, mat, a[0], a[1], color, bix, biy, color, aix, aiy, color);
            if (feathered) {
                // Outer feather
                triangle(buffer, mat,
                        a[0], a[1], color,
                        a[0] + a[2] * f, a[1] + a[3] * f, c0,
                        b[0] + b[2] * f, b[1] + b[3] * f, c0);
                triangle(buffer, mat,
                        a[0], a[1], color,
                        b[0] + b[2] * f, b[1] + b[3] * f, c0,
                        b[0], b[1], color);
                // Inner feather (fades further inward, past the inner edge of the band)
                triangle(buffer, mat,
                        aix, aiy, color,
                        bix, biy, color,
                        bix - b[2] * f, biy - b[3] * f, c0);
                triangle(buffer, mat,
                        aix, aiy, color,
                        bix - b[2] * f, biy - b[3] * f, c0,
                        aix - a[2] * f, aiy - a[3] * f, c0);
            }
        }
        end(buffer);
    }
}
