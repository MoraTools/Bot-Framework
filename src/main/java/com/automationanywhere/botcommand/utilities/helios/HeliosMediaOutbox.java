package com.automationanywhere.botcommand.utilities.helios;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Durable per-row media. Its files and receipts are independent of the JSON journal. */
public final class HeliosMediaOutbox {
    static final long MAX_BYTES = 256L * 1024 * 1024;
    static final long SCREENSHOT_BYTES = 1024L * 1024;
    static final long VIDEO_BYTES = 8L * 1024 * 1024;
    private static final Object ROOT_MUTEX = new Object();
    private static final java.util.concurrent.Semaphore FILE_TRANSFER = new java.util.concurrent.Semaphore(1);
    private final Path root;
    private final Path directory;
    private final String loggerId;
    private final String journalName;
    private final HeliosConfig config;
    private final HttpClient http;
    private final Consumer<String> warning;
    private final Thread worker;
    private volatile JSONObject capability;
    private volatile String currentSessionId;
    private volatile boolean jsonClosing;
    private volatile boolean stopping;
    private volatile boolean damagedManifestWarned;

    HeliosMediaOutbox(Path outboxRoot, Path partition, String loggerId, String journalName,
                      HeliosConfig config, HttpClient http, Consumer<String> warning) throws IOException {
        root = outboxRoot.resolve("media");
        directory = root.resolve(partition.getFileName().toString());
        this.loggerId = loggerId;
        this.journalName = journalName;
        this.config = config;
        this.http = http;
        this.warning = warning;
        Files.createDirectories(directory);
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
        worker = new Thread(this::run, "helios-media-delivery");
        worker.setDaemon(true);
        worker.start();
    }

    JSONArray register(int ordinal, ErrorMedia media) throws IOException {
        JSONArray declarations = new JSONArray();
        locked(root, () -> {
            JSONObject still = create(ordinal, "screenshot", media.capture == null
                    ? UUID.randomUUID().toString() : media.capture.captureId, media.screenshot);
            still.put("sourceOwned", media.ownedScreenshot);
            if (media.screenshot == null) fail(still, media.screenshotFailure == null ? "captureFailed" : media.screenshotFailure);
            else still.put("completed", true);
            save(jobPath(still), still);
            try { stage(still, jobPath(still)); }
            catch (IOException error) { fail(still, "storageFailure"); save(jobPath(still), still); }
            cleanupOwnedSource(still);
            declarations.put(declaration(still));
            if (media.video) {
                JSONObject video = create(ordinal, "video", media.capture == null
                        ? UUID.randomUUID().toString() : media.capture.captureId,
                        media.capture == null ? null : media.capture.video);
                String failure = media.capture == null ? "videoUnavailable"
                        : media.capture.unavailableReason != null ? media.capture.unavailableReason : media.capture.mediaFailure;
                if (failure != null) fail(video, failure);
                save(jobPath(video), video);
                declarations.put(declaration(video));
            }
            return null;
        });
        if (media.video && media.capture != null && media.capture.unavailableReason == null && media.capture.mediaFailure == null) media.capture.completion.thenAccept(path -> {
            try { completeCapture(root, media.capture.captureId, path,
                    media.capture.mediaFailure != null ? media.capture.mediaFailure : path == null ? "encodeFailed" : null); }
            catch (IOException error) { warning.accept("Media completion could not be saved. Recorder scratch is retained for recovery."); }
        });
        return declarations;
    }

    private JSONObject create(int ordinal, String kind, String captureId, Path source) {
        JSONObject job = new JSONObject().put("loggerSessionId", loggerId).put("journal", journalName)
                .put("ordinal", ordinal).put("kind", kind).put("captureId", captureId)
                .put("source", source == null ? "" : source.toAbsolutePath().toString())
                .put("createdAt", System.currentTimeMillis()).put("state", "pending")
                .put("confirmed", false).put("completed", false).put("reserved", 0);
        if (currentSessionId != null) job.put("sessionId", currentSessionId);
        if (capability != null) job.put("capability", capability);
        return job;
    }

    private Path jobPath(JSONObject job) {
        return directory.resolve(loggerId + "-" + job.getInt("ordinal") + "-" + job.getString("kind") + ".json");
    }

