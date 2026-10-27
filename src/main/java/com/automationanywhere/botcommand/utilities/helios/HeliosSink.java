package com.automationanywhere.botcommand.utilities.helios;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.nio.file.NoSuchFileException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** One background sender. Journals survive timeouts, rejected requests and runner restarts. */
public final class HeliosSink {
    static final String DELAY_WARNING = "Helios delivery was delayed. Saved entries retry from the disk journal.";
    static final String INVALID_URL = "Invalid Helios URL. Use an http or https URL with a host. "
            + "HTML logging continues without cloud logs.";
    static final String STREAMS_WARNING = "Helios did not confirm separate logger streams. Update Helios; "
            + "saved entries remain on disk for later delivery.";
    public static final String KEY_HEADER = "X-Helios-Ingest-Key";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private final HeliosConfig config;
    private final HttpClient http;
    private final HeliosJournal journal;
    private final HeliosMediaOutbox media;
    private final Consumer<String> warning;
    private final Thread worker;
    private final Deque<Path> recovery = new ArrayDeque<>();
    private volatile boolean closing;
    private volatile boolean delivered;
    private volatile boolean stopping;

    public HeliosSink(HeliosConfig config, Path root, Consumer<String> warning, String loggerSessionId) throws Exception {
        this.config = config;
        this.warning = warning;
        try {
            URI destination = URI.create(config.baseUrl);
            if (!("http".equalsIgnoreCase(destination.getScheme()) || "https".equalsIgnoreCase(destination.getScheme()))
                    || destination.getHost() == null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            // Fail before the journal: its folder is named by the URL, so a corrected URL would never replay it.
            throw new IllegalArgumentException(INVALID_URL);
        }
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(TIMEOUT);
        if (config.proxySelector != null) builder.proxy(config.proxySelector);
        http = builder.build();
        // Partition by destination and credential without persisting the credential itself.
        Path directory = root.resolve(hex(MessageDigest.getInstance("SHA-256")
                .digest((config.baseUrl + "\0" + config.ingestKey).getBytes(StandardCharsets.UTF_8))));
        Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".helios-journal"))
                    .sorted().forEach(recovery::add);
        }
        ZonedDateTime now = ZonedDateTime.now();
        JSONObject start = new JSONObject().put("executionId", config.executionId)
                .put("loggerSessionId", loggerSessionId)
                .put("sessionIdentity", true)
                .put("botUri", config.botUri).put("parentBotUri", config.parentBotUri)
                .put("fileId", config.fileId).put("machine", config.machine).put("user", config.user)
                .put("startedAt", LOCAL_TIME.format(now)).put("utcOffsetMinutes", offsetMinutes(now));
        journal = new HeliosJournal(directory.resolve(UUID.randomUUID() + ".helios-journal"), start,
                bufferBytes(warning));
        HeliosMediaOutbox mediaOutbox;
        try { mediaOutbox = new HeliosMediaOutbox(root, directory, loggerSessionId, journal.path.getFileName().toString(), config, http, warning); }
        catch (IOException | RuntimeException unavailable) {
            mediaOutbox = null;
            warning.accept("ERROR media storage could not start. JSON and HTML logging continue.");
        }
        media = mediaOutbox;
        com.automationanywhere.botcommand.utilities.screen.recorder.ScreenRecorder.recoverAtStartup();
        worker = new Thread(this::run, "helios-delivery");
        worker.setDaemon(true);
        worker.start();
    }

    public void append(JSONObject entry) throws IOException { journal.append(entry); }

    public JSONArray registerMedia(int ordinal, ErrorMedia request) throws IOException {
        if (media == null) throw new IOException("ERROR media outbox unavailable");
        return media.register(ordinal, request);
    }

    public void stopMedia() {
        if (media == null) return;
        media.awaitSessionMedia();
        media.stop();
    }

    /** Persist the expected count before waiting. A timeout never removes the journal. */
    public boolean finish(String status, int expectedEntries) throws IOException {
        ZonedDateTime now = ZonedDateTime.now();
        try {
            journal.append(new JSONObject().put("status", status).put("endedAt", LOCAL_TIME.format(now))
                    .put("utcOffsetMinutes", offsetMinutes(now)).put("expectedEntries", expectedEntries));
            closing = true;
            worker.join(5000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (media != null) media.finishJSON();
            stopping = true;
            worker.interrupt();
            try { worker.join(1000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        return delivered;
    }

    private void run() {
        Delivery current = new Delivery(journal);
        Delivery previous = null;
        try {
            while (!stopping) {
                if (previous == null) {
                    while (!recovery.isEmpty() && previous == null) {
                        try { previous = new Delivery(new HeliosJournal(recovery.removeFirst(), null)); }
                        catch (java.nio.channels.OverlappingFileLockException | HeliosJournal.BusyException
                                | NoSuchFileException unavailable) { /* owned or already delivered by another sender */ }
                        catch (IOException | RuntimeException error) {
                            warning.accept("An older journal could not be opened; it was retained.");
                        }
                    }
                }
                if (!delivered) delivered = current.step(closing);
                // Replay at most one older batch per turn so the current session gets a turn too.
                if (previous != null && (previous.step(true) || previous.refused)) {
                    previous.journal.close();
                    // Closed first: Windows cannot rename an open file. A failed rename retries at the next start.
                    if (previous.unverified) {
                        Path path = previous.journal.path;
                        try { Files.move(path, path.resolveSibling(path.getFileName() + ".unverified")); }
                        catch (IOException retained) { /* kept, or already renamed by another sender */ }
                    }
                    previous = null;
                }
                if ((delivered || closing && current.refused) && previous == null && recovery.isEmpty()) break;
                Thread.sleep(100);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            warning.accept("Delivery stopped; the disk journal was retained.");
        } finally {
            try { journal.close(); } catch (IOException ignored) { }
            if (previous != null) try { previous.journal.close(); } catch (IOException ignored) { }
        }
    }

    private final class Delivery {
        final HeliosJournal journal;
        String sessionId;
        long retryAt;
        long firstPendingAt;
        int failures;
        boolean refused;
        boolean replayed;
        boolean skipped;
        /** An older journal that can never be verified, after Helios stored all its complete records once. */
        boolean unverified;
        int batchEntries = HeliosJournal.BATCH_ENTRIES;
        JSONObject pending;

        Delivery(HeliosJournal journal) { this.journal = journal; }

        boolean step(boolean flush) throws InterruptedException {
            if (refused || System.currentTimeMillis() < retryAt) return false;
            try {
                if (sessionId == null) {
                    JSONObject response = new JSONObject(post("/api/ingest/sessions", journal.start));
                    String id = response.getString("sessionId");
                    String streamId = UUID.fromString(id).toString();
                    // A session ID that is not a canonical UUID is a bad reply, so it retries; only the stream fields refuse.
                    if (!streamId.equalsIgnoreCase(id)) throw new IOException("Invalid session response");
                    try {
                        String execution = response.getString("executionSessionId");
                        String executionSessionId = UUID.fromString(execution).toString();
                        String savedExecutionId = journal.start.getString("executionId");
                        // Helios returns its stored execution ID, limited to 256 characters.
                        if (!executionSessionId.equalsIgnoreCase(execution)
                                || streamId.equals(executionSessionId)
                                || !savedExecutionId.substring(0, Math.min(savedExecutionId.length(), 256))
                                        .equals(response.getString("executionId")))
                            throw new IllegalArgumentException("Invalid stream response");
                    } catch (RuntimeException unconfirmed) {
                        // A valid session reply without separate streams will not change on retry.
                        refused = true;
                        warning.accept(STREAMS_WARNING);
                        return false;
                    }
                    sessionId = streamId;
                    try { if (media != null) media.session(journal.path.getFileName().toString(), sessionId, response.optJSONObject("media")); }
                    catch (IOException mediaFailure) { warning.accept("Media session state could not be saved. JSON delivery continues; saved media is retained."); }
                }
                JSONObject body = pending == null ? journal.next(batchEntries) : pending;
                if (body == null) {
                    // No closing record means a previous run crashed. Upload its available rows,
                    // but never invent a verified close for that run.
                    unverified = flush && journal != HeliosSink.this.journal && !skipped;
                    return flush && journal != HeliosSink.this.journal;
                }
                String path = "/api/ingest/sessions/" + sessionId;
                if (body.has("entries")) {
                    JSONArray entries = body.getJSONArray("entries");
                    if (firstPendingAt == 0) firstPendingAt = System.nanoTime();
                    if (!flush && !body.optBoolean("journalFull") && entries.length() < batchEntries
                            && body.toString().getBytes(StandardCharsets.UTF_8).length < HeliosJournal.BATCH_BYTES
                            && System.nanoTime() - firstPendingAt < 2_000_000_000L) return false;
                    pending = body;
                    long offset = body.getLong("journalOffset");
                    JSONObject payload = new JSONObject(body.toString());
                    payload.remove("journalOffset");
                    payload.remove("journalFull");
                    JSONObject receipt = new JSONObject(post(path + "/entries", payload));
                    int count = entries.length();
                    if (receipt.getInt("accepted") < 0 || receipt.getInt("duplicates") < 0
                            || receipt.getInt("accepted") + receipt.getInt("duplicates") != count
                            || receipt.getInt("nextOrdinal") != body.getInt("firstOrdinal") + count)
                        throw new IOException("Invalid batch receipt");
                    try { if (media != null) media.acknowledge(journal.path.getFileName().toString(), body.getInt("firstOrdinal"), count); }
                    catch (IOException mediaFailure) { warning.accept("Media parent receipt could not be saved. JSON delivery continues; saved media is retained."); }
                    journal.acknowledge(offset);
                    pending = null;
                    firstPendingAt = 0;
                } else {
                    JSONObject receipt = new JSONObject(post(path + "/end", body));
                    if (!receipt.getBoolean("complete")
                            || receipt.getInt("storedEntries") != body.getInt("expectedEntries"))
                        throw new IOException("Invalid closing receipt");
                    // Before removing replayable JSON, persist the remote identity and all verified parents.
                    if (media != null) {
                        media.session(journal.path.getFileName().toString(), sessionId, null);
                        media.acknowledge(journal.path.getFileName().toString(), 0, body.getInt("expectedEntries"));
                    }
                    try { journal.deleteVerified(); }
                    catch (IOException retained) {
                        warning.accept("Helios verified delivery, but the disk journal could not be deleted. It was retained.");
                    }
                    return true;
                }
                failures = 0;
            } catch (ResponseError error) {
                if (error.status == 413 && pending != null) {
                    int count = pending.getJSONArray("entries").length();
                    if (count > 1) {
                        batchEntries = Math.max(1, count / 2);
                    } else {
                        // Advance delivery only: keep the rejected record in the journal for later recovery.
                        journal.acknowledge(pending.getLong("journalOffset"));
                        skipped = true;
                        batchEntries = HeliosJournal.BATCH_ENTRIES;
                        warning.accept("An oversized entry was retained on disk. Later entries will still be sent.");
                    }
                    pending = null;
                    firstPendingAt = 0;
                    return false;
                }
                if (error.status == 409 && error.missingEntries) {
                    if (replayed) {
                        refused = true;
                        warning.accept("Delivery is incomplete after replay; the journal was retained for inspection.");
                        return false;
                    }
                    replayed = true;
                    journal.rewind();
                    pending = null;
                }
                else if (error.status != 408 && error.status != 429 && error.status < 500) {
                    refused = true;
                    warning.accept("Helios rejected delivery (HTTP " + error.status + "); the journal was retained.");
                    return false;
                }
                delay(error.retryMillis);
            } catch (InvalidObjectException corrupt) {
                refused = true;
                unverified = journal != HeliosSink.this.journal && !skipped;
                warning.accept("A damaged journal was retained after sending its complete records.");
            } catch (IOException | RuntimeException error) {
                delay(0);
            }
            return false;
        }

        private void delay(long serverDelay) {
            warning.accept(DELAY_WARNING);
            retryAt = retryAt(failures, serverDelay);
            failures = Math.min(failures + 1, 5);
        }
    }

    static long retryAt(int failures, long serverDelay) {
        long now = System.currentTimeMillis();
        long delay = Math.max(serverDelay, Math.min(30_000L, 1000L << failures) + ThreadLocalRandom.current().nextLong(251));
        return now + Math.min(delay, Long.MAX_VALUE - now);
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    static Path outboxRoot() {
        return Path.of(System.getProperty("helios.outbox.directory",
                Path.of(System.getProperty("user.home"), ".helios", "outbox").toString()));
    }

    private String post(String path, JSONObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl + path)).timeout(TIMEOUT)
                .header("Content-Type", "application/json").header(KEY_HEADER, config.ingestKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new ResponseError(response);
        return response.body();
    }

    private static final class ResponseError extends IOException {
        final int status;
        final long retryMillis;
        final boolean missingEntries;
        ResponseError(HttpResponse<String> response) {
            super("HTTP " + response.statusCode());
            status = response.statusCode();
            retryMillis = retryAfterMillis(response.headers().firstValue("Retry-After").orElse(""));
            boolean missing = false;
            try { missing = "missingEntries".equals(new JSONObject(response.body()).optString("code")); }
            catch (RuntimeException ignored) { }
            missingEntries = missing;
        }
    }

    static long retryAfterMillis(String value) {
        try { return Math.max(0, Math.multiplyExact(Long.parseLong(value.trim()), 1000)); }
        catch (RuntimeException ignored) {
            try {
                return Math.max(0, Duration.between(Instant.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis());
            } catch (RuntimeException invalid) { return 0; }
        }
    }

    static long bufferBytes(Consumer<String> warning) {
        String value = System.getProperty("helios.bufferMiB", System.getenv("HELIOS_BUFFER_MIB"));
        if (value == null || value.isBlank()) return HeliosJournal.MAX_BYTES;
        try {
            long mib = Long.parseLong(value.trim());
            if (mib < 1 || mib > 65536) throw new IllegalArgumentException();
            return mib * 1024 * 1024;
        } catch (IllegalArgumentException invalid) {
            warning.accept("Invalid HELIOS_BUFFER_MIB; using the default 256 MiB per session.");
            return HeliosJournal.MAX_BYTES;
        }
    }

    private static int offsetMinutes(ZonedDateTime at) { return at.getOffset().getTotalSeconds() / 60; }
}
