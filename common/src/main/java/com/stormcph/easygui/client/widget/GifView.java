package com.stormcph.easygui.client.widget;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.stormcph.easygui.client.media.GifDecoder;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Icons;
import com.stormcph.easygui.client.render.Render2D;
import com.stormcph.easygui.client.screen.EasyScreen;
import com.stormcph.easygui.client.theme.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An animated GIF widget. Bytes are fetched and decoded entirely off-thread (via
 * {@link GifDecoder}); the composited frames are then uploaded once, on the render
 * thread, as one dynamic texture per frame. While loading it shows a spinner, and a
 * failed load shows a warning icon with the error as a tooltip — the render thread is
 * never blocked.
 *
 * <p>Playback advances by accumulated real time, honoring each frame's delay and the
 * file's loop count; {@link #setLoop(boolean)} forces endless looping, {@link #setPlaying}
 * pauses, {@link #restart()} rewinds. Frames draw like images, with
 * {@link Fit#CONTAIN}/{@link Fit#COVER}/{@link Fit#STRETCH} fit modes and rounded
 * corners via {@link #setRadius(float)}.</p>
 *
 * <p>Memory guard: a GIF whose total decoded volume (width × height × frames) exceeds
 * {@link #MAX_TOTAL_PIXELS} (~24M pixels ≈ 96&nbsp;MB of textures) is rejected with a
 * clear error instead of silently eating VRAM. Call {@link #dispose()} when the widget
 * is permanently gone to release all frame textures.</p>
 *
 * <pre>{@code
 * panel.add(GifView.fromUrl("https://example.com/cat.gif")
 *         .setFit(GifView.Fit.COVER)
 *         .setRadius(8f))
 *     .setBounds(x, y, 120, 90);
 * }</pre>
 */
@Environment(EnvType.CLIENT)
public class GifView extends Widget {
    /** Cap on {@code width × height × frames}; larger GIFs surface an error state. */
    public static final long MAX_TOTAL_PIXELS = 24_000_000L;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static volatile HttpClient httpClient;

    /** How the GIF maps onto the widget bounds when their aspect ratios differ. */
    public enum Fit {
        /** Largest size that fits entirely inside the bounds, centered (letterboxed). */
        CONTAIN,
        /** Scaled uniformly until the bounds are covered; the overflow is cropped. */
        COVER,
        /** Fills the bounds exactly, distorting the aspect ratio. */
        STRETCH
    }

    private enum State {
        LOADING, READY, ERROR, DISPOSED
    }

    private final int id = NEXT_ID.getAndIncrement();
    private final String sourceName;

    private volatile State state = State.LOADING;
    private volatile String errorMessage;
    private volatile boolean disposed;

    // Written on the render thread in upload(), read on the render thread while READY
    private ResourceLocation[] frameTextures;
    private int[] frameDelays;
    private int imageWidth;
    private int imageHeight;
    private int loopCount;

    private Fit fit = Fit.CONTAIN;
    private float radius;
    private int tint = 0xFFFFFFFF;

    private boolean playing = true;
    private boolean forceLoop;
    private boolean finished;
    private int frameIndex;
    private int completedLoops;
    private long lastAdvanceMillis = -1L;
    private long accumulatorMs;

    private GifView(String sourceName) {
        this.sourceName = sourceName;
        setSize(64, 64);
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    /** Loads a GIF shipped in mod assets or a resource pack. */
    public static GifView fromResource(ResourceLocation location) {
        GifView view = new GifView(location.toString());
        view.load(() -> {
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(location)) {
                return in.readAllBytes();
            }
        });
        return view;
    }

    /** Loads a GIF from a file on disk. */
    public static GifView fromFile(Path path) {
        GifView view = new GifView(path.toString());
        view.load(() -> Files.readAllBytes(path));
        return view;
    }

    /** Downloads a GIF over HTTP(S). The request runs on the background executor. */
    public static GifView fromUrl(String url) {
        GifView view = new GifView(url);
        view.load(() -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "EasyGUI (Minecraft mod)")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client().send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " fetching " + url);
            }
            return response.body();
        });
        return view;
    }

    private static HttpClient client() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (GifView.class) {
                client = httpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .connectTimeout(Duration.ofSeconds(10))
                            .executor(Util.backgroundExecutor())
                            .build();
                    httpClient = client;
                }
            }
        }
        return client;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /** How the GIF maps onto non-matching widget bounds (default {@link Fit#CONTAIN}). */
    public GifView setFit(Fit fit) {
        this.fit = fit;
        return this;
    }

    /** Corner radius for the drawn image; {@code 0} draws plain quads. */
    public GifView setRadius(float radius) {
        this.radius = radius;
        return this;
    }

    /** Tint multiplied into the frames (default white = untinted). */
    public GifView setTint(int tint) {
        this.tint = tint;
        return this;
    }

    /** Pauses/resumes playback (the current frame stays on screen while paused). */
    public GifView setPlaying(boolean playing) {
        this.playing = playing;
        return this;
    }

    /** Forces endless looping regardless of the file's loop count. */
    public GifView setLoop(boolean loop) {
        this.forceLoop = loop;
        if (loop) {
            finished = false; // a finished animation picks back up
        }
        return this;
    }

    /** Rewinds to the first frame and restarts playback (clears the finished state). */
    public GifView restart() {
        frameIndex = 0;
        completedLoops = 0;
        accumulatorMs = 0;
        lastAdvanceMillis = -1L;
        finished = false;
        return this;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public boolean isPlaying() {
        return playing;
    }

    /** True once all frames are decoded and uploaded. */
    public boolean isReady() {
        return state == State.READY;
    }

    public boolean isError() {
        return state == State.ERROR;
    }

    /** The failure description, or {@code null} unless {@link #isError()}. */
    public String getErrorMessage() {
        return errorMessage;
    }

    /** Native GIF canvas width in pixels ({@code 0} until ready). */
    public int getImageWidth() {
        return imageWidth;
    }

    /** Native GIF canvas height in pixels ({@code 0} until ready). */
    public int getImageHeight() {
        return imageHeight;
    }

    /** Number of animation frames ({@code 0} until ready). */
    public int getFrameCount() {
        ResourceLocation[] textures = frameTextures;
        return textures != null ? textures.length : 0;
    }

    /**
     * Releases every frame texture. Call when the widget is permanently discarded; safe
     * from any thread and safe to call while a load is still in flight (the pending
     * upload is cancelled).
     */
    public void dispose() {
        disposed = true;
        state = State.DISPOSED;
        ResourceLocation[] textures = frameTextures;
        frameTextures = null;
        if (textures != null) {
            Minecraft.getInstance().execute(() -> {
                TextureManager manager = Minecraft.getInstance().getTextureManager();
                for (ResourceLocation texture : textures) {
                    manager.release(texture);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // Loading pipeline (background thread -> render thread)
    // ------------------------------------------------------------------

    private interface ByteSource {
        byte[] read() throws Exception;
    }

    private void load(ByteSource source) {
        Util.backgroundExecutor().execute(() -> {
            try {
                byte[] bytes = source.read();
                GifDecoder.Result gif = GifDecoder.decode(new ByteArrayInputStream(bytes));
                long totalPixels = (long) gif.width * gif.height * gif.frames.size();
                if (totalPixels > MAX_TOTAL_PIXELS) {
                    fail("GIF too large to display: " + gif.width + "x" + gif.height + " x "
                            + gif.frames.size() + " frames (" + totalPixels + " pixels, limit "
                            + MAX_TOTAL_PIXELS + ")");
                    return;
                }
                // Pixel conversion is pure memory work; only the GL upload needs the
                // render thread.
                NativeImage[] images = new NativeImage[gif.frames.size()];
                int[] delays = new int[gif.frames.size()];
                try {
                    for (int i = 0; i < images.length; i++) {
                        GifDecoder.Frame frame = gif.frames.get(i);
                        images[i] = toNativeImage(gif.width, gif.height, frame.pixels);
                        delays[i] = Math.max(10, frame.delayMs);
                    }
                } catch (Throwable t) {
                    closeAll(images);
                    throw t;
                }
                Minecraft.getInstance().execute(
                        () -> upload(gif.width, gif.height, gif.loopCount, images, delays));
            } catch (Throwable t) {
                LOGGER.error("EasyGUI: failed to load GIF '{}'", sourceName, t);
                fail(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
        });
    }

    /** Render thread: wraps each frame in a dynamic texture and flips to READY. */
    private void upload(int gifWidth, int gifHeight, int gifLoopCount, NativeImage[] images, int[] delays) {
        if (disposed) {
            closeAll(images);
            return;
        }
        TextureManager manager = Minecraft.getInstance().getTextureManager();
        ResourceLocation[] textures = new ResourceLocation[images.length];
        for (int i = 0; i < images.length; i++) {
            DynamicTexture texture = new DynamicTexture(images[i]);
            texture.setFilter(true, false);
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath("easygui",
                    "dynamic/gif_" + id + "_" + i);
            manager.register(location, texture);
            textures[i] = location;
        }
        this.imageWidth = gifWidth;
        this.imageHeight = gifHeight;
        this.loopCount = gifLoopCount;
        this.frameDelays = delays;
        this.frameTextures = textures;
        this.state = State.READY;
        if (disposed) { // disposed from another thread while we were registering
            frameTextures = null;
            state = State.DISPOSED;
            for (ResourceLocation texture : textures) {
                manager.release(texture);
            }
        }
    }

    private void fail(String message) {
        errorMessage = message;
        if (!disposed) {
            state = State.ERROR;
        }
    }

    private static void closeAll(NativeImage[] images) {
        for (NativeImage image : images) {
            if (image != null) {
                image.close();
            }
        }
    }

    private static NativeImage toNativeImage(int width, int height, int[] argb) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int c = argb[row + x];
                // ARGB -> ABGR (NativeImage's RGBA byte order as a little-endian int)
                image.setPixelRGBA(x, y, (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16));
            }
        }
        return image;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        State current = state;
        if (current == State.DISPOSED) {
            return;
        }
        Theme theme = theme();
        if (current == State.LOADING) {
            drawLoading(graphics, theme);
            return;
        }
        if (current == State.ERROR) {
            drawError(graphics, theme, mouseX, mouseY);
            return;
        }
        advancePlayback();
        drawFrame(graphics);
    }

    private void drawLoading(GuiGraphics graphics, Theme theme) {
        Render2D.fillRoundedRect(graphics, x, y, width, height, radius, theme.surfaceVariant);
        long now = Util.getMillis();
        float rotation = (now % 1100L) / 1100f * 360f;
        float breathe = (float) (Math.sin((now % 1600L) / 1600.0 * Math.PI * 2) * 0.5 + 0.5);
        float sweep = 50f + breathe * 210f;
        float spinnerRadius = Math.min(Math.min(width, height) / 2f - 3f, 8f);
        if (spinnerRadius > 1f) {
            Render2D.drawArc(graphics, x + width / 2f, y + height / 2f, spinnerRadius, 2.5f,
                    rotation, rotation + sweep, theme.accent);
        }
    }

    private void drawError(GuiGraphics graphics, Theme theme, double mouseX, double mouseY) {
        Render2D.fillRoundedRect(graphics, x, y, width, height, radius, theme.surfaceVariant);
        float iconSize = Mth.clamp(Math.min(width, height) * 0.55f, 8f, 18f);
        Icons.WARNING.render(graphics, x + (width - iconSize) / 2f, y + (height - iconSize) / 2f,
                iconSize, theme.danger);
        if (isHovered() && tooltip == null) { // a user-set tooltip takes precedence
            EasyScreen screen = getScreen();
            if (screen != null) {
                String message = errorMessage != null ? errorMessage : "unknown error";
                screen.requestTooltip("GIF failed to load: " + message, (float) mouseX, (float) mouseY);
            }
        }
    }

    /** Advances the current frame by accumulated wall-clock time. */
    private void advancePlayback() {
        long now = Util.getMillis();
        if (!playing || finished || frameTextures.length <= 1) {
            lastAdvanceMillis = now; // don't fast-forward when resuming
            return;
        }
        if (lastAdvanceMillis < 0) {
            lastAdvanceMillis = now;
            return;
        }
        // Clamp huge gaps (window unfocused, lag spike) instead of spinning through frames
        accumulatorMs += Math.min(now - lastAdvanceMillis, 1000L);
        lastAdvanceMillis = now;
        while (accumulatorMs >= frameDelays[frameIndex]) {
            accumulatorMs -= frameDelays[frameIndex];
            if (frameIndex + 1 < frameTextures.length) {
                frameIndex++;
            } else {
                completedLoops++;
                if (forceLoop || loopCount == 0 || completedLoops < loopCount) {
                    frameIndex = 0;
                } else {
                    finished = true; // stay on the last frame
                    accumulatorMs = 0;
                    break;
                }
            }
        }
    }

    private void drawFrame(GuiGraphics graphics) {
        ResourceLocation texture = frameTextures[frameIndex];
        float drawX = x;
        float drawY = y;
        float drawWidth = width;
        float drawHeight = height;
        float u0 = 0f;
        float v0 = 0f;
        float u1 = 1f;
        float v1 = 1f;
        if (fit == Fit.CONTAIN) {
            float scale = Math.min(width / imageWidth, height / imageHeight);
            drawWidth = imageWidth * scale;
            drawHeight = imageHeight * scale;
            drawX = x + (width - drawWidth) / 2f;
            drawY = y + (height - drawHeight) / 2f;
        } else if (fit == Fit.COVER) {
            float scale = Math.max(width / imageWidth, height / imageHeight);
            float visibleU = width / (imageWidth * scale);
            float visibleV = height / (imageHeight * scale);
            u0 = (1f - visibleU) / 2f;
            u1 = 1f - u0;
            v0 = (1f - visibleV) / 2f;
            v1 = 1f - v0;
        }
        if (radius > 0f) {
            if (u0 == 0f && v0 == 0f && u1 == 1f && v1 == 1f) {
                Render2D.texturedRoundedRect(graphics, texture, drawX, drawY, drawWidth, drawHeight,
                        radius, tint);
            } else {
                texturedRoundedRectUV(graphics, texture, drawX, drawY, drawWidth, drawHeight,
                        radius, u0, v0, u1, v1, tint);
            }
        } else {
            Render2D.texturedRect(graphics, texture, drawX, drawY, drawWidth, drawHeight,
                    u0, v0, u1, v1, tint);
        }
    }

    // ------------------------------------------------------------------
    // Rounded textured quad with UV cropping
    // ------------------------------------------------------------------

    /**
     * Like {@code Render2D.texturedRoundedRect} but with an explicit UV sub-rectangle —
     * needed for {@link Fit#COVER}, which crops the texture. Mirrors Render2D's feathered
     * rounded mesh: a fan fill plus an anti-aliasing ring whose UVs stay clamped at the
     * silhouette.
     */
    private static void texturedRoundedRectUV(GuiGraphics graphics, ResourceLocation texture,
                                              float x, float y, float width, float height, float radius,
                                              float u0, float v0, float u1, float v1, int tint) {
        int c = Render2D.applyGlobalAlpha(tint);
        if (width <= 0 || height <= 0 || ColorUtil.alpha(c) == 0) {
            return;
        }
        float r = Math.min(radius, Math.min(width, height) / 2f);
        float guiScale = Math.max(1f, Render2D.guiScale());
        float feather = 1f / guiScale;
        int segments = Mth.clamp((int) Math.ceil(r * guiScale * 0.6f), 3, 32);

        // Clockwise perimeter with outward normals: {x, y, nx, ny}
        List<float[]> pts = new ArrayList<>();
        addCornerArc(pts, x + r, y + r, r, 180f, 270f, segments);
        addCornerArc(pts, x + width - r, y + r, r, 270f, 360f, segments);
        addCornerArc(pts, x + width - r, y + height - r, r, 0f, 90f, segments);
        addCornerArc(pts, x + r, y + height - r, r, 90f, 180f, segments);

        float centerX = x + width / 2f;
        float centerY = y + height / 2f;
        float uScale = (u1 - u0) / width;
        float vScale = (v1 - v0) / height;
        int c0 = c & 0x00FFFFFF;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Matrix4f mat = graphics.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            float[] a = pts.get(i);
            float[] b = pts.get((i + 1) % n);
            uvVertex(buffer, mat, centerX, centerY, centerX, centerY, x, y, u0, v0, uScale, vScale, c);
            uvVertex(buffer, mat, a[0], a[1], a[0], a[1], x, y, u0, v0, uScale, vScale, c);
            uvVertex(buffer, mat, b[0], b[1], b[0], b[1], x, y, u0, v0, uScale, vScale, c);
        }
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
            float mid = (float) Math.toRadians((fromDeg + toDeg) / 2f);
            pts.add(new float[]{cx, cy, (float) Math.cos(mid), (float) Math.sin(mid)});
            return;
        }
        for (int i = 0; i <= segments; i++) {
            float a = (float) Math.toRadians(fromDeg + (toDeg - fromDeg) * i / segments);
            float nx = (float) Math.cos(a);
            float ny = (float) Math.sin(a);
            pts.add(new float[]{cx + nx * r, cy + ny * r, nx, ny});
        }
    }

    /** Vertex at {@code (px, py)} whose UV comes from mapping {@code (uvx, uvy)} through the UV rect. */
    private static void uvVertex(BufferBuilder buffer, Matrix4f mat, float px, float py,
                                 float uvx, float uvy, float x, float y,
                                 float u0, float v0, float uScale, float vScale, int color) {
        buffer.addVertex(mat, px, py, 0)
                .setUv(u0 + (uvx - x) * uScale, v0 + (uvy - y) * vScale)
                .setColor(color);
    }
}
