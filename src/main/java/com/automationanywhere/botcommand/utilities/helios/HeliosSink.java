package com.automationanywhere.botcommand.utilities.helios;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
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
    public static final String KEY_HEADER = "X-Helios-Ingest-Key";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private final HeliosConfig config;
    private final HttpClient http;
    private final HeliosJournal journal;
    private final Consumer<String> warning;
    private final Thread worker;
    private final Deque<Path> recovery = new ArrayDeque<>();
    private volatile boolean closing;
    private volatile boolean delivered;
    private volatile boolean stopping;

    public HeliosSink(HeliosConfig config, Path root, Consumer<String> warning) throws Exception {
        this.config = config;
        this.warning = warning;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(TIMEOUT);
        if (config.proxySelector != null) builder.proxy(config.proxySelector);
        http = builder.build();
        // Partition by destination and credential without persisting the credential itself.
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((config.baseUrl + "\0" + config.ingestKey).getBytes(StandardCharsets.UTF_8));
        StringBuilder bucket = new StringBuilder();
        for (byte value : digest) bucket.append(String.format("%02x", value));
        Path directory = root.resolve(bucket.toString());
        Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".helios-journal"))
                    .sorted().forEach(recovery::add);
        }
        ZonedDateTime now = ZonedDateTime.now();
        JSONObject start = new JSONObject().put("executionId", config.executionId)
                .put("botUri", config.botUri).put("parentBotUri", config.parentBotUri)
                .put("fileId", config.fileId).put("machine", config.machine).put("user", config.user)
                .put("startedAt", LOCAL_TIME.format(now)).put("utcOffsetMinutes", offsetMinutes(now));
        journal = new HeliosJournal(directory.resolve(UUID.randomUUID() + ".helios-journal"), start,
                bufferBytes(warning));
        worker = new Thread(this::run, "helios-delivery");
        worker.setDaemon(true);
        worker.start();
    }

    public void append(JSONObject entry) throws IOException { journal.append(entry); }

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
                        catch (java.nio.channels.OverlappingFileLockException busy) { /* another logger owns it */ }
                        catch (IOException | RuntimeException error) {
                            warning.accept("An older journal could not be opened; it was retained.");
                        }
                    }
                }
                if (!delivered) delivered = current.step(closing);
                // Replay at most one older batch per turn so the current session gets a turn too.
                if (previous != null && (previous.step(true) || previous.refused)) {
                    previous.journal.close();
                    previous = null;
                }
                if (delivered && previous == null && recovery.isEmpty()) break;
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
        JSONObject pending;

        Delivery(HeliosJournal journal) { this.journal = journal; }

        boolean step(boolean flush) throws InterruptedException {
            if (refused || System.currentTimeMillis() < retryAt) return false;
            try {
                if (sessionId == null) {
                    JSONObject response = new JSONObject(post("/api/ingest/sessions", journal.start));
                    sessionId = UUID.fromString(response.getString("sessionId")).toString();
                }
                JSONObject body = pending == null ? journal.next() : pending;
                if (body == null) {
                    // No closing record means a previous run crashed. Upload its available rows,
                    // but never invent a verified close for that run.
                    return flush && journal != HeliosSink.this.journal;
                }
                String path = "/api/ingest/sessions/" + sessionId;
                if (body.has("entries")) {
                    JSONArray entries = body.getJSONArray("entries");
                    if (firstPendingAt == 0) firstPendingAt = System.nanoTime();
                    if (!flush && !body.optBoolean("journalFull") && entries.length() < HeliosJournal.BATCH_ENTRIES
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
                    journal.acknowledge(offset);
                    pending = null;
                    firstPendingAt = 0;
                } else {
                    String response = post(path + "/end", body);
                    // An older server's 204 is not proof of delivery: keep the journal.
                    if (response.isEmpty()) {
                        refused = true;
                        warning.accept("The server did not verify delivery. Update Helios; the journal was retained.");
                        return false;
                    }
                    JSONObject receipt = new JSONObject(response);
                    if (!receipt.getBoolean("complete")
                            || receipt.getInt("storedEntries") != body.getInt("expectedEntries"))
                        throw new IOException("Invalid closing receipt");
                    journal.deleteVerified();
                    return true;
                }
                failures = 0;
            } catch (ResponseError error) {
                if (error.status == 409 && error.missingEntries) {
                    journal.rewind();
                    pending = null;
                }
                else if (error.status != 408 && error.status != 429 && error.status < 500) {
                    refused = true;
                    warning.accept("Helios rejected delivery (HTTP " + error.status + "); the journal was retained.");
                    return false;
                }
                delay(error.retryMillis);
            } catch (IOException | RuntimeException error) {
                delay(0);
            }
            return false;
        }

        private void delay(long serverDelay) {
            long backoff = Math.min(30_000L, 1000L << failures);
            failures = Math.min(failures + 1, 5);
            long now = System.currentTimeMillis();
            long delay = Math.max(serverDelay, backoff + ThreadLocalRandom.current().nextLong(251));
            retryAt = now + Math.min(delay, Long.MAX_VALUE - now);
        }
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
