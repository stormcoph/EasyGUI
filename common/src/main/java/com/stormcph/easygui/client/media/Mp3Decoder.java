package com.stormcph.easygui.client.media;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * MP3 (MPEG 1/2/2.5 Layer III) decoding for EasyGUI, built on the tiny pure-Java
 * <a href="http://www.javazoom.net/javalayer/javalayer.html">JLayer</a> library
 * (© JavaZoom, LGPL-2.1 — bundled unmodified as a separate jar-in-jar artifact; see
 * {@code docs/MP3-LICENSE-NOTES.md} for the license analysis and attribution).
 *
 * <p>{@link #decode(byte[])} runs the classic JLayer frame loop ({@code Bitstream} →
 * {@code Decoder} → {@code SampleBuffer}) over the whole file and returns interleaved
 * 16-bit PCM ready for OpenAL. Like {@link GifDecoder}, decoding is synchronous and
 * touches no Minecraft classes, so run it on a background thread (e.g.
 * {@code Util.backgroundExecutor()}) for anything longer than a short jingle — never on
 * the render thread. Trailing garbage after the audio (ID3v1 tags, truncated downloads)
 * is tolerated once at least one frame decoded; ID3v2 tags at the start are skipped by
 * JLayer itself. The total decoded volume is capped at {@link #MAX_TOTAL_SAMPLES} so a
 * malicious file cannot balloon memory.</p>
 *
 * <p>{@link #toClip(byte[])} is the one-call bridge into the audio system: it decodes the
 * MP3 and hands the PCM to {@link AudioClip} (by wrapping it in an in-memory 16-bit WAV
 * for {@link AudioClip#fromBytes}), returning a clip that uploads asynchronously and is
 * playable through {@link EasyAudio} exactly like a WAV/OGG clip. The decode itself runs
 * on the calling thread — same rule as above, call it from a background thread.</p>
 *
 * <p><b>Soft dependency:</b> JLayer ships nested inside the released EasyGUI jar. If the
 * nested jar has been stripped, MP3 decoding fails with a clear
 * {@link UnsupportedOperationException} on the first decode attempt (all JLayer
 * references live in a private inner class that is only loaded then), and no other part
 * of EasyGUI is affected. Check {@link #isAvailable()} to probe support up front.</p>
 */
@Environment(EnvType.CLIENT)
public final class Mp3Decoder {
    /** Hard cap on total decoded samples (all channels, interleaved); decoding throws beyond this. */
    public static final long MAX_TOTAL_SAMPLES = 64_000_000L; // ~11 min of 48 kHz stereo, 128 MB

    private static final int TAG_RIFF = tag('R', 'I', 'F', 'F');
    private static final int TAG_WAVE = tag('W', 'A', 'V', 'E');
    private static final int TAG_FMT = tag('f', 'm', 't', ' ');
    private static final int TAG_DATA = tag('d', 'a', 't', 'a');

    private Mp3Decoder() {
    }

    /** A decoded MP3: interleaved 16-bit PCM samples plus the stream's layout. */
    public static final class Result {
        /** Interleaved signed 16-bit samples (L R L R … for stereo, every value for mono). */
        public final short[] samples;
        /** Channel count, 1 or 2. */
        public final int channels;
        /** Sample rate in Hz. */
        public final int sampleRate;

        Result(short[] samples, int channels, int sampleRate) {
            this.samples = samples;
            this.channels = channels;
            this.sampleRate = sampleRate;
        }

        /** Number of PCM frames (samples per channel). */
        public int frameCount() {
            return samples.length / channels;
        }

        /** Audio length in seconds. */
        public double durationSeconds() {
            return frameCount() / (double) sampleRate;
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Whether the bundled JLayer decoder is present on the classpath. {@code false} only
     * if someone stripped the nested {@code jlayer} jar from the mod.
     */
    public static boolean isAvailable() {
        try {
            Class.forName("javazoom.jl.decoder.Decoder", false, Mp3Decoder.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Quick magic-byte sniff: {@code true} if the data starts with an ID3v2 tag or an
     * MPEG audio frame sync. Useful for routing raw bytes between this decoder and
     * {@link AudioClip#fromBytes} (which handles WAV/OGG).
     */
    public static boolean looksLikeMp3(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        if (data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
            return true;
        }
        // Frame sync: 11 set bits, then a valid (non-reserved) MPEG version and layer
        int b0 = data[0] & 0xFF;
        int b1 = data[1] & 0xFF;
        return b0 == 0xFF && (b1 & 0xE0) == 0xE0 && (b1 & 0x18) != 0x08 && (b1 & 0x06) != 0;
    }

    /**
     * Decodes a complete MP3 file from memory into interleaved 16-bit PCM.
     *
     * @throws IOException                   if the data is not a decodable MP3, is
     *                                       corrupt, or exceeds {@link #MAX_TOTAL_SAMPLES}
     * @throws UnsupportedOperationException if the bundled JLayer library is missing
     *                                       (see {@link #isAvailable()})
     */
    public static Result decode(byte[] mp3) throws IOException {
        if (mp3 == null || mp3.length < 4) {
            throw new IOException("file too small to be an MP3 ("
                    + (mp3 == null ? 0 : mp3.length) + " bytes)");
        }
        try {
            return JLayerBridge.decode(mp3);
        } catch (NoClassDefFoundError e) {
            throw new UnsupportedOperationException("MP3 support requires the bundled JLayer library", e);
        }
    }

    /**
     * Decodes an MP3 and wires it into the audio system as an {@link AudioClip}, playable
     * via {@link EasyAudio#play}. The MP3 decode runs synchronously on the calling thread
     * (use a background thread for long files); the returned clip then finishes its
     * OpenAL upload asynchronously like any other clip ({@code LOADING} → {@code READY}).
     *
     * <p>Internally the PCM is handed over as an in-memory 16-bit WAV through
     * {@link AudioClip#fromBytes} — the cheapest lossless container AudioClip's public
     * surface accepts. Remember to {@link AudioClip#close()} the clip when done.</p>
     *
     * @throws IOException                   if the MP3 cannot be decoded
     * @throws UnsupportedOperationException if the bundled JLayer library is missing
     */
    public static AudioClip toClip(byte[] mp3) throws IOException {
        Result pcm = decode(mp3);
        return AudioClip.fromBytes(wrapAsWav(pcm), "wav");
    }

    // ------------------------------------------------------------------
    // PCM → in-memory WAV (the handoff format AudioClip accepts)
    // ------------------------------------------------------------------

    /** Wraps decoded PCM in a canonical 44-byte RIFF/WAVE header (16-bit PCM, little-endian). */
    private static byte[] wrapAsWav(Result pcm) {
        int dataLength = pcm.samples.length * 2;
        ByteBuffer out = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(TAG_RIFF);
        out.putInt(36 + dataLength);
        out.putInt(TAG_WAVE);
        out.putInt(TAG_FMT);
        out.putInt(16);                                       // fmt chunk size
        out.putShort((short) 1);                              // WAVE_FORMAT_PCM
        out.putShort((short) pcm.channels);
        out.putInt(pcm.sampleRate);
        out.putInt(pcm.sampleRate * pcm.channels * 2);        // byte rate
        out.putShort((short) (pcm.channels * 2));             // block align
        out.putShort((short) 16);                             // bits per sample
        out.putInt(TAG_DATA);
        out.putInt(dataLength);
        out.asShortBuffer().put(pcm.samples);                 // bulk copy at position 44
        return out.array();
    }

    private static int tag(char a, char b, char c, char d) {
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    // ------------------------------------------------------------------
    // JLayer bridge — the ONLY class that references javazoom.* types.
    // It is loaded lazily on the first decode attempt, so a stripped
    // jlayer jar surfaces as a clean UnsupportedOperationException in
    // decode() instead of a NoClassDefFoundError somewhere unrelated.
    // ------------------------------------------------------------------

    private static final class JLayerBridge {
        private JLayerBridge() {
        }

        static Result decode(byte[] mp3) throws IOException {
            javazoom.jl.decoder.Bitstream bitstream =
                    new javazoom.jl.decoder.Bitstream(new ByteArrayInputStream(mp3));
            try {
                javazoom.jl.decoder.Decoder decoder = new javazoom.jl.decoder.Decoder();
                short[] samples = new short[0];
                int sampleCount = 0;
                int channels = 0;
                int sampleRate = 0;

                while (true) {
                    javazoom.jl.decoder.Header header;
                    try {
                        header = bitstream.readFrame();
                    } catch (javazoom.jl.decoder.BitstreamException e) {
                        if (sampleCount > 0) {
                            break; // trailing garbage (ID3v1 tag, truncation) after good audio
                        }
                        throw new IOException("not a decodable MP3 stream: " + e.getMessage(), e);
                    }
                    if (header == null) {
                        break; // clean end of stream
                    }

                    javazoom.jl.decoder.SampleBuffer frame;
                    try {
                        frame = (javazoom.jl.decoder.SampleBuffer) decoder.decodeFrame(header, bitstream);
                    } catch (javazoom.jl.decoder.DecoderException e) {
                        throw new IOException("MP3 frame decode failed: " + e.getMessage(), e);
                    }

                    if (channels == 0) {
                        channels = frame.getChannelCount();
                        sampleRate = frame.getSampleFrequency();
                        if (channels < 1 || channels > 2 || sampleRate <= 0) {
                            throw new IOException("unsupported MP3: " + channels + " channels @ "
                                    + sampleRate + " Hz (only mono/stereo)");
                        }
                    } else if (frame.getChannelCount() != channels
                            || frame.getSampleFrequency() != sampleRate) {
                        throw new IOException("MP3 format changes mid-stream ("
                                + channels + "ch@" + sampleRate + "Hz -> "
                                + frame.getChannelCount() + "ch@" + frame.getSampleFrequency() + "Hz)");
                    }

                    // The decoder reuses one SampleBuffer for every frame — copy it out now
                    int got = frame.getBufferLength();
                    if (got > 0) {
                        if (sampleCount + (long) got > MAX_TOTAL_SAMPLES) {
                            throw new IOException("MP3 exceeds the decode cap of "
                                    + MAX_TOTAL_SAMPLES + " samples");
                        }
                        samples = grow(samples, sampleCount + got);
                        System.arraycopy(frame.getBuffer(), 0, samples, sampleCount, got);
                        sampleCount += got;
                    }
                    bitstream.closeFrame();
                }

                if (sampleCount == 0) {
                    throw new IOException("no decodable MPEG audio frames found");
                }
                if (samples.length != sampleCount) {
                    samples = Arrays.copyOf(samples, sampleCount);
                }
                return new Result(samples, channels, sampleRate);
            } catch (RuntimeException e) {
                // JLayer can throw unchecked (e.g. ArrayIndexOutOfBounds) on corrupt data
                throw new IOException("corrupt MP3 data: " + e, e);
            } finally {
                try {
                    bitstream.close();
                } catch (javazoom.jl.decoder.BitstreamException ignored) {
                    // closing a byte-array-backed stream cannot meaningfully fail
                }
            }
        }

        /** Grow-by-half amortized append buffer, never above the decode cap. */
        private static short[] grow(short[] array, int needed) {
            if (array.length >= needed) {
                return array;
            }
            long target = Math.max(needed, Math.max(16_384L, array.length + (array.length >> 1)));
            return Arrays.copyOf(array, (int) Math.min(target, MAX_TOTAL_SAMPLES));
        }
    }
}
