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
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.stb.STBTruetype.stbtt_FindGlyphIndex;
import static org.lwjgl.stb.STBTruetype.stbtt_GetCodepointKernAdvance;
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
        Baked baked = bakedFor(size);
        if (baked == null || text == null || text.isEmpty()) {
            return 0f;
        }
        float scale = Math.max(1f, Render2D.guiScale());
        float width = 0f;
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
            previous = cp;
        }
        return width / scale;
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

    private float drawGlyphs(GuiGraphics graphics, String text, float x, float y, float size, int color) {
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
                buffer.addVertex(mat, x0, y0, 0).setUv(g[o + 4], g[o + 5]).setColor(color);
                buffer.addVertex(mat, x0, y1, 0).setUv(g[o + 4], g[o + 7]).setColor(color);
                buffer.addVertex(mat, x1, y1, 0).setUv(g[o + 6], g[o + 7]).setColor(color);
                buffer.addVertex(mat, x0, y0, 0).setUv(g[o + 4], g[o + 5]).setColor(color);
                buffer.addVertex(mat, x1, y1, 0).setUv(g[o + 6], g[o + 7]).setColor(color);
                buffer.addVertex(mat, x1, y0, 0).setUv(g[o + 6], g[o + 5]).setColor(color);
            }
            penX += g[o + 8] * inv;
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
        final float kernScale;
        final ResourceLocation textureId;
        private final DynamicTexture texture;

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
