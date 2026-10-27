package com.automationanywhere.botcommand.utilities.helios;

import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import support.TempDirectories;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static support.LogEntries.occurrences;
import static support.Polling.await;

public class HeliosDeliveryRegressionTest {
    private final TempDirectories temp = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void deleteTemporaryFolders() { temp.deleteAll(); }

    @DataProvider public Object[][] invalidStarts() {
        // The last reply has valid stream fields; only its session ID is not a canonical UUID.
        String nonCanonical = new JSONObject().put("sessionId", "1-2-3-4-5").put("executionId", "retry")
                .put("executionSessionId", UUID.randomUUID().toString()).toString();
        return new Object[][] { { "<html>Sign in</html>" }, { "" }, { "{}" }, { "{\"sessionId\":\"invalid\"}" }, { nonCanonical } };
    }

    @Test(dataProvider = "invalidStarts")
    public void invalidStartReplyIsRetriedWithoutRefusing(String reply) throws Exception {
        Path root = temp.create("helios-start-retry");
        try (HeliosStubServer server = new HeliosStubServer()) {
            server.replyOnce("/sessions", 200, reply);
            List<String> warnings = new CopyOnWriteArrayList<>();
            HeliosSink sink = sink(server, "retry", root, warnings::add);
            try {
                sink.append(entry());
            } finally { Assert.assertTrue(sink.finish("OK", 1), warnings.toString()); sink.stopMedia(); }
            Assert.assertEquals(server.requests("/sessions").size(), 2);
            Assert.assertEquals(server.rows.size(), 1);
            Assert.assertEquals(warnings, List.of(HeliosSink.DELAY_WARNING));
        }
    }

