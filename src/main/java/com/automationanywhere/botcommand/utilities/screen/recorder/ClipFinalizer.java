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

    /** The flat segment list: FFmpeg's ring list in {@code ring/} and our copy of it in a scratch folder. */
    static final String MANIFEST = "list.txt";

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
        encodeSegmentsToMp4(ffmpegExe, segmentsDir, targetMp4, mode, segmentsInOrder(segmentsDir));
    }

    static void encodeSegmentsToMp4(Path ffmpegExe, Path segmentsDir, Path targetMp4,
            EncodingMode mode, List<Path> segments) throws IOException, InterruptedException {
        if (segments.isEmpty()) {
            throw new IOException("No .mov segments to encode in " + segmentsDir);
        }

        Path concatList = segmentsDir.resolve("concat-list.txt");
        writeConcatList(segments, concatList);

        Path tmpOut = segmentsDir.resolve("out.mp4.tmp");
        Files.deleteIfExists(tmpOut);

        String[] cmd = buildEncodeCommand(ffmpegExe, concatList, tmpOut, mode);

        // A file, not a pipe: a failing encode can write more than the OS pipe buffer and then block.
        Path output = segmentsDir.resolve("ffmpeg-output.txt");
        Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();

        int exitCode;
        try {
            boolean done = process.waitFor(ENCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!done) {
                kill(process);
                throw new IOException("ffmpeg encode timed out after "
                        + ENCODE_TIMEOUT_SECONDS + " s for " + targetMp4
                        + outputHead(output));
            }
            exitCode = process.exitValue();
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw e;
        }

        if (exitCode != 0) {
            throw new IOException("ffmpeg encode exit code " + exitCode
                    + " for " + targetMp4 + outputHead(output));
        }

        if (!Files.exists(tmpOut) || Files.size(tmpOut) == 0) {
            throw new IOException("ffmpeg produced empty output at " + tmpOut);
        }

        Files.createDirectories(targetMp4.getParent());
        moveIntoPlace(tmpOut, targetMp4);
    }

    /** Publishes the finished clip so that its final name only ever appears complete. */
    static void moveIntoPlace(Path tmpOut, Path targetMp4) throws IOException {
        try {
            Files.move(tmpOut, targetMp4, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Another volume: a plain move would grow the file under its final name.
            // Copy it next to the target first, then rename inside that volume.
            Path part = targetMp4.resolveSibling(targetMp4.getFileName() + ".part");
            Files.copy(tmpOut, part, StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, targetMp4, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(tmpOut);
        }
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
        // A kill between create and write must not leave a torn list behind.
        Path temporary = directory.resolve(MANIFEST + ".tmp");
        Files.writeString(temporary, list.toString());
        Files.move(temporary, directory.resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE);
    }

    /** Scratch folders only: the list is written after every copy is complete, so an unusable one falls back to file time. */
    static List<Path> segmentsInOrder(Path dir) throws IOException {
        List<Path> listed = readSegmentList(dir);
        return listed == null || listed.isEmpty() ? listMovFilesByMtimeAsc(dir) : listed;
    }

    static List<Path> completedRingSegments(Path dir, int bufferSeconds) throws IOException {
        if (bufferSeconds <= 0) throw new IOException("Invalid ring buffer size");
        List<Path> listed = readSegmentList(dir);
        int ringSize = bufferSeconds + 1;
        if (listed == null || listed.isEmpty() || listed.size() > ringSize) return List.of();
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
        return listed;
    }

    private static List<Path> readSegmentList(Path dir) throws IOException {
        Path manifest = dir.resolve(MANIFEST);
        if (!Files.exists(manifest)) return null;
        String list = Files.readString(manifest, StandardCharsets.UTF_8);
        // FFmpeg's flat list contains raw basenames and is published by rename after each trailer.
        if (list.isEmpty() || !list.endsWith("\n")) return new ArrayList<>();
        List<Path> segments = new ArrayList<>();
        for (String line : list.split("\n")) {
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

    /** Kills FFmpeg and gives it a moment to release its files. */
    private static void kill(Process process) {
        process.destroyForcibly();
        try {
            process.waitFor(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The first lines of an FFmpeg output file, for an error message. */
    static String outputHead(Path output) {
        List<String> head = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(Files.newInputStream(output), StandardCharsets.UTF_8))) {
            String line;
            while (head.size() < 8 && (line = reader.readLine()) != null) head.add(line);
        } catch (IOException unreadable) {
            // No output file: nothing to add.
        }
        return head.isEmpty() ? "" : "; tail: " + String.join(" | ", head);
    }
}
