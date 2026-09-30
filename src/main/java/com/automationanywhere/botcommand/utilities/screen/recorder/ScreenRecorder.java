package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.apache.logging.log4j.Level;
import com.automationanywhere.botcommand.utilities.helios.HeliosMediaOutbox;
import org.json.JSONObject;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Continuous screen recorder built on a child ffmpeg process.
 *
 * <p>Stage-1 (this class): a long-lived ffmpeg captures the desktop with
 * {@code gdigrab} into a ring of ffvhuff .mov segments under
 * {@code %LOCALAPPDATA%\A360-BotFramework\sessions\<sessionId>\ring\}.
 *
 * <p>Stage-2 (delegated to {@link ClipFinalizer}): when an error fires,
 * {@link #snapshotForError(String)} copies the ring slice into a per-error
 * scratch dir and queues a concat &rarr; libaom-av1 &rarr; mp4 finalize that
 * lands under {@code <logDir>/clips/<errorUuid>.mp4}.
 *
 * <p>{@link #start} never throws. If anything goes wrong during setup it
 * returns the {@link #DISABLED} sentinel whose methods all degrade safely,
 * so the surrounding logger session always works.
 *
 * @author Sumit Kumar
 */
public abstract class ScreenRecorder implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(ScreenRecorder.class);

    /**
     * Maximum time {@link RealScreenRecorder#close()} waits for in-flight
     * encodes before forcibly interrupting them. Sized for libaom-av1 with
     * {@link #ENCODER_PARALLELISM} workers: realistic bots logging a handful
     * of errors drain in ~5-30 s; 300 s is the safety ceiling for pathological
     * bursts.
     *
     * <p>The drain runs inside {@code CustomLogger.close()} (the
     * {@code CloseableSessionObject.close()} contract). AA's session container
     * invokes this reliably whether or not the bot calls
     * {@code Stop Logger Session} explicitly. We tried registering a
     * {@code BotShutdownHook} as a faster alternative, but AA's bot runner
     * only honours hooks discovered at code-gen time inside its own first-party
     * packages - {@code addBotShutdownHook(...)} from a custom package is
     * accepted but never invoked (verified empirically: every run of our bot
     * reports "total hooks: 0" in {@code Bot_*.executeBotShutdownHooks}).
     */
    private static final Duration CLOSE_DRAIN_TIMEOUT = Duration.ofSeconds(300);

    /** Sentinel used when video is disabled or setup failed. Always returned, never null. */
    public static final ScreenRecorder DISABLED = new Disabled();

    static final int CAPTURE_FRAMERATE = 5;
    static final int SEGMENT_TIME_SECONDS = 1;

    private static final Duration GRACEFUL_STOP = Duration.ofSeconds(3);

    /**
     * Maximum number of concurrent stage-2 encodes per session. The bundled
     * ffmpeg only ships with libaom-av1 (-cpu-used 8) so each encode is
     * ~5-15 s and CPU-bound; 4 workers gives parallelism on multi-core hosts
     * without oversubscribing typical 4-core bot runners. Excess submissions
     * queue and run as workers free up. The bot thread never waits on a
     * worker - it submits and returns immediately.
     */
    private static final int ENCODER_PARALLELISM = 4;

    /**
     * Upper bound on the synchronous wait inside {@code start()} for stage-1 to
     * produce its first two stable segments. ffmpeg gdigrab needs roughly one
     * {@code SEGMENT_TIME_SECONDS} interval plus startup overhead before the
     * first .mov is flushed; two segments are needed because
     * {@link RealScreenRecorder#stableSegments()} drops the in-flight one.
     * Past this deadline we give up waiting and proceed: the recorder stays
     * attached and will produce clips once segments materialise, but errors
     * fired before that happens will be silent (no clip).
     */
    private static final Duration WARMUP_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration WARMUP_POLL_INTERVAL = Duration.ofMillis(100);

    /**
     * Encoder pool's bounded backlog. With 1-second chunk dedup at most one
     * encode is submitted per second on a tight loop, and a fast encoder
     * drains 4-8/sec, so a queue depth of 16 is comfortably above steady
     * state. Submissions beyond this rejecting is the desired behavior:
     * {@link RealScreenRecorder#snapshotForError} returns null on rejection
     * and the HTML log surfaces a "video unavailable" badge for that row.
     */
    private static final int ENCODER_QUEUE_CAPACITY = 16;

    /**
     * Granularity at which {@link RealScreenRecorder#snapshotForError(String)}
     * dedups across log entries: every entry whose timestamp falls in the same
     * 1-second chunk shares a single clip. Aligned with
     * {@link #SEGMENT_TIME_SECONDS} so two snapshots within the same chunk
     * would have produced byte-identical clips anyway (no new ring segment
     * has flushed in between). Drops loop-warning workloads from N clips to
     * roughly N / chunks-touched without sacrificing visual fidelity.
     */
    private static final long DEDUP_CHUNK_MILLIS = 1000L;

    /**
     * Starts a recorder for the given session. Returns {@link #DISABLED} if
     * {@code recordingLevels} is empty, {@code bufferSeconds} is non-positive,
     * or any setup step fails. Triggers a one-time crash-orphan sweep on the
     * first call per JVM lifetime.
     */
    public static ScreenRecorder start(String sessionId,
                                       Path logDir,
                                       int bufferSeconds,
                                       Set<Level> recordingLevels,
                                       EncodingMode encodingMode) {
        return start(sessionId, logDir, bufferSeconds, recordingLevels, encodingMode, false);
    }

    public static ScreenRecorder start(String sessionId, Path logDir, int bufferSeconds,
            Set<Level> recordingLevels, EncodingMode encodingMode, boolean cloudOnly) {
        return start(sessionId, logDir, bufferSeconds, recordingLevels, encodingMode, cloudOnly, cloudOnly);
    }

    public static ScreenRecorder start(String sessionId, Path logDir, int bufferSeconds,
            Set<Level> recordingLevels, EncodingMode encodingMode, boolean cloudOnly, boolean mediaVideoEnabled) {
        if (recordingLevels == null || recordingLevels.isEmpty() || bufferSeconds <= 0) {
            return DISABLED;
        }
        EncodingMode mode = encodingMode != null ? encodingMode : EncodingMode.FAST;
        try {
            Path appDataRoot = FfmpegBinary.appDataRoot();
            Path ffmpegExe = FfmpegBinary.locate();
            CrashSweep.scheduleOnce(appDataRoot, ffmpegExe);
            return new RealScreenRecorder(sessionId, logDir, bufferSeconds,
                    new HashSet<>(recordingLevels), appDataRoot, ffmpegExe, mode, cloudOnly, mediaVideoEnabled);
        } catch (Throwable t) {
            LOGGER.warn("Screen recorder disabled for session {}: {}",
                    sessionId, t.toString());
            return DISABLED;
        }
    }

    public abstract boolean shouldRecordFor(Level level);

    /**
     * Snapshots the ring slice covering the last {@code bufferSeconds} ending
     * at "now" and queues an async stage-2 encode. Returns the eventual mp4
     * path. The file may not exist until the encode finishes, which is normal.
     *
     * <p>Returns {@code null} when the recorder is disabled, has failed, the
     * encoder queue is full, or the ring has nothing usable yet (e.g. session
     * just started). Callers render a "video unavailable" chip in that case.
     */
    public Path snapshotForError(String errorUuid) {
        CaptureResult result = snapshotForError(errorUuid, true, ignored -> { });
        return result.unavailableReason == null ? result.video : null;
    }

    public abstract CaptureResult snapshotForError(String errorUuid, boolean localRequired,
                                                   Consumer<CaptureResult> register);

    public static void recoverAtStartup() {
        try { CrashSweep.scheduleOnce(FfmpegBinary.appDataRoot(), FfmpegBinary.locate()); }
        catch (Throwable ignored) { }
    }

    @Override
    public abstract void close();

    public abstract boolean isClosed();

    // ===== implementations =====

    private static final class Disabled extends ScreenRecorder {
        @Override public boolean shouldRecordFor(Level level) { return false; }
        @Override public CaptureResult snapshotForError(String errorUuid, boolean localRequired, Consumer<CaptureResult> register) {
            CaptureResult result = CaptureResult.unavailable(errorUuid, "videoUnavailable");
            register.accept(result);
            return result;
        }
        @Override public void close() { }
        @Override public boolean isClosed() { return true; }
    }

    static final class RealScreenRecorder extends ScreenRecorder {
        private final String sessionId;
        private final Path logDir;
        private final int bufferSeconds;
        private final Set<Level> recordingLevels;
        private final EncodingMode encodingMode;
        private final Path sessionFolder;
        private final Path ringDir;
        private final Path scratchRoot;
        private final Path activeMarker;
        private final Path ffmpegExe;
        private final Path appDataRoot;
        private final boolean cloudOnlyRecorder;
        private final boolean mediaVideoEnabled;
        private final ProcessGuard guard;
        private final Process stage1Process;
        private RecorderStorage storage;
        private Thread storageWatchdog;
        private volatile boolean storageLimited;
        private long nativeHeadroom;
        private final java.util.Map<String, CaptureResult> captures = new java.util.HashMap<>();
        private final ThreadPoolExecutor encoderPool;
        private final AtomicBoolean stopping = new AtomicBoolean(false);
        private final AtomicBoolean failed = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        // Single atomic ref so the (chunkId, clipPath) pair is always read and
        // written as a unit. With two separate atomics, a concurrent caller
        // could observe a torn state where the chunkId has been advanced to
        // the new chunk but the clipPath still points at the previous chunk's
        // mp4, and return that stale path.
        private final AtomicReference<DedupSlot> dedupSlot =
                new AtomicReference<>(DedupSlot.EMPTY);

        RealScreenRecorder(String sessionId,
                           Path logDir,
                           int bufferSeconds,
                           Set<Level> recordingLevels,
                           Path appDataRoot,
                           Path ffmpegExe,
                           EncodingMode encodingMode) throws IOException {
            this(sessionId, logDir, bufferSeconds, recordingLevels, appDataRoot, ffmpegExe, encodingMode, false);
        }

        RealScreenRecorder(String sessionId, Path logDir, int bufferSeconds, Set<Level> recordingLevels,
                Path appDataRoot, Path ffmpegExe, EncodingMode encodingMode, boolean cloudOnly) throws IOException {
            this(sessionId, logDir, bufferSeconds, recordingLevels, appDataRoot, ffmpegExe, encodingMode, cloudOnly, cloudOnly);
        }

        RealScreenRecorder(String sessionId, Path logDir, int bufferSeconds, Set<Level> recordingLevels,
                Path appDataRoot, Path ffmpegExe, EncodingMode encodingMode, boolean cloudOnly, boolean mediaVideoEnabled) throws IOException {
            this.sessionId = sessionId;
            this.logDir = logDir;
            this.bufferSeconds = bufferSeconds;
            this.recordingLevels = recordingLevels;
            this.encodingMode = encodingMode;
            this.ffmpegExe = ffmpegExe;
            this.appDataRoot = appDataRoot;
            this.cloudOnlyRecorder = cloudOnly;
            this.mediaVideoEnabled = mediaVideoEnabled;

            this.sessionFolder = appDataRoot.resolve("sessions").resolve(sessionId);
            this.ringDir = sessionFolder.resolve("ring");
            this.scratchRoot = sessionFolder.resolve("scratch");
            this.activeMarker = sessionFolder.resolve("session.active");

            Files.createDirectories(ringDir);
            Files.createDirectories(scratchRoot);

            writeMeta();
            createMarker();

            if (cloudOnly) {
                java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
                nativeHeadroom = Math.addExact(Math.multiplyExact((long) screen.width * screen.height, 8L), 1024L * 1024);
                storage = new RecorderStorage(appDataRoot, sessionId);
            }
            this.guard = new ProcessGuard();
            try {
                this.stage1Process = guard.spawn(buildStage1Command(), null);
            } catch (IOException e) {
                guard.close();
                if (storage != null) storage.close();
                throw e;
            }
            startStderrWatchdog();
            if (storage != null) startStorageWatchdog();
            this.encoderPool = newEncoderPool(sessionId);
            awaitWarmup();
        }

        /**
         * Per-session daemon worker pool for stage-2 encodes. Daemon threads
         * because their lifecycle is bounded by {@link #close()}, which drains
         * the pool synchronously before returning. JVM-wide hooks cannot be
         * relied on in the AA Bot Runner host - the JVM is terminated after
         * the bot's last action regardless of thread daemon-ness or registered
         * shutdown hooks.
         */
        private static ThreadPoolExecutor newEncoderPool(String sessionId) {
            return new ThreadPoolExecutor(
                    ENCODER_PARALLELISM, ENCODER_PARALLELISM,
                    0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(ENCODER_QUEUE_CAPACITY),
                    r -> {
                        Thread t = new Thread(r, "a360-clip-encoder-" + shortId(sessionId));
                        t.setDaemon(true);
                        return t;
                    });
        }

        /**
         * Blocks until stage-1 has flushed at least two segments to the ring
         * (so {@link #stableSegments()} can return non-empty), or until the
         * stage-1 process dies, or until {@link #WARMUP_TIMEOUT} elapses.
         * Never throws: if warmup fails we still return a working recorder
         * that callers can use; subsequent {@code shouldRecordFor()} calls
         * reflect actual stage-1 health.
         */
        private void awaitWarmup() {
            Instant start = Instant.now();
            Instant deadline = start.plus(WARMUP_TIMEOUT);
            long pollMs = WARMUP_POLL_INTERVAL.toMillis();
            while (true) {
                if (!stage1Process.isAlive()) {
                    failed.set(true);
                    LOGGER.warn("ScreenRecorder session {} stage-1 died during warmup (exit={})",
                            sessionId, safeExitValue());
                    return;
                }
                if (countMovSegments() >= 2) {
                    long ms = Duration.between(start, Instant.now()).toMillis();
                    LOGGER.info("ScreenRecorder session {} warm in {} ms", sessionId, ms);
                    return;
                }
                if (!Instant.now().isBefore(deadline)) {
                    LOGGER.warn("ScreenRecorder session {} did not produce 2 stable segments "
                                    + "within {} ms; proceeding without warm buffer",
                            sessionId, WARMUP_TIMEOUT.toMillis());
                    return;
                }
                try {
                    Thread.sleep(pollMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.warn("ScreenRecorder session {} warmup interrupted; proceeding",
                            sessionId);
                    return;
                }
            }
        }

        private long countMovSegments() {
            try (Stream<Path> stream = Files.list(ringDir)) {
                return stream
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mov"))
                        .count();
            } catch (IOException e) {
                return 0L;
            }
        }

        private int safeExitValue() {
            try {
                return stage1Process.exitValue();
            } catch (IllegalThreadStateException e) {
                return -1;
            }
        }

        private void writeMeta() throws IOException {
            String packageVersion = readPackageVersion();
            long pid = ProcessHandle.current().pid();
            SessionMeta meta = new SessionMeta(
                    sessionId,
                    logDir.toAbsolutePath().toString(),
                    bufferSeconds,
                    recordingLevels,
                    Instant.now(),
                    packageVersion,
                    pid,
                    encodingMode);
            meta.write(sessionFolder.resolve("session.meta"));
        }

        private void createMarker() throws IOException {
            Files.deleteIfExists(activeMarker);
            Files.createFile(activeMarker);
        }

        private String[] buildStage1Command() {
            int ringSize = bufferSeconds + 1;
            return new String[]{
                    ffmpegExe.toAbsolutePath().toString(),
                    "-hide_banner",
                    "-loglevel", "warning",
                    "-f", "gdigrab",
                    "-framerate", String.valueOf(CAPTURE_FRAMERATE),
                    "-draw_mouse", "1",
                    "-i", "desktop",
                    "-c:v", "ffvhuff",
                    "-pix_fmt", "yuv420p",
                    "-f", "segment",
                    "-segment_time", String.valueOf(SEGMENT_TIME_SECONDS),
                    "-reset_timestamps", "1",
                    "-segment_wrap", String.valueOf(ringSize),
                    "-segment_list", ringDir.resolve("list.txt").toAbsolutePath().toString(),
                    "-segment_list_size", String.valueOf(ringSize),
                    ringDir.resolve("%01d.mov").toAbsolutePath().toString()
            };
        }

        private void startStorageWatchdog() {
            // Reserve headroom for two native frames and muxer buffers. Windows overshoot is a release gate.
            long stopAt = RecorderStorage.RING_BYTES - nativeHeadroom;
            storageWatchdog = new Thread(() -> {
                while (!stopping.get() && stage1Process.isAlive()) {
                    try {
                        long bytes = 0;
                        try (Stream<Path> files = Files.list(ringDir)) {
                            for (Path path : files.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList()))
                                bytes = Math.addExact(bytes, Files.size(path));
                        }
                        if (stopAt <= 0 || bytes >= stopAt) {
                            storageLimited = true;
                            failed.set(true);
                            stage1Process.destroyForcibly();
                            return;
                        }
                        Thread.sleep(100);
                    } catch (IOException | RuntimeException error) {
                        storageLimited = true;
                        failed.set(true);
                        stage1Process.destroyForcibly();
                        return;
                    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }, "a360-cloud-capture-budget-" + shortId(sessionId));
            storageWatchdog.setDaemon(true);
            storageWatchdog.start();
        }

        private void startStderrWatchdog() {
            Thread t = new Thread(this::pumpStderr,
                    "a360-recorder-stderr-" + shortId(sessionId));
            t.setDaemon(true);
            t.start();
        }

        private void pumpStderr() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stage1Process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.debug("[ffmpeg-{}] {}", shortId(sessionId), line);
                }
            } catch (IOException ignored) {
                // stream closed or interrupted; fall through
            }
            // stderr ended -> the process either exited or its pipes closed.
            // If we're not the ones stopping it, treat as failure.
            if (!stopping.get()) {
                failed.set(true);
                int exit = -1;
                try {
                    exit = stage1Process.exitValue();
                } catch (IllegalThreadStateException ignored) {
                }
                LOGGER.warn("ScreenRecorder session {} stage-1 exited unexpectedly (exit={})",
                        sessionId, exit);
            }
        }

        @Override
        public boolean shouldRecordFor(Level level) {
            return !closed.get() && !failed.get() && recordingLevels.contains(level);
        }

        @Override
        public synchronized CaptureResult snapshotForError(String errorUuid, boolean localRequired,
                                                          Consumer<CaptureResult> register) {
            if (closed.get() || failed.get()) return unavailable(errorUuid, storageLimited ? "storageBudget" : "videoUnavailable", register);
            long chunkId = System.currentTimeMillis() / DEDUP_CHUNK_MILLIS;
            DedupSlot slot = dedupSlot.get();
            if (slot.chunkId == chunkId && slot.capture != null) {
                CaptureResult capture = slot.capture;
                if (localRequired) capture.localRequired.set(true);
                boolean ownershipSaved = true;
                if (capture.mediaManaged || mediaVideoEnabled || !localRequired) {
                    if (!persistCaptureOwner(capture)) {
                        ownershipSaved = false;
                        capture.mediaFailure = "storageFailure";
                        if (!localRequired) return unavailable(capture.captureId, "storageFailure", register);
                        // A media-sidecar fault must not cancel the requested local owner.
                        if (capture.mediaManaged) HeliosMediaOutbox.captureFinished(capture.captureId, null, "storageFailure");
                    } else {
                        capture.mediaManaged = true;
                        capture.mediaFailure = null;
                    }
                }
                if (localRequired && ownershipSaved && !cloudOnlyRecorder && capture.cloudScratchReserved) {
                    storage.release("capture-" + capture.captureId);
                    capture.cloudScratchReserved = false;
                }
                register.accept(capture);
                return capture;
            }
            // Completed cloud-only originals stay until every durable owner has copied them.
            java.util.Iterator<java.util.Map.Entry<String, CaptureResult>> completed = captures.entrySet().iterator();
            while (completed.hasNext()) {
                CaptureResult capture = completed.next().getValue();
                if (capture.localRequired.get() || !capture.completion.isDone() || Files.isDirectory(capture.scratch)) continue;
                try {
                    Files.deleteIfExists(capture.video);
                    Files.deleteIfExists(sessionFolder.resolve("captures").resolve(capture.captureId + ".json"));
                    if (storage != null) storage.release("capture-" + capture.captureId);
                    completed.remove();
                } catch (IOException pendingDelete) { }
            }
            Path scratchDir = scratchRoot.resolve(errorUuid);
            CaptureResult created = null;
            boolean registered = false;
            try {
                List<Path> segments = stableSegments();
                if (segments.isEmpty() || encoderPool.getQueue().remainingCapacity() == 0)
                    return unavailable(errorUuid, "videoUnavailable", register);
                boolean cloudOwned = !localRequired;
                if (cloudOwned && storage == null) {
                    try { storage = new RecorderStorage(appDataRoot, sessionId, 0); }
                    catch (IOException unavailable) { return unavailable(errorUuid, "storageFailure", register); }
                }
                java.util.Map<Path, Long> admittedSizes = new java.util.HashMap<>();
                if (cloudOwned) {
                    long bytes = cloudOnlyRecorder ? 8L * 1024 * 1024 + nativeHeadroom : 0;
                    for (Path seg : segments) {
                        long size = Files.size(seg);
                        admittedSizes.put(seg, size);
                        bytes = Math.addExact(bytes, size);
                    }
                    if (!storage.reserve("capture-" + errorUuid, bytes)) return unavailable(errorUuid, "storageBudget", register);
                }
                Files.createDirectories(scratchDir);
                for (Path seg : segments) {
                    if (!cloudOwned) Files.copy(seg, scratchDir.resolve(seg.getFileName()),
                            StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                    else copyAdmittedSegment(seg, scratchDir.resolve(seg.getFileName()), admittedSizes.get(seg));
                }
                Path target = logDir.resolve("clips").resolve(errorUuid + ".mp4");
                CaptureResult capture = new CaptureResult(errorUuid, target, scratchDir, null);
                created = capture;
                capture.localRequired.set(localRequired);
                capture.cloudScratchReserved = cloudOwned;
                capture.mediaManaged = mediaVideoEnabled || cloudOwned;
                if (capture.mediaManaged && !persistCaptureOwner(capture)) {
                    capture.mediaFailure = "storageFailure";
                    if (!localRequired) {
                        deleteRecursivelyQuietly(scratchDir);
                        if (storage != null && !Files.exists(scratchDir)) storage.release("capture-" + errorUuid);
                        return unavailable(errorUuid, "storageFailure", register);
                    }
                    capture.mediaManaged = false;
                }
                if (capture.mediaManaged || cloudOwned) captures.put(errorUuid, capture);
                // Persist each caller's exact row identity before the encoder can complete.
                register.accept(capture);
                registered = true;
                encoderPool.execute(() -> runEncode(capture));
                dedupSlot.set(new DedupSlot(chunkId, target, capture));
                return capture;
            } catch (IOException | RejectedExecutionException error) {
                String reason = error instanceof RejectedExecutionException ? "videoUnavailable" : "captureFailed";
                boolean durable = !registered || !created.mediaManaged || HeliosMediaOutbox.captureFinished(errorUuid, null, reason);
                if (registered) {
                    created.unavailableReason = reason;
                    created.completion.complete(null);
                }
                if (durable) {
                    deleteRecursivelyQuietly(scratchDir);
                    if (storage != null && !Files.exists(scratchDir)) storage.release("capture-" + errorUuid);
                    captures.remove(errorUuid);
                    try { Files.deleteIfExists(sessionFolder.resolve("captures").resolve(errorUuid + ".json")); }
                    catch (IOException retained) { }
                }
                LOGGER.warn("snapshotForError({}) failed for session {}: {}", errorUuid, sessionId, error.toString());
                return registered ? created : unavailable(errorUuid, reason, register);
            }
        }

        private static void copyAdmittedSegment(Path source, Path target, long bytes) throws IOException {
            java.nio.file.attribute.FileTime timestamp = Files.getLastModifiedTime(source);
            try (java.io.InputStream input = Files.newInputStream(source);
                 java.io.OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[8192];
                long remaining = bytes;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new IOException("Ring segment changed during snapshot");
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
            Files.setLastModifiedTime(target, timestamp);
        }

        private CaptureResult unavailable(String id, String reason, Consumer<CaptureResult> register) {
            CaptureResult capture = CaptureResult.unavailable(id, reason);
            register.accept(capture);
            return capture;
        }

        private boolean persistCaptureOwner(CaptureResult capture) {
            if (capture.scratch == null) return true;
            try {
                String metadata = new JSONObject().put("captureId", capture.captureId)
                        .put("target", capture.video.toAbsolutePath().toString())
                        .put("localRequired", capture.localRequired.get()).put("cloudOnly", cloudOnlyRecorder).toString();
                if (Files.isDirectory(capture.scratch)) writeCapture(capture.scratch.resolve("capture.json"), metadata);
                Path capturesDir = sessionFolder.resolve("captures");
                Files.createDirectories(capturesDir);
                writeCapture(capturesDir.resolve(capture.captureId + ".json"), metadata);
                return true;
            } catch (IOException error) {
                LOGGER.warn("Could not persist capture ownership for {}", capture.captureId);
                return false;
            }
        }

        private static void writeCapture(Path path, String metadata) throws IOException {
            Path part = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(part, metadata);
            try (java.nio.channels.FileChannel file = java.nio.channels.FileChannel.open(part, java.nio.file.StandardOpenOption.WRITE)) {
                file.force(true);
            }
            try { Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(part, path, StandardCopyOption.REPLACE_EXISTING); }
        }

        private void runEncode(CaptureResult capture) {
            try {
                ClipFinalizer.encodeSegmentsToMp4(ffmpegExe, capture.scratch, capture.video, encodingMode,
                        cloudOnlyRecorder ? 8L * 1024 * 1024 : Long.MAX_VALUE);
                // Publish only after the finalizer has returned, never from file existence or size.
                boolean durable = !capture.mediaManaged || HeliosMediaOutbox.captureFinished(capture.captureId,
                        capture.mediaFailure == null ? capture.video : null, capture.mediaFailure);
                capture.completion.complete(capture.video);
                if (!durable) return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return; // Preserve the exact scratch and intent for CrashSweep.
            } catch (Throwable failure) {
                LOGGER.warn("Failed to finalize clip {}: {}", capture.video, failure.toString());
                String reason = capture.mediaFailure != null ? capture.mediaFailure
                        : failure instanceof ClipFinalizer.TooLargeException ? "tooLarge" : "encodeFailed";
                boolean durable = !capture.mediaManaged || HeliosMediaOutbox.captureFinished(capture.captureId, null, reason);
                capture.completion.complete(null);
                if (!durable) return;
            }
            deleteRecursivelyQuietly(capture.scratch);
            if (!cloudOnlyRecorder && capture.cloudScratchReserved && !Files.exists(capture.scratch)) {
                storage.release("capture-" + capture.captureId);
                capture.cloudScratchReserved = false;
            }
        }

        /**
         * Returns the ring's complete segments, oldest first. The most recently
         * written segment is dropped because stage-1 may still be writing to it -
         * including a partial segment with an incomplete moov atom can break
         * stage-2's concat demuxer.
         */
        private List<Path> stableSegments() throws IOException {
            List<Path> all = ClipFinalizer.listMovFilesByMtimeAsc(ringDir);
            if (all.size() <= 1) {
                return java.util.Collections.emptyList();
            }
            return new java.util.ArrayList<>(all.subList(0, all.size() - 1));
        }

        /**
         * Drains the encoder pool synchronously, then either deletes the
         * session folder (clean exit) or leaves it on disk for next-startup
         * recovery (drain timed out before all encodes finished).
         *
         * <p>AA invokes this via {@code CloseableSessionObject.close()}
         * either when the bot reaches {@code Stop Logger Session} or when
         * the session container is cleaned up at bot end. Either way, this
         * is the only callback we can rely on, so the wait happens here.
         *
         * <p>If the encoder pool drains within {@link #CLOSE_DRAIN_TIMEOUT}
         * every queued clip has finalized to {@code <logDir>/clips/<uuid>.mp4}
         * and the session folder under {@code %LOCALAPPDATA%} is removed.
         *
         * <p>If the drain times out (typical for COMPACT mode under heavy
         * loop-warning load with limited AA cleanup window), pending
         * {@code scratch/<uuid>/} subdirs hold the segments copied at
         * snapshot time and the matching {@code clips/<uuid>.mp4} paths
         * already appear in the HTML log. We leave the session folder on
         * disk (including {@code session.active}) so the next JVM's
         * {@link CrashSweep} re-encodes those scratches and lands the
         * missing mp4s at the exact paths the HTML rows already reference.
         * The HTML's broken video links self-heal on the next bot run.
         */
        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            stopping.set(true);
            if (storageWatchdog != null) storageWatchdog.interrupt();
            stopStage1();
            encoderPool.shutdown();
            boolean drained = false;
            try {
                drained = encoderPool.awaitTermination(
                        CLOSE_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!drained) {
                encoderPool.shutdownNow();
                LOGGER.warn("Encoder pool did not drain within {} for session {}; "
                                + "preserving residual scratches under {} for next-startup "
                                + "CrashSweep recovery",
                        CLOSE_DRAIN_TIMEOUT, sessionId, sessionFolder);
            }
            guard.close();
            if (drained && !hasPendingScratches()) {
                boolean removed = true;
                for (CaptureResult capture : captures.values()) {
                    if (!capture.localRequired.get()) {
                        try { Files.deleteIfExists(capture.video); }
                        catch (IOException pendingDelete) { removed = false; }
                    }
                }
                if (removed) {
                    deleteRecursivelyQuietly(sessionFolder);
                    if (storage != null && !Files.exists(sessionFolder)) storage.close();
                }
            }
            // If !drained, leave sessionFolder (session.active marker, ring/,
            // scratch/<uuid>/) on disk. CrashSweep on the next JVM start will
            // claim it, re-encode each pending scratch into <logDir>/clips/,
            // and remove the residual folder.
        }

        private boolean hasPendingScratches() {
            try (Stream<Path> folders = Files.list(scratchRoot)) { return folders.anyMatch(Files::isDirectory); }
            catch (IOException error) { return true; }
        }

        private void stopStage1() {
            if (stage1Process == null || !stage1Process.isAlive()) {
                return;
            }
            try (OutputStream stdin = stage1Process.getOutputStream()) {
                stdin.write("q\n".getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException ignored) {
                // process may have already exited
            }
            try {
                if (!stage1Process.waitFor(GRACEFUL_STOP.toMillis(), TimeUnit.MILLISECONDS)) {
                    stage1Process.destroyForcibly();
                    stage1Process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                stage1Process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }
    }

    /**
     * Snapshot of the most recently produced (chunkId, clipPath) pair. Stored
     * inside an {@link AtomicReference} so the pair is read and written
     * atomically. Immutable; constructed once per successful snapshot.
     */
    private static final class DedupSlot {
        static final DedupSlot EMPTY = new DedupSlot(Long.MIN_VALUE, null, null);

        final long chunkId;
        final Path clipPath;
        final CaptureResult capture;

        DedupSlot(long chunkId, Path clipPath, CaptureResult capture) {
            this.capture = capture;
            this.chunkId = chunkId;
            this.clipPath = clipPath;
        }
    }

    // ===== shared static helpers =====

    static String shortId(String sessionId) {
        if (sessionId == null) return "x";
        return sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
    }

    static String readPackageVersion() {
        Package pkg = ScreenRecorder.class.getPackage();
        String v = pkg != null ? pkg.getImplementationVersion() : null;
        return v != null ? v : UUID.randomUUID().toString().substring(0, 8);
    }

    static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
