package com.stormcph.easygui.client.font;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.stb.STBTruetype.stbtt_GetCodepointBitmapBox;
import static org.lwjgl.stb.STBTruetype.stbtt_InitFont;
import static org.lwjgl.stb.STBTruetype.stbtt_ScaleForPixelHeight;

/**
 * Headless checks (no GL context — STB TrueType is pure CPU) that
 * {@link TrueTypeFont#flattenGlyph} turns real glyph outlines into sane stroke-ready
 * contours: ring counts, bounding boxes matching STB's own bitmap box, the counter of
 * {@code 'o'} strictly inside its outer ring, and sane point spacing. Sizes mirror the
 * demo's hollow-text showcase: UI size 9 × label scale 2 × GUI scale 2 and 3.
 */
class GlyphContourFlatteningTest {
    private static final int[] EFFECTIVE_PX = {36, 54};

    private static byte[] fontBytes;
    private static TrueTypeFont font;
    private static ByteBuffer refData;
    private static STBTTFontinfo refInfo;

    @BeforeAll
    static void load() throws IOException {
        Path path = Path.of("src", "main", "resources", "assets", "easygui", "fonts", "inter.ttf");
        if (!Files.exists(path)) {
            path = Path.of("common").resolve(path); // CWD is the repo root, not :common
        }
        fontBytes = Files.readAllBytes(path);
        font = new TrueTypeFont(fontBytes, "inter-test");
        refData = MemoryUtil.memAlloc(fontBytes.length);
        refData.put(fontBytes).flip();
        refInfo = STBTTFontinfo.malloc();
        assertTrue(stbtt_InitFont(refInfo, refData), "reference stbtt_InitFont");
    }

    @AfterAll
    static void unload() {
        if (font != null) {
            font.close();
        }
        if (refInfo != null) {
            refInfo.free();
        }
        if (refData != null) {
            MemoryUtil.memFree(refData);
        }
    }

    /** Same font-units→physical-px factor as {@code Baked.kernScale} for this pixel size. */
    private static float scaleFor(int effectivePx) {
        return stbtt_ScaleForPixelHeight(refInfo, effectivePx);
    }

    @Test
    void contourCounts() {
        for (int px : EFFECTIVE_PX) {
            float scale = scaleFor(px);
            assertEquals(2, font.flattenGlyph('o', scale).length,
                    "'o' must flatten to outer ring + counter at " + px + "px");
            assertEquals(1, font.flattenGlyph('H', scale).length,
                    "'H' must flatten to a single ring at " + px + "px");
        }
    }

    @Test
    void bboxMatchesStbBitmapBox() {
        for (int px : EFFECTIVE_PX) {
            float scale = scaleFor(px);
            for (int cp : new int[]{'o', 'H'}) {
                float[][] contours = font.flattenGlyph(cp, scale);
                float[] box = bbox(contours);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer x0 = stack.mallocInt(1);
                    IntBuffer y0 = stack.mallocInt(1);
                    IntBuffer x1 = stack.mallocInt(1);
                    IntBuffer y1 = stack.mallocInt(1);
                    // Bitmap box is y-down relative to the baseline — same space as
                    // the flattened contours.
                    stbtt_GetCodepointBitmapBox(refInfo, cp, scale, scale, x0, y0, x1, y1);
                    String at = (char) cp + " at " + px + "px";
                    assertEquals(x0.get(0), box[0], 1.5f, "minX of " + at);
                    assertEquals(y0.get(0), box[1], 1.5f, "minY of " + at);
                    assertEquals(x1.get(0), box[2], 1.5f, "maxX of " + at);
                    assertEquals(y1.get(0), box[3], 1.5f, "maxY of " + at);
                    System.out.printf("bbox %s: contours [%.2f %.2f %.2f %.2f] stb [%d %d %d %d]%n",
                            at, box[0], box[1], box[2], box[3],
                            x0.get(0), y0.get(0), x1.get(0), y1.get(0));
                }
            }
        }
    }

    @Test
    void counterOfOSitsInsideOuterRingWithGap() {
        for (int px : EFFECTIVE_PX) {
            float[][] contours = font.flattenGlyph('o', scaleFor(px));
            assertEquals(2, contours.length);
            float[] a = bbox(new float[][]{contours[0]});
            float[] b = bbox(new float[][]{contours[1]});
            float[] outer = area(a) >= area(b) ? a : b;
            float[] inner = outer == a ? b : a;
            String at = px + "px";
            // The counter must be strictly inside, with at least a stem's worth of gap —
            // that gap is exactly what a hollow stroke leaves visible.
            float minGap = px / 30f;
            assertTrue(inner[0] - outer[0] >= minGap, "left gap at " + at);
            assertTrue(inner[1] - outer[1] >= minGap, "top gap at " + at);
            assertTrue(outer[2] - inner[2] >= minGap, "right gap at " + at);
            assertTrue(outer[3] - inner[3] >= minGap, "bottom gap at " + at);
            // And the counter itself must be big enough to read as a hole on screen.
            assertTrue(inner[2] - inner[0] >= px / 12f, "counter width at " + at);
            assertTrue(inner[3] - inner[1] >= px / 12f, "counter height at " + at);
            System.out.printf("'o' at %s: outer [%.2f %.2f %.2f %.2f], counter [%.2f %.2f %.2f %.2f]%n",
                    at, outer[0], outer[1], outer[2], outer[3],
                    inner[0], inner[1], inner[2], inner[3]);
        }
    }

    @Test
    void pointSpacingIsSane() {
        for (int px : EFFECTIVE_PX) {
            float scale = scaleFor(px);
            for (int cp : new int[]{'o', 'H'}) {
                float[][] contours = font.flattenGlyph(cp, scale);
                float[] glyphBox = bbox(contours);
                float diagonal = (float) Math.hypot(glyphBox[2] - glyphBox[0], glyphBox[3] - glyphBox[1]);
                for (int ring = 0; ring < contours.length; ring++) {
                    float[] c = contours[ring];
                    int n = c.length / 2;
                    float min = Float.MAX_VALUE;
                    float max = 0f;
                    for (int i = 0; i < n; i++) {
                        int j = (i + 1) % n; // include the closing wrap segment
                        float dx = c[j * 2] - c[i * 2];
                        float dy = c[j * 2 + 1] - c[i * 2 + 1];
                        float d = (float) Math.hypot(dx, dy);
                        assertFalse(Float.isNaN(d), "NaN spacing");
                        min = Math.min(min, d);
                        max = Math.max(max, d);
                    }
                    String at = (char) cp + " ring " + ring + " at " + px + "px (" + n + " pts)";
                    System.out.printf("spacing %s: min %.5f max %.3f px%n", at, min, max);
                    assertTrue(min > 1.0E-4f, "zero-length segment in " + at + ": " + min);
                    // A straight glyph edge ('H' stem) is legitimately one long segment, but
                    // no segment may exceed the glyph's own diagonal — that would mean points
                    // teleporting between rings or out of the glyph box.
                    assertTrue(max <= diagonal * 1.01f, "point jump beyond the glyph in " + at + ": " + max);
                }
            }
        }
    }

    /** {@code {minX, minY, maxX, maxY}} over all points of all rings. */
    private static float[] bbox(float[][] contours) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] c : contours) {
            for (int i = 0; i < c.length; i += 2) {
                minX = Math.min(minX, c[i]);
                maxX = Math.max(maxX, c[i]);
                minY = Math.min(minY, c[i + 1]);
                maxY = Math.max(maxY, c[i + 1]);
            }
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    private static float area(float[] box) {
        return (box[2] - box[0]) * (box[3] - box[1]);
    }
}
