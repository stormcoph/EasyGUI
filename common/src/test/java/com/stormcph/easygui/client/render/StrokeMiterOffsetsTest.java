package com.stormcph.easygui.client.render;

import com.stormcph.easygui.client.font.TrueTypeFont;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.stb.STBTruetype.stbtt_InitFont;
import static org.lwjgl.stb.STBTruetype.stbtt_ScaleForPixelHeight;

/**
 * Headless checks of {@link Render2D#miterOffsets} — the per-vertex offset directions that
 * {@code strokePath} extrudes a stroke band along. Offsets must stay ≈ unit length on the
 * dense, unevenly spaced rings that flattened glyph contours produce; an offset explosion
 * here is what would fatten a hollow-text stroke into a solid blob. The last test simulates
 * the exact band + feather triangles {@code strokePath} emits for the two rings of a real
 * {@code 'o'} at the demo's hollow-text size and asserts the counter's center stays
 * unpainted (hollow) while the ring edge is painted.
 */
class StrokeMiterOffsetsTest {
    private static TrueTypeFont font;
    private static float scale36;

    @BeforeAll
    static void load() throws IOException {
        Path path = Path.of("src", "main", "resources", "assets", "easygui", "fonts", "inter.ttf");
        if (!Files.exists(path)) {
            path = Path.of("common").resolve(path);
        }
        byte[] bytes = Files.readAllBytes(path);
        font = new TrueTypeFont(bytes, "inter-test");
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes).flip();
        STBTTFontinfo info = STBTTFontinfo.malloc();
        try {
            assertTrue(stbtt_InitFont(info, data), "reference stbtt_InitFont");
            scale36 = stbtt_ScaleForPixelHeight(info, 36); // demo size at GUI scale 2
        } finally {
            info.free();
            MemoryUtil.memFree(data);
        }
    }

    @AfterAll
    static void unload() {
        if (font != null) {
            font.close();
        }
    }

    @Test
    void denseCircleOffsetsStayUnit() {
        int n = 256;
        float[] pts = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            pts[i * 2] = 10f * (float) Math.cos(a);
            pts[i * 2 + 1] = 10f * (float) Math.sin(a);
        }
        assertOffsetsBounded(pts, 1.05f, "dense circle");
    }

    @Test
    void unevenlySpacedRingOffsetsStayUnit() {
        // Alternate tiny (0.0005 rad ≈ 0.005 px) and normal steps around a circle — the
        // kind of wildly uneven spacing bézier flattening produces at curve joins.
        List<float[]> points = new ArrayList<>();
        double a = 0;
        while (a < 2 * Math.PI) {
            points.add(new float[]{10f * (float) Math.cos(a), 10f * (float) Math.sin(a)});
            a += points.size() % 2 == 0 ? 0.0005 : 0.1;
        }
        float[] pts = flatten(points);
        assertOffsetsBounded(pts, 1.2f, "unevenly spaced ring");
    }

    @Test
    void realGlyphContourOffsetsStayBounded() throws Exception {
        for (float[] contour : glyphContoursGui('o')) {
            assertOffsetsBounded(contour, 2.0f, "'o' contour (" + contour.length / 2 + " pts)");
        }
    }

    @Test
    void hollowStrokeLeavesCounterOfOUnpainted() throws Exception {
        float[][] rings = glyphContoursGui('o');
        assertEquals(2, rings.length, "'o' must have two rings");
        // Demo hollow style: 1.2 GUI px stroke; feather is 1 physical px = 0.5 GUI px.
        float half = 1.2f / 2f;
        float feather = 0.5f;
        List<float[]> triangles = new ArrayList<>();
        for (float[] ring : rings) {
            emitStrokeBand(ring, half, feather, triangles);
        }
        float[] inner = area(bbox(rings[0])) < area(bbox(rings[1])) ? rings[0] : rings[1];
        float[] innerBox = bbox(inner);
        float cx = (innerBox[0] + innerBox[2]) / 2f;
        float cy = (innerBox[1] + innerBox[3]) / 2f;
        assertFalse(covered(triangles, cx, cy),
                "counter center (" + cx + ", " + cy + ") must stay unpainted — hollow");
        assertTrue(covered(triangles, rings[0][0], rings[0][1]),
                "a point on the contour itself must be painted");
        System.out.printf("hollow check: %d triangles, counter center (%.2f, %.2f) open%n",
                triangles.size(), cx, cy);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Flattened contours of {@code cp} at the demo size, converted physical→GUI px (scale 2). */
    private static float[][] glyphContoursGui(int cp) throws Exception {
        Method flatten = TrueTypeFont.class.getDeclaredMethod("flattenGlyph", int.class, float.class);
        flatten.setAccessible(true);
        float[][] physical = (float[][]) flatten.invoke(font, cp, scale36);
        float inv = 0.5f; // 1 / guiScale
        float[][] gui = new float[physical.length][];
        for (int i = 0; i < physical.length; i++) {
            gui[i] = new float[physical[i].length];
            for (int p = 0; p < physical[i].length; p++) {
                gui[i][p] = physical[i][p] * inv;
            }
        }
        return gui;
    }

    private static void assertOffsetsBounded(float[] pts, float limit, String what) {
        float[][] miters = Render2D.miterOffsets(pts, true);
        float max = 0f;
        for (int i = 0; i < miters[0].length; i++) {
            float len = (float) Math.hypot(miters[0][i], miters[1][i]);
            max = Math.max(max, len);
            assertTrue(len <= limit, what + ": offset " + i + " is " + len + " (limit " + limit + ")");
            assertTrue(len >= 0.9f, what + ": offset " + i + " collapsed to " + len);
        }
        System.out.printf("offsets %s: max |off| %.3f%n", what, max);
    }

    /**
     * Emits the exact core-band and feather triangles {@code Render2D.strokePath} draws for
     * a closed path (kept in lockstep with that method) so coverage can be tested headless.
     */
    private static void emitStrokeBand(float[] pts, float half, float f, List<float[]> out) {
        int n = pts.length / 2;
        float[][] miters = Render2D.miterOffsets(pts, true);
        float[] offX = miters[0];
        float[] offY = miters[1];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float ax = pts[i * 2], ay = pts[i * 2 + 1];
            float bx = pts[j * 2], by = pts[j * 2 + 1];
            float aLx = ax + offX[i] * half, aLy = ay + offY[i] * half;
            float aRx = ax - offX[i] * half, aRy = ay - offY[i] * half;
            float bLx = bx + offX[j] * half, bLy = by + offY[j] * half;
            float bRx = bx - offX[j] * half, bRy = by - offY[j] * half;
            out.add(new float[]{aLx, aLy, bLx, bLy, bRx, bRy});
            out.add(new float[]{aLx, aLy, bRx, bRy, aRx, aRy});
            float aLfx = ax + offX[i] * (half + f), aLfy = ay + offY[i] * (half + f);
            float bLfx = bx + offX[j] * (half + f), bLfy = by + offY[j] * (half + f);
            float aRfx = ax - offX[i] * (half + f), aRfy = ay - offY[i] * (half + f);
            float bRfx = bx - offX[j] * (half + f), bRfy = by - offY[j] * (half + f);
            out.add(new float[]{aLx, aLy, aLfx, aLfy, bLfx, bLfy});
            out.add(new float[]{aLx, aLy, bLfx, bLfy, bLx, bLy});
            out.add(new float[]{aRx, aRy, bRfx, bRfy, aRfx, aRfy});
            out.add(new float[]{aRx, aRy, bRx, bRy, bRfx, bRfy});
        }
    }

    private static boolean covered(List<float[]> triangles, float x, float y) {
        for (float[] t : triangles) {
            if (inTriangle(x, y, t[0], t[1], t[2], t[3], t[4], t[5])) {
                return true;
            }
        }
        return false;
    }

    private static boolean inTriangle(float px, float py, float x1, float y1,
                                      float x2, float y2, float x3, float y3) {
        float d1 = sign(px, py, x1, y1, x2, y2);
        float d2 = sign(px, py, x2, y2, x3, y3);
        float d3 = sign(px, py, x3, y3, x1, y1);
        boolean hasNeg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean hasPos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(hasNeg && hasPos);
    }

    private static float sign(float px, float py, float ax, float ay, float bx, float by) {
        return (px - bx) * (ay - by) - (ax - bx) * (py - by);
    }

    private static float[] flatten(List<float[]> points) {
        float[] pts = new float[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            pts[i * 2] = points.get(i)[0];
            pts[i * 2 + 1] = points.get(i)[1];
        }
        return pts;
    }

    private static float[] bbox(float[] c) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < c.length; i += 2) {
            minX = Math.min(minX, c[i]);
            maxX = Math.max(maxX, c[i]);
            minY = Math.min(minY, c[i + 1]);
            maxY = Math.max(maxY, c[i + 1]);
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    private static float area(float[] box) {
        return (box[2] - box[0]) * (box[3] - box[1]);
    }
}
