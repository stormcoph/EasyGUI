package com.stormcph.easygui.client.media;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous image loading and GPU texture lifecycle for EasyGUI media widgets.
 *
 * <p>Images are decoded with {@link NativeImage#read} (stb_image, which Minecraft already
 * bundles — PNG, JPEG, TGA, BMP) on a background thread, then uploaded as a
 * {@link DynamicTexture} on the render thread under a generated
 * {@code easygui:dynamic/img_<n>} id. The render thread is never blocked: callers get a
 * {@link Handle} back immediately and poll its {@link Handle#state()} each frame, drawing
 * a loading/error placeholder until it flips to {@link State#READY}.</p>
 *
 * <p>Three sources are supported:</p>
 * <ul>
 *   <li>{@link #fromResource} — mod assets, read through the active resource manager.</li>
 *   <li>{@link #fromFile} — an image file on disk.</li>
 *   <li>{@link #fromUrl} — an {@code http(s)} URL, downloaded with a 10&nbsp;second
 *       timeout and a {@value #MAX_DOWNLOAD_MB}&nbsp;MB size cap.</li>
 * </ul>
 *
 * <p><b>Ownership and caching.</b> Resource and file handles are owned by the caller:
 * call {@link Handle#close()} when the image is permanently no longer needed. URL handles
 * are different — screens rebuild constantly, and re-downloading (or even re-decoding) an
 * avatar on every rebuild would be wasteful, so URL results are cached in a static map:
 * the same URL always returns the <em>same shared handle</em>, with no refcounting —
 * a cached texture simply stays resident until {@link #release(String)} or
 * {@link #releaseAll()} is called explicitly. Consequently {@link Handle#close()} is a
 * deliberate no-op on shared handles, so a widget being thrown away on a screen rebuild
 * can never pull a cached texture out from under other users. Failed URL loads are cached
 * too (so a dead server isn't hammered once per rebuild); {@code release(url)} clears the
 * entry, allowing a retry.</p>
 *
 * <p>The decode-off-thread / upload-on-render-thread pattern here is intentionally
 * reusable: a video widget can follow the same shape, swapping the one-shot decode for a
 * frame pump that calls {@link DynamicTexture#upload()} per frame.</p>
 */
@Environment(EnvType.CLIENT)
public final class ImageLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Download size cap for {@link #fromUrl}, in megabytes. */
    static final int MAX_DOWNLOAD_MB = 32;
    private static final long MAX_DOWNLOAD_BYTES = MAX_DOWNLOAD_MB * 1024L * 1024L;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final Map<String, Handle> URL_CACHE = new ConcurrentHashMap<>();
    private static volatile HttpClient httpClient;

    private ImageLoader() {
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /**
     * Loads an image from mod assets (e.g. {@code mymod:textures/gui/banner.png}).
     * Unlike binding the resource directly, this decodes the file once to learn its
     * pixel dimensions, which fit modes like CONTAIN/COVER need for exact aspect math.
     * The caller owns the returned handle and should {@link Handle#close()} it when done.
     */
    public static Handle fromResource(ResourceLocation location) {
        return load(location.toString(), false, () -> {
            try (InputStream stream = Minecraft.getInstance().getResourceManager().open(location)) {
                return NativeImage.read(stream);
            }
        });
    }

    /**
     * Loads an image file from disk. The caller owns the returned handle and should
     * {@link Handle#close()} it when done.
     */
    public static Handle fromFile(Path path) {
        return load(path.toString(), false, () -> {
            try (InputStream stream = Files.newInputStream(path)) {
                return NativeImage.read(stream);
            }
        });
    }

    /**
     * Loads an image from an {@code http(s)} URL. Results are cached: the same URL always
     * returns the same shared handle (see the class javadoc for the lifecycle rules), and
     * {@link Handle#close()} is a no-op on it — free it explicitly with
     * {@link #release(String)} if ever needed.
     */
    public static Handle fromUrl(String url) {
        return URL_CACHE.computeIfAbsent(url, u -> load(u, true, () -> NativeImage.read(download(u))));
    }

    /**
     * Evicts a cached URL image and releases its texture (also clears cached failures so
     * the next {@link #fromUrl} retries). Safe to call for URLs that were never loaded.
     * Handles still held by widgets flip to {@link State#CLOSED} and draw as placeholders.
     */
    public static void release(String url) {
        Handle handle = URL_CACHE.remove(url);
        if (handle != null) {
            handle.forceClose();
        }
    }

    /** Releases every cached URL image (e.g. when tearing the whole UI down). */
    public static void releaseAll() {
        for (String url : URL_CACHE.keySet()) {
            release(url);
        }
    }

    private static Handle load(String source, boolean shared, ImageSupplier decoder) {
        Handle handle = new Handle(source, shared);
        CompletableFuture.runAsync(() -> {
            NativeImage image;
            try {
                image = decoder.get();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                handle.fail(describe(e));
                return;
            }
            // Texture creation talks to OpenGL; hop to the render thread for the upload.
            Minecraft.getInstance().execute(() -> handle.upload(image));
        }, Util.backgroundExecutor());
        return handle;
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    private static byte[] download(String url) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Unsupported URL scheme (need http/https): " + url);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", "EasyGUI (Minecraft client mod)")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode());
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > MAX_DOWNLOAD_BYTES) {
                throw new IOException("Image is " + declared + " bytes, over the " + MAX_DOWNLOAD_MB + " MB limit");
            }
            return readCapped(body);
        }
    }

    /** Reads the whole stream, aborting as soon as the size cap is exceeded. */
    private static byte[] readCapped(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] chunk = new byte[16 * 1024];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            out.write(chunk, 0, read);
            if (out.size() > MAX_DOWNLOAD_BYTES) {
                throw new IOException("Download exceeded the " + MAX_DOWNLOAD_MB + " MB limit");
            }
        }
        return out.toByteArray();
    }

    private static HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (ImageLoader.class) {
                client = httpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(HTTP_TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    httpClient = client;
                }
            }
        }
        return client;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    /** Like {@link java.util.function.Supplier} but allowed to throw (IO, decode errors). */
    @FunctionalInterface
    private interface ImageSupplier {
        NativeImage get() throws Exception;
    }

    // ------------------------------------------------------------------
    // Handle
    // ------------------------------------------------------------------

    /** Lifecycle of a {@link Handle}. */
    @Environment(EnvType.CLIENT)
    public enum State {
        /** Decode/download still in flight; draw a loading placeholder. */
        LOADING,
        /** Texture registered and ready to draw via {@link Handle#textureId()}. */
        READY,
        /** Load failed; {@link Handle#error()} has the reason. */
        ERROR,
        /** Texture released; the handle will never become drawable again. */
        CLOSED
    }

    /**
     * A loaded (or in-flight) image. Poll {@link #state()} each frame; once {@link State#READY},
     * draw {@link #textureId()} and use {@link #width()}/{@link #height()} for aspect math.
     * All accessors are safe from the render thread at any point in the load.
     */
    @Environment(EnvType.CLIENT)
    public static final class Handle {
        private final String source;
        private final boolean shared;
        private volatile State state = State.LOADING;
        private volatile ResourceLocation textureId;
        private volatile int width;
        private volatile int height;
        private volatile String error;

        Handle(String source, boolean shared) {
            this.source = source;
            this.shared = shared;
        }

        public State state() {
            return state;
        }

        public boolean isReady() {
            return state == State.READY;
        }

        /** The registered texture id, or {@code null} unless {@link State#READY}. */
        public ResourceLocation textureId() {
            return textureId;
        }

        /** Image width in pixels (0 until {@link State#READY}). */
        public int width() {
            return width;
        }

        /** Image height in pixels (0 until {@link State#READY}). */
        public int height() {
            return height;
        }

        /** Human-readable failure reason, or {@code null} unless {@link State#ERROR}. */
        public String error() {
            return error;
        }

        /** The source this handle was loaded from (resource id, file path, or URL). */
        public String source() {
            return source;
        }

        /** Whether this handle lives in the shared URL cache (see the class javadoc). */
        public boolean isShared() {
            return shared;
        }

        /**
         * Releases the texture (GPU and pixel memory) for a caller-owned handle. On shared
         * URL-cache handles this is a <em>no-op by design</em> — widgets call this freely on
         * screen rebuild without disturbing the cache; use {@link ImageLoader#release(String)}
         * to actually free a cached URL. Safe to call from any thread, and more than once.
         */
        public void close() {
            if (!shared) {
                forceClose();
            }
        }

        /** Actually releases the texture, shared or not. Internal: cache eviction uses this. */
        void forceClose() {
            // TextureManager touches GL state; execute() runs inline if already on the render thread.
            Minecraft.getInstance().execute(() -> {
                synchronized (this) {
                    if (state == State.CLOSED) {
                        return;
                    }
                    if (state == State.READY && textureId != null) {
                        // Closes the DynamicTexture, which also closes its NativeImage.
                        Minecraft.getInstance().getTextureManager().release(textureId);
                    }
                    // If still LOADING, upload() will see CLOSED and discard the decoded image.
                    textureId = null;
                    state = State.CLOSED;
                }
            });
        }

        synchronized void fail(String message) {
            if (state == State.CLOSED) {
                return;
            }
            error = message;
            state = State.ERROR;
            LOGGER.warn("EasyGUI: failed to load image '{}': {}", source, message);
        }

        /** Render thread only: wraps the decoded pixels in a texture and flips to READY. */
        synchronized void upload(NativeImage image) {
            if (state == State.CLOSED) {
                image.close();
                return;
            }
            try {
                DynamicTexture texture = new DynamicTexture(image);
                texture.setFilter(true, false); // linear sampling: images scale smoothly
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath("easygui",
                        "dynamic/img_" + NEXT_ID.getAndIncrement());
                Minecraft.getInstance().getTextureManager().register(id, texture);
                width = image.getWidth();
                height = image.getHeight();
                textureId = id;
                state = State.READY;
            } catch (Exception e) {
                image.close();
                fail(describe(e));
            }
        }
    }
}