    void session(String journal, String sessionId, JSONObject media) throws IOException {
        if (journalName.equals(journal)) currentSessionId = sessionId;
        if (validCapability(media)) capability = new JSONObject(media.toString());
        locked(root, () -> {
            for (Path path : jobs(directory)) {
                JSONObject job = readJob(path);
                if (job == null) continue;
                if (!journal.equals(job.optString("journal"))) continue;
                if (job.has("sessionId") && !sessionId.equals(job.getString("sessionId"))) job.put("confirmed", false);
                job.put("sessionId", sessionId);
                if (validCapability(media)) job.put("capability", media);
                save(path, job);
            }
            return null;
        });
    }

    void acknowledge(String journal, int first, int count) throws IOException {
        locked(root, () -> {
            for (Path path : jobs(directory)) {
                JSONObject job = readJob(path);
                if (job == null) continue;
                int ordinal = job.getInt("ordinal");
                if (journal.equals(job.optString("journal")) && ordinal >= first && ordinal < (long) first + count) {
                    job.put("confirmed", true);
                    save(path, job);
                }
            }
            return null;
        });
    }

    void stop() {
        stopping = true;
        worker.interrupt();
        try { worker.join(1000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    void finishJSON() { jsonClosing = true; }

    /** Called only after a live or recovery finalizer returns successfully. */
    public static boolean captureFinished(String captureId, Path path, String failure) {
        try {
            return completeCapture(HeliosSink.outboxRoot().resolve("media"), captureId, path, failure);
        } catch (IOException error) { return false; }
    }

    static boolean completeCapture(Path root, String captureId, Path source, String failure) throws IOException {
        if (!Files.isDirectory(root)) return true;
        return locked(root, () -> {
            boolean durable = true;
            for (Path path : jobs(root)) {
                JSONObject job;
                try { job = read(path); }
                catch (IOException | RuntimeException damaged) { durable = false; continue; }
                if (!captureId.equals(job.optString("captureId")) || !"video".equals(job.optString("kind"))) continue;
                if (!"pending".equals(job.optString("state"))) continue;
                if (failure != null) fail(job, failure);
                else job.put("completed", true).put("source", source.toAbsolutePath().toString());
                save(path, job);
                stage(job, path);
            }
            return durable;
        });
    }

    private static void cleanupOwnedSource(JSONObject job) {
        if (!job.optBoolean("sourceOwned") || job.optString("source").isEmpty()) return;
        // The immutable copy or terminal failure is already durable before ownership is released.
        if (!job.has("sha256") && !"unavailable".equals(job.optString("state"))) return;
        try { Files.deleteIfExists(Path.of(job.getString("source"))); }
        catch (IOException retained) { /* retry cleanup; owned bytes still count in admission */ }
    }

    private static JSONObject declaration(JSONObject job) {
        JSONObject result = new JSONObject().put("kind", job.getString("kind"))
                .put("state", job.getString("state"));
        if (job.has("reason")) result.put("reason", job.getString("reason"));
        return result;
    }

    private static void fail(JSONObject job, String reason) {
        job.put("state", "unavailable").put("reason", reason).put("reserved", 0);
    }

    /** Root lock covers reservation, copy and commit, including every destination partition. */
    private static void stage(JSONObject job, Path manifest) throws IOException {
        if (!"pending".equals(job.getString("state")) || !job.optBoolean("completed") || job.has("sha256")) return;
        Path root = manifest.getParent().getParent();
        Path source = Path.of(job.getString("source"));
        Path existingReady = bytesPath(manifest);
        if (Files.isRegularFile(existingReady)) {
            long recoveredBytes = Files.size(existingReady);
            if (recoveredBytes > 0 && recoveredBytes <= ("video".equals(job.getString("kind")) ? VIDEO_BYTES : SCREENSHOT_BYTES)) {
                job.put("sha256", sha256(existingReady)).put("bytes", recoveredBytes).put("reserved", 0);
                save(manifest, job);
                return;
            }
            Files.deleteIfExists(existingReady);
        }
        if (!Files.isRegularFile(source)) {
            fail(job, "video".equals(job.getString("kind")) ? "encodeFailed" : "captureFailed");
            save(manifest, job);
            return;
        }
        long bytes = Files.size(source);
        long cap = "video".equals(job.getString("kind")) ? VIDEO_BYTES : SCREENSHOT_BYTES;
        if (bytes == 0 || bytes > cap) {
            fail(job, bytes > cap ? "tooLarge" : "captureFailed");
            save(manifest, job);
            return;
        }
        Path ready = bytesPath(manifest);
        Path part = ready.resolveSibling(ready.getFileName() + ".part");
        // An interrupted copy is never a completed file. Its source is used only with persisted completion.
        Files.deleteIfExists(part);
        job.put("reserved", 0);
        save(manifest, job);
        if (!admit(root, bytes, job.getString("kind"), manifest)) {
            fail(job, "storageBudget");
            save(manifest, job);
            return;
        }
        job.put("reserved", bytes);
        save(manifest, job);
        try {
            Files.copy(source, part, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(part) != bytes) throw new IOException("Media source changed during staging");
            try (FileChannel file = FileChannel.open(part, StandardOpenOption.WRITE)) { file.force(true); }
            String hash = sha256(part);
            move(part, ready);
            job.put("sha256", hash).put("bytes", bytes).put("reserved", 0);
            save(manifest, job);
        } catch (IOException error) {
            // The completion and source stay durable. A later start retries staging.
            Files.deleteIfExists(part);
            job.put("reserved", 0);
            save(manifest, job);
            throw error;
        }
    }

    static boolean admit(Path root, long bytes, String incomingKind, Path incoming) throws IOException {
        if (bytes > MAX_BYTES) return false;
        List<Path> candidates = jobs(root);
        candidates.sort(Comparator.comparingInt((Path path) -> "video".equals(readQuiet(path).optString("kind")) ? 0 : 1)
                .thenComparingLong(path -> readQuiet(path).optLong("createdAt")));
        for (Path path : candidates) {
            if (usage(root) + bytes <= MAX_BYTES) return true;
            if (path.equals(incoming)) continue;
            JSONObject old;
            try { old = read(path); } catch (IOException | RuntimeException damaged) { continue; }
            if ("video".equals(incomingKind) && !"video".equals(old.optString("kind"))) break;
            if (!Files.exists(bytesPath(path))) continue;
            try (FileChannel channel = lockChannel(path); FileLock pin = tryLock(channel)) {
                if (pin == null) continue;
                fail(old, "storageBudget");
                old.remove("sha256");
                old.remove("bytes");
                save(path, old);
                Files.deleteIfExists(bytesPath(path));
            }
        }
        return usage(root) + bytes <= MAX_BYTES;
    }

    static long usage(Path root) throws IOException {
        long actual = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String name = path.getFileName().toString();
                if (name.endsWith(".ready") || name.endsWith(".ready.part")) actual = Math.addExact(actual, Files.size(path));
            }
        }
        for (Path manifest : jobs(root)) {
            JSONObject job;
            try { job = read(manifest); }
            catch (IOException | RuntimeException damaged) { actual = Math.addExact(actual, MAX_BYTES); continue; }
            Path part = bytesPath(manifest).resolveSibling(bytesPath(manifest).getFileName() + ".part");
            if (job.optBoolean("sourceOwned") && !job.optString("source").isEmpty()) {
                Path ownedSource = Path.of(job.getString("source"));
                if (Files.isRegularFile(ownedSource)) actual = Math.addExact(actual, Files.size(ownedSource));
            }
            long filled = Files.exists(part) ? Files.size(part) : 0;
            if (Files.exists(bytesPath(manifest))) filled = Math.addExact(filled, Files.size(bytesPath(manifest)));
            actual = Math.addExact(actual, Math.max(0, job.optLong("reserved") - filled));
        }
        return actual;
    }

    private void run() {
        while (!stopping) {
            try {
                for (Path manifest : jobs(directory)) {
                    if (stopping) break;
                    try (FileChannel pinFile = lockChannel(manifest); FileLock pin = tryLock(pinFile)) {
                        if (pin == null) continue;
                        send(manifest);
                    } catch (IOException | RuntimeException damaged) {
                        if (!damagedManifestWarned) {
                            damagedManifestWarned = true;
                            warning.accept("A media record could not be processed (" + errorType(damaged) + "). It is retained; other media delivery continues.");
                        }
                    }
                }
                cleanupLocks();
                if (jsonClosing && jobs(directory).isEmpty()) return;
                Thread.sleep(200);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            catch (IOException | RuntimeException error) {
                warning.accept("Media delivery is pending. Saved media will retry at the next Helios logger start.");
                try { Thread.sleep(1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private JSONObject readJob(Path path) {
        try { return read(path); }
        catch (IOException | RuntimeException damaged) {
            if (!damagedManifestWarned) {
                damagedManifestWarned = true;
                warning.accept("A media record could not be read (" + errorType(damaged) + "). It is retained; JSON and other media delivery continue.");
            }
            return null;
        }
    }

    // Class names only: messages can hold paths or request data, and the ingest key must never appear in a warning.
    private static String errorType(Exception error) {
        return error.getClass().getName() + (error.getCause() == null ? "" : " caused by " + error.getCause().getClass().getName());
    }

    private void cleanupLocks() throws IOException {
        locked(root, () -> {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path lock : files.filter(path -> path.toString().endsWith(".json.lock")).collect(Collectors.toList())) {
                    String name = lock.getFileName().toString();
                    Path manifest = lock.resolveSibling(name.substring(0, name.length() - 5));
                    if (Files.exists(manifest) || Files.exists(bytesPath(manifest))
                            || Files.exists(bytesPath(manifest).resolveSibling(bytesPath(manifest).getFileName() + ".part"))) continue;
                    boolean unused;
                    try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE); FileLock pin = tryLock(channel)) {
                        unused = pin != null;
                    }
                    if (unused) {
                        try { Files.deleteIfExists(lock); }
                        catch (IOException busyOnWindows) { /* retry after another process closes its handle */ }
                    }
                }
            }
            return null;
        });
    }

    private void send(Path manifest) throws IOException, InterruptedException {
        if (!FILE_TRANSFER.tryAcquire()) return;
        try { sendClaimed(manifest); }
        finally { FILE_TRANSFER.release(); }
    }

    private void sendClaimed(Path manifest) throws IOException, InterruptedException {
        JSONObject job = locked(root, () -> {
            if (!Files.exists(manifest)) return null;
            JSONObject value = read(manifest);
            if (value.optLong("retryAt") > System.currentTimeMillis()) return null;
            stage(value, manifest);
            cleanupOwnedSource(value);
            return value;
        });
        if (job == null || !job.optBoolean("confirmed") || !job.has("sessionId")) return;
        JSONObject caps = capability != null ? capability : job.optJSONObject("capability");
        // Wait for a session start of this logger to bring the limits.
        if (!validCapability(caps)) return;
        String kind = job.getString("kind");
        long maximum = caps.getLong("video".equals(kind) ? "maxVideoBytes" : "maxScreenshotBytes");
        if (job.has("bytes") && job.getLong("bytes") > maximum) {
            locked(root, () -> {
                JSONObject current = current(manifest, job);
                if (current != null) { fail(current, "tooLarge"); save(manifest, current); }
                return null;
            });
            job.put("state", "unavailable").put("reason", "tooLarge");
        }
        String path = "/api/ingest/sessions/" + job.getString("sessionId") + "/entries/" + job.getInt("ordinal") + "/media";
        boolean upload = "pending".equals(job.getString("state")) && job.has("sha256");
        if ("pending".equals(job.getString("state")) && !upload) return;
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.baseUrl + path + (upload ? "/" + kind : "")))
                .timeout(Duration.ofSeconds(10)).header(HeliosSink.KEY_HEADER, config.ingestKey);
        if (upload) {
            request.header("Content-Type", "video".equals(kind) ? "video/mp4" : "image/png").header("X-Helios-Media-SHA256", job.getString("sha256"))
                    // Helios may answer before it reads the body, and Windows cannot delete a file the JDK still holds open.
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(Files.readAllBytes(bytesPath(manifest))));
        } else request.header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(declaration(job).toString()));
        HttpResponse<String> response;
        try { response = http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (IOException error) { retry(manifest, job, 0); return; }
        JSONObject receipt;
        try { receipt = new JSONObject(response.body()); }
        catch (RuntimeException invalid) { receipt = new JSONObject(); }
        if (response.statusCode() / 100 == 2 && validReceipt(job, receipt, upload)) {
            discard(manifest, job);
            return;
        }
        String code = receipt.optString("code");
        if ((response.statusCode() == 404 && "parentMissing".equals(code))
                || (response.statusCode() == 409 && "rowPending".equals(code))) {
            // Media is sent only after confirmed row storage. Retention must never recreate that parent.
            discard(manifest, job);
            return;
        }
        if (upload && (response.statusCode() == 413 || (response.statusCode() >= 400 && response.statusCode() < 500
                && response.statusCode() != 408 && response.statusCode() != 429))) {
            String reason = "tooLarge".equals(code) ? "tooLarge" : "uploadRejected";
            locked(root, () -> {
                JSONObject current = current(manifest, job);
                if (current != null) { fail(current, reason); save(manifest, current); }
                return null;
            });
            job.put("state", "unavailable").put("reason", reason);
        }
        retry(manifest, job, HeliosSink.retryAfterMillis(response.headers().firstValue("Retry-After").orElse("")));
    }

