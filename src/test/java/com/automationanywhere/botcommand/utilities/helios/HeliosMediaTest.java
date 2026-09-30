package com.automationanywhere.botcommand.utilities.helios;

import com.automationanywhere.botcommand.utilities.screen.recorder.CaptureResult;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

public class HeliosMediaTest {
    @Test public void everyOrdinalGetsAnIndependentPosterAndVideoIntent() throws Exception {
        try (Server server = new Server()) {
            Path root = Files.createTempDirectory("helios-media-rows");
            HeliosMediaOutbox media = server.outbox(root, "logger", "journal");
            try {
                Path poster = png(root);
                CaptureResult capture = CaptureResult.unavailable("shared", "videoUnavailable");
                JSONArray first = media.register(0, new ErrorMedia(poster, null, capture, true));
                JSONArray second = media.register(1, new ErrorMedia(poster, null, capture, true));
                Assert.assertEquals(first.length(), 2);
                Assert.assertEquals(second.length(), 2);
                Assert.assertEquals(first.getJSONObject(1).getString("reason"), "videoUnavailable");
                Assert.assertEquals(countJobs(root), 4);
                Assert.assertEquals(server.requests.get(), 0, "no media before verified parent receipt");
                media.session("journal", server.sessionId, caps());
                media.acknowledge("journal", 0, 2);
                await(() -> countJobsQuiet(root) == 0);
                Assert.assertEquals(server.raw.get(), 2);
                Assert.assertEquals(server.failures.get(), 2);
                await(() -> {
                    try (Stream<Path> files = Files.walk(root.resolve("media"))) {
                        return files.noneMatch(path -> path.toString().endsWith(".json.lock"));
                    } catch (Exception error) { return false; }
                });
                Assert.assertTrue(Files.exists(poster), "local original is not an outbox-owned file");
            } finally { media.stop(); }
        }
    }

    @Test public void oldServerRecoveryDeclaresMediaAfterJsonJournalIsGone() throws Exception {
        try (Server server = new Server()) {
            Path root = Files.createTempDirectory("helios-media-upgrade");
            HeliosMediaOutbox old = server.outbox(root, "logger", "old-journal");
            old.register(0, new ErrorMedia(png(root), null, null, false));
            old.session("old-journal", server.sessionId, null);
            old.acknowledge("old-journal", 0, 1);
            Thread.sleep(250);
            Assert.assertEquals(server.requests.get(), 0);
            old.stop();
            Assert.assertEquals(countJobs(root), 1);
            HeliosMediaOutbox laterNoVideo = server.outbox(root, "next-no-video", "next-journal");
            try {
                laterNoVideo.session("next-journal", UUID.randomUUID().toString(), caps());
                await(() -> countJobsQuiet(root) == 0);
                Assert.assertEquals(server.declarations.get(), 1);
                Assert.assertEquals(server.raw.get(), 1);
            } finally { laterNoVideo.stop(); }
        }
    }

    @Test public void unavailableRawReceiptIsTerminalAndInvalidExpiryKeepsBytes() throws Exception {
        try (Server server = new Server()) {
            server.rawUnavailable = true;
            Path root = Files.createTempDirectory("helios-media-terminal");
            HeliosMediaOutbox media = server.outbox(root, "logger", "journal");
            try {
                media.register(0, new ErrorMedia(png(root), null, null, false));
                media.session("journal", server.sessionId, caps());
                media.acknowledge("journal", 0, 1);
                await(() -> countJobsQuiet(root) == 0);
                Assert.assertEquals(server.raw.get(), 1);
            } finally { media.stop(); }
        }
        JSONObject job = job("pending");
        JSONObject receipt = receipt(job, "available").put("sha256", job.getString("sha256")).put("bytes", 9);
        Assert.assertTrue(HeliosMediaOutbox.validReceipt(job, receipt, true));
        receipt.put("expiresAtUtc", "not-a-time");
        Assert.assertFalse(HeliosMediaOutbox.validReceipt(job, receipt, true));
        receipt.put("expiresAtUtc", "2030-01-01T00:00:00+02:00");
        Assert.assertFalse(HeliosMediaOutbox.validReceipt(job, receipt, true));
        receipt.remove("expiresAtUtc");
        Assert.assertFalse(HeliosMediaOutbox.validReceipt(job, receipt, true));
    }

