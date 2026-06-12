package com.stormcph.easygui.client.media;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A complete, dependency-free GIF87a/GIF89a decoder in pure Java.
 *
 * <p>{@link #decode(InputStream)} parses the full format — logical screen descriptor,
 * global/local color tables, graphics control extensions, interlaced images, variable
 * code-size LZW (with clear/end-of-information codes and the KwKwK edge case), and the
 * NETSCAPE2.0 loop-count extension — and returns <em>fully composited</em> ARGB frames:
 * disposal methods 0/1 (none/keep), 2 (restore to background, treated as transparent like
 * browsers do) and 3 (restore to previous) are applied, so every {@link Frame} is a
 * complete {@code width × height} canvas ready to upload as a texture.</p>
 *
 * <p>Per-frame delays of 0 or 1 centiseconds are normalized to 100&nbsp;ms, matching
 * browser behavior. Decoding is bounded: the total decoded pixel volume
 * ({@code width × height × frames}) is capped at {@link #MAX_TOTAL_PIXELS}, and malformed
 * input fails with a descriptive {@link IOException} instead of corrupting memory. A
 * stream that ends cleanly between blocks but lacks the trailer byte is tolerated (also
 * like browsers), as long as at least one frame decoded. The decoder touches no Minecraft
 * classes, so it is safe to run on a background thread.</p>
 */
@Environment(EnvType.CLIENT)
public final class GifDecoder {
    /** Hard cap on {@code width × height × frameCount}; decoding throws beyond this. */
    public static final long MAX_TOTAL_PIXELS = 64_000_000L;

    private static final int MAX_LZW_CODES = 4096;
    private static final int DISPOSAL_BACKGROUND = 2;
    private static final int DISPOSAL_PREVIOUS = 3;

    /** One fully composited animation frame. */
    public static final class Frame {
        /** Packed ARGB pixels, {@code width × height} of the {@link Result} canvas. */
        public final int[] pixels;
        /** How long this frame stays on screen, in milliseconds (always ≥ 20). */
        public final int delayMs;

        Frame(int[] pixels, int delayMs) {
            this.pixels = pixels;
            this.delayMs = delayMs;
        }
    }

    /** A decoded GIF: canvas size, loop count and the composited frames in order. */
    public static final class Result {
        public final int width;
        public final int height;
        /**
         * Total number of times the animation plays: {@code 0} means loop forever. A GIF
         * without a NETSCAPE2.0 extension plays once; a NETSCAPE repeat count of
         * {@code n > 0} plays {@code n + 1} times (browser behavior).
         */
        public final int loopCount;
        public final List<Frame> frames;

        Result(int width, int height, int loopCount, List<Frame> frames) {
            this.width = width;
            this.height = height;
            this.loopCount = loopCount;
            this.frames = List.copyOf(frames);
        }
    }

    private final InputStream in;

    private int width;
    private int height;
    private int[] globalColorTable;
    private int[] canvas;
    private int loopCount = 1;
    private final List<Frame> frames = new ArrayList<>();

    // Graphics-control state for the next image (reset after each frame)
    private int disposal;
    private boolean transparent;
    private int transparentIndex;
    private int delayCs;

    private GifDecoder(InputStream in) {
        this.in = in;
    }

    /**
     * Decodes a complete GIF from {@code input} (the stream is not closed). Unbuffered
     * streams are wrapped automatically.
     *
     * @throws IOException if the data is not a GIF, is malformed, or exceeds
     *                     {@link #MAX_TOTAL_PIXELS} total decoded pixels
     */
    public static Result decode(InputStream input) throws IOException {
        InputStream stream = input instanceof BufferedInputStream || input instanceof ByteArrayInputStream
                ? input : new BufferedInputStream(input);
        return new GifDecoder(stream).parse();
    }

    // ------------------------------------------------------------------
    // File structure
    // ------------------------------------------------------------------

    private Result parse() throws IOException {
        readHeader();
        while (true) {
            int block = in.read();
            if (block < 0 || block == 0x3B) { // EOF (tolerated) or trailer
                break;
            }
            if (block == 0x21) {
                readExtension();
            } else if (block == 0x2C) {
                readFrame();
            } else if (block != 0x00) { // stray padding zeros are tolerated
                throw new IOException(String.format("Malformed GIF: unexpected block 0x%02X", block));
            }
        }
        if (frames.isEmpty()) {
            throw new IOException("GIF contains no image frames");
        }
        return new Result(width, height, loopCount, frames);
    }

    private void readHeader() throws IOException {
        byte[] header = new byte[6];
        readFully(header, 6);
        String signature = new String(header, StandardCharsets.US_ASCII);
        if (!signature.equals("GIF89a") && !signature.equals("GIF87a")) {
            throw new IOException("Not a GIF file (header \"" + signature + "\")");
        }
        width = read2();
        height = read2();
        int packed = read1();
        read1(); // background color index (compositing uses transparency instead)
        read1(); // pixel aspect ratio
        if (width <= 0 || height <= 0) {
            throw new IOException("Malformed GIF: canvas is " + width + "x" + height);
        }
        if ((long) width * height > MAX_TOTAL_PIXELS) {
            throw new IOException("GIF too large: " + width + "x" + height
                    + " exceeds the " + MAX_TOTAL_PIXELS + " pixel decode budget");
        }
        if ((packed & 0x80) != 0) {
            globalColorTable = readColorTable(2 << (packed & 0x07));
        }
        canvas = new int[width * height];
    }

    private void readExtension() throws IOException {
        int label = read1();
        if (label == 0xF9) {
            readGraphicControl();
        } else if (label == 0xFF) {
            readApplicationExtension();
        } else {
            skipSubBlocks(); // comment (0xFE), plain text (0x01), anything unknown
        }
    }

    private void readGraphicControl() throws IOException {
        int size = read1();
        if (size >= 4) {
            int packed = read1();
            delayCs = read2();
            transparentIndex = read1();
            skipFully(size - 4);
            disposal = (packed >> 2) & 0x07;
            if (disposal == 4) {
                disposal = DISPOSAL_PREVIOUS; // off-by-one bug in some old encoders
            } else if (disposal > DISPOSAL_PREVIOUS) {
                disposal = 0; // reserved values: treat as "no disposal"
            }
            transparent = (packed & 0x01) != 0;
        } else {
            skipFully(size);
        }
        skipSubBlocks();
    }

    private void readApplicationExtension() throws IOException {
        int size = read1();
        byte[] header = new byte[size];
        readFully(header, size);
        String application = new String(header, StandardCharsets.US_ASCII);
        boolean netscape = size == 11
                && (application.equals("NETSCAPE2.0") || application.equals("ANIMEXTS1.0"));
        byte[] data = new byte[255];
        int length;
        while ((length = read1()) != 0) {
            readFully(data, length);
            if (netscape && length >= 3 && (data[0] & 0xFF) == 1) {
                int repeats = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
                loopCount = repeats == 0 ? 0 : repeats + 1;
            }
        }
    }

    // ------------------------------------------------------------------
    // Frames and compositing
    // ------------------------------------------------------------------

    private void readFrame() throws IOException {
        int frameX = read2();
        int frameY = read2();
        int frameWidth = read2();
        int frameHeight = read2();
        int packed = read1();
        boolean interlaced = (packed & 0x40) != 0;
        int[] palette = (packed & 0x80) != 0 ? readColorTable(2 << (packed & 0x07)) : globalColorTable;
        if (palette == null) {
            throw new IOException("Malformed GIF: frame " + frames.size() + " has no color table");
        }
        if (frameWidth <= 0 || frameHeight <= 0) {
            throw new IOException("Malformed GIF: frame " + frames.size() + " is "
                    + frameWidth + "x" + frameHeight);
        }
        if ((long) frameWidth * frameHeight > MAX_TOTAL_PIXELS
                || (long) width * height * (frames.size() + 1) > MAX_TOTAL_PIXELS) {
            throw new IOException("GIF too large: " + width + "x" + height + " with "
                    + (frames.size() + 1) + "+ frames exceeds the " + MAX_TOTAL_PIXELS
                    + " pixel decode budget");
        }

        byte[] indices = new byte[frameWidth * frameHeight];
        int produced = decodeLzw(indices);

        // Frames may be smaller than the canvas, sit at an offset, or even hang off the
        // right/bottom edge; everything outside the canvas is clipped.
        int[] snapshot = disposal == DISPOSAL_PREVIOUS ? canvas.clone() : null;
        int[] rowOrder = rowOrder(frameHeight, interlaced);
        for (int row = 0; row < frameHeight; row++) {
            int sourceBase = row * frameWidth;
            if (sourceBase >= produced) {
                break; // truncated LZW data: keep what decoded, skip the rest
            }
            int targetY = frameY + rowOrder[row];
            if (targetY >= height) {
                continue;
            }
            int sourceEnd = Math.min(sourceBase + frameWidth, produced);
            int targetBase = targetY * width;
            for (int source = sourceBase, targetX = frameX; source < sourceEnd && targetX < width;
                 source++, targetX++) {
                int index = indices[source] & 0xFF;
                if ((transparent && index == transparentIndex) || index >= palette.length) {
                    continue; // transparent (or out-of-table) pixels leave the canvas as-is
                }
                canvas[targetBase + targetX] = palette[index];
            }
        }

        int delayMs = delayCs <= 1 ? 100 : delayCs * 10; // 0/1 cs → 100 ms, like browsers
        frames.add(new Frame(canvas.clone(), delayMs));

        // Dispose, preparing the canvas for the next frame
        if (disposal == DISPOSAL_BACKGROUND) {
            int x0 = Math.min(frameX, width);
            int x1 = Math.min(frameX + frameWidth, width);
            int y0 = Math.min(frameY, height);
            int y1 = Math.min(frameY + frameHeight, height);
            for (int y = y0; y < y1; y++) {
                Arrays.fill(canvas, y * width + x0, y * width + x1, 0);
            }
        } else if (snapshot != null) {
            canvas = snapshot;
        }

        // Graphics-control state applies to one image only
        disposal = 0;
        transparent = false;
        transparentIndex = 0;
        delayCs = 0;
    }

    /** Maps the n-th decoded row to its actual row, honoring 4-pass interlacing. */
    private static int[] rowOrder(int frameHeight, boolean interlaced) {
        int[] order = new int[frameHeight];
        if (!interlaced) {
            for (int i = 0; i < frameHeight; i++) {
                order[i] = i;
            }
            return order;
        }
        int i = 0;
        int[][] passes = {{0, 8}, {4, 8}, {2, 4}, {1, 2}};
        for (int[] pass : passes) {
            for (int row = pass[0]; row < frameHeight; row += pass[1]) {
                order[i++] = row;
            }
        }
        return order;
    }

    private int[] readColorTable(int size) throws IOException {
        byte[] data = new byte[size * 3];
        readFully(data, data.length);
        int[] table = new int[size];
        for (int i = 0; i < size; i++) {
            table[i] = 0xFF000000
                    | (data[i * 3] & 0xFF) << 16
                    | (data[i * 3 + 1] & 0xFF) << 8
                    | (data[i * 3 + 2] & 0xFF);
        }
        return table;
    }

    // ------------------------------------------------------------------
    // LZW
    // ------------------------------------------------------------------

    /**
     * Decodes one image's LZW data (sub-block chained) into {@code out}, returning how
     * many pixels were produced. Handles variable code sizes (up to 12 bits), clear-code
     * dictionary resets, the end-of-information code, and the "code == next free slot"
     * (KwKwK) case; output beyond the frame's pixel count is discarded.
     */
    private int decodeLzw(byte[] out) throws IOException {
        int minCodeSize = read1();
        if (minCodeSize < 1 || minCodeSize > 8) {
            throw new IOException("Malformed GIF: invalid LZW minimum code size " + minCodeSize);
        }
        int clearCode = 1 << minCodeSize;
        int eoiCode = clearCode + 1;
        int available = eoiCode + 1;
        int codeSize = minCodeSize + 1;
        int codeMask = (1 << codeSize) - 1;
        int oldCode = -1;
        int firstPixel = 0;

        int[] prefix = new int[MAX_LZW_CODES];
        byte[] suffix = new byte[MAX_LZW_CODES];
        byte[] stack = new byte[MAX_LZW_CODES + 2];
        int stackTop = 0;
        for (int i = 0; i < clearCode; i++) {
            suffix[i] = (byte) i;
        }

        int produced = 0;
        int datum = 0;
        int bits = 0;
        boolean done = false;
        byte[] block = new byte[255];
        int blockLength;
        while ((blockLength = read1()) != 0) { // sub-block chain ends with a zero-length block
            readFully(block, blockLength);
            if (done || produced >= out.length) {
                continue; // already finished; just consume the remaining sub-blocks
            }
            int blockIndex = 0;
            while (blockIndex < blockLength && !done && produced < out.length) {
                datum |= (block[blockIndex++] & 0xFF) << bits;
                bits += 8;
                while (bits >= codeSize && !done && produced < out.length) {
                    int code = datum & codeMask;
                    datum >>>= codeSize;
                    bits -= codeSize;

                    if (code == clearCode) {
                        codeSize = minCodeSize + 1;
                        codeMask = (1 << codeSize) - 1;
                        available = eoiCode + 1;
                        oldCode = -1;
                        continue;
                    }
                    if (code == eoiCode) {
                        done = true;
                        break;
                    }
                    if (oldCode == -1) { // first code after a clear must be a literal
                        if (code >= clearCode) {
                            throw new IOException("Malformed GIF: LZW data begins with invalid code " + code);
                        }
                        out[produced++] = (byte) code;
                        firstPixel = code;
                        oldCode = code;
                        continue;
                    }

                    int inCode = code;
                    if (code >= available) {
                        if (code > available) {
                            throw new IOException("Malformed GIF: LZW code " + code + " out of range");
                        }
                        stack[stackTop++] = (byte) firstPixel; // KwKwK: code not in table yet
                        code = oldCode;
                    }
                    while (code >= clearCode) { // walk the prefix chain down to a literal
                        stack[stackTop++] = suffix[code];
                        code = prefix[code];
                    }
                    firstPixel = suffix[code] & 0xFF;
                    stack[stackTop++] = (byte) firstPixel;

                    if (available < MAX_LZW_CODES) {
                        prefix[available] = oldCode;
                        suffix[available] = (byte) firstPixel;
                        available++;
                        if ((available & codeMask) == 0 && available < MAX_LZW_CODES) {
                            codeSize++;
                            codeMask += available;
                        }
                    }
                    oldCode = inCode;

                    while (stackTop > 0 && produced < out.length) {
                        out[produced++] = stack[--stackTop];
                    }
                    stackTop = 0; // anything left over would not fit in the frame anyway
                }
            }
        }
        return produced;
    }

    // ------------------------------------------------------------------
    // Stream helpers
    // ------------------------------------------------------------------

    private int read1() throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new IOException("Unexpected end of GIF data");
        }
        return value;
    }

    /** Reads a little-endian unsigned 16-bit value. */
    private int read2() throws IOException {
        return read1() | read1() << 8;
    }

    private void readFully(byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new IOException("Unexpected end of GIF data");
            }
            offset += read;
        }
    }

    private void skipFully(int count) throws IOException {
        try {
            in.skipNBytes(count);
        } catch (EOFException e) {
            throw new IOException("Unexpected end of GIF data");
        }
    }

    private void skipSubBlocks() throws IOException {
        int length;
        while ((length = read1()) != 0) {
            skipFully(length);
        }
    }
}
