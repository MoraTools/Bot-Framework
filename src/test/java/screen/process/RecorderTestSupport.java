package screen.process;

import com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode;
import com.automationanywhere.botcommand.utilities.screen.recorder.FfmpegBinary;
import com.automationanywhere.botcommand.utilities.screen.recorder.SessionMeta;
import org.testng.SkipException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Shared helpers for tests that need to wait on real ffmpeg side effects.
 * Polls for the condition rather than using fixed Thread.sleep windows so
 * tests stay deterministic even on slow runners.
 */
public final class RecorderTestSupport {

    private RecorderTestSupport() {
    }

    /**
     * Returns the ring directory under
     * {@code %LOCALAPPDATA%\A360-BotFramework\sessions\<sessionId>\ring}.
     * Throws if the folder doesn't exist (e.g. recorder failed to start).
     */
    public static Path sessionRing(String sessionId) throws IOException {
        Path appData = FfmpegBinary.appDataRoot();
        Path ring = appData.resolve("sessions").resolve(sessionId).resolve("ring");
        if (!Files.isDirectory(ring)) {
            throw new IOException("session ring directory not found at " + ring);
        }
        return ring;
    }

    /** Counts {@code .mov} files in {@code dir}; returns 0 if the dir is gone. */
    public static long countMov(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mov"))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Polls {@code ringDir} until at least {@code minSegments} {@code .mov}
     * files exist, or {@code timeout} elapses. Throws AssertionError on
     * timeout so the caller's stack trace points at the wait that failed.
     *
     * <p>The caller usually wants {@code minSegments == 2}: that means at
     * least one segment is fully written (stable) plus one in-flight, which
     * is exactly what {@link com.automationanywhere.botcommand.utilities.screen.recorder.ScreenRecorder}
     * snapshots from on a level-trigger.
     */
    public static void awaitMovSegments(Path ringDir, int minSegments, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long count;
        while (true) {
            count = countMov(ringDir);
            if (count >= minSegments) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out after " + timeout + " waiting for >="
                + minSegments + " .mov segments in " + ringDir
                + " (last seen: " + count + ")");
    }

    /**
     * Convenience: poll the ring of the supplied {@code sessionId} until
     * at least {@code minSegments} .mov files exist, or {@code timeout}
     * elapses. Throws AssertionError on timeout.
     */
    public static void awaitSessionMovSegments(String sessionId, int minSegments,
                                                Duration timeout)
            throws IOException, InterruptedException {
        awaitMovSegments(sessionRing(sessionId), minSegments, timeout);
    }

    /**
     * Polls {@code clipsDir} until at least {@code minClips} {@code .mp4}
     * files exist, or {@code timeout} elapses. Use after
     * {@code StopLoggerSession.stop()} - close() is non-blocking, so encodes
     * may finish on background threads after the session closes.
     */
    public static void awaitMp4Clips(Path clipsDir, int minClips, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long count;
        while (true) {
            count = countByExt(clipsDir, ".mp4");
            if (count >= minClips) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out after " + timeout + " waiting for >="
                + minClips + " .mp4 clips in " + clipsDir
                + " (last seen: " + count + ")");
    }

    /** Counts the files in {@code dir} whose name ends in {@code ext}, in any letter case; 0 if the dir is gone. */
    public static long countByExt(Path dir, String ext) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            String lower = ext.toLowerCase();
            return s.filter(p -> p.toString().toLowerCase().endsWith(lower))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Sleeps until just after the next 1-second boundary, so the snapshots that follow share one dedup chunk. */
    public static void awaitNextChunk() throws InterruptedException {
        Thread.sleep(1000 - System.currentTimeMillis() % 1000 + 5);
    }

    /** Points the Helios media outbox at {@code directory} until the returned handle is closed. */
    public static AutoCloseable useOutbox(Path directory) {
        String previous = System.getProperty("helios.outbox.directory");
        System.setProperty("helios.outbox.directory", directory.toString());
        return () -> {
            if (previous == null) System.clearProperty("helios.outbox.directory");
            else System.setProperty("helios.outbox.directory", previous);
        };
    }

    public static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
    }

    /**
     * Publishes a flat segment list the way FFmpeg's segment muxer does: the names are written to
     * {@code list.txt.tmp} and renamed over {@code list.txt}, so a reader never sees a torn list.
     */
    public static void publishSegmentList(Path ringDir, String... names) throws IOException {
        Path temporary = ringDir.resolve("list.txt.tmp");
        Files.writeString(temporary, names.length == 0 ? "" : String.join("\n", names) + "\n");
        Files.move(temporary, ringDir.resolve("list.txt"), StandardCopyOption.ATOMIC_MOVE);
    }

    /** Skips the calling test when there is no POSIX shell to stand in for ffmpeg. */
    public static void requireShell() {
        if (!Files.isExecutable(Path.of("/bin/sh"))) throw new SkipException("The ffmpeg stub needs a POSIX shell");
    }

    /**
     * Writes an executable shell script that stands in for ffmpeg. Its last argument, the output file, is
     * available as {@code $last}.
     */
    public static Path shellStub(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/sh\nfor last; do :; done\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    /** Quotes a path for use inside {@link #shellStub}. */
    public static String shellQuote(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    /**
     * Creates {@code <root>/sessions/<name>} the way a crashed recorder leaves it: a session.meta whose
     * owner is not running and the session.active marker.
     */
    public static Path deadSession(Path root, Path logs, String name, int bufferSeconds) throws IOException {
        Path session = Files.createDirectories(root.resolve("sessions").resolve(name));
        new SessionMeta(name, logs.toString(), bufferSeconds, Instant.now(), Long.MAX_VALUE,
                EncodingMode.FAST).write(session.resolve("session.meta"));
        Files.createFile(session.resolve("session.active"));
        return session;
    }
}