    @DataProvider public Object[][] unconfirmedStreams() {
        // The stub's session ID for the AA execution "streams".
        String id = UUID.nameUUIDFromBytes("streams".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String other = UUID.randomUUID().toString();
        return new Object[][] {
                { new JSONObject().put("sessionId", id).put("executionId", "streams").put("executionSessionId", JSONObject.NULL).toString() },
                { new JSONObject().put("sessionId", id).put("executionId", "streams").put("executionSessionId", "1-2-3-4-5").toString() },
                { new JSONObject().put("sessionId", id).put("executionId", "streams").put("executionSessionId", id).toString() },
                { new JSONObject().put("sessionId", id).put("executionId", "another-aa-run").put("executionSessionId", other).toString() } };
    }

    @Test(dataProvider = "unconfirmedStreams")
    public void unconfirmedStreamsRefuseOnceAndStopFast(String reply) throws Exception {
        Path root = temp.create("helios-streams");
        try (HeliosStubServer server = new HeliosStubServer()) {
            server.reply("/sessions", 200, reply);
            List<String> warnings = new CopyOnWriteArrayList<>();
            HeliosSink sink = sink(server, "streams", root, warnings::add);
            try {
                sink.append(entry());
            } finally {
                long start = System.nanoTime();
                Assert.assertFalse(sink.finish("OK", 1));
                sink.stopMedia();
                long millis = (System.nanoTime() - start) / 1_000_000;
                Assert.assertTrue(millis < 2500, "refused finish took " + millis + " ms");
            }
            Assert.assertEquals(server.requests("/sessions").size(), 1);
            Assert.assertTrue(server.requests("/entries").isEmpty());
            Assert.assertTrue(server.requests("/end").isEmpty());
            Assert.assertEquals(warnings, List.of(HeliosSink.STREAMS_WARNING));
            try (Stream<Path> files = Files.walk(root)) {
                List<Path> journals = files.filter(path -> path.toString().endsWith(".helios-journal")).collect(java.util.stream.Collectors.toList());
                Assert.assertEquals(journals.size(), 1);
                Assert.assertEquals(Files.readAllLines(journals.get(0)).size(), 3);
            }
        }
    }

    @DataProvider public Object[][] refusals() { return new Object[][] { { 400 }, { 401 }, { 403 }, { 404 } }; }

    @Test(dataProvider = "refusals")
    public void refusedSessionStaysOpenUntilFinishThenStopsFast(int status) throws Exception {
        Path root = temp.create("helios-refused");
        try (HeliosStubServer server = new HeliosStubServer()) {
            server.reply("/sessions", status, "");
            List<String> warnings = new CopyOnWriteArrayList<>();
            HeliosSink sink = sink(server, "refused", root, warnings::add);
            try {
                await(() -> warnings.stream().anyMatch(text -> text.contains("HTTP " + status)));
                // Refusal must not close a live journal: later bot entries still need durable capture.
                sink.append(entry());
            } finally {
                long start = System.nanoTime();
                Assert.assertFalse(sink.finish("OK", 1));
                sink.stopMedia();
                long millis = (System.nanoTime() - start) / 1_000_000;
                Assert.assertTrue(millis < 2500, "refused finish took " + millis + " ms");
            }
            Assert.assertEquals(server.requests("/sessions").size(), 1);
            Assert.assertTrue(server.requests("/entries").isEmpty());
            try (Stream<Path> files = Files.walk(root)) {
                Path journal = files.filter(path -> path.toString().endsWith(".helios-journal")).findFirst().orElseThrow();
                Assert.assertEquals(Files.readAllLines(journal).size(), 3);
            }
        }
    }

    @DataProvider public Object[][] terminalFailures() { return new Object[][] { { false }, { true } }; }

    @Test(dataProvider = "terminalFailures")
    public void terminalWarningsRemainVisibleAfterDelay(boolean incomplete) throws Exception {
        Path root = temp.create("helios-terminal-warning");
        String previous = System.getProperty("helios.outbox.directory");
        System.setProperty("helios.outbox.directory", root.resolve("outbox").toString());
        try (HeliosStubServer server = new HeliosStubServer()) {
            String suffix = incomplete ? "/end" : "/entries";
            server.replyOnce(suffix, 503, "");
            server.reply(suffix, incomplete ? 409 : 401,
                    incomplete ? "{\"code\":\"missingEntries\"}" : "");
            Path log = root.resolve("log.html");
            CustomLogger logger = new CustomLogger("terminal", log.toString(), 1000, 0, Set.of(), EncodingMode.FAST,
                    server.config("terminal"));
            try { logger.getLogger().info("one row"); }
            finally { logger.close(); }
            String html = Files.readString(log);
            Assert.assertEquals(occurrences(html, "Helios delivery was delayed"), 1);
            Assert.assertEquals(occurrences(html, incomplete ? "Delivery is incomplete after replay" : "Helios rejected delivery (HTTP 401)"), 1);
            Assert.assertEquals(occurrences(html, "Helios delivery is pending"), 1);
            Assert.assertEquals(server.rows.size(), incomplete ? 1 : 0, "local warnings never enter the cloud journal");
        } finally { restoreOutbox(previous); }
    }

    @DataProvider public Object[][] invalidUrls() {
        return new Object[][] { { "127.0.0.1:5180" }, { "ftp://helios.example" }, { "http://" }, { "" }, { "http://bad host" } };
    }

    @Test(dataProvider = "invalidUrls")
    public void invalidUrlFailsBeforeAnyJournalIsCreated(String url) throws Exception {
        Path root = temp.create("helios-invalid-sink");
        IllegalArgumentException error = Assert.expectThrows(IllegalArgumentException.class, () -> new HeliosSink(
                new HeliosConfig(url, "key", "invalid", "", "", "", "", "", null), root, ignored -> { }, UUID.randomUUID().toString()));
        Assert.assertEquals(error.getMessage(), HeliosSink.INVALID_URL);
        try (Stream<Path> files = Files.list(root)) {
            Assert.assertEquals(files.count(), 0L, "no journal folder, journal or media folder");
        }
    }

    @Test
    public void invalidUrlWarnsOnceAndStopDoesNotWait() throws Exception {
        Path root = temp.create("helios-invalid-url");
        String previous = System.getProperty("helios.outbox.directory");
        System.setProperty("helios.outbox.directory", root.resolve("outbox").toString());
        try {
            Path log = root.resolve("log.html");
            CustomLogger logger = new CustomLogger("invalid-url", log.toString(), 1000, 0, Set.of(), EncodingMode.FAST,
                    new HeliosConfig("127.0.0.1:5180", "key", "invalid", "", "", "", "", "", null));
            long start = System.nanoTime();
            try { logger.getLogger().info("saved row"); }
            finally { logger.close(); }
            long millis = (System.nanoTime() - start) / 1_000_000;
            Assert.assertTrue(millis < 2500, "stop took " + millis + " ms");
            String html = Files.readString(log);
            Assert.assertEquals(occurrences(html, HeliosSink.INVALID_URL), 1);
            Assert.assertEquals(occurrences(html, "Helios"), 1, "no delay or pending warning");
            Assert.assertFalse(html.contains("Cloud capture could not start"), "the cause is not hidden");
            Assert.assertTrue(html.contains("saved row"));
            Assert.assertFalse(Files.exists(root.resolve("outbox")), "nothing is saved for an URL that cannot be replayed");
        } finally { restoreOutbox(previous); }
    }

    @Test
    public void verifiedDeliveryDoesNotBecomePendingWhenJournalDeleteFails() throws Exception {
        Path root = temp.create("helios-delete-failure");
        Path[] blocked = { null };
        Set<PosixFilePermission>[] permissions = new Set[] { null };
        try (HeliosStubServer server = new HeliosStubServer()) {
            AtomicInteger ends = new AtomicInteger();
            server.respond(request -> {
                if (!request.path.endsWith("/end")) return null;
                ends.incrementAndGet();
                try (Stream<Path> files = Files.walk(root)) {
                    Path journal = files.filter(path -> path.toString().endsWith(".helios-journal")).findFirst().orElseThrow();
                    blocked[0] = journal;
                    if (Files.getFileStore(journal).supportsFileAttributeView("posix")) {
                        permissions[0] = Files.getPosixFilePermissions(journal.getParent());
                        Files.setPosixFilePermissions(journal.getParent(), PosixFilePermissions.fromString("r-x------"));
                    } else Files.setAttribute(journal, "dos:readonly", true);
                }
                return null;
            });
            List<String> warnings = new CopyOnWriteArrayList<>();
            HeliosSink sink = sink(server, "verified", root, warnings::add);
            try { sink.append(entry()); }
            finally { Assert.assertTrue(sink.finish("OK", 1), "a verified end is delivered even if deletion fails"); sink.stopMedia(); }
            Assert.assertEquals(ends.get(), 1, "do not send the verified end again");
            Assert.assertTrue(Files.exists(blocked[0]));
            Assert.assertEquals(warnings.stream().filter(text -> text.contains("could not be deleted")).count(), 1L);
            Assert.assertFalse(warnings.stream().anyMatch(text -> text.contains("delayed")));
        } finally {
            if (blocked[0] != null) {
                if (permissions[0] != null) Files.setPosixFilePermissions(blocked[0].getParent(), permissions[0]);
                else if (Files.exists(blocked[0])) Files.setAttribute(blocked[0], "dos:readonly", false);
            }
        }
    }

    private static HeliosSink sink(HeliosStubServer server, String execution, Path root,
                                   java.util.function.Consumer<String> warning) throws Exception {
        return new HeliosSink(server.config(execution), root, warning, UUID.randomUUID().toString());
    }

    private static JSONObject entry() {
        return new JSONObject().put("firstOrdinal", 0).put("entries", new JSONArray().put(new JSONObject().put("message", "saved row")));
    }
    private static void restoreOutbox(String value) {
        if (value == null) System.clearProperty("helios.outbox.directory");
        else System.setProperty("helios.outbox.directory", value);
    }
}
