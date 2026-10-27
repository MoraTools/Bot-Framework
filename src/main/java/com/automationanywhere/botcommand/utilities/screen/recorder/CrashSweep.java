package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.apache.logging.log4j.LogManager;
import com.automationanywhere.botcommand.utilities.helios.HeliosMediaOutbox;
import org.json.JSONObject;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Once-per-JVM background sweep that recovers recordings left behind by
 * sessions whose JVM died, or whose {@link ScreenRecorder#close()} drain
 * timed out, before the encoder pool finished all queued encodes.
 *
 * <p>For each {@code sessions\<id>} folder under
 * {@code %LOCALAPPDATA%\A360-BotFramework\}:
 * <ol>
 *   <li>Skip sessions with a live or unknown owner. Hold one sweep lock so another
 *       JVM cannot remove work that is being salvaged.</li>
 *   <li>Retry any {@code .salvaging} folder left by an interrupted sweep.</li>
 *   <li>Otherwise atomically rename it to {@code <id>.salvaging} to claim it.
 *       If the rename fails, another JVM has it - skip.</li>
 *   <li>If {@code session.active} exists in the claimed folder, salvage in
 *       this order:
 *       <ul>
 *         <li>Encode any pending {@code scratch/<errorUuid>/} subdirs into
 *             {@code <logDir>/clips/<errorUuid>.mp4} using the encoding
 *             mode persisted in {@link SessionMeta}. The HTML log already
 *             references those exact paths, so previously-broken video
 *             links self-heal once the next bot run completes the sweep.</li>
 *         <li>If the ring has any complete segments, encode them into a
 *             {@code crash-recording-<id>.mp4} alongside the per-error
 *             clips, when the log folder still exists.</li>
 *         <li>Append one summary line to {@code crash-log.txt}.</li>
 *         <li>Remove {@code session.active}. A folder that stays on disk
 *             (a media record is not durable yet) is then never salvaged
 *             twice; only its pending scratches are retried.</li>
 *       </ul></li>
 *   <li>Delete the claimed folder after terminal recovery and durable media handling.
 *       Preserve the sources if recovery is interrupted.</li>
 * </ol>
 *
 * <p>Runs on a daemon thread at {@link Thread#MIN_PRIORITY} so it yields to
 * the active session's actual work.
 *
 * @author Sumit Kumar
 */
final class CrashSweep {

    private static final Logger LOGGER = LogManager.getLogger(CrashSweep.class);

    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);
    private static final String SALVAGING_SUFFIX = ".salvaging";
    private static final String SCRATCH_DIR = "scratch";
    private static final String RING_DIR = "ring";
    private static final String SESSION_ACTIVE_MARKER = "session.active";
    private static final String SESSION_META = "session.meta";
    private static final String CLIPS_DIR = "clips";
    private static final String CRASH_LOG = "crash-log.txt";
    /** A folder without session.meta is a leftover once it has been idle this long. */
    private static final Duration ORPHAN_AGE = Duration.ofDays(1);
    private static final DateTimeFormatter LOG_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CrashSweep() {
    }

    /** Schedules at most one sweep across the entire JVM lifetime. Returns immediately. */
    static void scheduleOnce(Path appDataRoot, Path ffmpegExe) {
        if (!SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> sweep(appDataRoot, ffmpegExe), "a360-recorder-sweep");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** One sweep on the calling thread. {@link #scheduleOnce} runs it once per JVM on a daemon thread. */
    static void sweep(Path appDataRoot, Path ffmpegExe) {
        Path sessionsRoot = appDataRoot.resolve("sessions");
        if (!Files.isDirectory(sessionsRoot)) {
            return;
        }
        // Keep the lock file: deleting it would let two processes lock different file instances.
        try (FileChannel channel = FileChannel.open(sessionsRoot.resolve(".sweep.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) return;
            try (Stream<Path> dirs = Files.list(sessionsRoot)) {
                dirs.filter(folder -> Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
                        .forEach(folder -> processOne(folder, ffmpegExe));
            }
        } catch (OverlappingFileLockException busy) {
            // Another sweep in this JVM owns the root.
        } catch (IOException e) {
            LOGGER.debug("Sweep listing failed at {}: {}", sessionsRoot, e.toString());
        }
    }

    private static void processOne(Path folder, Path ffmpegExe) {
        if (ownerMayBeRunning(folder)) return;
        String name = folder.getFileName().toString();
        Path claimed = folder;
        String sessionId = name.endsWith(SALVAGING_SUFFIX)
                ? name.substring(0, name.length() - SALVAGING_SUFFIX.length()) : name;
        if (!name.endsWith(SALVAGING_SUFFIX)) {
            claimed = folder.resolveSibling(name + SALVAGING_SUFFIX);
            try {
                Files.move(folder, claimed, StandardCopyOption.ATOMIC_MOVE);
            } catch (NoSuchFileException e) {
                return;
            } catch (IOException e) {
                LOGGER.debug("Could not claim {}: {}", folder, e.toString());
                return;
            }
        }
        try {
            if (Files.exists(claimed.resolve(SESSION_ACTIVE_MARKER)) || hasManagedScratches(claimed))
                trySalvage(claimed, sessionId, ffmpegExe);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        } catch (IOException terminal) {
            LOGGER.debug("Recovery failed for session {}: {}", sessionId, terminal.toString());
        }
        if (Thread.currentThread().isInterrupted() || hasManagedScratches(claimed)) return;
        ScreenRecorder.deleteRecursivelyQuietly(claimed);
    }

    private static boolean hasManagedScratches(Path folder) {
        Path scratch = folder.resolve(SCRATCH_DIR);
        if (!Files.isDirectory(scratch)) return false;
        try (Stream<Path> files = Files.walk(scratch)) {
            return files.anyMatch(path -> path.getFileName().toString().equals("capture.json"));
        } catch (IOException error) { return true; }
    }

    private static boolean ownerMayBeRunning(Path folder) {
        Path metaFile = folder.resolve(SESSION_META);
        if (!Files.exists(metaFile)) {
            // Another bot may still be starting its recorder, but it writes the meta within moments.
            // A folder we claimed ourselves, or one idle for a day, is what a failed delete left behind.
            return !folder.getFileName().toString().endsWith(SALVAGING_SUFFIX) && !olderThanOrphanAge(folder);
        }
        try {
            SessionMeta meta = SessionMeta.read(metaFile);
            // Conservatively preserve reused PIDs too; a live process is never a deletion target.
            return meta.jvmPid <= 0 || ProcessHandle.of(meta.jvmPid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean olderThanOrphanAge(Path folder) {
        try {
            return Files.getLastModifiedTime(folder).toInstant().isBefore(Instant.now().minus(ORPHAN_AGE));
        } catch (IOException e) {
            return false;
        }
    }

    private static void trySalvage(Path claimedFolder, String sessionId, Path ffmpegExe)
            throws IOException, InterruptedException {
        Path metaFile = claimedFolder.resolve(SESSION_META);

        SessionMeta meta;
        try {
            meta = SessionMeta.read(metaFile);
        } catch (Exception e) {
            throw new IOException("Session metadata unreadable for " + sessionId, e);
        }

        EncodingMode mode = meta.encodingMode != null ? meta.encodingMode : EncodingMode.FAST;
        Path logDir = resolveLogDir(meta);

        // 1. Per-error scratches: re-encode each into the exact path the HTML
        //    log already references. This is the load-bearing recovery for
        //    drain-timeout / kill-mid-encode scenarios.
        List<Path> recoveredClips = recoverPendingScratches(claimedFolder, ffmpegExe, mode, logDir, sessionId);

        // 2. Ring buffer: produce one crash-recording-<id>.mp4 alongside the
        //    per-error clips. Useful for hard-kill cases where the ring still
        //    holds the last bufferSeconds of footage; mostly noise for clean
        //    drain-timeout cases (ring content is "moment of close") but
        //    cheap and consistent. Only while the marker exists: it is removed
        //    below, so a folder that stays on disk is not salvaged again. Not
        //    when the log folder is gone: nothing could link to the recording.
        Path marker = claimedFolder.resolve(SESSION_ACTIVE_MARKER);
        Path crashRecording = !Files.exists(marker) || logDir == null
                ? null : trySalvageRing(claimedFolder, ffmpegExe, mode, logDir, sessionId, meta.bufferSeconds);

        if (!recoveredClips.isEmpty() || crashRecording != null) {
            Path summaryDir = crashRecording != null ? crashRecording.getParent()
                    : recoveredClips.get(0).getParent();
            appendCrashLog(summaryDir, meta, crashRecording, recoveredClips);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Recovery interrupted");
        Files.deleteIfExists(marker);
    }

    /**
     * Resolves the original logDir from {@link SessionMeta} if it still
     * exists on disk, otherwise returns null.
     */
    private static Path resolveLogDir(SessionMeta meta) {
        if (meta.logDir == null || meta.logDir.isEmpty()) {
            return null;
        }
        Path logDir = Path.of(meta.logDir);
        return Files.isDirectory(logDir) ? logDir : null;
    }

    /**
     * Walks {@code claimedFolder/scratch/<errorUuid>/} subdirs and re-encodes
     * each into {@code <logDir>/clips/<errorUuid>.mp4}. Each subdir is
     * processed independently: a corrupt or empty scratch fails its own
     * encode and the loop continues. Already-existing target mp4s are
     * skipped (idempotent: a partially-completed prior sweep can resume).
     *
     * <p>If the original logDir is gone, the per-error clips are dropped on
     * the floor: the HTML log they would have populated is also gone, so
     * orphan mp4s would be unreachable and just leak disk. Skip and let the scratches be cleaned along with the rest of
     * the session folder.
     *
     * @return the absolute paths of clips that were successfully encoded in
     *         this pass (empty when there are no scratches, when logDir is
     *         missing, or when every scratch failed to encode).
     */
    private static List<Path> recoverPendingScratches(Path claimedFolder, Path ffmpegExe,
                                                      EncodingMode mode, Path logDir, String sessionId)
            throws InterruptedException {
        Path scratchRoot = claimedFolder.resolve(SCRATCH_DIR);
        if (!Files.isDirectory(scratchRoot)) return List.of();
        if (logDir == null && !hasManagedScratches(claimedFolder)) {
            LOGGER.info("Session {} has pending scratches but original logDir is gone; "
                    + "nothing useful to recover (HTML log is also missing)", sessionId);
            return List.of();
        }
        Path clipsDir = logDir == null ? claimedFolder.resolve(CLIPS_DIR) : logDir.resolve(CLIPS_DIR);
        try { Files.createDirectories(clipsDir); }
        catch (IOException error) {
            LOGGER.warn("Could not create clips dir {} for session {}: {}", clipsDir, sessionId, error.toString());
            return List.of();
        }
        List<Path> recovered = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(scratchRoot)) {
            for (Path scratchDir : dirs.filter(Files::isDirectory).collect(java.util.stream.Collectors.toList())) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Recovery interrupted");
                String errorUuid = scratchDir.getFileName().toString();
                Path captureFile = scratchDir.resolve("capture.json");
                if (Files.isRegularFile(captureFile)) {
                    recoverMediaScratch(scratchDir, captureFile, ffmpegExe, mode, recovered);
                    continue;
                }
                Path target = clipsDir.resolve(errorUuid + ".mp4");
                if (!Files.exists(target)) {
                    try {
                        ClipFinalizer.encodeSegmentsToMp4(ffmpegExe, scratchDir, target, mode);
                        recovered.add(target);
                        LOGGER.info("Recovered pending clip for session {} -> {}", sessionId, target);
                    } catch (IOException terminal) {
                        LOGGER.debug("Could not recover scratch {}/{}: {}", sessionId, errorUuid, terminal.toString());
                    }
                }
                // Success or a terminal encoder result makes this source safe to remove.
                ScreenRecorder.deleteRecursivelyQuietly(scratchDir);
            }
        } catch (IOException error) {
            LOGGER.debug("Could not list scratches under {}: {}", scratchRoot, error.toString());
        }
        return recovered;
    }

    private static void recoverMediaScratch(Path scratch, Path metadata, Path ffmpeg, EncodingMode mode,
                                            List<Path> recovered) throws InterruptedException {
        try {
            JSONObject capture = new JSONObject(Files.readString(metadata));
            Path target = Path.of(capture.getString("target"));
            // An existing target is not proof that the interrupted finalizer returned.
            try {
                ClipFinalizer.encodeSegmentsToMp4(ffmpeg, scratch, target, mode);
            } catch (IOException terminal) {
                if (HeliosMediaOutbox.captureFinished(capture.getString("captureId"), null, "encodeFailed"))
                    ScreenRecorder.deleteRecursivelyQuietly(scratch);
                return;
            }
            if (!HeliosMediaOutbox.captureFinished(capture.getString("captureId"), target, null)) return;
            recovered.add(target);
            ScreenRecorder.deleteRecursivelyQuietly(scratch);
        } catch (IOException retained) {
            LOGGER.debug("Could not finish managed recovery at {}: {}", scratch, retained.toString());
        } catch (RuntimeException damaged) {
            // Keep unrecognized ownership metadata; never attach its ring to another row.
        }
    }

    /**
     * Encodes the ring buffer to {@code <logDir>/clips/crash-recording-<id>.mp4}.
     * Returns the encoded path, or null when the ring is empty or the encode
     * fails.
     */
    private static Path trySalvageRing(Path claimedFolder, Path ffmpegExe, EncodingMode mode,
                                       Path logDir, String sessionId, int bufferSeconds)
            throws InterruptedException {
        Path ringDir = claimedFolder.resolve(RING_DIR);
        if (!Files.isDirectory(ringDir)) {
            LOGGER.debug("No ring/ in session {}; skipping ring salvage", sessionId);
            return null;
        }
        Path target = logDir.resolve(CLIPS_DIR).resolve("crash-recording-" + sessionId + ".mp4");
        try {
            Files.createDirectories(target.getParent());
            List<Path> segments = Files.exists(ringDir.resolve(ClipFinalizer.MANIFEST))
                    ? ClipFinalizer.completedRingSegments(ringDir, bufferSeconds)
                    : ClipFinalizer.listMovFilesByMtimeAsc(ringDir);
            ClipFinalizer.encodeSegmentsToMp4(ffmpegExe, ringDir, target, mode, segments);
            LOGGER.info("Salvaged ring buffer for session {} -> {}", sessionId, target);
            return target;
        } catch (IOException | RuntimeException e) {
            // Empty rings are normal when stage-1 produced nothing usable; log
            // at debug to avoid alarming users with a stack-y warning.
            LOGGER.debug("Ring salvage skipped for session {}: {}", sessionId, e.toString());
            return null;
        }
    }

    private static void appendCrashLog(Path clipsDir, SessionMeta meta, Path crashRecording,
                                       List<Path> recoveredClips) {
        Path log = clipsDir.resolve(CRASH_LOG);
        StringBuilder line = new StringBuilder(160)
                .append(LOG_TIMESTAMP.format(Instant.now().atZone(java.time.ZoneId.systemDefault())))
                .append("  session ").append(meta.sessionId)
                .append(" crashed (started ").append(meta.startedAt)
                .append(", encoder ").append(meta.encodingMode).append(")");
        if (crashRecording != null) {
            line.append(" - ring salvaged to ").append(crashRecording.getFileName());
        }
        if (!recoveredClips.isEmpty()) {
            line.append(" - ").append(recoveredClips.size()).append(" pending clip(s) recovered");
        }
        line.append(System.lineSeparator());
        try {
            Files.write(log, line.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.debug("Could not append to crash log {}: {}", log, e.toString());
        }
    }
}