    private void discard(Path manifest, JSONObject snapshot) throws IOException {
        locked(root, () -> {
            if (current(manifest, snapshot) != null) {
                cleanupOwnedSource(snapshot);
                if (snapshot.optBoolean("sourceOwned") && !snapshot.optString("source").isEmpty()
                        && Files.exists(Path.of(snapshot.getString("source")))) return null;
                Files.deleteIfExists(bytesPath(manifest));
                Files.deleteIfExists(manifest);
            }
            return null;
        });
    }

    private static JSONObject current(Path manifest, JSONObject snapshot) throws IOException {
        if (!Files.exists(manifest)) return null;
        JSONObject current = read(manifest);
        for (String field : new String[] { "sessionId", "ordinal", "kind", "captureId", "state", "sha256", "bytes" })
            if (!java.util.Objects.equals(current.opt(field), snapshot.opt(field))) return null;
        return current;
    }

    static boolean validIdentity(JSONObject job, JSONObject receipt) {
        if (!job.optString("sessionId").equals(receipt.optString("sessionId"))
                || job.getInt("ordinal") != receipt.optInt("ordinal", -1)
                || !job.getString("kind").equals(receipt.optString("kind"))) return false;
        try {
            java.time.OffsetDateTime expiry = java.time.OffsetDateTime.parse(receipt.getString("expiresAtUtc"));
            return expiry.getOffset().equals(java.time.ZoneOffset.UTC);
        } catch (RuntimeException invalid) { return false; }
    }

