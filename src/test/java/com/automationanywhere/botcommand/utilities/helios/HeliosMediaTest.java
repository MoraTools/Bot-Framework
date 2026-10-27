package com.automationanywhere.botcommand.utilities.helios;

import com.automationanywhere.botcommand.utilities.screen.recorder.CaptureResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import support.TempDirectories;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static support.Polling.await;

public class HeliosMediaTest {
    private final TempDirectories temp = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void deleteTemporaryFolders() { temp.deleteAll(); }

    /** The Helios session the outbox tests talk about. The server answers for any session ID. */
    private static final String SESSION_ID = UUID.randomUUID().toString();

    @Test public void everyOrdinalGetsAnIndependentPosterAndVideoIntent() throws Exception {
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            Path root = temp.create("helios-media-rows");
            HeliosMediaOutbox media = outbox(server, root, "logger", "journal");
            try {
                Path poster = png(root);
                CaptureResult capture = CaptureResult.unavailable("shared", "videoUnavailable");
                JSONArray first = media.register(0, new ErrorMedia(poster, null, capture, true));
                JSONArray second = media.register(1, new ErrorMedia(poster, null, capture, true));
                Assert.assertEquals(first.length(), 2);
                Assert.assertEquals(second.length(), 2);
                Assert.assertEquals(first.getJSONObject(1).getString("reason"), "videoUnavailable");
                Assert.assertEquals(countJobs(root), 4);
                Assert.assertEquals(server.requests.size(), 0, "no media before verified parent receipt");
                media.session("journal", SESSION_ID, caps());
                media.acknowledge("journal", 0, 2);
                await(() -> countJobsQuiet(root) == 0);
                Assert.assertEquals(uploads(server).size(), 2);
                Assert.assertEquals(failures(server).size(), 2);
                await(() -> {
                    try (Stream<Path> files = Files.walk(root.resolve("media"))) {
                        return files.noneMatch(path -> path.toString().endsWith(".json.lock"));
                    } catch (Exception error) { return false; }
                });
                Assert.assertTrue(Files.exists(poster), "local original is not an outbox-owned file");
            } finally { media.stop(); }
        }
    }

    @Test public void mediaWithoutLimitsWaitsForALaterLoggerStart() throws Exception {
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            Path root = temp.create("helios-media-upgrade");
            HeliosMediaOutbox old = outbox(server, root, "logger", "old-journal");
            old.register(0, new ErrorMedia(png(root), null, null, false));
            old.session("old-journal", SESSION_ID, null);
            old.acknowledge("old-journal", 0, 1);
            Thread.sleep(250);
            Assert.assertEquals(server.requests.size(), 0);
            old.stop();
            Assert.assertEquals(countJobs(root), 1);
            HeliosMediaOutbox laterNoVideo = outbox(server, root, "next-no-video", "next-journal");
            try {
                laterNoVideo.session("next-journal", UUID.randomUUID().toString(), caps());
                await(() -> countJobsQuiet(root) == 0);
                Assert.assertEquals(uploads(server).size(), 1);
            } finally { laterNoVideo.stop(); }
        }
    }

    @Test public void unavailableRawReceiptIsTerminalAndInvalidExpiryKeepsBytes() throws Exception {
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            server.respond(request -> isUpload(request) ? HeliosStubServer.Reply.of(200,
                    HeliosStubServer.receipt(request, "unavailable").put("reason", "storageBudget").toString()) : null);
            Path root = temp.create("helios-media-terminal");
            HeliosMediaOutbox media = outbox(server, root, "logger", "journal");
            try {
                media.register(0, new ErrorMedia(png(root), null, null, false));
                media.session("journal", SESSION_ID, caps());
                media.acknowledge("journal", 0, 1);
                await(() -> countJobsQuiet(root) == 0);
                Assert.assertEquals(uploads(server).size(), 1);
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
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            CountDownLatch uploadStarted = new CountDownLatch(1);
            CountDownLatch uploadRelease = new CountDownLatch(1);
            server.respond(request -> {
                if (isUpload(request)) {
                    uploadStarted.countDown();
                    uploadRelease.await(5, TimeUnit.SECONDS);
                }
                return null;
            });
            Path root = temp.create("helios-media-remap");
            HeliosMediaOutbox media = outbox(server, root, "logger", "journal");
            try {
                media.register(0, new ErrorMedia(png(root), null, null, false));
                media.session("journal", SESSION_ID, caps());
                media.acknowledge("journal", 0, 1);
                Assert.assertTrue(uploadStarted.await(5, TimeUnit.SECONDS));
                String remapped = UUID.randomUUID().toString();
                media.session("journal", remapped, caps());
                uploadRelease.countDown();
                Thread.sleep(250);
                Assert.assertEquals(countJobs(root), 1);
                JSONObject saved = new JSONObject(Files.readString(firstJob(root)));
                Assert.assertEquals(saved.getString("sessionId"), remapped);
                Assert.assertFalse(saved.getBoolean("confirmed"));
                Assert.assertTrue(Files.exists(firstJob(root).resolveSibling(firstJob(root).getFileName() + ".ready")));
            } finally { uploadRelease.countDown(); media.stop(); }
        }
    }

    @Test public void rawProxy413ReportsUploadRejectedAndTypedTooLargeReportsTooLarge() throws Exception {
        for (String code : new String[] { "", "tooLarge" }) {
            try (HeliosStubServer server = HeliosStubServer.withMedia()) {
                server.reply("/media/screenshot", 413, new JSONObject().put("code", code).toString());
                Path root = temp.create("helios-media-reject");
                HeliosMediaOutbox media = outbox(server, root, "logger", "journal");
                try {
                    media.register(0, new ErrorMedia(png(root), null, null, false));
                    media.session("journal", SESSION_ID, caps());
                    media.acknowledge("journal", 0, 1);
                    await(() -> failures(server).size() == 1);
                    Assert.assertEquals(failures(server).get(0).getString("reason"), code.isEmpty() ? "uploadRejected" : "tooLarge");
                    await(() -> countJobsQuiet(root) == 0);
                } finally { media.stop(); }
            }
        }
    }

    @Test public void oversizeAndCaptureFailureKeepTheirTerminalDeclarations() throws Exception {
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            Path root = temp.create("helios-media-size");
            HeliosMediaOutbox media = outbox(server, root, "logger", "journal");
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
        Path root = temp.create("helios-media-budget");
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
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            Path root = temp.create("helios-media-damaged");
            HeliosMediaOutbox media = outbox(server, root, "logger", "journal");
            try {
                media.register(0, new ErrorMedia(png(root), null, null, false));
                Path damaged = root.resolve("media/partition/damaged.json");
                Files.writeString(damaged, "{torn");
                media.session("journal", SESSION_ID, caps());
                media.acknowledge("journal", 0, 1);
                await(() -> uploads(server).size() == 1);
                await(() -> countJobsQuiet(root) == 1);
                Assert.assertEquals(Files.readString(damaged), "{torn");
            } finally { media.stop(); }
        }
    }

    @Test public void ownedStillCleanupNeedsDurableStagingAndIoFailureDoesNotLoseTheErrorRow() throws Exception {
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            Path root = temp.create("helios-owned-still");
            HeliosMediaOutbox media = outbox(server, root, "logger", "journal");
            try {
                Path owned = png(root);
                media.register(0, new ErrorMedia(owned, null, null, false, true));
                Assert.assertFalse(Files.exists(owned));
                Path local = png(root);
                media.register(1, new ErrorMedia(local, null, null, false, false));
                Assert.assertTrue(Files.exists(local));
            } finally { media.stop(); }
            Path failedRoot = temp.create("helios-owned-io");
            String logger = UUID.randomUUID().toString();
            String previousRoot = System.getProperty("helios.outbox.directory");
            System.setProperty("helios.outbox.directory", failedRoot.toString());
            com.automationanywhere.botcommand.utilities.logger.CustomLogger custom = null;
            Path owned = png(failedRoot);
            try {
                custom = new com.automationanywhere.botcommand.utilities.logger.CustomLogger("io-test", failedRoot.resolve("log.html").toString(),
                        1000, 0, java.util.Set.of(), com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode.FAST, server.config("activity"));
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
                Assert.assertEquals(server.rows.size(), 1);
                Assert.assertEquals(lastEntry(server).getString("level"), "ERROR");
                Assert.assertEquals(lastEntry(server).getJSONArray("media").getJSONObject(0).getString("reason"), "storageFailure");
            } finally {
                if (custom != null) custom.close();
                if (previousRoot == null) System.clearProperty("helios.outbox.directory");
                else System.setProperty("helios.outbox.directory", previousRoot);
            }
        }
    }

    @Test public void separateChildrenHaveStableStreamIdsAndKeepTheAaExecutionId() throws Exception {
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            Path root = temp.create("helios-child-loggers");
            String previous = System.getProperty("helios.outbox.directory");
            System.setProperty("helios.outbox.directory", root.toString());
            com.automationanywhere.botcommand.utilities.logger.CustomLogger inherited = null;
            com.automationanywhere.botcommand.utilities.logger.CustomLogger child = null;
            try {
                inherited = new com.automationanywhere.botcommand.utilities.logger.CustomLogger("parent", root.resolve("parent.html").toString(),
                        1000, 0, java.util.Set.of(), com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode.FAST, server.config("activity"));
                child = new com.automationanywhere.botcommand.utilities.logger.CustomLogger("child", root.resolve("child.html").toString(),
                        1000, 0, java.util.Set.of(), com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode.FAST, server.config("activity"));
                inherited.getLogger().error("first ERROR");
                inherited.getLogger().info("recovered in inherited child");
                child.getLogger().error("child ERROR");
                inherited.close();
                child.close();
                Assert.assertEquals(server.requests("/sessions").size(), 2);
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (HeliosStubServer.Request request : server.requests("/sessions")) {
                    JSONObject start = request.json();
                    ids.add(start.getString("loggerSessionId"));
                    Assert.assertEquals(start.getString("executionId"), "activity");
                }
                Assert.assertEquals(ids.size(), 2);
                Assert.assertTrue(ids.contains(inherited.getLoggerId()));
                Assert.assertTrue(ids.contains(child.getLoggerId()));
                Assert.assertEquals(server.rows.size(), 3);
            } finally {
                if (inherited != null) inherited.close();
                if (child != null) child.close();
                if (previous == null) System.clearProperty("helios.outbox.directory");
                else System.setProperty("helios.outbox.directory", previous);
            }
        }
    }

    @Test public void sidecarFailureDeclaresOnlyCloudVideoUnavailableAndPreservesTheCompletedLocalClip() throws Exception {
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            Path root = temp.create("helios-local-sidecar-fault");
            Path local = root.resolve("local.mp4");
            Files.writeString(local, "completed local original");
            java.lang.reflect.Constructor<CaptureResult> constructor = CaptureResult.class.getDeclaredConstructor(
                    String.class, Path.class, Path.class, String.class);
            constructor.setAccessible(true);
            CaptureResult capture = constructor.newInstance("local-capture", local, null, null);
            capture.mediaFailure = "storageFailure";
            capture.completion.complete(local);
            HeliosMediaOutbox media = outbox(server, root, "logger", "journal");
            try {
                JSONArray declarations = media.register(0, new ErrorMedia(png(root), null, capture, true));
                Assert.assertEquals(declarations.getJSONObject(0).getString("state"), "pending");
                Assert.assertEquals(declarations.getJSONObject(1).getString("state"), "unavailable");
                Assert.assertEquals(declarations.getJSONObject(1).getString("reason"), "storageFailure");
                media.session("journal", SESSION_ID, caps());
                media.acknowledge("journal", 0, 1);
                await(() -> countJobsQuiet(root) == 0);
                Assert.assertEquals(uploads(server).size(), 1, "the poster still uploads independently");
                Assert.assertEquals(failures(server).get(failures(server).size() - 1).getString("reason"), "storageFailure");
                Assert.assertEquals(Files.readString(local), "completed local original");
            } finally { media.stop(); }
        }
    }

    @Test public void storedVideoLeftByAClosedSessionIsRemovedWhenHeliosAnswersBeforeTheBody() throws Exception {
        try (EarlyReceiptServer server = new EarlyReceiptServer()) {
            Path root = temp.create("helios-early-receipt");
            HeliosConfig config = new HeliosConfig(server.url(), "test-key", "activity", "bot", "", "", "machine", "user", null);
            java.lang.reflect.Constructor<CaptureResult> constructor = CaptureResult.class.getDeclaredConstructor(
                    String.class, Path.class, Path.class, String.class);
            constructor.setAccessible(true);
            Path clip = root.resolve("clip.mp4");
            CaptureResult capture = constructor.newInstance("closed-capture", clip, null, null);
            HeliosMediaOutbox closed = new HeliosMediaOutbox(root, root.resolve("partition"), "closed", "closed-journal", config,
                    HttpClient.newHttpClient(), ignored -> { });
            try {
                closed.register(1, new ErrorMedia(png(root), null, capture, true));
                closed.session("closed-journal", SESSION_ID, caps());
                closed.acknowledge("closed-journal", 0, 2);
                closed.finishJSON();
            } finally { closed.stop(); }
            Files.write(clip, new byte[7 * 1024 * 1024]);
            capture.completion.complete(clip);
            Path video = root.resolve("media/partition/closed-1-video.json");
            Path ready = video.resolveSibling(video.getFileName() + ".ready");
            JSONObject staged = new JSONObject(Files.readString(video));
            Assert.assertTrue(staged.getBoolean("confirmed"));
            Assert.assertEquals(staged.getString("state"), "pending");
            Assert.assertTrue(staged.has("sha256"));
            Assert.assertTrue(Files.exists(ready));
            List<String> warnings = java.util.Collections.synchronizedList(new ArrayList<>());
            HeliosMediaOutbox next = new HeliosMediaOutbox(root, root.resolve("partition"), "next", "next-journal", config,
                    HttpClient.newHttpClient(), warnings::add);
            try {
                await(() -> countJobsQuiet(root) == 0);
                Assert.assertEquals(warnings, List.of());
                Path descriptors = Path.of("/proc/self/fd");
                if (Files.isDirectory(descriptors)) {
                    try (Stream<Path> fds = Files.list(descriptors)) {
                        for (Path fd : (Iterable<Path>) fds::iterator) {
                            String target;
                            try { target = Files.readSymbolicLink(fd).toString(); }
                            catch (java.io.IOException vanished) { continue; }
                            Assert.assertFalse(target.startsWith(ready.toString()), "the uploaded file is still open: " + target);
                        }
                    }
                }
            } finally { next.stop(); }
        }
    }

    @Test public void sendLoopWarningNamesTheErrorClassWithoutTheIngestKey() throws Exception {
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            Path root = temp.create("helios-media-warning");
            Files.createDirectories(root.resolve("media/partition"));
            Files.writeString(root.resolve("media/partition/damaged.json"), "{torn");
            List<String> warnings = java.util.Collections.synchronizedList(new ArrayList<>());
            HeliosMediaOutbox media = new HeliosMediaOutbox(root, root.resolve("partition"), "logger", "journal",
                    server.config("activity"), HttpClient.newHttpClient(), warnings::add);
            try {
                await(() -> warnings.size() == 1);
                String warning = warnings.get(0);
                Assert.assertTrue(warning.startsWith("A media record could not be processed"), warning);
                Assert.assertTrue(warning.contains("org.json.JSONException"), warning);
                Assert.assertFalse(warning.contains("test-key"), warning);
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

    /** Answers each upload from the request head, waits, and closes without reading the body, like Helios for stored media. */
    private static final class EarlyReceiptServer implements AutoCloseable {
        private final java.net.ServerSocket server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());

        EarlyReceiptServer() throws java.io.IOException {
            Thread accept = new Thread(() -> {
                while (!server.isClosed()) {
                    try (java.net.Socket socket = server.accept()) { answer(socket); }
                    catch (Exception closed) { /* the server or the client closed */ }
                }
            });
            accept.setDaemon(true);
            accept.start();
        }

        String url() { return "http://127.0.0.1:" + server.getLocalPort(); }

        private static void answer(java.net.Socket socket) throws java.io.IOException, InterruptedException {
            java.io.InputStream in = socket.getInputStream();
            java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
            while (!head.toString(java.nio.charset.StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                int next = in.read();
                if (next < 0) return;
                head.write(next);
            }
            String[] lines = head.toString(java.nio.charset.StandardCharsets.ISO_8859_1).split("\r\n");
            String[] parts = lines[0].split(" ")[1].split("/");
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(), lines[i].substring(colon + 1).trim());
            }
            byte[] body = new JSONObject().put("sessionId", parts[4]).put("ordinal", Integer.parseInt(parts[6])).put("kind", parts[8])
                    .put("state", "available").put("sha256", headers.get("x-helios-media-sha256"))
                    .put("bytes", Long.parseLong(headers.get("content-length")))
                    .put("expiresAtUtc", Instant.now().plusSeconds(86400).toString()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            // Closing at once resets the connection over the unread body, and on Windows the reset can discard the receipt.
            Thread.sleep(300);
        }

        @Override public void close() throws java.io.IOException { server.close(); }
    }

    private static JSONObject caps() { return HeliosStubServer.mediaCapability(); }
    private static HeliosMediaOutbox outbox(HeliosStubServer server, Path root, String logger, String journal) throws Exception {
        return new HeliosMediaOutbox(root, root.resolve("partition"), logger, journal, server.config("activity"),
                HttpClient.newHttpClient(), ignored -> { });
    }
    /** The bytes of a screenshot or video: {@code .../entries/<ordinal>/media/<kind>}. */
    private static boolean isUpload(HeliosStubServer.Request request) { return request.path.split("/").length == 9; }
    private static List<HeliosStubServer.Request> uploads(HeliosStubServer server) {
        List<HeliosStubServer.Request> result = new ArrayList<>();
        for (HeliosStubServer.Request request : server.requests) {
            if (!isUpload(request)) continue;
            Assert.assertEquals(request.headers.getFirst("Content-Length"), Integer.toString(request.content.length));
            result.add(request);
        }
        return result;
    }
    /** The terminal declarations sent to {@code .../entries/<ordinal>/media}. */
    private static List<JSONObject> failures(HeliosStubServer server) {
        List<JSONObject> result = new ArrayList<>();
        for (HeliosStubServer.Request request : server.requests)
            if (request.path.split("/").length == 8) result.add(request.json());
        return result;
    }
    private static JSONObject lastEntry(HeliosStubServer server) {
        List<HeliosStubServer.Request> batches = server.requests("/entries");
        return batches.get(batches.size() - 1).json().getJSONArray("entries").getJSONObject(0);
    }
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
}
