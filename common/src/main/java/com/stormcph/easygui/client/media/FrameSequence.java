package com.stormcph.easygui.client.media;

import com.stormcph.easygui.client.widget.VideoView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A video source backed by a folder (or zip archive) of individually encoded
 * {@code .jpg}/{@code .jpeg}/{@code .png} frames, played in alphabetical order at a
 * caller-given frame rate — the dead-simple "image sequence" format every tool can
 * export ({@code ffmpeg -i in.mp4 frames/f_%04d.jpg}).
 *
 * <p>Implements the same {@link VideoView.FrameSource} contract as {@link MjpegStream},
 * so {@link VideoView} drives both identically: {@link #nextFrame()} returns one frame's
 * still-encoded bytes (decoded later by the caller, off the render thread), and because
 * every frame is independently addressable the sequence is always fully
 * {@linkplain #isSeekable() seekable} — {@link #skipFrame()} and {@link #seekToFrame}
 * are just index arithmetic with zero I/O.</p>
 *
 * <p>Frames are read lazily, one at a time, so a long sequence never sits in memory all
 * at once. Ordering is a case-insensitive name sort: number your frames with zero-padded
 * names ({@code f_0001.png}, …) or {@code f_10} will sort before {@code f_2}. Entries
 * starting with {@code .} and zip metadata folders ({@code __MACOSX}) are ignored.
 * Like {@link MjpegStream}, instances are not thread-safe — they are meant to be driven
 * by a single decode thread — and zip-backed sequences must be {@link #close()}d.</p>
 */
@Environment(EnvType.CLIENT)
public final class FrameSequence implements VideoView.FrameSource {
    private final List<Entry> frames;
    private final double fps;
    private final ZipFile zip; // null for directory-backed sequences
    private int next;
    private boolean closed;

    private FrameSequence(List<Entry> frames, double fps, ZipFile zip, String source) throws IOException {
        if (fps <= 0 || !Double.isFinite(fps)) {
            throw new IllegalArgumentException("fps must be > 0, got " + fps);
        }
        if (frames.isEmpty()) {
            throw new IOException("No .jpg/.jpeg/.png frames found in " + source);
        }
        frames.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Entry::name));
        this.frames = frames;
        this.fps = fps;
        this.zip = zip;
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /** Opens {@code dirOrZip} as a frame sequence, dispatching on whether it is a directory. */
    public static FrameSequence of(Path dirOrZip, double fps) throws IOException {
        return Files.isDirectory(dirOrZip) ? fromDirectory(dirOrZip, fps) : fromZip(dirOrZip, fps);
    }

    /** A sequence of the image files directly inside {@code dir} (not recursive). */
    public static FrameSequence fromDirectory(Path dir, double fps) throws IOException {
        List<Entry> frames = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            for (Path p : (Iterable<Path>) children::iterator) {
                String name = p.getFileName().toString();
                if (isFrameName(name) && Files.isRegularFile(p)) {
                    frames.add(new Entry(name, () -> Files.readAllBytes(p)));
                }
            }
        }
        return new FrameSequence(frames, fps, null, dir.toString());
    }

    /** A sequence of the image entries in a zip archive (any folder structure inside). */
    public static FrameSequence fromZip(Path zipFile, double fps) throws IOException {
        ZipFile zip = new ZipFile(zipFile.toFile());
        try {
            List<Entry> frames = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String full = entry.getName();
                if (entry.isDirectory() || full.startsWith("__MACOSX")) {
                    continue;
                }
                String name = full.substring(full.lastIndexOf('/') + 1);
                if (isFrameName(name)) {
                    frames.add(new Entry(full, () -> {
                        try (InputStream in = zip.getInputStream(entry)) {
                            return in.readAllBytes();
                        }
                    }));
                }
            }
            return new FrameSequence(frames, fps, zip, zipFile.toString());
        } catch (IOException | RuntimeException e) {
            try {
                zip.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private static boolean isFrameName(String name) {
        if (name.isEmpty() || name.charAt(0) == '.') {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    // ------------------------------------------------------------------
    // FrameSource
    // ------------------------------------------------------------------

    @Override
    public double fps() {
        return fps;
    }

    @Override
    public int frameCount() {
        return frames.size();
    }

    @Override
    public boolean isSeekable() {
        return true;
    }

    @Override
    public byte[] nextFrame() throws IOException {
        checkOpen();
        if (next >= frames.size()) {
            return null;
        }
        return frames.get(next++).loader.load();
    }

    @Override
    public boolean skipFrame() throws IOException {
        checkOpen();
        if (next >= frames.size()) {
            return false;
        }
        next++;
        return true;
    }

    @Override
    public void reset() {
        next = 0;
    }

    @Override
    public boolean seekToFrame(int index) {
        next = Math.max(0, Math.min(index, frames.size()));
        return true;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            if (zip != null) {
                zip.close();
            }
        }
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("Sequence is closed");
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Reads one frame's encoded bytes; runs on the decode thread, so blocking I/O is fine. */
    @FunctionalInterface
    private interface FrameLoader {
        byte[] load() throws IOException;
    }

    private record Entry(String name, FrameLoader loader) {
    }
}
