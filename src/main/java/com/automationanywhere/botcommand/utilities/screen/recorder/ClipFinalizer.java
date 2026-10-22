package com.automationanywhere.botcommand.utilities.screen.recorder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Stage-2 encoder helper. Takes a directory of ffvhuff .mov segments produced
 * by ScreenRecorder's stage-1 ring buffer, runs ffmpeg with the concat demuxer
 * + the requested encoder, and lands a browser-playable mp4 at the target
 * path. Stateless and synchronous - the caller owns threading.
 *
 * <p>Used both by {@code RealScreenRecorder}'s per-error encoder pool and by
 * {@link CrashSweep} for orphan ring salvage outside any session.
 *
 * <p>{@link EncodingMode#FAST} uses libx264 with {@code -preset ultrafast}
 * (~0.5-1 s for 30 s of footage, ~3-5x larger files).
 *
 * <p>{@link EncodingMode#COMPACT} uses libaom-av1 with {@code -cpu-used 8}
 * (~5-15 s for 30 s of footage, smaller files). The wall-time cost lives
 * entirely in the per-session encoder pool and never blocks the bot.
 *
 * <p>Both encoders are present in the bundled ffmpeg.exe; see
 * {@code tools/ffmpeg-build/Dockerfile} for the cross-compile recipe.
 *
 * @author Sumit Kumar
 */
public final class ClipFinalizer {

    private static final int ENCODE_TIMEOUT_SECONDS = 120;

    private ClipFinalizer() {
    }

    /**
     * Encodes all .mov files in {@code segmentsDir} into {@code targetMp4}
     * using the chosen encoder. Synchronous - blocks the caller's thread for
     * the full duration of the stage-2 ffmpeg invocation.
     *
     * @throws IOException          on any io / ffmpeg failure
     * @throws InterruptedException if the calling thread is interrupted while
     *                              waiting for ffmpeg
     */
    public static void encodeSegmentsToMp4(Path ffmpegExe, Path segmentsDir, Path targetMp4,
                                           EncodingMode mode)
            throws IOException, InterruptedException {
        encodeSegmentsToMp4(ffmpegExe, segmentsDir, targetMp4, mode, Long.MAX_VALUE);
    }

    public static void encodeSegmentsToMp4(Path ffmpegExe, Path segmentsDir, Path targetMp4,
            EncodingMode mode, long maximumBytes) throws IOException, InterruptedException {
        encodeSegmentsToMp4(ffmpegExe, segmentsDir, targetMp4, mode, maximumBytes,
                segmentsInOrder(segmentsDir));
    }

    static void encodeSegmentsToMp4(Path ffmpegExe, Path segmentsDir, Path targetMp4,
            EncodingMode mode, long maximumBytes, List<Path> segments) throws IOException, InterruptedException {
        if (segments.isEmpty()) {
            throw new IOException("No .mov segments to encode in " + segmentsDir);
        }

        Path concatList = segmentsDir.resolve("concat-list.txt");
        writeConcatList(segments, concatList);

        Path tmpOut = segmentsDir.resolve("out.mp4.tmp");
        Files.deleteIfExists(tmpOut);

        String[] cmd = buildEncodeCommand(ffmpegExe, concatList, tmpOut, mode);
        if (maximumBytes != Long.MAX_VALUE) {
            List<String> bounded = new ArrayList<>(Arrays.asList(cmd));
            bounded.add(bounded.size() - 1, "-fs");
            bounded.add(bounded.size() - 1, Long.toString(maximumBytes + 1));
            cmd = bounded.toArray(new String[0]);
        }

        Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

        // ffmpeg's combined stdout/stderr must be drained continuously: with
        // -loglevel error normal runs are quiet, but a failing encode can emit
        // more than the OS pipe buffer (~64 KB on Windows), at which point the
        // ffmpeg writer blocks and waitFor never sees the process exit until
        // the timeout fires. Drain on a background thread; capture the first
        // few lines for error reporting.
        OutputCapture capture = new OutputCapture();
        Thread drainer = new Thread(
                () -> drainInto(process.getInputStream(), capture),
                "a360-clip-encoder-drain");
        drainer.setDaemon(true);
        drainer.start();

        int exitCode;
        try {
            boolean done = process.waitFor(ENCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                joinQuietly(drainer, 500);
                throw new IOException("ffmpeg encode timed out after "
                        + ENCODE_TIMEOUT_SECONDS + " s for " + targetMp4
                        + capture.suffix());
            }
            exitCode = process.exitValue();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            joinQuietly(drainer, 500);
            Thread.currentThread().interrupt();
            throw e;
        }

        // Process has exited; drainer should finish promptly. Bound the wait
        // so a stuck reader can't pin this thread.
        joinQuietly(drainer, 1000);

        if (exitCode != 0) {
            throw new IOException("ffmpeg encode exit code " + exitCode
                    + " for " + targetMp4 + capture.suffix());
        }

        if (!Files.exists(tmpOut) || Files.size(tmpOut) == 0) {
            throw new IOException("ffmpeg produced empty output at " + tmpOut);
        }

        if (Files.size(tmpOut) > maximumBytes) throw new TooLargeException();
        Files.createDirectories(targetMp4.getParent());
        try {
            Files.move(tmpOut, targetMp4, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Cross-volume - the copy is non-atomic at byte level but the destination
            // filename only appears once the copy completes.
            Files.move(tmpOut, targetMp4, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static final class TooLargeException extends IOException {
        TooLargeException() { super("Cloud video exceeds the byte limit"); }
    }

    private static String[] buildEncodeCommand(Path ffmpegExe, Path concatList, Path tmpOut,
                                               EncodingMode mode) {
        String ff = ffmpegExe.toAbsolutePath().toString();
        String input = concatList.toAbsolutePath().toString();
        String output = tmpOut.toAbsolutePath().toString();

        List<String> command = new ArrayList<>(Arrays.asList(
                ff,
                "-hide_banner",
                "-loglevel", "error",
                "-f", "concat",
                "-safe", "0",
                "-fflags", "+igndts+genpts",
                "-i", input));
        if (mode == EncodingMode.FAST) {
            command.addAll(Arrays.asList(
                    "-c:v", "libx264",
                    "-preset", "ultrafast",
                    "-crf", "23"));
        } else {
            // COMPACT (default fallback): libaom-av1.
            command.addAll(Arrays.asList(
                    "-c:v", "libaom-av1",
                    "-b:v", "1500k",
                    "-cpu-used", "8",
                    "-row-mt", "1",
                    "-tile-columns", "2",
                    "-g", "60"));
        }
        command.addAll(Arrays.asList(
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                "-f", "mp4",
                output));
        return command.toArray(new String[0]);
    }

    private static void writeConcatList(List<Path> segments, Path concatList) throws IOException {
        StringBuilder sb = new StringBuilder(segments.size() * 80);
        for (Path seg : segments) {
            String posixPath = seg.toAbsolutePath().toString().replace('\\', '/');
            // Escape single quotes inside the file directive: ' -> '\''
            String escaped = posixPath.replace("'", "'\\''");
            sb.append("file '").append(escaped).append("'\n");
        }
        Files.write(concatList, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void writeSegmentOrder(Path directory, List<Path> segments) throws IOException {
        StringBuilder list = new StringBuilder();
        for (Path segment : segments) list.append(segment.getFileName()).append('\n');
        Files.writeString(directory.resolve("list.txt"), list.toString());
    }

    static List<Path> segmentsInOrder(Path dir) throws IOException {
        List<Path> listed = readSegmentList(dir);
        return listed == null ? listMovFilesByMtimeAsc(dir) : listed;
    }

    static List<Path> completedRingSegments(Path dir, int bufferSeconds) throws IOException {
        if (bufferSeconds <= 0 || bufferSeconds == Integer.MAX_VALUE) throw new IOException("Invalid ring buffer size");
        List<Path> listed = readSegmentList(dir);
        if (listed == null) {
            List<Path> legacy = listMovFilesByMtimeAsc(dir);
            return legacy.size() <= 1 ? List.of() : new ArrayList<>(legacy.subList(
                    Math.max(0, legacy.size() - bufferSeconds - 1), legacy.size() - 1));
        }
        int ringSize = bufferSeconds + 1;
        if (listed.isEmpty() || listed.size() > ringSize) return List.of();
        int previous = -1;
        for (Path segment : listed) {
            String name = segment.getFileName().toString();
            int slot;
            try { slot = Integer.parseInt(name.substring(0, name.length() - 4)); }
            catch (NumberFormatException invalid) { return List.of(); }
            if (slot < 0 || slot >= ringSize || !name.equals(slot + ".mov")
                    || (previous >= 0 && slot != (previous + 1) % ringSize)) return List.of();
            previous = slot;
        }
        // The next slot may already be open, even while its previous entry remains in the list.
        Path active = dir.resolve(((previous + 1) % ringSize) + ".mov");
        listed.remove(active);
        return new ArrayList<>(listed.subList(Math.max(0, listed.size() - bufferSeconds), listed.size()));
    }

    private static List<Path> readSegmentList(Path dir) throws IOException {
        Path manifest = dir.resolve("list.txt");
        if (!Files.exists(manifest)) return null;
        String list = Files.readString(manifest, StandardCharsets.UTF_8);
        // FFmpeg's flat list contains raw basenames and is published by rename after each trailer.
        if (list.isEmpty() || !list.endsWith("\n")) return new ArrayList<>();
        List<Path> segments = new ArrayList<>();
        for (String line : list.split("\n")) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            Path filename;
            try { filename = Path.of(line); }
            catch (java.nio.file.InvalidPathException invalid) { return new ArrayList<>(); }
            if (filename.isAbsolute() || filename.getNameCount() != 1 || line.indexOf('\\') >= 0
                    || !line.toLowerCase(java.util.Locale.ROOT).endsWith(".mov")) return new ArrayList<>();
            Path segment = dir.resolve(filename);
            if (!Files.isRegularFile(segment, LinkOption.NOFOLLOW_LINKS) || segments.contains(segment)) return new ArrayList<>();
            segments.add(segment);
        }
        return segments;
    }

    static List<Path> listMovFilesByMtimeAsc(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mov"))
                    .sorted(Comparator.comparingLong(ClipFinalizer::mtimeOrZero))
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    private static long mtimeOrZero(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void drainInto(java.io.InputStream in, OutputCapture capture) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                capture.accept(line);
            }
        } catch (IOException ignored) {
            // Stream closed (process exited or destroyed); drainer exits.
        }
    }

    private static void joinQuietly(Thread t, long millis) {
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Thread-safe capture of the first few lines of ffmpeg output for inclusion
     * in error messages. Subsequent lines are still drained from the pipe (so
     * ffmpeg never blocks on a full buffer) but not stored.
     */
    private static final class OutputCapture {
        private static final int MAX_LINES = 8;
        private final List<String> firstLines = new ArrayList<>(MAX_LINES);

        synchronized void accept(String line) {
            if (firstLines.size() < MAX_LINES) {
                firstLines.add(line);
            }
        }

        synchronized String suffix() {
            return firstLines.isEmpty() ? "" : "; tail: " + String.join(" | ", firstLines);
        }
    }
}
