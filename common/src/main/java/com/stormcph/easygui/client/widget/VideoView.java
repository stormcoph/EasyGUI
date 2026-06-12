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
import com.stormcph.easygui.client.animation.SmoothValue;
import com.stormcph.easygui.client.media.FrameSequence;
import com.stormcph.easygui.client.media.MjpegStream;
import com.stormcph.easygui.client.render.ColorUtil;
import com.stormcph.easygui.client.render.Icons;
import com.stormcph.easygui.client.render.Render2D;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Plays MJPEG video (AVI or raw stream) and image frame sequences inside the GUI, with
 * strictly off-thread decoding — the render thread never waits on the decoder.
 *
 * <p><b>Why MJPEG + frame sequences, and not H.264.</b> Minecraft ships no H.264 decoder,
 * and the only pure-Java option (JCodec) was evaluated and rejected: it decodes roughly
 * 15–40&nbsp;fps at 720p on typical JVMs with multi-hundred-millisecond GC-heavy worst-case
 * frames — unacceptable for a GUI library that promises 60&nbsp;fps — and it would bundle
 * a ~2&nbsp;MB dependency against EasyGUI's no-heavyweight-deps rule. MJPEG frames decode
 * through stb_image ({@link NativeImage#read}), which Minecraft already ships, in low
 * single-digit milliseconds; that covers the actual GUI use cases (HUD elements, menu and
 * background video). Anyone holding an H.264 file pre-converts once:
 * {@code ffmpeg -i in.mp4 -c:v mjpeg -q:v 5 out.avi}.</p>
 *
 * <p><b>Pipeline.</b> One daemon worker thread per view opens the source
 * ({@link MjpegStream} or {@link FrameSequence}) and decodes ahead into a small ring of
 * {@value #RING_CAPACITY} frames. The render thread advances a playback clock, takes the
 * frame whose presentation time is due, copies its pixels into <em>one</em> reused
 * {@link DynamicTexture} ({@code getPixels().copyFrom(...)} + {@code upload()} — no
 * per-frame texture churn) and keeps showing the last uploaded frame whenever nothing new
 * is ready. <b>Frame drop:</b> if the clock runs ahead (lag spike, heavy frame), both
 * sides skip work — the render thread discards all but the newest due frame, and the
 * worker jumps straight to the due frame index, skipping intermediate frames without
 * decoding them (for MJPEG it still parses chunk/segment headers to find frame
 * boundaries, which is nearly free; with an AVI {@code idx1} index it seeks directly).</p>
 *
 * <p><b>States and controls.</b> A spinner plays until the first frame arrives (the
 * first decoded frame also serves as the poster while paused); failures show a warning
 * icon with the reason as the tooltip. {@link #play()}/{@link #pause()}/{@link #stop()}
 * control playback ({@code stop} rewinds to frame 0), looping is on by default
 * ({@link #setLoop}), and {@link #seek} jumps when the source is seekable (AVI with an
 * {@code idx1} index, or any frame sequence) and is a documented no-op otherwise.
 * {@link #setShowControls} enables a minimal self-drawn overlay: a play/pause button and
 * a thin progress bar that seeks on click/drag. {@link Fit} modes and
 * {@link #setRadius rounded clipping} match {@link ImageView}.</p>
 *
 * <p><b>Lifecycle.</b> The worker starts lazily on the first render (so the poster
 * appears) or on {@link #play()}. Call {@link #dispose()} when the view is permanently
 * gone: it stops the worker, frees buffered frames and releases the GPU texture. A
 * disposed view draws nothing and cannot be restarted. Playback controls are meant to be
 * called from the client thread; {@link #withFps} must be set before playback starts.</p>
 */
@Environment(EnvType.CLIENT)
public class VideoView extends Widget {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    /** Decode-ahead ring size: enough to absorb jitter, small enough to keep memory flat. */
    private static final int RING_CAPACITY = 4;
    /** Frame rate used when neither the container nor {@link #withFps} provides one. */
    private static final double DEFAULT_FPS = 30.0;
    private static final float CONTROLS_HEIGHT = 13f;

    // ------------------------------------------------------------------
    // Pluggable sources
    // ------------------------------------------------------------------

    /**
     * The minimal contract a video source fulfils: sequential access to still-encoded
     * (JPEG/PNG) frame payloads plus cheap skipping for frame drop. Implemented by
     * {@link MjpegStream} and {@link FrameSequence}; lives here because the consumer
     * defines what it needs. All methods are called from the view's decode thread only.
     */
    @Environment(EnvType.CLIENT)
    public interface FrameSource {
        /** Container frame rate, or {@code 0} when the container carries no timing. */
        double fps();

        /** Total frames, or {@code -1} when unknown (learned at the end of the first pass). */
        int frameCount();

        /** The next frame's encoded image bytes, or {@code null} at end of stream. */
        byte[] nextFrame() throws IOException;

        /** Advances past one frame without copying its payload; {@code false} at end of stream. */
        boolean skipFrame() throws IOException;

        /** Rewinds so the next frame returned is frame 0. */
        void reset() throws IOException;

        /** Positions so the next frame returned is {@code index}; {@code false} if unsupported. */
        boolean seekToFrame(int index) throws IOException;

        /** Whether {@link #seekToFrame} works for arbitrary indices. */
        boolean isSeekable();

        void close() throws IOException;
    }

    /** Opens a {@link FrameSource}; runs on the decode thread, so blocking I/O is fine. */
    @Environment(EnvType.CLIENT)
    @FunctionalInterface
    public interface SourceOpener {
        FrameSource open() throws IOException;
    }

    /** How the video maps onto the widget bounds when their aspect ratios differ. */
    @Environment(EnvType.CLIENT)
    public enum Fit {
        /** Scale uniformly until the bounds are fully covered; the overflow is cropped. */
        COVER,
        /** Scale uniformly until the whole frame fits; letterboxed and centered. */
        CONTAIN,
        /** Fill the bounds exactly, distorting the aspect ratio. */
        STRETCH
    }

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    private final SourceOpener opener;
    private final String sourceName;
    private final int viewId = NEXT_ID.getAndIncrement();
    private final ResourceLocation dynamicId =
            ResourceLocation.fromNamespaceAndPath("easygui", "dynamic/video_" + viewId);

    // Widget configuration (render thread only)
    private Fit fit = Fit.CONTAIN;
    private float radius;
    private boolean showControls;
    private final SmoothValue controlsAlpha = new SmoothValue(0f, 12f);
    private boolean draggingProgress;

    // Playback state shared with the decode worker
    private volatile boolean playing;
    private volatile boolean loop = true;
    private volatile boolean disposed;
    private volatile String error;
    private volatile double fpsOverride;
    private volatile long frameMicros;          // 0 until the source is open
    private volatile long durationMicros = -1;  // -1 until the frame count is known
    private volatile boolean sourceSeekable;
    private volatile int generation;            // bumped on seek; stamps queued frames
    private volatile double pendingSeekSeconds = -1;
    private final AtomicLong clockMicros = new AtomicLong();
    private final ArrayBlockingQueue<DecodedFrame> ring = new ArrayBlockingQueue<>(RING_CAPACITY);
    private volatile Thread worker;

    // Render-thread playback bookkeeping
    private long lastTickMillis = -1;
    private boolean ended;

    // GPU side (render thread only)
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private int videoWidth;
    private int videoHeight;

    private record DecodedFrame(int gen, long ptsMicros, NativeImage image) {
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /**
     * A view over any {@link FrameSource}. The opener runs on the decode thread, so it
     * may block on I/O; {@code sourceName} only labels log messages.
     */
    public VideoView(SourceOpener opener, String sourceName) {
        this.opener = opener;
        this.sourceName = sourceName;
        setSize(160, 90);
    }

    /**
     * Plays a video file from disk, dispatching on what {@code path} is: a directory or
     * {@code .zip} becomes a {@link FrameSequence} of its image files (set the rate with
     * {@link #withFps}; default {@value #DEFAULT_FPS}), anything else is opened as
     * {@link MjpegStream MJPEG} (AVI or raw stream).
     */
    public static VideoView fromFile(Path path) {
        if (Files.isDirectory(path)) {
            return new VideoView(() -> FrameSequence.fromDirectory(path, DEFAULT_FPS), path.toString());
        }
        if (path.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return new VideoView(() -> FrameSequence.fromZip(path, DEFAULT_FPS), path.toString());
        }
        return new VideoView(() -> MjpegStream.open(path), path.toString());
    }

    /** Plays an MJPEG video (AVI or raw stream) bundled in mod assets. */
    public static VideoView fromResource(ResourceLocation location) {
        return new VideoView(() -> {
            try (InputStream stream = Minecraft.getInstance().getResourceManager().open(location)) {
                return MjpegStream.of(stream.readAllBytes());
            }
        }, location.toString());
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /**
     * Overrides the frame rate — required for raw MJPEG streams (which carry no timing)
     * and the way to set a frame sequence's rate when using {@link #fromFile}. Takes
     * precedence over the container's own rate; must be called before playback starts.
     */
    public VideoView withFps(double fps) {
        if (fps <= 0 || !Double.isFinite(fps)) {
            throw new IllegalArgumentException("fps must be > 0, got " + fps);
        }
        this.fpsOverride = fps;
        return this;
    }

    /** Sets how frames map onto the widget bounds. Default {@link Fit#CONTAIN}. */
    public VideoView setFit(Fit fit) {
        this.fit = fit;
        return this;
    }

    public Fit getFit() {
        return fit;
    }

    /** Rounds the video's corners by {@code radius} GUI pixels (0 = square). */
    public VideoView setRadius(float radius) {
        this.radius = Math.max(0f, radius);
        return this;
    }

    public float getRadius() {
        return radius;
    }

    /** Shows the built-in play/pause + progress overlay (fades in on hover/pause). */
    public VideoView setShowControls(boolean showControls) {
        this.showControls = showControls;
        return this;
    }

    public boolean getShowControls() {
        return showControls;
    }

    /** Whether playback restarts from frame 0 at the end. Default {@code true}. */
    public VideoView setLoop(boolean loop) {
        this.loop = loop;
        return this;
    }

    public boolean isLooping() {
        return loop;
    }

    // ------------------------------------------------------------------
    // Playback control
    // ------------------------------------------------------------------

    /** Starts (or resumes) playback; restarts from frame 0 if the video had ended. */
    public VideoView play() {
        if (disposed) {
            return this;
        }
        long duration = durationMicros;
        if (!loop && duration > 0 && clockMicros.get() >= duration) {
            requestSeek(0);
        }
        ended = false;
        playing = true;
        ensureWorker();
        return this;
    }

    /** Pauses playback, freezing on the current frame. */
    public VideoView pause() {
        playing = false;
        return this;
    }

    /** Stops playback and rewinds to the first frame. */
    public VideoView stop() {
        playing = false;
        requestSeek(0);
        return this;
    }

    public boolean isPlaying() {
        return playing;
    }

    /** Current playback position in seconds (wraps each loop). */
    public double getTime() {
        long clock = clockMicros.get();
        long duration = durationMicros;
        if (duration > 0) {
            return (loop ? clock % duration : Math.min(clock, duration)) / 1_000_000.0;
        }
        return clock / 1_000_000.0;
    }

    /** Total length in seconds, or {@code -1} while unknown (raw streams pre-first-pass). */
    public double getDuration() {
        long duration = durationMicros;
        return duration > 0 ? duration / 1_000_000.0 : -1;
    }

    /** True once the source is open and supports random access (AVI idx1 / frame sequence). */
    public boolean isSeekable() {
        return sourceSeekable;
    }

    /**
     * Jumps to {@code seconds}, best-effort. Works when {@link #isSeekable()} — an AVI
     * with an {@code idx1} index jumps via the index, a frame sequence by index math.
     * On an unseekable source this is a no-op (except before the source has opened,
     * where the request is kept and applied if it turns out to be seekable).
     */
    public VideoView seek(double seconds) {
        if (disposed) {
            return this;
        }
        if (frameMicros != 0 && !sourceSeekable) {
            return this; // documented no-op
        }
        requestSeek(Math.max(0, seconds));
        return this;
    }

    /** Worker-failure reason, or {@code null}. The last good frame stays on screen. */
    public String getError() {
        return error;
    }

    private void requestSeek(double seconds) {
        generation++; // queued frames from before the seek are discarded by their stamp
        clockMicros.set((long) (seconds * 1_000_000));
        ended = false;
        pendingSeekSeconds = seconds;
        Thread w = worker;
        if (w != null) {
            LockSupport.unpark(w);
        }
    }

    /**
     * Permanently tears the view down: stops the decode worker, frees all buffered
     * frames and releases the GPU texture. Safe to call from any thread and more than
     * once; the view draws nothing afterwards.
     */
    public void dispose() {
        disposed = true;
        playing = false;
        Thread w = worker;
        if (w != null) {
            LockSupport.unpark(w);
        }
        Minecraft.getInstance().execute(() -> {
            // The worker also drains on exit; poll() makes the two drains race-safe.
            for (DecodedFrame frame; (frame = ring.poll()) != null; ) {
                frame.image().close();
            }
            releaseTexture();
        });
    }

    // ------------------------------------------------------------------
    // Rendering (render thread)
    // ------------------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics graphics, double mouseX, double mouseY, float delta) {
        if (disposed) {
            return;
        }
        ensureWorker(); // started on first sight so the poster frame appears without play()
        tickClock();
        drainRing();

        if (textureId != null) {
            drawVideo(graphics);
        } else {
            Render2D.fillRoundedRect(graphics, x, y, width, height, radius, theme().surfaceVariant);
            if (error == null) {
                drawLoadingArc(graphics);
            } else {
                float size = Math.min(Math.min(width, height) * 0.5f, 18f);
                Icons.WARNING.render(graphics, x + (width - size) / 2f, y + (height - size) / 2f,
                        size, theme().textMuted);
            }
        }
        if (error != null) {
            tooltip = error;
        }
        if (showControls && textureId != null) {
            drawControls(graphics, mouseX, mouseY);
        }
    }

    /** Advances the playback clock and handles the not-looping end-of-video stop. */
    private void tickClock() {
        long now = Util.getMillis();
        if (lastTickMillis >= 0 && playing && !ended) {
            // Clamp huge gaps (window unfocused) so the clock doesn't leap past whole loops
            long dt = Math.min(now - lastTickMillis, 250);
            if (dt > 0) {
                clockMicros.addAndGet(dt * 1000);
            }
        }
        lastTickMillis = now;

        long duration = durationMicros;
        if (!loop && duration > 0 && clockMicros.get() >= duration) {
            clockMicros.set(duration);
            playing = false;
            ended = true;
        }
    }

    /**
     * Takes the newest frame whose presentation time is due and uploads it; earlier due
     * frames are dropped (render-side frame drop), future frames stay queued, and frames
     * stamped before the last seek are discarded. Never waits: with nothing due, the
     * last uploaded frame simply stays on screen.
     */
    private void drainRing() {
        long clock = clockMicros.get();
        int gen = generation;
        DecodedFrame take = null;
        for (DecodedFrame head; (head = ring.peek()) != null; ) {
            if (head.gen() != gen) {
                ring.poll();
                head.image().close();
                continue;
            }
            if (head.ptsMicros() <= clock) {
                ring.poll();
                if (take != null) {
                    take.image().close(); // dropped: a newer frame is also already due
                }
                take = head;
                continue;
            }
            break;
        }
        if (take != null) {
            uploadFrame(take);
        }
    }

    /** Copies the frame's pixels into the one reused texture and uploads — no GL churn. */
    private void uploadFrame(DecodedFrame frame) {
        NativeImage image = frame.image();
        int w = image.getWidth();
        int h = image.getHeight();
        try {
            if (texture != null && (w != videoWidth || h != videoHeight)) {
                releaseTexture(); // dimension change (mixed frame sequence): rebuild once
            }
            if (texture == null) {
                DynamicTexture created = new DynamicTexture(image); // takes ownership + uploads
                created.setFilter(true, false); // linear sampling: video scales smoothly
                Minecraft.getInstance().getTextureManager().register(dynamicId, created);
                texture = created;
                textureId = dynamicId;
                videoWidth = w;
                videoHeight = h;
            } else {
                texture.getPixels().copyFrom(image);
                texture.upload();
                image.close();
            }
        } catch (Exception e) {
            image.close(); // NativeImage.close is idempotent, so the ownership paths are safe
            error = describe(e);
            LOGGER.warn("EasyGUI: video '{}' frame upload failed: {}", sourceName, error);
        }
    }

    private void releaseTexture() {
        if (textureId != null) {
            // Closes the DynamicTexture, which also closes its NativeImage
            Minecraft.getInstance().getTextureManager().release(textureId);
        }
        texture = null;
        textureId = null;
        videoWidth = 0;
        videoHeight = 0;
    }

    private void drawVideo(GuiGraphics graphics) {
        float imgW = videoWidth;
        float imgH = videoHeight;
        if (imgW <= 0 || imgH <= 0 || width <= 0 || height <= 0) {
            return;
        }
        int tint = 0xFFFFFFFF;
        switch (fit) {
            case STRETCH -> drawAt(graphics, x, y, width, height, tint);
            case CONTAIN -> {
                float scale = Math.min(width / imgW, height / imgH);
                float dw = imgW * scale;
                float dh = imgH * scale;
                drawAt(graphics, x + (width - dw) / 2f, y + (height - dh) / 2f, dw, dh, tint);
            }
            case COVER -> {
                float scale = Math.max(width / imgW, height / imgH);
                if (radius > 0f) {
                    // Crop in UV space so the rounded silhouette sits exactly on the bounds
                    float uHalf = width / (imgW * scale) / 2f;
                    float vHalf = height / (imgH * scale) / 2f;
                    texturedRoundedRectUV(graphics, textureId, x, y, width, height, radius,
                            0.5f - uHalf, 0.5f - vHalf, 0.5f + uHalf, 0.5f + vHalf, tint);
                } else {
                    float dw = imgW * scale;
                    float dh = imgH * scale;
                    Render2D.pushScissor(graphics, x, y, width, height);
                    Render2D.texturedRect(graphics, textureId, x + (width - dw) / 2f,
                            y + (height - dh) / 2f, dw, dh, tint);
                    Render2D.popScissor(graphics);
                }
            }
        }
    }

    private void drawAt(GuiGraphics graphics, float dx, float dy, float dw, float dh, int tint) {
        if (radius > 0f) {
            Render2D.texturedRoundedRect(graphics, textureId, dx, dy, dw, dh, radius, tint);
        } else {
            Render2D.texturedRect(graphics, textureId, dx, dy, dw, dh, tint);
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

    // ------------------------------------------------------------------
    // Control strip overlay (self-drawn; no child widgets)
    // ------------------------------------------------------------------

    private float controlsTop() {
        return y + height - CONTROLS_HEIGHT;
    }

    private float barLeft() {
        return x + 18f;
    }

    private float barRight() {
        return x + width - 7f;
    }

    private void drawControls(GuiGraphics graphics, double mouseX, double mouseY) {
        controlsAlpha.setTarget(isHovered() || !playing || draggingProgress ? 1f : 0f);
        float alpha = controlsAlpha.get();
        if (alpha <= 0.01f) {
            return;
        }
        float top = controlsTop();
        Render2D.pushAlpha(alpha);
        // Scrim with the widget's bottom corner rounding so it hugs the silhouette
        Render2D.fillRoundedRect(graphics, x, top, width, CONTROLS_HEIGHT, 0f, 0f, radius, radius,
                0xA80D0D12);

        // Play/pause glyph
        float gs = 7f;
        float gx = x + 5f;
        float gy = top + (CONTROLS_HEIGHT - gs) / 2f;
        if (playing) {
            Render2D.fillRoundedRect(graphics, gx, gy, 2.2f, gs, 0.8f, 0xFFFFFFFF);
            Render2D.fillRoundedRect(graphics, gx + 3.8f, gy, 2.2f, gs, 0.8f, 0xFFFFFFFF);
        } else {
            Render2D.fillPolygon(graphics, new float[]{
                    gx, gy,
                    gx + gs * 0.92f, gy + gs / 2f,
                    gx, gy + gs
            }, 0xFFFFFFFF);
        }

        // Progress bar (Slider-style: thin rounded track + accent fill)
        float bx0 = barLeft();
        float bx1 = barRight();
        if (bx1 > bx0 + 4f) {
            float trackH = 2.4f;
            float trackY = top + (CONTROLS_HEIGHT - trackH) / 2f;
            Render2D.fillRoundedRect(graphics, bx0, trackY, bx1 - bx0, trackH, trackH / 2f, 0x59FFFFFF);
            double duration = getDuration();
            if (duration > 0) {
                float t = (float) Mth.clamp(getTime() / duration, 0.0, 1.0);
                if (t > 0.002f) {
                    Render2D.fillRoundedRect(graphics, bx0, trackY, Math.max(trackH, (bx1 - bx0) * t),
                            trackH, trackH / 2f, theme().accent);
                }
                boolean overBar = mouseY >= top && mouseY < y + height
                        && mouseX >= bx0 - 3f && mouseX <= bx1 + 3f;
                if (isSeekable() && (overBar || draggingProgress)) {
                    Render2D.fillCircle(graphics, bx0 + (bx1 - bx0) * t, trackY + trackH / 2f,
                            2.6f, 0xFFFFFFFF);
                }
            }
        }
        Render2D.popAlpha();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled || disposed || button != 0 || !showControls || textureId == null
                || !contains(mouseX, mouseY) || mouseY < controlsTop()) {
            return false;
        }
        if (mouseX < barLeft() - 2f) {
            if (playing) {
                pause();
            } else {
                play();
            }
            return true;
        }
        if (isSeekable() && getDuration() > 0) {
            draggingProgress = true;
            seekFromMouse(mouseX);
        }
        return true; // strip clicks never fall through to the video underneath
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingProgress && button == 0) {
            seekFromMouse(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingProgress && button == 0) {
            draggingProgress = false;
            return true;
        }
        return false;
    }

    private void seekFromMouse(double mouseX) {
        double duration = getDuration();
        if (duration <= 0) {
            return;
        }
        double t = Mth.clamp((mouseX - barLeft()) / (barRight() - barLeft()), 0.0, 1.0);
        seek(t * duration);
    }

    // ------------------------------------------------------------------
    // Decode worker
    // ------------------------------------------------------------------

    private void ensureWorker() {
        if (worker != null || disposed) {
            return;
        }
        synchronized (this) {
            if (worker != null || disposed) {
                return;
            }
            Thread thread = new Thread(this::runWorker, "EasyGUI-Video-" + viewId);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            worker = thread;
            thread.start();
        }
    }

    /**
     * The decode loop. Owns the {@link FrameSource} exclusively. Maps an ever-growing
     * absolute frame index onto the source (modulo the frame count once known, which is
     * how looping works without any clock rewinding), drops frames by jumping the index
     * forward to whatever the render clock says is due, and parks whenever the ring is
     * full — so at most {@value #RING_CAPACITY} decoded frames ever exist at once.
     */
    private void runWorker() {
        FrameSource source = null;
        try {
            source = opener.open();
            double srcFps = source.fps();
            double override = fpsOverride;
            double fps = override > 0 ? override : (srcFps > 0 ? srcFps : DEFAULT_FPS);
            long fm = Math.max(1L, Math.round(1_000_000.0 / fps));
            frameMicros = fm;
            int count = source.frameCount();
            if (count > 0) {
                durationMicros = count * fm;
            }
            sourceSeekable = source.isSeekable();

            long nextAbs = 0;   // next absolute-timeline frame to decode
            int sourceNext = 0; // index of the frame the source will deliver next

            while (!disposed) {
                // Capture the seek generation BEFORE positioning/decoding: a seek that
                // lands mid-decode must leave this frame stamped stale (the render side
                // discards it), or a pre-seek frame with a far-future presentation time
                // could wedge the ring after a backward seek.
                int gen = generation;
                double seekSeconds = pendingSeekSeconds;
                if (seekSeconds >= 0) {
                    pendingSeekSeconds = -1;
                    nextAbs = (long) (seekSeconds * 1_000_000) / fm;
                    gen = generation; // bumped before the pending write, so this is current
                }
                if (ring.remainingCapacity() == 0) {
                    LockSupport.parkNanos(playing ? 4_000_000L : 25_000_000L);
                    continue;
                }

                // Frame drop: decode whatever the render clock is due for, skipping the rest
                long due = clockMicros.get() / fm;
                if (due > nextAbs) {
                    nextAbs = due;
                }

                int knownCount = source.frameCount();
                if (knownCount > 0 && !loop && nextAbs >= knownCount) {
                    LockSupport.parkNanos(20_000_000L); // at the end; wait for seek/dispose
                    continue;
                }

                int srcIdx = knownCount > 0 ? (int) (nextAbs % knownCount) : (int) nextAbs;
                sourceNext = positionSource(source, sourceNext, srcIdx);
                byte[] payload = source.nextFrame();
                if (payload == null) {
                    // True end of stream — the source has now learned its real frame count
                    if (sourceNext == 0) {
                        throw new IOException("Stream contains no video frames");
                    }
                    long total = (long) sourceNext * fm;
                    if (durationMicros != total) {
                        durationMicros = total;
                    }
                    if (!loop) {
                        LockSupport.parkNanos(20_000_000L);
                        continue;
                    }
                    source.reset();
                    sourceNext = 0;
                    continue; // re-map nextAbs against the refined frame count
                }
                sourceNext++;

                NativeImage image;
                try {
                    image = NativeImage.read(payload); // stb decode, fully off-thread
                } catch (IOException e) {
                    // One corrupt frame shouldn't kill playback; skip it
                    LOGGER.warn("EasyGUI: video '{}' dropped corrupt frame {}: {}",
                            sourceName, nextAbs, describe(e));
                    nextAbs++;
                    continue;
                }
                DecodedFrame frame = new DecodedFrame(gen, nextAbs * fm, image);
                if (!ring.offer(frame)) {
                    image.close(); // raced with a refill; drop rather than ever block
                }
                nextAbs++;
            }
        } catch (Throwable t) {
            if (!disposed) {
                error = describe(t);
                LOGGER.warn("EasyGUI: video '{}' failed: {}", sourceName, error);
            }
        } finally {
            if (disposed) {
                for (DecodedFrame frame; (frame = ring.poll()) != null; ) {
                    frame.image().close();
                }
            }
            if (source != null) {
                try {
                    source.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Moves the source so its next frame is {@code target}: random access when supported,
     * otherwise rewind-and-skip (header-only parsing, no decoding). Returns the source's
     * actual next index, which falls short of {@code target} only at end of stream.
     */
    private static int positionSource(FrameSource source, int sourceNext, int target) throws IOException {
        if (target == sourceNext) {
            return sourceNext;
        }
        if (source.seekToFrame(target)) {
            return target;
        }
        if (target < sourceNext) {
            source.reset();
            sourceNext = 0;
        }
        while (sourceNext < target && source.skipFrame()) {
            sourceNext++;
        }
        return sourceNext;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------------
    // Rounded textured quad with an explicit UV window (for COVER cropping)
    // ------------------------------------------------------------------

    /**
     * Like {@link Render2D#texturedRoundedRect} but with the UVs mapped across a
     * {@code [u0..u1, v0..v1]} sub-window of the texture — what COVER cropping inside a
     * rounded silhouette needs (full-rect UVs would distort; scissor would cut the
     * corners square). Mirrors Render2D's feathered-perimeter tessellation, duplicated
     * from {@link ImageView}; both copies are candidates to fold into Render2D.
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
