package com.stormcph.easygui.client.font;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Render2D;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackRange;
import org.lwjgl.stb.STBTTPackedchar;
import org.lwjgl.stb.STBTTVertex;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.stb.STBTruetype.STBTT_vcubic;
import static org.lwjgl.stb.STBTruetype.STBTT_vcurve;
import static org.lwjgl.stb.STBTruetype.STBTT_vline;
import static org.lwjgl.stb.STBTruetype.STBTT_vmove;
import static org.lwjgl.stb.STBTruetype.stbtt_FindGlyphIndex;
import static org.lwjgl.stb.STBTruetype.stbtt_FreeShape;
import static org.lwjgl.stb.STBTruetype.stbtt_GetCodepointKernAdvance;
import static org.lwjgl.stb.STBTruetype.stbtt_GetCodepointShape;
import static org.lwjgl.stb.STBTruetype.stbtt_GetFontVMetrics;
import static org.lwjgl.stb.STBTruetype.stbtt_GetPackedQuad;
import static org.lwjgl.stb.STBTruetype.stbtt_InitFont;
import static org.lwjgl.stb.STBTruetype.stbtt_PackBegin;
import static org.lwjgl.stb.STBTruetype.stbtt_PackEnd;
import static org.lwjgl.stb.STBTruetype.stbtt_PackFontRanges;
import static org.lwjgl.stb.STBTruetype.stbtt_PackSetOversampling;
import static org.lwjgl.stb.STBTruetype.stbtt_ScaleForPixelHeight;

/**
 * A TrueType font rendered through EasyGUI's own glyph pipeline (STB TrueType, which ships
 * with Minecraft). Unlike vanilla resource-pack fonts, sizes are free-form and chosen at
 * draw time, glyphs are baked at the <em>physical</em> pixel size for the current GUI scale
 * (with oversampling for small sizes), and positioning is sub-pixel — the "premium client"
 * crisp text look.
 *
 * <p>Get instances from {@link Fonts} (mod resources, files on disk, or raw bytes). Draw
 * directly with {@link #draw}, or route the entire widget toolkit through a custom font
 * with {@code Text2D.setUiFont(font, size)}.</p>
 *
 * <p>Glyph atlases are baked lazily per effective pixel size and cached. The default baked
 * range covers Latin, Latin-1, Latin Extended-A and common punctuation; pass custom ranges
 * for more. Missing glyphs render as {@code ?}. All methods must be called from the render
 * thread.</p>
 */
