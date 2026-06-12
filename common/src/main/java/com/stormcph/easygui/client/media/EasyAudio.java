package com.stormcph.easygui.client.media;

import com.mojang.logging.LogUtils;
import dev.architectury.event.events.client.ClientLifecycleEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A tiny static facade for playing {@link AudioClip}s: UI clicks, notification pings,
 * menu jingles. {@code EasyAudio.play(clip)} returns a {@link PlaybackHandle} you can
 * adjust ({@link PlaybackHandle#setVolume}, {@link PlaybackHandle#setPitch},
 * {@link PlaybackHandle#setLooping}) or {@link PlaybackHandle#stop()}.
 *
 * <p><b>This deliberately bypasses Minecraft's SoundManager.</b> There is no positional
 * audio, no attenuation, no sounds.json registration and no subtitle support — sources
 * play head-locked at unit distance, which is exactly what GUI/menu sounds want. Anything
 * that should exist in the world should go through vanilla's sound system instead.
 * Because this is raw OpenAL, <em>you</em> own the native resources: {@link AudioClip#close()}
 * clips you no longer need; finished sources are reaped automatically.</p>
 *
 * <h2>Threading</h2>
 * <p>Every OpenAL call EasyGUI makes is funneled through one dedicated daemon thread
 * ({@code EasyGUI-Audio}), mirroring how vanilla serializes its own AL calls on its
 * "Sound engine" thread (see {@code com.mojang.blaze3d.audio.Library} /
 * {@code net.minecraft.client.sounds.SoundEngine}). This is safe because vanilla's
 * {@code Library.init} makes the ALC context current <em>process-wide</em> (plain
 * {@code alcMakeContextCurrent}; the thread-local-context extension is not used) and
 * installs process-wide ALCapabilities, and OpenAL-Soft — the implementation LWJGL ships
 * with Minecraft — explicitly supports concurrent AL calls from multiple threads. Our
 * calls are ordered amongst themselves by the single thread, and we only ever touch
 * sources/buffers we created, so we never race vanilla on shared object state. Nothing
 * here ever blocks the render thread. Before any AL work we verify a context exists
 * ({@code alcGetCurrentContext() != NULL}); with no audio device, clips land in
 * {@link AudioClip.State#ERROR} and playback is a silent no-op — never a crash.</p>
 *
 * <h2>Volume</h2>
 * <p>{@link #setMasterVolume(float)} scales every EasyGUI sound (live handles update
 * immediately). {@link #respectGameVolume(boolean)} additionally multiplies by the
 * player's Master sound category volume from the options screen. Note that while the
 * vanilla sound engine is running it also drives the global OpenAL <em>listener</em>
 * gain with that same Master slider, which already affects all sources including ours;
 * enable this option when you want EasyGUI sounds to track the slider even more strongly
 * (applied per-source as well), or when another mod overrides the listener gain.</p>
 *
 * <p>Source bookkeeping follows the {@link com.stormcph.easygui.client.config.EasyConfig}
 * bootstrap pattern: the first EasyAudio/AudioClip use lazily registers a
 * {@link ClientTickEvent} that reaps finished sources, retries uploads that arrived
 * before the OpenAL context existed, and refreshes gains — and every public EasyAudio
 * call also reaps lazily, so AL sources never leak even without ticks.</p>
 *
 * <pre>{@code
 * private static final AudioClip CLICK =
 *         AudioClip.fromResource(ResourceLocation.fromNamespaceAndPath("mymod", "sounds/click.ogg"));
 *
 * // later, e.g. in a Button callback — safe even while CLICK is still loading
 * EasyAudio.play(CLICK).setVolume(0.6f).setPitch(1.1f);
 * }</pre>
 */
@Environment(EnvType.CLIENT)
public final class EasyAudio {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** How long a decoded clip waits for an OpenAL context before failing. */
    private static final long UPLOAD_RETRY_WINDOW_MILLIS = 15_000L;
    /** Minimum interval between lazy (non-tick) reap passes. */
    private static final long LAZY_REAP_INTERVAL_MILLIS = 250L;

    /** The single thread all EasyGUI OpenAL calls run on (see class javadoc). */
    private static final ExecutorService AL_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "EasyGUI-Audio");
        thread.setDaemon(true);
        return thread;
    });

    /** Live (playing or starting) handles; small list, iterated on tick. */
    private static final List<PlaybackHandle> HANDLES = new CopyOnWriteArrayList<>();
    /** Clips decoded before the OpenAL context existed, waiting to upload. */
    private static final Queue<AudioClip> PENDING_UPLOADS = new ConcurrentLinkedQueue<>();

    private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean();
    private static volatile float masterVolume = 1f;
    private static volatile boolean respectGameVolume;
    /** Master sound-category volume, sampled on the client thread each tick. */
    private static volatile float gameMasterVolume = 1f;
    private static volatile long lastReapMillis;
    private static volatile boolean warnedNoContext;

    private EasyAudio() {
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Starts playing {@code clip} and returns a handle for live control. Safe in every
     * clip state: a {@link AudioClip.State#LOADING} clip starts sounding the moment it
     * becomes ready, and an {@link AudioClip.State#ERROR}/closed clip returns an inert,
     * already-finished handle. Never blocks.
     */
    public static PlaybackHandle play(AudioClip clip) {
        Objects.requireNonNull(clip, "clip");
        ensureBootstrap();
        PlaybackHandle handle = new PlaybackHandle(clip);
        if (clip.isClosed() || clip.getState() == AudioClip.State.ERROR) {
            handle.finished = true;
            return handle;
        }
        HANDLES.add(handle);
        if (clip.alBuffer != 0) {
            handle.submitRealize();
        }
        lazyReap();
        return handle;
    }

    /** Stops every live EasyGUI sound and releases their sources. */
    public static void stopAll() {
        ensureBootstrap();
        for (PlaybackHandle handle : HANDLES) {
            handle.stopRequested = true;
        }
        onAudioThread(() -> {
            for (PlaybackHandle handle : HANDLES) {
                handle.destroyOnAudioThread();
            }
        });
    }

    /** Global multiplier (0..1) applied to all EasyGUI sounds, live ones included. */
    public static void setMasterVolume(float volume) {
        ensureBootstrap();
        masterVolume = Mth.clamp(volume, 0f, 1f);
        submitGainRefresh();
        lazyReap();
    }

    public static float getMasterVolume() {
        return masterVolume;
    }

    /**
     * When {@code true}, every EasyGUI sound is additionally multiplied by the player's
     * Master sound category volume ({@code options.getSoundSourceVolume(SoundSource.MASTER)}).
     * Defaults to {@code false} — see the class javadoc for how this interacts with the
     * listener gain vanilla already applies. Live handles update within a tick.
     */
    public static void respectGameVolume(boolean respect) {
        ensureBootstrap();
        respectGameVolume = respect;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.isSameThread() && minecraft.options != null) {
            gameMasterVolume = minecraft.options.getSoundSourceVolume(SoundSource.MASTER);
        }
        submitGainRefresh();
        lazyReap();
    }

    public static boolean isRespectingGameVolume() {
        return respectGameVolume;
    }

    /** True when an OpenAL context exists (i.e. the game has a working audio device). */
    public static boolean isAvailable() {
        return alAvailable();
    }

    // ------------------------------------------------------------------
    // Bootstrap (lazy, EasyConfig-style)
    // ------------------------------------------------------------------

    private static void ensureBootstrap() {
        if (!BOOTSTRAPPED.compareAndSet(false, true)) {
            return;
        }
        // Architectury event registration is not thread-safe; hop to the client thread
        // when the first use comes from a background (e.g. decode) thread.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && !minecraft.isSameThread()) {
            minecraft.execute(EasyAudio::registerEvents);
        } else {
            registerEvents();
        }
    }

    private static void registerEvents() {
        ClientTickEvent.CLIENT_POST.register(EasyAudio::tick);
        ClientLifecycleEvent.CLIENT_STOPPING.register(minecraft -> stopAll());
    }

    /** Runs on the client thread every tick once bootstrapped. */
    private static void tick(Minecraft minecraft) {
        // Sample the option on the client thread; the audio thread only reads the cache.
        if (minecraft.options != null) {
            gameMasterVolume = minecraft.options.getSoundSourceVolume(SoundSource.MASTER);
        }
        processPendingUploads();
        if (!HANDLES.isEmpty()) {
            lastReapMillis = System.currentTimeMillis();
            onAudioThread(EasyAudio::maintainOnAudioThread);
        }
    }

    /** Throttled reap used by the public entry points ("on the next EasyAudio call"). */
    private static void lazyReap() {
        long now = System.currentTimeMillis();
        if (now - lastReapMillis < LAZY_REAP_INTERVAL_MILLIS) {
            return;
        }
        lastReapMillis = now;
        processPendingUploads();
        if (!HANDLES.isEmpty()) {
            onAudioThread(EasyAudio::maintainOnAudioThread);
        }
    }

    // ------------------------------------------------------------------
    // Clip upload plumbing (called by AudioClip)
    // ------------------------------------------------------------------

    /** Called from the decode thread once a clip's PCM is ready for the AL upload. */
    static void enqueueUpload(AudioClip clip) {
        ensureBootstrap();
        if (alAvailable()) {
            onAudioThread(() -> {
                clip.uploadOnAudioThread();
                startWaitersFor(clip);
            });
        } else {
            // Sound engine not up yet (early init) or no audio device: retry from the
            // tick handler until the context appears, then fail with a clear error.
            clip.uploadDeadlineMillis = System.currentTimeMillis() + UPLOAD_RETRY_WINDOW_MILLIS;
            PENDING_UPLOADS.add(clip);
            if (!warnedNoContext) {
                warnedNoContext = true;
                LOGGER.warn("EasyGUI: no OpenAL context yet; queued audio clip '{}' for retry", clip.getName());
            }
        }
    }

    /** Stops sources using {@code clip}, deletes its AL buffer, frees pending PCM. */
    static void releaseClip(AudioClip clip) {
        ensureBootstrap();
        PENDING_UPLOADS.remove(clip);
        onAudioThread(() -> {
            for (PlaybackHandle handle : HANDLES) {
                if (handle.clip == clip) {
                    handle.destroyOnAudioThread();
                }
            }
            clip.freePendingPcm();
            int buffer = clip.alBuffer;
            if (buffer != 0) {
                clip.alBuffer = 0;
                AL10.alDeleteBuffers(buffer);
                drainAlErrors();
            }
        });
    }

    private static void processPendingUploads() {
        if (PENDING_UPLOADS.isEmpty()) {
            return;
        }
        if (alAvailable()) {
            AudioClip clip;
            while ((clip = PENDING_UPLOADS.poll()) != null) {
                AudioClip pending = clip;
                onAudioThread(() -> {
                    pending.uploadOnAudioThread();
                    startWaitersFor(pending);
                });
            }
        } else {
            long now = System.currentTimeMillis();
            for (Iterator<AudioClip> it = PENDING_UPLOADS.iterator(); it.hasNext(); ) {
                AudioClip clip = it.next();
                if (now > clip.uploadDeadlineMillis) {
                    it.remove();
                    clip.failLoad("no OpenAL context (audio device missing or sound engine disabled)", null);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Audio-thread internals
    // ------------------------------------------------------------------

    /** Submits {@code task} to the audio thread, shielding it so the thread never dies. */
    private static void onAudioThread(Runnable task) {
        AL_EXECUTOR.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.error("EasyGUI: audio task failed", t);
            }
        });
    }

    /** Starts handles that were played while {@code clip} was still loading. */
    private static void startWaitersFor(AudioClip clip) {
        for (PlaybackHandle handle : HANDLES) {
            if (handle.clip != clip || handle.source != 0) {
                continue;
            }
            if (clip.alBuffer == 0) { // upload failed
                handle.finished = true;
                HANDLES.remove(handle);
            } else if (handle.realizeSubmitted.compareAndSet(false, true)) {
                handle.realizeOnAudioThread();
            }
        }
    }

    /** Reaps stopped sources, starts late clips, refreshes gains. Audio thread only. */
    private static void maintainOnAudioThread() {
        boolean al = alAvailable();
        for (PlaybackHandle handle : HANDLES) {
            if (handle.finished) {
                HANDLES.remove(handle);
                continue;
            }
            if (handle.source == 0) {
                // Not started yet: drop it if its clip can never play, start it if ready
                AudioClip clip = handle.clip;
                if (clip.getState() == AudioClip.State.ERROR || clip.isClosed()) {
                    handle.finished = true;
                    HANDLES.remove(handle);
                } else if (al && clip.alBuffer != 0 && handle.realizeSubmitted.compareAndSet(false, true)) {
                    handle.realizeOnAudioThread();
                }
                continue;
            }
            if (!al) {
                continue;
            }
            int state = AL10.alGetSourcei(handle.source, AL10.AL_SOURCE_STATE);
            if (state == AL10.AL_STOPPED) {
                AL10.alDeleteSources(handle.source);
                handle.source = 0;
                handle.playing = false;
                handle.finished = true;
                HANDLES.remove(handle);
            } else {
                AL10.alSourcef(handle.source, AL10.AL_GAIN, handle.effectiveGain());
            }
        }
        if (al) {
            drainAlErrors();
        }
    }

    private static void submitGainRefresh() {
        onAudioThread(() -> {
            if (!alAvailable()) {
                return;
            }
            for (PlaybackHandle handle : HANDLES) {
                if (handle.source != 0 && !handle.finished) {
                    AL10.alSourcef(handle.source, AL10.AL_GAIN, handle.effectiveGain());
                }
            }
            drainAlErrors();
        });
    }

    /**
     * Whether AL calls may be made: Minecraft's sound library has created its context
     * (process-current, see class javadoc). Defensive against missing natives too.
     */
    static boolean alAvailable() {
        try {
            return ALC10.alcGetCurrentContext() != MemoryUtil.NULL;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Clears any stale AL error state so the next check is attributable. */
    static void drainAlErrors() {
        try {
            for (int i = 0; i < 16 && AL10.alGetError() != AL10.AL_NO_ERROR; i++) {
                // keep draining
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Playback handle
    // ------------------------------------------------------------------

    /**
     * Live control over one playing sound. Setters are chainable and safe from any
     * thread at any time — before the source exists (the values apply when it starts),
     * while playing, or after it finished (no-ops). Handles clean themselves up; just
     * drop the reference when you stop caring.
     */
    @Environment(EnvType.CLIENT)
    public static final class PlaybackHandle {
        final AudioClip clip;
        final AtomicBoolean realizeSubmitted = new AtomicBoolean();

        /** AL source name; 0 before start and after reaping. Audio thread writes only. */
        volatile int source;
        volatile boolean playing;
        volatile boolean finished;
        volatile boolean stopRequested;
        private volatile float volume = 1f;
        private volatile float pitch = 1f;
        private volatile boolean looping;

        PlaybackHandle(AudioClip clip) {
            this.clip = clip;
        }

        public AudioClip getClip() {
            return clip;
        }

        /** Per-sound volume, 0..1 (multiplied by the EasyAudio master volume). */
        public PlaybackHandle setVolume(float volume) {
            this.volume = Mth.clamp(volume, 0f, 1f);
            submitApply();
            return this;
        }

        public float getVolume() {
            return volume;
        }

        /** Playback speed/pitch; 1 is normal, clamped to 0.05..4. */
        public PlaybackHandle setPitch(float pitch) {
            this.pitch = Mth.clamp(pitch, 0.05f, 4f);
            submitApply();
            return this;
        }

        public float getPitch() {
            return pitch;
        }

        /** Loops until {@link #stop()} or the clip is closed. */
        public PlaybackHandle setLooping(boolean looping) {
            this.looping = looping;
            submitApply();
            return this;
        }

        public boolean isLooping() {
            return looping;
        }

        /** Stops the sound and releases its source. Idempotent. */
        public void stop() {
            stopRequested = true;
            onAudioThread(this::destroyOnAudioThread);
        }

        /**
         * True while the sound is audible or still starting up (clip loading). Reflects
         * the most recent poll of the source, so a just-finished sound may read playing
         * for a fraction of a tick.
         */
        public boolean isPlaying() {
            if (finished || stopRequested) {
                return false;
            }
            if (source == 0) {
                return clip.getState() != AudioClip.State.ERROR && !clip.isClosed();
            }
            return playing;
        }

        float effectiveGain() {
            float gain = volume * masterVolume;
            if (respectGameVolume) {
                gain *= gameMasterVolume;
            }
            return Mth.clamp(gain, 0f, 1f);
        }

        void submitRealize() {
            if (realizeSubmitted.compareAndSet(false, true)) {
                onAudioThread(this::realizeOnAudioThread);
            }
        }

        /** Creates and starts the AL source. Audio thread only; every call checked. */
        void realizeOnAudioThread() {
            if (finished || source != 0) {
                return;
            }
            if (stopRequested || clip.alBuffer == 0 || clip.isClosed() || !alAvailable()) {
                finished = true;
                HANDLES.remove(this);
                return;
            }
            drainAlErrors();
            int src = AL10.alGenSources();
            if (AL10.alGetError() != AL10.AL_NO_ERROR || src == 0) {
                LOGGER.warn("EasyGUI: alGenSources failed; cannot play '{}'", clip.getName());
                finished = true;
                HANDLES.remove(this);
                return;
            }
            AL10.alSourcei(src, AL10.AL_BUFFER, clip.alBuffer);
            // Head-locked UI sound: relative to the listener, at the origin
            AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(src, AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alSourcef(src, AL10.AL_GAIN, effectiveGain());
            AL10.alSourcef(src, AL10.AL_PITCH, pitch);
            AL10.alSourcei(src, AL10.AL_LOOPING, looping ? AL10.AL_TRUE : AL10.AL_FALSE);
            AL10.alSourcePlay(src);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                AL10.alDeleteSources(src);
                drainAlErrors();
                LOGGER.warn("EasyGUI: failed to start playback of '{}'", clip.getName());
                finished = true;
                HANDLES.remove(this);
                return;
            }
            source = src;
            playing = true;
        }

        /** Pushes the current volume/pitch/looping to a live source. */
        private void submitApply() {
            if (finished) {
                return;
            }
            onAudioThread(() -> {
                if (source == 0 || finished || !alAvailable()) {
                    return;
                }
                AL10.alSourcef(source, AL10.AL_GAIN, effectiveGain());
                AL10.alSourcef(source, AL10.AL_PITCH, pitch);
                AL10.alSourcei(source, AL10.AL_LOOPING, looping ? AL10.AL_TRUE : AL10.AL_FALSE);
                drainAlErrors();
            });
        }

        /** Stops and deletes the source. Audio thread only; idempotent. */
        void destroyOnAudioThread() {
            if (finished && source == 0) {
                HANDLES.remove(this);
                return;
            }
            int src = source;
            if (src != 0) {
                source = 0;
                try {
                    AL10.alSourceStop(src);
                    AL10.alDeleteSources(src);
                    drainAlErrors();
                } catch (Throwable ignored) {
                }
            }
            playing = false;
            finished = true;
            HANDLES.remove(this);
        }
    }
}