    @Test public void delayedReceiptCannotDeleteAChangedRemoteSession() throws Exception {
        try (Server server = new Server()) {
            server.blockRaw = true;
            Path root = Files.createTempDirectory("helios-media-remap");
            HeliosMediaOutbox media = server.outbox(root, "logger", "journal");
            try {
                media.register(0, new ErrorMedia(png(root), null, null, false));
                media.session("journal", server.sessionId, caps());
                media.acknowledge("journal", 0, 1);
                Assert.assertTrue(server.rawStarted.await(5, TimeUnit.SECONDS));
                String remapped = UUID.randomUUID().toString();
                media.session("journal", remapped, caps());
                server.rawRelease.countDown();
                Thread.sleep(250);
                Assert.assertEquals(countJobs(root), 1);
                JSONObject saved = new JSONObject(Files.readString(firstJob(root)));
                Assert.assertEquals(saved.getString("sessionId"), remapped);
                Assert.assertFalse(saved.getBoolean("confirmed"));
                Assert.assertTrue(Files.exists(firstJob(root).resolveSibling(firstJob(root).getFileName() + ".ready")));
            } finally { server.rawRelease.countDown(); media.stop(); }
        }
    }

    @Test public void rawProxy413ReportsUploadRejectedAndTypedTooLargeReportsTooLarge() throws Exception {
        for (String code : new String[] { "", "tooLarge" }) {
            try (Server server = new Server()) {
                server.rawReject = code;
                Path root = Files.createTempDirectory("helios-media-reject");
                HeliosMediaOutbox media = server.outbox(root, "logger", "journal");
                try {
                    media.register(0, new ErrorMedia(png(root), null, null, false));
                    media.session("journal", server.sessionId, caps());
                    media.acknowledge("journal", 0, 1);
                    await(() -> server.failures.get() == 1);
                    Assert.assertEquals(server.lastReason, code.isEmpty() ? "uploadRejected" : "tooLarge");
                    await(() -> countJobsQuiet(root) == 0);
                } finally { media.stop(); }
            }
        }
    }