    static boolean validReceipt(JSONObject job, JSONObject receipt, boolean upload) {
        if (!validIdentity(job, receipt)) return false;
        String state = receipt.optString("state");
        if ("expired".equals(state) || "evicted".equals(state)) return true;
        if ("unavailable".equals(state)) return !receipt.optString("reason").isEmpty();
        if ("available".equals(state)) {
            String hash = receipt.optString("sha256");
            long bytes = receipt.optLong("bytes", -1);
            if (!hash.matches("[a-fA-F0-9]{64}") || bytes <= 0) return false;
            return !upload || (job.optString("sha256").equalsIgnoreCase(hash) && job.optLong("bytes", -1) == bytes);
        }
        return false;
    }

    private void retry(Path manifest, JSONObject job, long serverDelay) throws IOException {
        locked(root, () -> {
            JSONObject current = current(manifest, job);
            if (current == null) return null;
            int failures = Math.min(current.optInt("failures") + 1, 5);
            current.put("failures", failures).put("retryAt", HeliosSink.retryAt(failures, serverDelay));
            save(manifest, current);
            return null;
        });
    }

    private static boolean validCapability(JSONObject value) {
        return value != null && value.optInt("version") == 1 && value.optLong("maxScreenshotBytes") > 0 && value.optLong("maxVideoBytes") > 0;
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = in.read(bytes)) != -1) digest.update(bytes, 0, count);
            }
            return HeliosSink.hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    private static Path bytesPath(Path manifest) { return manifest.resolveSibling(manifest.getFileName() + ".ready"); }
    private static FileChannel lockChannel(Path manifest) throws IOException {
        return FileChannel.open(manifest.resolveSibling(manifest.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }
    private static FileLock tryLock(FileChannel channel) throws IOException {
        try { return channel.tryLock(); }
        catch (OverlappingFileLockException busy) { return null; }
    }
    private static List<Path> jobs(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return new ArrayList<>();
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().collect(Collectors.toList());
        }
    }
    private static JSONObject read(Path path) throws IOException { return new JSONObject(Files.readString(path)); }
    private static JSONObject readQuiet(Path path) {
        try { return read(path); } catch (IOException | RuntimeException invalid) { return new JSONObject(); }
    }
    private static void save(Path path, JSONObject value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        byte[] bytes = value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
        }
        move(temporary, path);
    }
    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static <T> T locked(Path root, java.util.concurrent.Callable<T> operation) throws IOException {
        synchronized (ROOT_MUTEX) {
            Files.createDirectories(root);
            try (FileChannel file = FileChannel.open(root.resolve(".admission.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = file.lock()) {
                try { return operation.call(); }
                catch (IOException error) { throw error; }
                catch (Exception error) { throw new IOException(error); }
            }
        }
    }
}
