package com.stormcph.easygui.client.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.stormcph.easygui.client.media.ImageLoader;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Icons;
import com.stormcph.easygui.client.render.Render2D;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Displays a PNG/JPEG image loaded asynchronously from mod assets, a file on disk, or an
 * {@code http(s)} URL (see {@link ImageLoader} for the loading pipeline and lifecycle).
 *
 * <p>While the image loads, a small spinner arc plays over a placeholder fill; if loading
 * fails, a muted warning icon is shown and hovering reveals the error message as the
 * tooltip (replacing any custom tooltip while in the error state). Once ready, the image
 * is drawn with one of three {@link Fit} modes — {@link Fit#CONTAIN} (default, letterboxed
 * and centered), {@link Fit#COVER} (fills the bounds, cropping the overflow), or
 * {@link Fit#STRETCH} — with optional rounded-corner clipping via {@link #setRadius} and a
 * multiply tint via {@link #setTint}. All drawing respects the global alpha fade from
 * {@link Render2D#pushAlpha}.</p>
 *
 * <p><b>Disposal.</b> Screens rebuild often, so the rules are forgiving: URL-backed views
 * never need cleanup (their texture lives in {@link ImageLoader}'s shared cache and
 * {@link #close()} is a no-op on it — only {@link ImageLoader#release} frees it). For
 * resource/file-backed views, call {@link #close()} when the image is permanently no
 * longer needed to release its texture.</p>
 */
@Environment(EnvType.CLIENT)
public class ImageView extends Widget {

    /** How the image maps onto the widget bounds when their aspect ratios differ. */
    @Environment(EnvType.CLIENT)
    public enum Fit {
        /** Scale uniformly until the bounds are fully covered; the overflow is cropped. */
        COVER,
        /** Scale uniformly until the whole image fits; letterboxed and centered. */
        CONTAIN,
        /** Fill the bounds exactly, distorting the aspect ratio. */
        STRETCH
    }

    private final ImageLoader.Handle handle;
    private Fit fit = Fit.CONTAIN;
    private float radius;
    private int tint = 0xFFFFFFFF;

    /** Shows an image from mod assets (e.g. {@code mymod:textures/gui/banner.png}). */
    public ImageView(ResourceLocation texture) {
        this(ImageLoader.fromResource(texture));
    }

    /** Shows an already-obtained {@link ImageLoader.Handle} (e.g. one shared across views). */
    public ImageView(ImageLoader.Handle handle) {
        this.handle = handle;
        setSize(64, 64);
    }

    /** Shows an image file from disk. */
    public static ImageView fromFile(Path path) {
        return new ImageView(ImageLoader.fromFile(path));
    }

    /** Shows an image downloaded from an {@code http(s)} URL (cached; see {@link ImageLoader}). */
    public static ImageView fromUrl(String url) {
        return new ImageView(ImageLoader.fromUrl(url));
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** Sets how the image maps onto the widget bounds. Default {@link Fit#CONTAIN}. */
    public ImageView setFit(Fit fit) {
        this.fit = fit;
        return this;
    }

    public Fit getFit() {
        return fit;
    }

    /** Rounds the image's corners by {@code radius} GUI pixels (0 = square). */
    public ImageView setRadius(float radius) {
        this.radius = Math.max(0f, radius);
        return this;
    }

    public float getRadius() {
        return radius;
    }

    /** Multiplies the image by an ARGB color ({@code 0xFFFFFFFF} = unchanged). */
    public ImageView setTint(int argb) {
        this.tint = argb;
        return this;
    }

    public int getTint() {
        return tint;
    }

    /** The underlying load handle (poll its state, read dimensions, share across views). */
    public ImageLoader.Handle getHandle() {
        return handle;
    }

    /**
     * Releases the image texture if this view owns it. No-op for URL-backed views — their
     * texture belongs to {@link ImageLoader}'s shared cache and survives screen rebuilds;
     * free those explicitly with {@link ImageLoader#release} instead.
     */
    public void close() {
        handle.close();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        ImageLoader.State state = handle.state();
        if (state == ImageLoader.State.READY) {
            drawImage(graphics);
            return;
        }

        // Placeholder fill so the widget keeps a visible footprint while loading / on error
        Render2D.fillRoundedRect(graphics, x, y, width, height, radius, theme().surfaceVariant);
        if (state == ImageLoader.State.LOADING) {
            drawLoadingArc(graphics);
        } else if (state == ImageLoader.State.ERROR) {
            float size = Math.min(Math.min(width, height) * 0.5f, 18f);
            Icons.WARNING.render(graphics, x + (width - size) / 2f, y + (height - size) / 2f,
                    size, theme().textMuted);
            if (handle.error() != null) {
                tooltip = handle.error();
            }
        }
    }

    /** Indeterminate spinner arc, centered — same breathing-sweep idiom as {@link Spinner}. */
    private void drawLoadingArc(GuiGraphics graphics) {
        float arcRadius = Math.min(Math.min(width, height) / 2f - 3f, 8f);
        if (arcRadius < 2f) {
            return;
        }
        long now = Util.getMillis();
        float rotation = (now % 1100L) / 1100f * 360f;
        float breathe = (float) (Math.sin((now % 1600L) / 1600.0 * Math.PI * 2) * 0.5 + 0.5);
        float sweep = 50f + breathe * 210f;
        Render2D.drawArc(graphics, x + width / 2f, y + height / 2f, arcRadius, 2.5f,
                rotation, rotation + sweep, theme().accent);
    }

    private void drawImage(GuiGraphics graphics) {
        ResourceLocation texture = handle.textureId();
        float imgW = handle.width();
        float imgH = handle.height();
        if (texture == null || imgW <= 0 || imgH <= 0 || width <= 0 || height <= 0) {
            return;
        }
        switch (fit) {
            case STRETCH -> drawAt(graphics, texture, x, y, width, height);
            case CONTAIN -> {
                // Largest uniform scale at which the whole image fits; centered letterbox
                float scale = Math.min(width / imgW, height / imgH);
                float dw = imgW * scale;
                float dh = imgH * scale;
                drawAt(graphics, texture, x + (width - dw) / 2f, y + (height - dh) / 2f, dw, dh);
            }
            case COVER -> {
                // Smallest uniform scale that fully covers the bounds; overflow is cropped
                float scale = Math.max(width / imgW, height / imgH);
                if (radius > 0f) {
                    // Crop in UV space so the rounded silhouette sits exactly on the bounds
                    // (a scissor cut would leave the corners square).
                    float uHalf = width / (imgW * scale) / 2f;
                    float vHalf = height / (imgH * scale) / 2f;
                    texturedRoundedRectUV(graphics, texture, x, y, width, height, radius,
                            0.5f - uHalf, 0.5f - vHalf, 0.5f + uHalf, 0.5f + vHalf, tint);
                } else {
                    float dw = imgW * scale;
                    float dh = imgH * scale;
                    Render2D.pushScissor(graphics, x, y, width, height);
                    Render2D.texturedRect(graphics, texture, x + (width - dw) / 2f,
                            y + (height - dh) / 2f, dw, dh, tint);
                    Render2D.popScissor(graphics);
                }
            }
        }
    }

    private void drawAt(GuiGraphics graphics, ResourceLocation texture, float dx, float dy,
                        float dw, float dh) {
        if (radius > 0f) {
            Render2D.texturedRoundedRect(graphics, texture, dx, dy, dw, dh, radius, tint);
        } else {
            Render2D.texturedRect(graphics, texture, dx, dy, dw, dh, tint);
        }
    }

    // ------------------------------------------------------------------
    // Rounded textured quad with an explicit UV window (for COVER cropping)
    // ------------------------------------------------------------------

    /**
     * Like {@link Render2D#texturedRoundedRect} but with the UVs mapped across a
     * {@code [u0..u1, v0..v1]} sub-window of the texture instead of the full 0..1 range —
     * exactly what COVER cropping inside a rounded silhouette needs. Mirrors Render2D's
     * feathered-perimeter tessellation (fan from the center plus a sub-pixel feather ring
     * that fades to transparent, with UVs clamped at the silhouette) so the anti-aliasing
     * matches every other rounded shape; a candidate to fold into Render2D later.
     */
    private static void texturedRoundedRectUV(GuiGraphics graphics, ResourceLocation texture,
                                              float x, float y, float w, float h, float radius,
                                              float u0, float v0, float u1, float v1, int tint) {
        int c = Render2D.applyGlobalAlpha(tint);
        if (w <= 0 || h <= 0 || ColorUtil.alpha(c) == 0) {
            return;
        }
        float r = Mth.clamp(radius, 0f, Math.min(w, h) / 2f);
        float guiScale = Math.max(1f, Render2D.guiScale());
        float feather = 1f / guiScale;
        int segments = Mth.clamp((int) Math.ceil(r * guiScale * 0.6f), 3, 32);

        // Clockwise perimeter: {x, y, outwardNormalX, outwardNormalY}
        List<float[]> pts = new ArrayList<>((segments + 1) * 4);
        addCornerArc(pts, x + r, y + r, r, 180f, 270f, segments);
        addCornerArc(pts, x + w - r, y + r, r, 270f, 360f, segments);
        addCornerArc(pts, x + w - r, y + h - r, r, 0f, 90f, segments);
        addCornerArc(pts, x + r, y + h - r, r, 90f, 180f, segments);

        float cx = x + w / 2f;
        float cy = y + h / 2f;
        float uScale = (u1 - u0) / w;
        float vScale = (v1 - v0) / h;
        int c0 = c & 0x00FFFFFF;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Matrix4f mat = graphics.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,
                DefaultVertexFormat.POSITION_TEX_COLOR);
        int n = pts.size();
        // Solid fan from the center
        for (int i = 0; i < n; i++) {
            float[] a = pts.get(i);
            float[] b = pts.get((i + 1) % n);
            uvVertex(buffer, mat, cx, cy, cx, cy, x, y, u0, v0, uScale, vScale, c);
            uvVertex(buffer, mat, a[0], a[1], a[0], a[1], x, y, u0, v0, uScale, vScale, c);
            uvVertex(buffer, mat, b[0], b[1], b[0], b[1], x, y, u0, v0, uScale, vScale, c);
        }
        // Anti-aliasing feather ring (UVs clamped at the silhouette)
        for (int i = 0; i < n; i++) {
            float[] a = pts.get(i);
            float[] b = pts.get((i + 1) % n);
            float ax = a[0] + a[2] * feather;
            float ay = a[1] + a[3] * feather;
            float bx = b[0] + b[2] * feather;
            float by = b[1] + b[3] * feather;
            uvVertex(buffer, mat, a[0], a[1], a[0], a[1], x, y, u0, v0, uScale, vScale, c);
            uvVertex(buffer, mat, ax, ay, a[0], a[1], x, y, u0, v0, uScale, vScale, c0);
            uvVertex(buffer, mat, bx, by, b[0], b[1], x, y, u0, v0, uScale, vScale, c0);
            uvVertex(buffer, mat, a[0], a[1], a[0], a[1], x, y, u0, v0, uScale, vScale, c);
            uvVertex(buffer, mat, bx, by, b[0], b[1], x, y, u0, v0, uScale, vScale, c0);
            uvVertex(buffer, mat, b[0], b[1], b[0], b[1], x, y, u0, v0, uScale, vScale, c);
        }
        MeshData mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void addCornerArc(List<float[]> pts, float cx, float cy, float r,
                                     float fromDeg, float toDeg, int segments) {
        if (r <= 0f) {
            double mid = Math.toRadians((fromDeg + toDeg) / 2f);
            pts.add(new float[]{cx, cy, (float) Math.cos(mid), (float) Math.sin(mid)});
            return;
        }
        for (int i = 0; i <= segments; i++) {
            double a = Math.toRadians(fromDeg + (toDeg - fromDeg) * i / (float) segments);
            float nx = (float) Math.cos(a);
            float ny = (float) Math.sin(a);
            pts.add(new float[]{cx + nx * r, cy + ny * r, nx, ny});
        }
    }

    /** Vertex at {@code (px, py)} whose UV comes from mapping {@code (uvx, uvy)} through the UV window. */
    private static void uvVertex(BufferBuilder buffer, Matrix4f mat, float px, float py,
                                 float uvx, float uvy, float x, float y,
                                 float u0, float v0, float uScale, float vScale, int color) {
        buffer.addVertex(mat, px, py, 0)
                .setUv(u0 + (uvx - x) * uScale, v0 + (uvy - y) * vScale)
                .setColor(color);
    }
}
