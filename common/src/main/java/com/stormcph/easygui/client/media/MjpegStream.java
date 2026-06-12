package com.stormcph.easygui.client.media;

import com.stormcph.easygui.client.widget.VideoView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A pure-Java demuxer for MJPEG video — the video format EasyGUI plays (see
 * {@link VideoView} for why H.264 is deliberately not supported).
 *
 * <p>Two container layouts are accepted:</p>
 * <ul>
 *   <li><b>MJPEG-in-AVI</b> (what {@code ffmpeg -c:v mjpeg} produces): the RIFF
 *       {@code 'AVI '} structure is parsed — {@code hdrl} for the frame rate and frame
 *       count ({@code avih}/{@code strh}), the {@code movi} LIST for the {@code 00dc}/
 *       {@code 00db} chunks whose payloads are complete JPEG images (word-aligned chunk
 *       sizes, {@code 'rec '} grouping LISTs descended into). When an {@code idx1} index
 *       is present it is used for direct frame addressing, making the stream
 *       {@linkplain #isSeekable() seekable}; otherwise frames are found by a cheap
 *       sequential chunk scan.</li>
 *   <li><b>Raw concatenated JPEGs</b> (an {@code .mjpeg}/{@code .mjpg} byte stream): when
 *       the RIFF header is absent but the data starts with a JPEG SOI marker, frames are
 *       split by walking real JPEG segment structure (length-prefixed segments, entropy
 *       data with byte stuffing and RST markers) rather than naive {@code FFD9} searches.
 *       The container carries no timing, so {@link #fps()} returns {@code 0} and the
 *       caller supplies the rate (e.g. {@link VideoView#withFps}).</li>
 * </ul>
 *
 * <p>This class only <em>demuxes</em>; it never decodes pixels. {@link #nextFrameJpeg()}
 * hands back the raw JPEG payload for the caller to decode (e.g. with
 * {@code NativeImage.read}, i.e. stb_image), and {@link #skipFrame()} advances past a
 * frame while touching only chunk/segment headers — which is what makes frame-dropping
 * under load cheap. Parsing is plain {@code byte[]}/channel arithmetic with no Minecraft
 * or native dependencies, so it is safe on any background thread; instances are
 * <em>not</em> thread-safe and are meant to be driven by a single decode thread.</p>
 */
@Environment(EnvType.CLIENT)
public final class MjpegStream implements VideoView.FrameSource {
    /** Sanity cap on a single frame payload, guarding against corrupt chunk sizes. */
    public static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;

    private static final int FCC_RIFF = fourcc('R', 'I', 'F', 'F');
    private static final int FCC_AVI = fourcc('A', 'V', 'I', ' ');
    private static final int FCC_LIST = fourcc('L', 'I', 'S', 'T');
    private static final int FCC_HDRL = fourcc('h', 'd', 'r', 'l');
    private static final int FCC_AVIH = fourcc('a', 'v', 'i', 'h');
    private static final int FCC_STRL = fourcc('s', 't', 'r', 'l');
    private static final int FCC_STRH = fourcc('s', 't', 'r', 'h');
    private static final int FCC_VIDS = fourcc('v', 'i', 'd', 's');
    private static final int FCC_MOVI = fourcc('m', 'o', 'v', 'i');
    private static final int FCC_IDX1 = fourcc('i', 'd', 'x', '1');

    private static final byte[] EMPTY = new byte[0];

    private final Input in;
    private final long fileSize;
    private final boolean raw;

    // AVI metadata (unused in raw mode)
    private double fps;
    private int frameCount = -1;
    private int videoChunkDc;
    private int videoChunkDb;
    private long moviDataStart;
    private long moviEnd;
    /** Absolute file positions of each video chunk's 8-byte header, from idx1 (or null). */
    private long[] indexOffsets;
    private int[] indexSizes;

    // Cursor state
    private long pos;
    private int nextIndex;
    private boolean closed;

    // Small read-through window so header/marker scans don't hit the channel per byte
    private final byte[] cache = new byte[16 * 1024];
    private long cacheStart = -1;
    private int cacheLen;

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /** Opens an MJPEG-AVI or raw concatenated-JPEG file from disk. */
    public static MjpegStream open(Path file) throws IOException {
        return create(new ChannelInput(Files.newByteChannel(file, StandardOpenOption.READ)));
    }

    /** Parses an in-memory MJPEG-AVI or raw concatenated-JPEG byte stream. */
    public static MjpegStream of(byte[] data) throws IOException {
        return create(new ArrayInput(data));
    }

    private static MjpegStream create(Input in) throws IOException {
        try {
            return new MjpegStream(in);
        } catch (IOException | RuntimeException e) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private MjpegStream(Input in) throws IOException {
        this.in = in;
        this.fileSize = in.size();
        if (readFourcc(0) == FCC_RIFF && readFourcc(8) == FCC_AVI) {
            this.raw = false;
            parseAvi();
            if (moviEnd <= 0) {
                throw new IOException("AVI file has no 'movi' data");
            }
            this.pos = moviDataStart;
        } else if (u8(0) == 0xFF && u8(1) == 0xD8) {
            this.raw = true;
            this.pos = 0;
        } else {
            throw new IOException("Not an MJPEG-AVI file or raw JPEG stream");
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Frame rate from the AVI header, or {@code 0} when unknown (raw streams). */
    @Override
    public double fps() {
        return fps;
    }

    /** Total frame count, or {@code -1} when unknown (raw streams before a full pass). */
    @Override
    public int frameCount() {
        return frameCount;
    }

    /** True when an {@code idx1} index is present, enabling {@link #seekToFrame}. */
    @Override
    public boolean isSeekable() {
        return indexOffsets != null;
    }

    /**
     * The next frame's complete JPEG payload, or {@code null} at end of stream. On a raw
     * stream hitting the end (or a sequential AVI without an index), the now-known frame
     * count is recorded so a looping caller learns the duration after the first pass.
     */
    public byte[] nextFrameJpeg() throws IOException {
        checkOpen();
        if (raw) {
            return rawNext(true);
        }
        if (indexOffsets != null) {
            if (nextIndex >= indexOffsets.length) {
                return null;
            }
            long header = indexOffsets[nextIndex];
            int length = indexSizes[nextIndex];
            byte[] out = readFully(header + 8, length);
            nextIndex++;
            return out;
        }
        return scanNext(true);
    }

    @Override
    public byte[] nextFrame() throws IOException {
        return nextFrameJpeg();
    }

    /**
     * Advances past one frame without copying its payload — only chunk headers (AVI) or
     * JPEG segment headers (raw) are parsed, so dropping frames under load costs almost
     * nothing. Returns {@code false} at end of stream.
     */
    @Override
    public boolean skipFrame() throws IOException {
        checkOpen();
        if (raw) {
            return rawNext(false) != null;
        }
        if (indexOffsets != null) {
            if (nextIndex >= indexOffsets.length) {
                return false;
            }
            nextIndex++;
            return true;
        }
        return scanNext(false) != null;
    }

    /** Rewinds so the next frame returned is frame 0. */
    @Override
    public void reset() {
        nextIndex = 0;
        pos = raw ? 0 : moviDataStart;
    }

    /**
     * Positions the stream so the next frame returned is {@code index}. Supported when an
     * {@code idx1} index was found ({@link #isSeekable()}); without one, only
     * {@code index == 0} succeeds (via {@link #reset()}) and anything else returns
     * {@code false}.
     */
    @Override
    public boolean seekToFrame(int index) {
        if (indexOffsets != null) {
            nextIndex = Math.max(0, Math.min(index, indexOffsets.length));
            return true;
        }
        if (index == 0) {
            reset();
            return true;
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            in.close();
        }
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
    }

    // ------------------------------------------------------------------
    // AVI structure
    // ------------------------------------------------------------------

    private void parseAvi() throws IOException {
        long moviListPos = -1;
        long idx1Pos = -1;
        long idx1Size = 0;
        int videoStream = -1;

        long p = 12;
        while (p + 8 <= fileSize) {
            int fcc = readFourcc(p);
            long size = readU32(p + 4);
            long data = p + 8;
            if (size < 0 || data + size > fileSize) {
                break; // truncated trailing chunk; keep what we have
            }
            if (fcc == FCC_LIST && size >= 4) {
                int listType = readFourcc(data);
                if (listType == FCC_HDRL) {
                    videoStream = parseHdrl(data + 4, data + size);
                } else if (listType == FCC_MOVI) {
                    moviListPos = data;
                    moviDataStart = data + 4;
                    moviEnd = data + size;
                }
            } else if (fcc == FCC_IDX1) {
                idx1Pos = data;
                idx1Size = size;
            }
            p = data + size + (size & 1);
        }

        if (videoStream < 0) {
            videoStream = 0; // tolerate a missing/odd hdrl; stream 0 is the overwhelming default
        }
        videoChunkDc = videoChunkId(videoStream, 'd', 'c');
        videoChunkDb = videoChunkId(videoStream, 'd', 'b');

        if (idx1Pos > 0 && moviListPos > 0) {
            parseIdx1(idx1Pos, idx1Size, moviListPos);
        }
        if (indexOffsets != null) {
            frameCount = indexOffsets.length; // the index is the most truthful count
        }
    }

    /** Parses the {@code hdrl} LIST for timing/length; returns the video stream number (or -1). */
    private int parseHdrl(long start, long end) throws IOException {
        int stream = -1;
        int streamCounter = 0;
        long p = start;
        while (p + 8 <= end) {
            int fcc = readFourcc(p);
            long size = readU32(p + 4);
            long data = p + 8;
            if (size < 0 || data + size > end) {
                break;
            }
            if (fcc == FCC_AVIH && size >= 20) {
                long microsPerFrame = readU32(data);
                if (fps <= 0 && microsPerFrame > 0) {
                    fps = 1_000_000.0 / microsPerFrame;
                }
                long total = readU32(data + 16);
                if (total > 0) {
                    frameCount = (int) Math.min(total, Integer.MAX_VALUE);
                }
            } else if (fcc == FCC_LIST && size >= 4 && readFourcc(data) == FCC_STRL) {
                // The first subchunk of an strl LIST is the stream header
                long q = data + 4;
                if (q + 8 <= data + size && readFourcc(q) == FCC_STRH && readU32(q + 4) >= 36) {
                    long s = q + 8;
                    if (readFourcc(s) == FCC_VIDS && stream < 0) {
                        stream = streamCounter;
                        long scale = readU32(s + 20);
                        long rate = readU32(s + 24);
                        if (scale > 0 && rate > 0) {
                            fps = (double) rate / scale;
                        }
                        long length = readU32(s + 32);
                        if (length > 0) {
                            frameCount = (int) Math.min(length, Integer.MAX_VALUE);
                        }
                    }
                }
                streamCounter++;
            }
            p = data + size + (size & 1);
        }
        return stream;
    }

    /**
     * Reads the {@code idx1} index, keeping only the video stream's entries. Offsets are
     * conventionally relative to the position of the {@code 'movi'} fourcc, but some
     * encoders write absolute file offsets — the base is resolved by checking which
     * interpretation lands the first entry on its own chunk id.
     */
    private void parseIdx1(long start, long size, long moviListPos) throws IOException {
        int entries = (int) Math.min(size / 16, Integer.MAX_VALUE);
        long[] offsets = new long[entries];
        int[] sizes = new int[entries];
        long base = Long.MIN_VALUE;
        int count = 0;
        for (int i = 0; i < entries; i++) {
            long e = start + (long) i * 16;
            int ckid = readFourcc(e);
            if (ckid != videoChunkDc && ckid != videoChunkDb) {
                continue;
            }
            long offset = readU32(e + 8);
            long length = readU32(e + 12);
            if (offset < 0 || length < 0 || length > MAX_FRAME_BYTES) {
                continue;
            }
            if (base == Long.MIN_VALUE) {
                if (readFourcc(moviListPos + offset) == ckid) {
                    base = moviListPos;
                } else if (readFourcc(offset) == ckid) {
                    base = 0;
                } else {
                    return; // index doesn't match the data; fall back to sequential scanning
                }
            }
            offsets[count] = base + offset;
            sizes[count] = (int) length;
            count++;
        }
        if (count > 0) {
            indexOffsets = new long[count];
            indexSizes = new int[count];
            System.arraycopy(offsets, 0, indexOffsets, 0, count);
            System.arraycopy(sizes, 0, indexSizes, 0, count);
        }
    }

    /** Sequential {@code movi} scan to the next video chunk (used when there is no index). */
    private byte[] scanNext(boolean copy) throws IOException {
        while (pos + 8 <= moviEnd) {
            int fcc = readFourcc(pos);
            long size = readU32(pos + 4);
            if (fcc == FCC_LIST) {
                pos += 12; // descend into 'rec ' grouping lists; their children follow inline
                continue;
            }
            long data = pos + 8;
            if (size < 0 || data + size > fileSize) {
                break; // corrupt size; treat as end of stream
            }
            pos = data + size + (size & 1);
            if (fcc == videoChunkDc || fcc == videoChunkDb) {
                if (size > MAX_FRAME_BYTES) {
                    throw new IOException("Video chunk too large: " + size + " bytes");
                }
                nextIndex++;
                return copy ? readFully(data, (int) size) : EMPTY;
            }
        }
        // End of movi: we now know the real frame count (headers sometimes lie)
        if (nextIndex > 0) {
            frameCount = nextIndex;
        }
        return null;
    }

    private static int videoChunkId(int stream, char c1, char c2) {
        return fourcc((char) ('0' + stream / 10 % 10), (char) ('0' + stream % 10), c1, c2);
    }

    // ------------------------------------------------------------------
    // Raw concatenated-JPEG streams
    // ------------------------------------------------------------------

    private byte[] rawNext(boolean copy) throws IOException {
        long soi = findSoi(pos);
        if (soi < 0) {
            pos = fileSize;
            if (nextIndex > 0) {
                frameCount = nextIndex;
            }
            return null;
        }
        long end = findJpegEnd(soi);
        if (end < 0) {
            // Truncated tail; drop it and report end of stream
            pos = fileSize;
            if (nextIndex > 0) {
                frameCount = nextIndex;
            }
            return null;
        }
        pos = end;
        nextIndex++;
        if (!copy) {
            return EMPTY;
        }
        long length = end - soi;
        if (length > MAX_FRAME_BYTES) {
            throw new IOException("JPEG frame too large: " + length + " bytes");
        }
        return readFully(soi, (int) length);
    }

    /** Finds the next SOI marker ({@code FF D8}) at or after {@code from}, or -1. */
    private long findSoi(long from) throws IOException {
        for (long p = from; p + 1 < fileSize; p++) {
            if (u8(p) == 0xFF && u8(p + 1) == 0xD8) {
                return p;
            }
        }
        return -1;
    }

    /**
     * Walks real JPEG segment structure from the SOI at {@code soi} and returns the
     * position just past the EOI marker, or -1 if the stream ends mid-frame. Handles
     * length-prefixed segments, standalone markers, fill bytes, and entropy-coded scan
     * data with {@code FF 00} byte stuffing and RST0–7 restart markers (so an embedded
     * thumbnail's own EOI can never split a frame early).
     */
    private long findJpegEnd(long soi) throws IOException {
        long p = soi + 2;
        long limit = Math.min(fileSize, soi + MAX_FRAME_BYTES);
        while (p < limit) {
            if (u8(p) != 0xFF) {
                return -1; // lost sync; treat as corrupt/truncated
            }
            int marker;
            do {
                p++;
                marker = u8(p);
                if (marker < 0) {
                    return -1;
                }
            } while (marker == 0xFF); // skip fill bytes
            p++;
            if (marker == 0xD9) {
                return p; // EOI
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue; // standalone markers carry no payload
            }
            int length = u16be(p);
            if (length < 2) {
                return -1;
            }
            p += length;
            if (marker == 0xDA) {
                // Entropy-coded data: scan for the next real marker
                while (true) {
                    int b = u8(p);
                    if (b < 0) {
                        return -1;
                    }
                    p++;
                    if (b != 0xFF) {
                        continue;
                    }
                    int m = u8(p);
                    if (m < 0) {
                        return -1;
                    }
                    if (m == 0x00 || (m >= 0xD0 && m <= 0xD7)) {
                        p++; // stuffed byte or restart marker: still scan data
                        continue;
                    }
                    if (m == 0xFF) {
                        continue; // fill byte; re-examine
                    }
                    p--; // a real marker: back up onto the FF for the outer loop
                    break;
                }
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Low-level reads
    // ------------------------------------------------------------------

    /** Byte at {@code p} through the read-through window, or -1 past the end. */
    private int u8(long p) throws IOException {
        if (p < 0 || p >= fileSize) {
            return -1;
        }
        if (p < cacheStart || p >= cacheStart + cacheLen) {
            int n = in.read(p, cache, 0, (int) Math.min(cache.length, fileSize - p));
            if (n <= 0) {
                return -1;
            }
            cacheStart = p;
            cacheLen = n;
        }
        return cache[(int) (p - cacheStart)] & 0xFF;
    }

    private int u16be(long p) throws IOException {
        int hi = u8(p);
        int lo = u8(p + 1);
        return hi < 0 || lo < 0 ? -1 : (hi << 8) | lo;
    }

    /** Little-endian u32 at {@code p}, or -1 past the end. */
    private long readU32(long p) throws IOException {
        int b0 = u8(p);
        int b1 = u8(p + 1);
        int b2 = u8(p + 2);
        int b3 = u8(p + 3);
        if ((b0 | b1 | b2 | b3) < 0) {
            return -1;
        }
        return b0 | (long) b1 << 8 | (long) b2 << 16 | (long) b3 << 24;
    }

    /** Big-endian fourcc at {@code p} (matches {@link #fourcc}), or 0 past the end. */
    private int readFourcc(long p) throws IOException {
        int b0 = u8(p);
        int b1 = u8(p + 1);
        int b2 = u8(p + 2);
        int b3 = u8(p + 3);
        if ((b0 | b1 | b2 | b3) < 0) {
            return 0;
        }
        return b0 << 24 | b1 << 16 | b2 << 8 | b3;
    }

    private byte[] readFully(long p, int length) throws IOException {
        byte[] out = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(p + off, out, off, length - off);
            if (n <= 0) {
                throw new EOFException("Truncated frame data at " + (p + off));
            }
            off += n;
        }
        return out;
    }

    private static int fourcc(char a, char b, char c, char d) {
        return a << 24 | b << 16 | c << 8 | d;
    }

    // ------------------------------------------------------------------
    // Backing storage
    // ------------------------------------------------------------------

    /** Random-access byte source: a byte array or a seekable channel. */
    private interface Input extends Closeable {
        /** Reads up to {@code len} bytes at absolute {@code pos}; -1 at end of input. */
        int read(long pos, byte[] dst, int off, int len) throws IOException;

        long size() throws IOException;
    }

    private static final class ArrayInput implements Input {
        private final byte[] data;

        ArrayInput(byte[] data) {
            this.data = data;
        }

        @Override
        public int read(long pos, byte[] dst, int off, int len) {
            if (pos >= data.length) {
                return -1;
            }
            int n = (int) Math.min(len, data.length - pos);
            System.arraycopy(data, (int) pos, dst, off, n);
            return n;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public void close() {
        }
    }

    private static final class ChannelInput implements Input {
        private final SeekableByteChannel channel;

        ChannelInput(SeekableByteChannel channel) {
            this.channel = channel;
        }

        @Override
        public int read(long pos, byte[] dst, int off, int len) throws IOException {
            channel.position(pos);
            ByteBuffer buffer = ByteBuffer.wrap(dst, off, len);
            int total = 0;
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer);
                if (n <= 0) {
                    break;
                }
                total += n;
            }
            return total == 0 ? -1 : total;
        }

        @Override
        public long size() throws IOException {
            return channel.size();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