    @Test public void oversizeAndCaptureFailureKeepTheirTerminalDeclarations() throws Exception {
        try (Server server = new Server()) {
            Path root = Files.createTempDirectory("helios-media-size");
            HeliosMediaOutbox media = server.outbox(root, "logger", "journal");
            try {
                Path tooLarge = root.resolve("large.png");
                try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(tooLarge.toFile(), "rw")) {
                    file.setLength(HeliosMediaOutbox.SCREENSHOT_BYTES + 1);
                }
                JSONArray size = media.register(0, new ErrorMedia(tooLarge, null, null, false));
                Assert.assertEquals(size.getJSONObject(0).getString("reason"), "tooLarge");
                JSONArray failure = media.register(1, new ErrorMedia(null, "captureFailed", null, true));
                Assert.assertEquals(failure.getJSONObject(0).getString("reason"), "captureFailed");
                Assert.assertEquals(failure.getJSONObject(1).getString("reason"), "videoUnavailable");
                Assert.assertEquals(HeliosMediaOutbox.usage(root.resolve("media")), 0L);
            } finally { media.stop(); }
        }
    }

    @Test public void reservationsCountFilledPartOnlyOnceAndVideoEvictsBeforeScreenshot() throws Exception {
        Path root = Files.createTempDirectory("helios-media-budget");
        Path partition = Files.createDirectories(root.resolve("partition"));
        Path video = partition.resolve("video.json");
        Files.writeString(video, job("pending").put("kind", "video").put("createdAt", 1).toString());
        Path still = partition.resolve("still.json");
        Files.writeString(still, job("pending").put("kind", "screenshot").put("createdAt", 0).toString());
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(video + ".ready", "rw")) {
            file.setLength(HeliosMediaOutbox.MAX_BYTES - 16);
        }
        Files.write(still.resolveSibling(still.getFileName() + ".ready"), new byte[16]);
        Assert.assertTrue(HeliosMediaOutbox.admit(root, 9, "video", partition.resolve("incoming.json")));
        Assert.assertFalse(Files.exists(video.resolveSibling(video.getFileName() + ".ready")));
        Assert.assertTrue(Files.exists(still.resolveSibling(still.getFileName() + ".ready")));
        Assert.assertEquals(new JSONObject(Files.readString(video)).getString("reason"), "storageBudget");
        Path partJob = partition.resolve("part.json");
        Files.writeString(partJob, job("pending").put("reserved", 100).toString());
        Files.write(partJob.resolveSibling(partJob.getFileName() + ".ready.part"), new byte[40]);
        Assert.assertEquals(HeliosMediaOutbox.usage(root), 116L);
    }

    @Test public void damagedManifestDoesNotBlockJsonAcknowledgementOrAnotherReadyFile() throws Exception {
        try (Server server = new Server()) {
            Path root = Files.createTempDirectory("helios-media-damaged");
            HeliosMediaOutbox media = server.outbox(root, "logger", "journal");
            try {
                media.register(0, new ErrorMedia(png(root), null, null, false));
                Path damaged = root.resolve("media/partition/damaged.json");
                Files.writeString(damaged, "{torn");
                media.session("journal", server.sessionId, caps());
                media.acknowledge("journal", 0, 1);
                await(() -> server.raw.get() == 1);
                await(() -> countJobsQuiet(root) == 1);
                Assert.assertEquals(Files.readString(damaged), "{torn");
            } finally { media.stop(); }
        }
    }

    @Test public void ownedStillCleanupNeedsDurableStagingAndIoFailureDoesNotLoseTheErrorRow() throws Exception {
        try (Server server = new Server()) {
            Path root = Files.createTempDirectory("helios-owned-still");
            HeliosMediaOutbox media = server.outbox(root, "logger", "journal");
            try {
                Path owned = png(root);
                media.register(0, new ErrorMedia(owned, null, null, false, true));
                Assert.assertFalse(Files.exists(owned));
                Path local = png(root);
                media.register(1, new ErrorMedia(local, null, null, false, false));
                Assert.assertTrue(Files.exists(local));
            } finally { media.stop(); }
            Path failedRoot = Files.createTempDirectory("helios-owned-io");
            String logger = UUID.randomUUID().toString();
            String previousRoot = System.getProperty("helios.outbox.directory");
            System.setProperty("helios.outbox.directory", failedRoot.toString());
            com.automationanywhere.botcommand.utilities.logger.CustomLogger custom = null;
            Path owned = png(failedRoot);
            try {
                custom = new com.automationanywhere.botcommand.utilities.logger.CustomLogger("io-test", failedRoot.resolve("log.html").toString(),
                        1000, 0, java.util.Set.of(), com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode.FAST, server.config());
                Path partition;
                try (Stream<Path> files = Files.list(failedRoot.resolve("media"))) {
                    partition = files.filter(Files::isDirectory).findFirst().orElseThrow();
                }
                Path blocked = Files.createDirectories(partition.resolve(custom.getLoggerId() + "-0-screenshot.json.tmp"));
                Files.writeString(blocked.resolve("keep"), "simulate inaccessible durable write");
                java.util.Map<String, Object> message = new java.util.HashMap<>();
                message.put(com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout.Columns.MESSAGE, "preserved error");
                message.put(ErrorMedia.COLUMN, new ErrorMedia(owned, null, null, false, true));
                custom.getLogger().error(message);
                Assert.assertTrue(Files.exists(owned), "failed durable registration retains the capture");
                custom.close();
                Assert.assertEquals(server.rows.get(), 1);
                Assert.assertEquals(server.lastEntry.getString("level"), "ERROR");
                Assert.assertEquals(server.lastEntry.getJSONArray("media").getJSONObject(0).getString("reason"), "storageFailure");
            } finally {
                if (custom != null) custom.close();
                if (previousRoot == null) System.clearProperty("helios.outbox.directory");
                else System.setProperty("helios.outbox.directory", previousRoot);
            }
        }
    }

    @Test public void separateChildrenHaveStableStreamIdsAndKeepTheAaExecutionId() throws Exception {
        try (Server server = new Server()) {
            Path root = Files.createTempDirectory("helios-child-loggers");
            String previous = System.getProperty("helios.outbox.directory");
            System.setProperty("helios.outbox.directory", root.toString());
            com.automationanywhere.botcommand.utilities.logger.CustomLogger inherited = null;
            com.automationanywhere.botcommand.utilities.logger.CustomLogger child = null;
            try {
                inherited = new com.automationanywhere.botcommand.utilities.logger.CustomLogger("parent", root.resolve("parent.html").toString(),
                        1000, 0, java.util.Set.of(), com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode.FAST, server.config());
                child = new com.automationanywhere.botcommand.utilities.logger.CustomLogger("child", root.resolve("child.html").toString(),
                        1000, 0, java.util.Set.of(), com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode.FAST, server.config());
                inherited.getLogger().error("first ERROR");
                inherited.getLogger().info("recovered in inherited child");
                child.getLogger().error("child ERROR");
                inherited.close();
                child.close();
                Assert.assertEquals(server.starts.size(), 2);
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (JSONObject start : server.starts) {
                    ids.add(start.getString("loggerSessionId"));
                    Assert.assertEquals(start.getString("executionId"), "activity");
                }
                Assert.assertEquals(ids.size(), 2);
                Assert.assertTrue(ids.contains(inherited.getLoggerId()));
                Assert.assertTrue(ids.contains(child.getLoggerId()));
                Assert.assertEquals(server.rows.get(), 3);
            } finally {
                if (inherited != null) inherited.close();
                if (child != null) child.close();
                if (previous == null) System.clearProperty("helios.outbox.directory");
                else System.setProperty("helios.outbox.directory", previous);
            }
        }
    }

    @Test public void sidecarFailureDeclaresOnlyCloudVideoUnavailableAndPreservesTheCompletedLocalClip() throws Exception {
        try (Server server = new Server()) {
            Path root = Files.createTempDirectory("helios-local-sidecar-fault");
            Path local = root.resolve("local.mp4");
            Files.writeString(local, "completed local original");
            java.lang.reflect.Constructor<CaptureResult> constructor = CaptureResult.class.getDeclaredConstructor(
                    String.class, Path.class, Path.class, String.class);
            constructor.setAccessible(true);
            CaptureResult capture = constructor.newInstance("local-capture", local, null, null);
            capture.localRequired.set(true);
            capture.mediaFailure = "storageFailure";
            capture.completion.complete(local);
            HeliosMediaOutbox media = server.outbox(root, "logger", "journal");
            try {
                JSONArray declarations = media.register(0, new ErrorMedia(png(root), null, capture, true));
                Assert.assertEquals(declarations.getJSONObject(0).getString("state"), "pending");
                Assert.assertEquals(declarations.getJSONObject(1).getString("state"), "unavailable");
                Assert.assertEquals(declarations.getJSONObject(1).getString("reason"), "storageFailure");
                media.session("journal", server.sessionId, caps());
                media.acknowledge("journal", 0, 1);
                await(() -> countJobsQuiet(root) == 0);
                Assert.assertEquals(server.raw.get(), 1, "the poster still uploads independently");
                Assert.assertEquals(server.lastReason, "storageFailure");
                Assert.assertEquals(Files.readString(local), "completed local original");
            } finally { media.stop(); }
        }
    }

    @Test public void terminalReceiptsNeedIdentityAndAvailableContentMustMatch() {
        JSONObject job = job("pending");
        Assert.assertTrue(HeliosMediaOutbox.validReceipt(job, receipt(job, "unavailable").put("reason", "storageBudget"), true));
        Assert.assertTrue(HeliosMediaOutbox.validReceipt(job, receipt(job, "expired"), true));
        Assert.assertTrue(HeliosMediaOutbox.validReceipt(job, receipt(job, "evicted"), true));
        Assert.assertFalse(HeliosMediaOutbox.validReceipt(job, receipt(job, "available").put("sha256", "b".repeat(64)).put("bytes", 9), true));
        Assert.assertFalse(HeliosMediaOutbox.validReceipt(job, receipt(job, "expired").put("ordinal", 1), true));
    }

    private static JSONObject caps() { return new JSONObject().put("version", 1).put("maxScreenshotBytes", 1048576).put("maxVideoBytes", 8388608); }
    private static JSONObject job(String state) {
        return new JSONObject().put("sessionId", UUID.randomUUID().toString()).put("ordinal", 0).put("kind", "screenshot")
                .put("state", state).put("sha256", "a".repeat(64)).put("bytes", 9).put("reserved", 0);
    }
    private static JSONObject receipt(JSONObject job, String state) {
        return new JSONObject().put("sessionId", job.getString("sessionId")).put("ordinal", job.getInt("ordinal"))
                .put("kind", job.getString("kind")).put("state", state).put("expiresAtUtc", Instant.now().plusSeconds(86400).toString());
    }
    private static Path png(Path directory) throws Exception {
        Path file = directory.resolve(UUID.randomUUID() + ".png");
        Files.write(file, new byte[] { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 42 });
        return file;
    }
    private static long countJobs(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root.resolve("media"))) { return files.filter(p -> p.toString().endsWith(".json")).count(); }
    }
    private static long countJobsQuiet(Path root) { try { return countJobs(root); } catch (Exception error) { return -1; } }
    private static Path firstJob(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root.resolve("media"))) { return files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow(); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + 8_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(20);
        Assert.assertTrue(condition.getAsBoolean(), "media state did not arrive");
    }

    private static final class Server implements AutoCloseable {
        final HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        final String sessionId = UUID.randomUUID().toString();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger rows = new AtomicInteger();
        final AtomicInteger raw = new AtomicInteger();
        final AtomicInteger declarations = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final CountDownLatch rawStarted = new CountDownLatch(1);
        final CountDownLatch rawRelease = new CountDownLatch(1);
        volatile boolean rawUnavailable;
        volatile boolean blockRaw;
        volatile String rawReject;
        volatile String lastReason;
        volatile JSONObject lastEntry;
        final java.util.List<JSONObject> starts = new java.util.concurrent.CopyOnWriteArrayList<>();
        Server() throws Exception {
            server.createContext("/api/ingest/sessions", exchange -> {
                requests.incrementAndGet();
                byte[] content = exchange.getRequestBody().readAllBytes();
                String[] parts = exchange.getRequestURI().getPath().split("/");
                if (parts.length == 4 || parts.length == 5 || (parts.length == 6 && !"entries".equals(parts[5]))) {
                    JSONObject body = new JSONObject(new String(content, StandardCharsets.UTF_8));
                    JSONObject reply;
                    if (parts.length == 4) {
                        starts.add(body);
                        reply = new JSONObject().put("sessionId", body.optString("loggerSessionId", sessionId)).put("media", caps());
                    }
                    else if ("entries".equals(parts[5])) {
                        int count = body.getJSONArray("entries").length();
                        rows.addAndGet(count);
                        reply = new JSONObject().put("accepted", count).put("duplicates", 0)
                                .put("nextOrdinal", body.getInt("firstOrdinal") + count);
                    } else reply = new JSONObject().put("complete", true).put("storedEntries", body.getInt("expectedEntries"));
                    byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                    return;
                }
                if (parts.length == 6) {
                    JSONObject body = new JSONObject(new String(content, StandardCharsets.UTF_8));
                    int count = body.getJSONArray("entries").length();
                    rows.addAndGet(count);
                    lastEntry = body.getJSONArray("entries").getJSONObject(0);
                    byte[] bytes = new JSONObject().put("accepted", count).put("duplicates", 0)
                            .put("nextOrdinal", body.getInt("firstOrdinal") + count).toString().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                    return;
                }
                JSONObject identity = new JSONObject().put("sessionId", parts[4]).put("ordinal", Integer.parseInt(parts[6]));
                JSONObject reply;
                int status = 200;
                if (parts.length == 8) {
                    JSONObject declaration = new JSONObject(new String(content, StandardCharsets.UTF_8));
                    identity.put("kind", declaration.getString("kind"));
                    if ("pending".equals(declaration.getString("state"))) declarations.incrementAndGet();
                    else { failures.incrementAndGet(); lastReason = declaration.getString("reason"); }
                    reply = receipt(identity, declaration.getString("state"));
                    if (declaration.has("reason")) reply.put("reason", declaration.getString("reason"));
                } else {
                    raw.incrementAndGet();
                    rawStarted.countDown();
                    if (blockRaw) {
                        try { rawRelease.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                    identity.put("kind", parts[8]);
                    Assert.assertEquals(exchange.getRequestHeaders().getFirst("Content-Length"), Integer.toString(content.length));
                    if (rawReject != null) { status = 413; reply = new JSONObject().put("code", rawReject); }
                    else if (rawUnavailable) reply = receipt(identity, "unavailable").put("reason", "storageBudget");
                    else reply = receipt(identity, "available").put("sha256", exchange.getRequestHeaders().getFirst("X-Helios-Media-SHA256"))
                            .put("bytes", content.length);
                }
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }
        HeliosConfig config() {
            return new HeliosConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "activity", "bot", "", "", "machine", "user", null);
        }
        HeliosMediaOutbox outbox(Path root, String logger, String journal) throws Exception {
            return new HeliosMediaOutbox(root, root.resolve("partition"), logger, journal, config(),
                    HttpClient.newHttpClient(), ignored -> { });
        }
        @Override public void close() { rawRelease.countDown(); server.stop(0); }
    }
}