@Environment(EnvType.CLIENT)
public final class TrueTypeFont implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    /** Inclusive codepoint range pairs: Latin, Latin-1 + Extended-A, punctuation, €, ™. */
    public static final int[] DEFAULT_RANGES = {
            0x20, 0x7E,
            0xA0, 0x17F,
            0x2010, 0x2027,
            0x20AC, 0x20AC,
            0x2122, 0x2122,
    };

    private static final int MIN_PIXEL_SIZE = 6;
    private static final int MAX_PIXEL_SIZE = 192;
    private static final int MAX_ATLAS_SIZE = 4096;
    private static final int MAX_CACHED_SIZES = 6;
    private static final int FLOATS_PER_GLYPH = 9; // x0 y0 x1 y1 u0 v0 u1 v1 advance

    /** Soft-shadow sample offsets {@code {dx, dy, weight}} — centre plus two rings. */
    private static final float[][] SOFT_SHADOW_SAMPLES = {
            {0f, 0f, 0.50f},
            {0.5f, 0f, 0.22f}, {-0.5f, 0f, 0.22f}, {0f, 0.5f, 0.22f}, {0f, -0.5f, 0.22f},
            {0.35f, 0.35f, 0.18f}, {-0.35f, 0.35f, 0.18f}, {0.35f, -0.35f, 0.18f}, {-0.35f, -0.35f, 0.18f},
            {1f, 0f, 0.12f}, {-1f, 0f, 0.12f}, {0f, 1f, 0.12f}, {0f, -1f, 0.12f},
            {0.7f, 0.7f, 0.10f}, {-0.7f, 0.7f, 0.10f}, {0.7f, -0.7f, 0.10f}, {-0.7f, -0.7f, 0.10f},
    };

    /** Returns the packed ARGB color a glyph vertex at {@code (x, y)} in GUI space should get. */
    @FunctionalInterface
    private interface VertexColor {
        int at(float x, float y);
    }

    private final int id = NEXT_ID.getAndIncrement();
    private final String name;
    private final ByteBuffer data; // referenced by STB for the font's whole lifetime
    private final STBTTFontinfo info;
    private final int[] codepoints;
    private final Int2IntMap glyphIndex = new Int2IntOpenHashMap();
    private final Int2ObjectMap<Baked> bakedSizes = new Int2ObjectOpenHashMap<>();
    private final int unitsAscent;
    private final int unitsDescent;
    private final int unitsLineGap;
    private final int fallbackIndex;
    private boolean kerning = true;
    private boolean closed;

    public TrueTypeFont(byte[] ttf, String name) {
        this(ttf, name, DEFAULT_RANGES);
    }

    /**
     * @param codepointRanges inclusive {@code [from, to]} pairs of codepoints to bake
     */
    public TrueTypeFont(byte[] ttf, String name, int[] codepointRanges) {
        this.name = name;
        this.data = MemoryUtil.memAlloc(ttf.length);
        this.data.put(ttf).flip();
        this.info = STBTTFontinfo.malloc();
        if (!stbtt_InitFont(info, data)) {
            info.free();
            MemoryUtil.memFree(data);
            throw new IllegalArgumentException("Not a usable TrueType font: " + name);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer ascent = stack.mallocInt(1);
            IntBuffer descent = stack.mallocInt(1);
            IntBuffer lineGap = stack.mallocInt(1);
            stbtt_GetFontVMetrics(info, ascent, descent, lineGap);
            this.unitsAscent = ascent.get(0);
            this.unitsDescent = descent.get(0);
            this.unitsLineGap = lineGap.get(0);
        }

        glyphIndex.defaultReturnValue(-1);
        IntList list = new IntArrayList();
        for (int i = 0; i + 1 < codepointRanges.length; i += 2) {
            for (int cp = codepointRanges[i]; cp <= codepointRanges[i + 1]; cp++) {
                if (glyphIndex.containsKey(cp)) {
                    continue;
                }
                if (cp == ' ' || stbtt_FindGlyphIndex(info, cp) != 0) {
                    glyphIndex.put(cp, list.size());
                    list.add(cp);
                }
            }
        }
        this.codepoints = list.toIntArray();
        if (codepoints.length == 0) {
            info.free();
            MemoryUtil.memFree(data);
            throw new IllegalArgumentException("Font has no glyphs in the requested ranges: " + name);
        }
        int fallback = glyphIndex.get('?');
        this.fallbackIndex = fallback >= 0 ? fallback : glyphIndex.get(' ');
    }

    public String getName() {
        return name;
    }

    /** Kerning from the font's {@code kern} table (on by default; not all fonts carry one). */
    public TrueTypeFont setKerning(boolean kerning) {
        this.kerning = kerning;
        return this;
    }

    // ------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------

    /** Width of {@code text} in GUI units at the given size. */
    public float width(String text, float size) {
        return width(text, size, 0f);
    }

    /** Width of {@code text} in GUI units, accounting for the {@link TextStyle}'s letter-spacing. */
    public float width(String text, float size, TextStyle style) {
        return width(text, size, style == null ? 0f : style.resolveTracking(size));
    }

    /** Width of {@code text} in GUI units with {@code tracking} extra GUI units after every glyph. */
    public float width(String text, float size, float tracking) {
        Baked baked = bakedFor(size);
        if (baked == null || text == null || text.isEmpty()) {
            return 0f;
        }
        float scale = Math.max(1f, Render2D.guiScale());
        float width = 0f;
        int count = 0;
        int previous = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int glyph = indexOf(cp);
            if (glyph < 0) {
                continue;
            }
            if (kerning && previous >= 0) {
                width += stbtt_GetCodepointKernAdvance(info, previous, cp) * baked.kernScale;
            }
            width += baked.glyphs[glyph * FLOATS_PER_GLYPH + 8];
            count++;
            previous = cp;
        }
        return width / scale + tracking * count;
    }

    /** Distance from the top of a line to the baseline, in GUI units. */
    public float ascent(float size) {
        return size * unitsAscent / (unitsAscent - unitsDescent);
    }

    /** Recommended baseline-to-baseline distance, in GUI units (≥ {@code size}). */
    public float lineHeight(float size) {
        return size * (unitsAscent - unitsDescent + unitsLineGap) / (float) (unitsAscent - unitsDescent);
    }

    /** Longest prefix of {@code text} that fits in {@code maxWidth} GUI units. */
    public String trimToWidth(String text, float maxWidth, float size) {
        Baked baked = bakedFor(size);
        if (baked == null || text == null || text.isEmpty()) {
            return "";
        }
        float scale = Math.max(1f, Render2D.guiScale());
        float budget = maxWidth * scale;
        float width = 0f;
        int previous = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            int glyph = indexOf(cp);
            if (glyph >= 0) {
                if (kerning && previous >= 0) {
                    width += stbtt_GetCodepointKernAdvance(info, previous, cp) * baked.kernScale;
                }
                width += baked.glyphs[glyph * FLOATS_PER_GLYPH + 8];
                if (width > budget) {
                    return text.substring(0, i);
                }
                previous = cp;
            }
            i = next;
        }
        return text;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /** Draws {@code text} with its top-left at {@code (x, y)}; returns the end X position. */
    public float draw(GuiGraphics graphics, String text, float x, float y, float size, int color) {
        return draw(graphics, text, x, y, size, color, false);
    }

    public float draw(GuiGraphics graphics, String text, float x, float y, float size, int color, boolean shadow) {
        if (text == null || text.isEmpty() || closed) {
            return x;
        }
        int c = Render2D.applyGlobalAlpha(color);
        if (ColorUtil.alpha(c) < 4) {
            return x;
        }
        if (shadow) {
            float offset = Math.max(0.5f, Math.round(size / 9f));
            int shadowColor = (c & 0xFF000000) | ((c & 0xFCFCFC) >> 2);
            drawGlyphs(graphics, text, x + offset, y + offset, size, shadowColor);
        }
        return drawGlyphs(graphics, text, x, y, size, c);
    }

    /**
     * Draws {@code text} with its top-left at {@code (x, y)} decorated by {@code style} —
     * letter-spacing, gradient fills, soft/blurred shadow, outline or hollow lettering,
     * underline/strikethrough and faux-bold, in that draw order. Returns the end X position.
     * Passing {@code null} draws plain white text.
     */
    public float draw(GuiGraphics graphics, String text, float x, float y, float size, TextStyle style) {
        if (text == null || text.isEmpty() || closed) {
            return x;
        }
        if (style == null) {
            return draw(graphics, text, x, y, size, 0xFFFFFFFF);
        }
        float tracking = style.resolveTracking(size);
        Baked baked = bakedFor(size);
        if (baked == null) {
            return x;
        }
        float scale = Math.max(1f, Render2D.guiScale());
        float inv = 1f / scale;
        float originX = Math.round(x * scale) * inv;
        float baseline = Math.round(y * scale + baked.ascentPx) * inv;
        float totalWidth = width(text, size, tracking);
        if (Render2D.getGlobalAlpha() <= 0.004f) {
            return originX + totalWidth;
        }

        // Shadow sits behind everything.
        if (style.isShadow()) {
            drawTextShadow(graphics, text, x, y, size, style, tracking);
        }
        // Outline: stroke the glyphs' real vector contours with feathered polylines. The
        // stroke is centered on the contour, so a filled outline doubles the width to keep
        // roughly `thickness` visible outside the fill; hollow text keeps it as-is so the
        // letter interiors stay genuinely empty.
        if (style.isOutline() && ColorUtil.alpha(style.getOutlineColor()) > 0 && style.getOutlineThickness() > 0f) {
            float strokeWidth = style.isHollow()
                    ? style.getOutlineThickness() : style.getOutlineThickness() * 2f;
            strokeGlyphs(graphics, text, x, y, size, strokeWidth, style.getOutlineColor(), tracking);
        }
        // Fill (skipped for hollow text), optionally doubled half a pixel over for faux-bold.
        if (!style.isHollow()) {
            VertexColor fill = fillPaint(style, originX, totalWidth, baseline, baked, inv);
            drawGlyphs(graphics, text, x, y, size, fill, tracking);
            if (style.isBold()) {
                drawGlyphs(graphics, text, x + 0.5f * inv, y, size, fill, tracking);
            }
        }
        // Underline / strikethrough on top.
        drawDecorations(graphics, style, originX, baseline, totalWidth, size, inv);
        return originX + totalWidth;
    }

    /** Builds the per-vertex fill color for {@code style} (solid or gradient across the bounds). */
    private VertexColor fillPaint(TextStyle style, float originX, float totalWidth,
                                  float baseline, Baked baked, float inv) {
        if (style.isGradient()) {
            int start = style.getGradientStart();
            int end = style.getGradientEnd();
            if (style.getGradientDir() == TextStyle.GradientDir.HORIZONTAL) {
                float span = Math.max(1.0E-4f, totalWidth);
                return (vx, vy) -> Render2D.applyGlobalAlpha(ColorUtil.lerp(start, end, (vx - originX) / span));
            }
            float top = baseline - baked.ascentPx * inv;
            float span = Math.max(1.0E-4f, (baked.ascentPx - baked.descentPx) * inv);
            return (vx, vy) -> Render2D.applyGlobalAlpha(ColorUtil.lerp(start, end, (vy - top) / span));
        }
        int c = Render2D.applyGlobalAlpha(style.getColor());
        return (vx, vy) -> c;
    }

    /** Draws the shadow layer: a single hard copy, or several faded copies for a soft blur. */
    private void drawTextShadow(GuiGraphics graphics, String text, float x, float y, float size,
                               TextStyle style, float tracking) {
        float ox = style.getShadowOffsetX();
        float oy = style.getShadowOffsetY();
        int shadowColor = style.getShadowColor();
        int rawAlpha = ColorUtil.alpha(shadowColor);
        if (rawAlpha == 0) {
            return;
        }
        float blur = Math.max(0f, style.getShadowBlur());
        if (blur <= 0.01f) {
            int c = Render2D.applyGlobalAlpha(shadowColor);
            drawGlyphs(graphics, text, x + ox, y + oy, size, (vx, vy) -> c, tracking);
            return;
        }
        for (float[] s : SOFT_SHADOW_SAMPLES) {
            int a = Math.round(rawAlpha * s[2]);
            if (a <= 0) {
                continue;
            }
            int c = Render2D.applyGlobalAlpha(ColorUtil.withAlpha(shadowColor, a));
            drawGlyphs(graphics, text, x + ox + s[0] * blur, y + oy + s[1] * blur, size,
                    (vx, vy) -> c, tracking);
        }
    }

    /** Draws underline/strikethrough as thin rects positioned from the font metrics. */
    private void drawDecorations(GuiGraphics graphics, TextStyle style, float originX, float baseline,
                                 float totalWidth, float size, float inv) {
        if (!style.isUnderline() && !style.isStrikethrough()) {
            return;
        }
        int color = style.resolveDecorationColor();
        float thickness = Math.max(inv, size / 14f);
        if (style.isUnderline()) {
            float uy = baseline + Math.max(inv, size * 0.12f);
            Render2D.fillRect(graphics, originX, Render2D.roundToPixel(uy), totalWidth, thickness, color);
        }
        if (style.isStrikethrough()) {
            float sy = baseline - ascent(size) * 0.3f;
            Render2D.fillRect(graphics, originX, Render2D.roundToPixel(sy), totalWidth, thickness, color);
        }
    }

    /**
     * Strokes each glyph's real vector contours (from {@code stbtt_GetCodepointShape},
     * flattened and cached per baked size) with the feathered polyline machinery — genuinely
     * hollow lettering and crisp outlines at any thickness. Pen advance mirrors
     * {@link #drawGlyphs} exactly so the stroke registers with the fill. {@code color} is
     * raw ARGB; {@link Render2D#polylineClosed} applies the global alpha.
     */
    private void strokeGlyphs(GuiGraphics graphics, String text, float x, float y, float size,
                              float thickness, int color, float tracking) {
        Baked baked = bakedFor(size);
        if (baked == null) {
            return;
        }
        float scale = Math.max(1f, Render2D.guiScale());
        float inv = 1f / scale;
        float originX = Math.round(x * scale) * inv;
        float baseline = Math.round(y * scale + baked.ascentPx) * inv;

        float penX = originX;
        int previous = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int glyph = indexOf(cp);
            if (glyph < 0) {
                continue;
            }
            if (kerning && previous >= 0) {
                penX += stbtt_GetCodepointKernAdvance(info, previous, cp) * baked.kernScale * inv;
            }
            for (float[] contour : baked.contoursFor(codepoints[glyph])) {
                float[] pts = new float[contour.length];
                for (int p = 0; p < contour.length; p += 2) {
                    pts[p] = penX + contour[p] * inv;
                    pts[p + 1] = baseline + contour[p + 1] * inv;
                }
                Render2D.polylineClosed(graphics, pts, thickness, color);
            }
            penX += baked.glyphs[glyph * FLOATS_PER_GLYPH + 8] * inv + tracking;
            previous = cp;
        }
    }

    /**
     * Flattens a glyph's vector outline (move/line/quadratic/cubic verbs from STB) into
     * closed polyline contours, in physical pixels relative to the pen origin and baseline.
     * STB shapes are y-up in font units; the screen is y-down, so Y is negated.
     * Package-private so the contour geometry is unit-testable without a GL context.
     */
    float[][] flattenGlyph(int cp, float scale) {
        STBTTVertex.Buffer shape = stbtt_GetCodepointShape(info, cp);
        if (shape == null) {
            return new float[0][];
        }
        try {
            List<float[]> out = new ArrayList<>();
            FloatArrayList contour = null;
            float px = 0f, py = 0f;
            for (int v = 0; v < shape.limit(); v++) {
                STBTTVertex vertex = shape.get(v);
                float vx = vertex.x() * scale;
                float vy = -vertex.y() * scale;
                switch (vertex.type()) {
                    case STBTT_vmove -> {
                        finishContour(out, contour);
                        contour = new FloatArrayList();
                        contour.add(vx);
                        contour.add(vy);
                    }
                    case STBTT_vline -> {
                        if (contour != null) {
                            contour.add(vx);
                            contour.add(vy);
                        }
                    }
                    case STBTT_vcurve -> {
                        if (contour != null) {
                            addQuadratic(contour, px, py,
                                    vertex.cx() * scale, -vertex.cy() * scale, vx, vy);
                        }
                    }
                    case STBTT_vcubic -> {
                        if (contour != null) {
                            addCubic(contour, px, py,
                                    vertex.cx() * scale, -vertex.cy() * scale,
                                    vertex.cx1() * scale, -vertex.cy1() * scale, vx, vy);
                        }
                    }
                    default -> {
                    }
                }
                px = vx;
                py = vy;
            }
            finishContour(out, contour);
            return out.toArray(new float[0][]);
        } finally {
            stbtt_FreeShape(info, shape);
        }
    }

    /** Closes off a contour: drops a duplicated closing point, skips degenerate (&lt;3 point) rings. */
    private static void finishContour(List<float[]> out, FloatArrayList contour) {
        if (contour == null) {
            return;
        }
        int n = contour.size();
        // polylineClosed closes the loop itself; a repeated start point would make a
        // zero-length segment and a broken miter.
        if (n >= 4
                && Math.abs(contour.getFloat(0) - contour.getFloat(n - 2)) < 0.01f
                && Math.abs(contour.getFloat(1) - contour.getFloat(n - 1)) < 0.01f) {
            contour.removeElements(n - 2, n);
            n -= 2;
        }
        if (n >= 6) {
            out.add(contour.toFloatArray());
        }
    }

    /** Flattens a quadratic bézier from {@code (x0, y0)} into line segments (endpoint excluded start). */
    private static void addQuadratic(FloatArrayList contour, float x0, float y0,
                                     float cx, float cy, float x1, float y1) {
        int segments = curveSegments(Math.abs(cx - x0) + Math.abs(cy - y0)
                + Math.abs(x1 - cx) + Math.abs(y1 - cy));
        for (int i = 1; i <= segments; i++) {
            float t = (float) i / segments;
            float u = 1f - t;
            contour.add(u * u * x0 + 2f * u * t * cx + t * t * x1);
            contour.add(u * u * y0 + 2f * u * t * cy + t * t * y1);
        }
    }

    /** Flattens a cubic bézier (CFF/OTF fonts) from {@code (x0, y0)} into line segments. */
    private static void addCubic(FloatArrayList contour, float x0, float y0,
                                 float c1x, float c1y, float c2x, float c2y, float x1, float y1) {
        int segments = curveSegments(Math.abs(c1x - x0) + Math.abs(c1y - y0)
                + Math.abs(c2x - c1x) + Math.abs(c2y - c1y)
                + Math.abs(x1 - c2x) + Math.abs(y1 - c2y));
        for (int i = 1; i <= segments; i++) {
            float t = (float) i / segments;
            float u = 1f - t;
            contour.add(u * u * u * x0 + 3f * u * u * t * c1x + 3f * u * t * t * c2x + t * t * t * x1);
            contour.add(u * u * u * y0 + 3f * u * u * t * c1y + 3f * u * t * t * c2y + t * t * t * y1);
        }
    }

    /** Segment count for a curve whose control polygon spans ~{@code extent} physical pixels. */
    private static int curveSegments(float extent) {
        return Mth.clamp((int) Math.ceil(Math.sqrt(extent * 1.5f)), 2, 16);
    }

    private float drawGlyphs(GuiGraphics graphics, String text, float x, float y, float size, int color) {
        return drawGlyphs(graphics, text, x, y, size, (vx, vy) -> color, 0f);
    }

    private float drawGlyphs(GuiGraphics graphics, String text, float x, float y, float size,
                             VertexColor color, float tracking) {
        Baked baked = bakedFor(size);
        if (baked == null) {
            return x;
        }
        float scale = Math.max(1f, Render2D.guiScale());
        float inv = 1f / scale;
        // Snap the origin to physical pixels; glyph quads keep their sub-pixel offsets
        // relative to it, which is what makes small text crisp.
        float originX = Math.round(x * scale) * inv;
        float baseline = Math.round(y * scale + baked.ascentPx) * inv;

        Matrix4f mat = graphics.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, baked.textureId);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);

        float penX = originX;
        int previous = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int glyph = indexOf(cp);
            if (glyph < 0) {
                continue;
            }
            if (kerning && previous >= 0) {
                penX += stbtt_GetCodepointKernAdvance(info, previous, cp) * baked.kernScale * inv;
            }
            int o = glyph * FLOATS_PER_GLYPH;
            float[] g = baked.glyphs;
            if (g[o + 2] > g[o]) { // skip empty quads (e.g. space)
                float x0 = penX + g[o] * inv;
                float y0 = baseline + g[o + 1] * inv;
                float x1 = penX + g[o + 2] * inv;
                float y1 = baseline + g[o + 3] * inv;
                int cTL = color.at(x0, y0);
                int cBL = color.at(x0, y1);
                int cBR = color.at(x1, y1);
                int cTR = color.at(x1, y0);
                buffer.addVertex(mat, x0, y0, 0).setUv(g[o + 4], g[o + 5]).setColor(cTL);
                buffer.addVertex(mat, x0, y1, 0).setUv(g[o + 4], g[o + 7]).setColor(cBL);
                buffer.addVertex(mat, x1, y1, 0).setUv(g[o + 6], g[o + 7]).setColor(cBR);
                buffer.addVertex(mat, x0, y0, 0).setUv(g[o + 4], g[o + 5]).setColor(cTL);
                buffer.addVertex(mat, x1, y1, 0).setUv(g[o + 6], g[o + 7]).setColor(cBR);
                buffer.addVertex(mat, x1, y0, 0).setUv(g[o + 6], g[o + 5]).setColor(cTR);
            }
            penX += g[o + 8] * inv + tracking;
            previous = cp;
        }

        MeshData mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        return penX;
    }

    private int indexOf(int codepoint) {
        int index = glyphIndex.get(codepoint);
        return index >= 0 ? index : fallbackIndex;
    }

    // ------------------------------------------------------------------
    // Atlas baking
    // ------------------------------------------------------------------

    private Baked bakedFor(float size) {
        if (closed) {
            return null;
        }
        int effectivePx = Mth.clamp(Math.round(size * Math.max(1f, Render2D.guiScale())),
                MIN_PIXEL_SIZE, MAX_PIXEL_SIZE);
        Baked baked = bakedSizes.get(effectivePx);
        if (baked == null) {
            if (bakedSizes.size() >= MAX_CACHED_SIZES) {
                // GUI scale changes are rare; just drop everything and rebake on demand.
                for (Baked old : bakedSizes.values()) {
                    old.close();
                }
                bakedSizes.clear();
            }
            try {
                baked = new Baked(effectivePx);
            } catch (Exception e) {
                LOGGER.error("EasyGUI: failed to bake font '{}' at {}px", name, effectivePx, e);
                closed = true;
                return null;
            }
            bakedSizes.put(effectivePx, baked);
        }
        return baked;
    }

    private final class Baked implements AutoCloseable {
        final float[] glyphs;
        final float ascentPx;
        final float descentPx;
        final float kernScale;
        final ResourceLocation textureId;
        private final DynamicTexture texture;
        /** Flattened outline contours per codepoint (physical px, y-down, baseline-relative). */
        private final Int2ObjectMap<float[][]> contours = new Int2ObjectOpenHashMap<>();

        /** The glyph's flattened contours at this size, extracting and caching on first use. */
        float[][] contoursFor(int cp) {
            float[][] cached = contours.get(cp);
            if (cached == null) {
                cached = flattenGlyph(cp, kernScale);
                contours.put(cp, cached);
            }
            return cached;
        }

        Baked(int effectivePx) {
            int count = codepoints.length;
            int oversample = effectivePx <= 36 ? 2 : 1;
            long glyphArea = (long) (effectivePx * oversample + 2) * (effectivePx * oversample + 2);
            int side = 128;
            while ((long) side * side < glyphArea * count && side < MAX_ATLAS_SIZE) {
                side <<= 1;
            }

            STBTTPackedchar.Buffer chars = null;
            ByteBuffer bitmap = null;
            try {
                while (true) {
                    bitmap = MemoryUtil.memCalloc(side * side);
                    chars = STBTTPackedchar.malloc(count);
                    boolean packed;
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        STBTTPackContext context = STBTTPackContext.malloc(stack);
                        if (!stbtt_PackBegin(context, bitmap, side, side, 0, 2)) {
                            throw new IllegalStateException("stbtt_PackBegin failed");
                        }
                        stbtt_PackSetOversampling(context, oversample, oversample);
                        IntBuffer cps = stack.mallocInt(count);
                        cps.put(codepoints).flip();
                        STBTTPackRange.Buffer range = STBTTPackRange.calloc(1, stack);
                        range.font_size(effectivePx);
                        range.array_of_unicode_codepoints(cps);
                        range.num_chars(count);
                        range.chardata_for_range(chars);
                        packed = stbtt_PackFontRanges(context, data, 0, range);
                        stbtt_PackEnd(context);
                    }
                    if (packed || side >= MAX_ATLAS_SIZE) {
                        if (!packed) {
                            LOGGER.warn("EasyGUI: font atlas for '{}' at {}px overflowed {}x{}; "
                                    + "some glyphs will be missing", name, effectivePx, side, side);
                        }
                        break;
                    }
                    MemoryUtil.memFree(bitmap);
                    chars.free();
                    bitmap = null;
                    chars = null;
                    side <<= 1;
                }

                NativeImage image = new NativeImage(NativeImage.Format.RGBA, side, side, false);
                for (int py = 0; py < side; py++) {
                    int row = py * side;
                    for (int px = 0; px < side; px++) {
                        int alpha = bitmap.get(row + px) & 0xFF;
                        image.setPixelRGBA(px, py, (alpha << 24) | 0x00FFFFFF);
                    }
                }
                this.texture = new DynamicTexture(image);
                this.texture.setFilter(true, false);
                this.textureId = ResourceLocation.fromNamespaceAndPath("easygui",
                        "font/" + id + "/" + effectivePx);
                Minecraft.getInstance().getTextureManager().register(textureId, texture);

                this.glyphs = new float[count * FLOATS_PER_GLYPH];
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    STBTTAlignedQuad quad = STBTTAlignedQuad.malloc(stack);
                    FloatBuffer xPos = stack.floats(0f);
                    FloatBuffer yPos = stack.floats(0f);
                    for (int i = 0; i < count; i++) {
                        xPos.put(0, 0f);
                        yPos.put(0, 0f);
                        stbtt_GetPackedQuad(chars, side, side, i, xPos, yPos, quad, false);
                        int o = i * FLOATS_PER_GLYPH;
                        glyphs[o] = quad.x0();
                        glyphs[o + 1] = quad.y0();
                        glyphs[o + 2] = quad.x1();
                        glyphs[o + 3] = quad.y1();
                        glyphs[o + 4] = quad.s0();
                        glyphs[o + 5] = quad.t0();
                        glyphs[o + 6] = quad.s1();
                        glyphs[o + 7] = quad.t1();
                        glyphs[o + 8] = xPos.get(0);
                    }
                }
            } finally {
                if (bitmap != null) {
                    MemoryUtil.memFree(bitmap);
                }
                if (chars != null) {
                    chars.free();
                }
            }

            float scale = stbtt_ScaleForPixelHeight(info, effectivePx);
            this.kernScale = scale;
            this.ascentPx = unitsAscent * scale;
            this.descentPx = unitsDescent * scale; // negative (below the baseline)
        }

        @Override
        public void close() {
            Minecraft.getInstance().getTextureManager().release(textureId);
        }
    }

    /** Frees native font data and releases all baked atlas textures. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Baked baked : bakedSizes.values()) {
            baked.close();
        }
        bakedSizes.clear();
        info.free();
        MemoryUtil.memFree(data);
    }
}
