package com.stormcph.easygui.client.media;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.openal.AL10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A fully decoded sound effect (WAV or OGG Vorbis) held in a single OpenAL buffer,
 * ready to be played through {@link EasyAudio} — the UI/menu-sound counterpart to a
 * texture: load once, play many times.
 *
 * <p>Decoding never touches the render thread. {@link #fromResource}, {@link #fromFile}
 * and {@link #fromBytes} return immediately with a clip in the {@link State#LOADING}
 * state; bytes are read and decoded on {@link Util#backgroundExecutor()}, and the PCM is
 * then uploaded to OpenAL on EasyGUI's dedicated audio thread (see {@link EasyAudio} for
 * the threading rationale). The clip flips to {@link State#READY} when playable or
 * {@link State#ERROR} on any failure (bad file, unsupported codec, no audio device…) —
 * it never throws, and playing a clip that is still loading simply starts the sound as
 * soon as it becomes ready.</p>
 *
 * <p>Supported formats, using only decoders Minecraft already ships:</p>
 * <ul>
 *   <li><b>WAV</b> — RIFF/WAVE parsed in pure Java; uncompressed PCM, 8-bit or 16-bit,
 *       mono or stereo (other codecs such as IEEE float, A-law, ADPCM are rejected with
 *       a clear error).</li>
 *   <li><b>OGG Vorbis</b> — decoded with {@code stb_vorbis} via LWJGL
 *       ({@link STBVorbis#stb_vorbis_decode_memory}); mono or stereo.</li>
 * </ul>
 *
 * <p>Clips own a native OpenAL buffer: call {@link #close()} when a clip is no longer
 * needed (it also stops any playback still using it). Clips are whole-file in-memory
 * decodes, intended for short UI sounds — clicks, notifications, jingles — not for
 * streaming music.</p>
 */
@Environment(EnvType.CLIENT)
public final class AudioClip implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int TAG_RIFF = tag('R', 'I', 'F', 'F');
    private static final int TAG_WAVE = tag('W', 'A', 'V', 'E');
    private static final int TAG_FMT = tag('f', 'm', 't', ' ');
    private static final int TAG_DATA = tag('d', 'a', 't', 'a');
    private static final int TAG_OGGS = tag('O', 'g', 'g', 'S');
    private static final int WAVE_FORMAT_PCM = 0x0001;
    private static final int WAVE_FORMAT_EXTENSIBLE = 0xFFFE;

    /** Lifecycle of a clip. Terminal states are {@code READY} and {@code ERROR}. */
    public enum State {
        /** Bytes are being read/decoded/uploaded in the background. */
        LOADING,
        /** The OpenAL buffer exists; the clip is playable. */
        READY,
        /** Loading failed (see {@link #getError()}); playing it is a silent no-op. */
        ERROR
    }

    private final String name;
    private volatile State state = State.LOADING;
    private volatile String error;
    private volatile boolean closed;
    private volatile double durationSeconds;
    private volatile int channels;
    private volatile int sampleRate;

    /** OpenAL buffer name; 0 until uploaded. Written only on the audio thread. */
    volatile int alBuffer;
    /** Decoded PCM awaiting AL upload; guarded by {@code this} (see {@link #takePendingPcm()}). */
    private Pcm pendingPcm;
    /** Deadline for the "OpenAL context not ready yet" retry loop (see EasyAudio). */
    volatile long uploadDeadlineMillis;

    private AudioClip(String name) {
        this.name = name;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /** Loads a WAV/OGG shipped in mod assets or a resource pack. Returns immediately ({@link State#LOADING}). */
    public static AudioClip fromResource(ResourceLocation location) {
        AudioClip clip = new AudioClip(location.toString());
        decodeAsync(clip, () -> {
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(location)) {
                return in.readAllBytes();
            }
        }, hintFromName(location.getPath()));
        return clip;
    }

    /** Loads a WAV/OGG from disk. Returns immediately ({@link State#LOADING}). */
    public static AudioClip fromFile(Path path) {
        AudioClip clip = new AudioClip(path.toString());
        decodeAsync(clip, () -> Files.readAllBytes(path), hintFromName(path.getFileName().toString()));
        return clip;
    }

    /**
     * Decodes raw file bytes. {@code formatHint} ("wav" or "ogg", case-insensitive, with
     * or without a leading dot) is only consulted when the magic bytes are inconclusive;
     * it may be {@code null}. The array is copied, so the caller may reuse it.
     */
    public static AudioClip fromBytes(byte[] data, String formatHint) {
        int length = data != null ? data.length : 0;
        AudioClip clip = new AudioClip("bytes[" + length + "]");
        byte[] copy = data != null ? data.clone() : new byte[0];
        decodeAsync(clip, () -> copy, formatHint);
        return clip;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    public State getState() {
        return state;
    }

    /** Convenience for {@code getState() == State.READY && !isClosed()}. */
    public boolean isReady() {
        return state == State.READY && !closed;
    }

    /** Human-readable failure reason while in {@link State#ERROR}, otherwise {@code null}. */
    public String getError() {
        return error;
    }

    /** Clip length in seconds; {@code 0} until decoding has finished. */
    public double getDurationSeconds() {
        return durationSeconds;
    }

    /** Channel count (1 or 2); {@code 0} until decoding has finished. */
    public int getChannels() {
        return channels;
    }

    /** Sample rate in Hz; {@code 0} until decoding has finished. */
    public int getSampleRate() {
        return sampleRate;
    }

    /** The resource/file/bytes label used in log messages. */
    public String getName() {
        return name;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Releases the OpenAL buffer (stopping any playback that still uses this clip) and
     * frees any decoded PCM still in flight. Safe to call from any thread, more than
     * once, and while the clip is still loading.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        EasyAudio.releaseClip(this);
    }

    // ------------------------------------------------------------------
    // Decode pipeline (background executor)
    // ------------------------------------------------------------------

    private interface ByteSource {
        byte[] read() throws IOException;
    }

    private static void decodeAsync(AudioClip clip, ByteSource source, String formatHint) {
        Util.backgroundExecutor().execute(() -> {
            try {
                byte[] bytes = source.read();
                Pcm pcm = decode(bytes, formatHint);
                clip.channels = pcm.channels;
                clip.sampleRate = pcm.sampleRate;
                clip.durationSeconds = pcm.durationSeconds;
                clip.setPendingPcm(pcm);
                if (clip.closed) {
                    clip.freePendingPcm();
                    return;
                }
                EasyAudio.enqueueUpload(clip);
            } catch (Throwable t) {
                clip.failLoad(t.getMessage() != null ? t.getMessage() : t.toString(),
                        t instanceof IOException ? null : t);
            }
        });
    }

    private static Pcm decode(byte[] bytes, String formatHint) throws IOException {
        if (bytes.length < 12) {
            throw new IOException("file too small to be a WAV or OGG (" + bytes.length + " bytes)");
        }
        String format = sniffFormat(bytes, formatHint);
        if ("wav".equals(format)) {
            return decodeWav(bytes);
        }
        if ("ogg".equals(format)) {
            return decodeOgg(bytes);
        }
        throw new IOException("unrecognized audio data (expected a WAV or OGG Vorbis file)");
    }

    /** Identifies the container by magic bytes first, falling back to the caller's hint. */
    private static String sniffFormat(byte[] bytes, String hint) {
        if (bytes.length >= 12) {
            ByteBuffer head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int magic = head.getInt(0);
            if (magic == TAG_RIFF && head.getInt(8) == TAG_WAVE) {
                return "wav";
            }
            if (magic == TAG_OGGS) {
                return "ogg";
            }
        }
        if (hint != null) {
            String h = hint.trim().toLowerCase(Locale.ROOT);
            int dot = h.lastIndexOf('.');
            if (dot >= 0) {
                h = h.substring(dot + 1);
            }
            switch (h) {
                case "wav", "wave" -> {
                    return "wav";
                }
                case "ogg", "oga", "vorbis" -> {
                    return "ogg";
                }
                default -> {
                }
            }
        }
        return null;
    }

    private static String hintFromName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : null;
    }

    // ------------------------------------------------------------------
    // WAV: pure-Java RIFF/WAVE parser (PCM 8/16-bit, mono/stereo)
    // ------------------------------------------------------------------

    private static Pcm decodeWav(byte[] bytes) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.remaining() < 12 || buf.getInt() != TAG_RIFF) {
            throw new IOException("not a RIFF file");
        }
        buf.getInt(); // declared RIFF payload size — frequently wrong in the wild, ignored
        if (buf.getInt() != TAG_WAVE) {
            throw new IOException("RIFF file is not a WAVE file");
        }

        boolean fmtSeen = false;
        int audioFormat = 0;
        int channels = 0;
        int sampleRate = 0;
        int blockAlign = 0;
        int bitsPerSample = 0;
        int dataOffset = -1;
        int dataLength = 0;

        while (buf.remaining() >= 8) {
            int id = buf.getInt();
            int size = buf.getInt();
            if (size < 0) {
                throw new IOException("corrupt chunk size");
            }
            int chunkStart = buf.position();
            int available = Math.min(size, buf.remaining());
            if (id == TAG_FMT) {
                if (available < 16) {
                    throw new IOException("fmt chunk too small (" + available + " bytes)");
                }
                audioFormat = Short.toUnsignedInt(buf.getShort());
                channels = Short.toUnsignedInt(buf.getShort());
                sampleRate = buf.getInt();
                buf.getInt(); // byte rate (redundant)
                blockAlign = Short.toUnsignedInt(buf.getShort());
                bitsPerSample = Short.toUnsignedInt(buf.getShort());
                if (audioFormat == WAVE_FORMAT_EXTENSIBLE && available >= 26) {
                    buf.getShort(); // cbSize
                    buf.getShort(); // valid bits per sample
                    buf.getInt();   // channel mask
                    // The first two bytes of the sub-format GUID hold the real codec id
                    audioFormat = Short.toUnsignedInt(buf.getShort());
                }
                fmtSeen = true;
            } else if (id == TAG_DATA && dataOffset < 0) {
                dataOffset = chunkStart;
                dataLength = available;
            }
            // Chunks are word-aligned; an odd size is followed by one pad byte
            long next = (long) chunkStart + size + (size & 1);
            buf.position((int) Math.min(next, buf.limit()));
        }

        if (!fmtSeen) {
            throw new IOException("WAV is missing its fmt chunk");
        }
        if (dataOffset < 0) {
            throw new IOException("WAV is missing its data chunk");
        }
        if (audioFormat != WAVE_FORMAT_PCM) {
            throw new IOException("unsupported WAV codec " + wavCodecName(audioFormat)
                    + " — only uncompressed PCM (8/16-bit) is supported");
        }
        if (channels < 1 || channels > 2) {
            throw new IOException("unsupported WAV channel count " + channels + " (only mono/stereo)");
        }
        if (bitsPerSample != 8 && bitsPerSample != 16) {
            throw new IOException("unsupported WAV bit depth " + bitsPerSample + " (only 8-bit and 16-bit PCM)");
        }
        if (sampleRate <= 0 || sampleRate > 384_000) {
            throw new IOException("implausible WAV sample rate " + sampleRate);
        }

        int bytesPerFrame = channels * (bitsPerSample / 8);
        if (blockAlign != 0 && blockAlign != bytesPerFrame) {
            // Some encoders write nonsense here; trust channels × bit depth instead
            LOGGER.debug("EasyGUI: WAV block align {} != computed {} — ignoring", blockAlign, bytesPerFrame);
        }
        int frames = dataLength / bytesPerFrame;
        if (frames <= 0) {
            throw new IOException("WAV data chunk is empty");
        }

        Pcm pcm = new Pcm();
        pcm.channels = channels;
        pcm.sampleRate = sampleRate;
        pcm.durationSeconds = frames / (double) sampleRate;
        int sampleCount = frames * channels;
        if (bitsPerSample == 16) {
            // Copy through a little-endian short view into a native-order direct buffer,
            // so the data is correct regardless of platform endianness.
            ByteBuffer view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            view.position(dataOffset).limit(dataOffset + sampleCount * 2);
            pcm.pcm16 = MemoryUtil.memAllocShort(sampleCount);
            pcm.pcm16.put(view.asShortBuffer()).flip();
            pcm.alFormat = channels == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
        } else {
            // WAV 8-bit is unsigned, exactly what AL_FORMAT_MONO8/STEREO8 expect
            pcm.pcm8 = MemoryUtil.memAlloc(sampleCount);
            pcm.pcm8.put(bytes, dataOffset, sampleCount).flip();
            pcm.alFormat = channels == 2 ? AL10.AL_FORMAT_STEREO8 : AL10.AL_FORMAT_MONO8;
        }
        return pcm;
    }

    private static String wavCodecName(int code) {
        String label = switch (code) {
            case 0x0002 -> "MS ADPCM";
            case 0x0003 -> "IEEE float";
            case 0x0006 -> "A-law";
            case 0x0007 -> "mu-law";
            case 0x0011 -> "IMA ADPCM";
            case 0x0055 -> "MP3";
            default -> "unknown";
        };
        return String.format(Locale.ROOT, "0x%04X (%s)", code, label);
    }

    // ------------------------------------------------------------------
    // OGG: stb_vorbis via LWJGL (ships with Minecraft)
    // ------------------------------------------------------------------

    private static Pcm decodeOgg(byte[] bytes) throws IOException {
        ByteBuffer mem = MemoryUtil.memAlloc(bytes.length);
        ShortBuffer decoded = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            mem.put(bytes).flip();
            IntBuffer channelsOut = stack.mallocInt(1);
            IntBuffer rateOut = stack.mallocInt(1);
            decoded = STBVorbis.stb_vorbis_decode_memory(mem, channelsOut, rateOut);
            if (decoded == null) {
                throw new IOException("stb_vorbis could not decode the file (not OGG Vorbis, or corrupt)");
            }
            int channels = channelsOut.get(0);
            int sampleRate = rateOut.get(0);
            if (channels < 1 || channels > 2 || sampleRate <= 0) {
                throw new IOException("unsupported OGG: " + channels + " channels @ " + sampleRate
                        + " Hz (only mono/stereo)");
            }
            Pcm pcm = new Pcm();
            pcm.pcm16 = decoded;
            pcm.stbAllocated = true;
            pcm.alFormat = channels == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
            pcm.channels = channels;
            pcm.sampleRate = sampleRate;
            pcm.durationSeconds = (decoded.remaining() / channels) / (double) sampleRate;
            decoded = null; // ownership moved into the Pcm
            return pcm;
        } finally {
            MemoryUtil.memFree(mem);
            if (decoded != null) {
                // stb_vorbis allocates the output with malloc (LWJGL docs: free with LibCStdlib)
                LibCStdlib.free(decoded);
            }
        }
    }

    // ------------------------------------------------------------------
    // AL upload (runs on EasyAudio's audio thread)
    // ------------------------------------------------------------------

    /**
     * Creates the OpenAL buffer from the decoded PCM. Called by {@link EasyAudio} on the
     * dedicated audio thread once an OpenAL context exists; every AL call is checked with
     * {@code alGetError} so a missing/broken audio device degrades to {@link State#ERROR}
     * instead of crashing.
     */
    void uploadOnAudioThread() {
        Pcm pcm = takePendingPcm();
        if (pcm == null) {
            return;
        }
        if (closed || state == State.ERROR) {
            pcm.free();
            return;
        }
        try {
            EasyAudio.drainAlErrors();
            int buffer = AL10.alGenBuffers();
            int err = AL10.alGetError();
            if (err != AL10.AL_NO_ERROR || buffer == 0) {
                pcm.free();
                failLoad("alGenBuffers failed (0x" + Integer.toHexString(err) + ")", null);
                return;
            }
            if (pcm.pcm16 != null) {
                AL10.alBufferData(buffer, pcm.alFormat, pcm.pcm16, pcm.sampleRate);
            } else {
                AL10.alBufferData(buffer, pcm.alFormat, pcm.pcm8, pcm.sampleRate);
            }
            err = AL10.alGetError();
            pcm.free();
            if (err != AL10.AL_NO_ERROR) {
                AL10.alDeleteBuffers(buffer);
                EasyAudio.drainAlErrors();
                failLoad("alBufferData failed (0x" + Integer.toHexString(err) + ")", null);
                return;
            }
            alBuffer = buffer;
            state = State.READY;
        } catch (Throwable t) {
            pcm.free(); // idempotent
            failLoad("OpenAL upload failed", t);
        }
    }

    /** Marks the clip failed, logs once, and frees any PCM still held. */
    void failLoad(String message, Throwable cause) {
        if (state == State.ERROR) {
            return;
        }
        error = message;
        state = State.ERROR;
        if (cause != null) {
            LOGGER.error("EasyGUI: could not load audio clip '{}': {}", name, message, cause);
        } else {
            LOGGER.error("EasyGUI: could not load audio clip '{}': {}", name, message);
        }
        freePendingPcm();
    }

    void freePendingPcm() {
        Pcm pcm = takePendingPcm();
        if (pcm != null) {
            pcm.free();
        }
    }

    private synchronized void setPendingPcm(Pcm pcm) {
        pendingPcm = pcm;
    }

    /** Atomically claims the decoded PCM, so upload/close/timeout can never double-free it. */
    private synchronized Pcm takePendingPcm() {
        Pcm pcm = pendingPcm;
        pendingPcm = null;
        return pcm;
    }

    private static int tag(char a, char b, char c, char d) {
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    // ------------------------------------------------------------------
    // Decoded PCM container
    // ------------------------------------------------------------------

    /** Decoded samples in a native buffer, freed after the AL upload (or on failure). */
    static final class Pcm {
        ShortBuffer pcm16;
        ByteBuffer pcm8;
        /** True when {@link #pcm16} came from stb_vorbis (freed with libc free, not memFree). */
        boolean stbAllocated;
        int alFormat;
        int channels;
        int sampleRate;
        double durationSeconds;

        void free() {
            if (pcm16 != null) {
                if (stbAllocated) {
                    LibCStdlib.free(pcm16);
                } else {
                    MemoryUtil.memFree(pcm16);
                }
                pcm16 = null;
            }
            if (pcm8 != null) {
                MemoryUtil.memFree(pcm8);
                pcm8 = null;
            }
        }
    }
}
