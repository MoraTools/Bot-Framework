package logs;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.utilities.helios.HeliosConfig;
import com.automationanywhere.botcommand.utilities.helios.HeliosAppender;
import com.automationanywhere.botcommand.utilities.helios.HeliosSink;
import com.automationanywhere.botcommand.utilities.helios.HeliosStubServer;
import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import support.LogEntries;
import support.TempDirectories;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Exercises the whole streaming path against a local {@link HeliosStubServer}: session start,
 * entry envelopes, session end, and the behaviour when the server is not there at all.
 *
 * @author jamir-boop
 */
public class HeliosSinkTest {

    /** The stub derives the session ID from the AA execution ID of {@link #config(String)}. */
    private static final String SESSION_ID = UUID.nameUUIDFromBytes("execution-1".getBytes(StandardCharsets.UTF_8)).toString();
    private static final String KEY = "hik_test_secret";
    /** Bot that opens the logger session; its own fileId must lose to the master's. */
    private static final String BOT_URI =
            "file:///c/Automation Anywhere/tasks/My Task?workspace=PUBLIC&fileId=child-file";
    /** Master Task Bot that started the run; Helios Cloud identifies the bot by this one. */
    private static final String PARENT_BOT_URI =
            "file:///c/Automation Anywhere/tasks/Master Task?workspace=PUBLIC&fileId=abc";

    private final TempDirectories temp = new TempDirectories();
    private HeliosStubServer server;
    private Path outbox;

    @AfterClass(alwaysRun = true)
    public void deleteTemporaryFolders() { temp.deleteAll(); }

    @BeforeMethod
    public void isolateOutbox() throws IOException {
        outbox = temp.create("helios-sender-test");
        System.setProperty("helios.outbox.directory", outbox.toString());
    }

    @AfterMethod(alwaysRun = true)
    public void stopServer() {
        if (server != null) {
            server.close();
            server = null;
        }
        System.clearProperty("helios.outbox.directory");
        System.clearProperty("helios.bufferMiB");
    }

    private String startStubServer() throws IOException {
        server = new HeliosStubServer();
        return server.url();
    }

    private static HeliosConfig config(String baseUrl) {
        // fileId is the master's, the way StartLoggerSession resolves it.
        return new HeliosConfig(baseUrl + "/", KEY, "execution-1", BOT_URI, PARENT_BOT_URI,
                "abc", "test-machine", "test-user", null);
    }

    private Path newLogFile() throws IOException {
        return temp.create("helios-log").resolve("log.html");
    }

    private static void logThreeRows(CustomLogger session) {
        Logger logger = session.getLogger();
        logger.info(row("first"));
        logger.warn(row("second"));
        logger.error(row("third"));
    }

    private static Map<String, Object> row(String message) {
        Map<String, Object> row = new HashMap<>();
        row.put(CustomHTMLLayout.Columns.MESSAGE, message);
        row.put(CustomHTMLLayout.Columns.SOURCE, "PUBLIC:/Bots/tasks/My Task@v1#L1");
        row.put(CustomHTMLLayout.Columns.SCREENSHOT, "");
        row.put(CustomHTMLLayout.Columns.VIDEO, "");
        return row;
    }

    private List<HeliosStubServer.Request> callsEndingWith(String suffix) {
        return server.requests(suffix);
    }

    private static String key(HeliosStubServer.Request call) {
        return call.headers.getFirst(HeliosSink.KEY_HEADER);
    }

    @Test
    public void streamsStartEntriesAndEnd() throws Exception {
        String baseUrl = startStubServer();
        Path logFile = newLogFile();

        CustomLogger session = new CustomLogger("CustomLogger_" + UUID.randomUUID(), logFile.toString(),
                1000, 0, new HashSet<>(), EncodingMode.FAST, config(baseUrl));
        logThreeRows(session);
        session.close();

        List<HeliosStubServer.Request> starts = callsEndingWith("/api/ingest/sessions");
        Assert.assertEquals(starts.size(), 1, "one start call");
        HeliosStubServer.Request startCall = starts.get(0);
        JSONObject start = startCall.json();
        Assert.assertEquals(key(startCall), KEY, "ingest key header");
        Assert.assertEquals(start.getString("executionId"), "execution-1");
        Assert.assertEquals(start.getString("fileId"), "abc",
                "fileId is the master bot URI's, not the child's");
        Assert.assertEquals(start.getString("machine"), "test-machine");
        Assert.assertEquals(start.getString("user"), "test-user");
        Assert.assertTrue(start.getBoolean("sessionIdentity"));
        Assert.assertTrue(start.getString("startedAt").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"),
                "startedAt is local time yyyy-MM-dd'T'HH:mm:ss but was " + start.getString("startedAt"));
        Assert.assertTrue(start.has("utcOffsetMinutes"));
        Assert.assertEquals(start.getString("botUri"), BOT_URI);
        Assert.assertEquals(start.getString("parentBotUri"), PARENT_BOT_URI,
                "the master Task Bot travels next to the bot URI");

        List<HeliosStubServer.Request> entries = callsEndingWith("/entries");
        Assert.assertEquals(entries.size(), 1, "three entries share one request");
        HeliosStubServer.Request batch = entries.get(0);
        Assert.assertEquals(batch.path, "/api/ingest/sessions/" + SESSION_ID + "/entries");
        Assert.assertEquals(key(batch), KEY);
        Assert.assertEquals(batch.json().getInt("firstOrdinal"), 0);
        JSONArray array = batch.json().getJSONArray("entries");
        Assert.assertEquals(array.length(), 3);
        for (int index = 0; index < array.length(); index++) {
            JSONObject entry = array.getJSONObject(index);
            Assert.assertEquals(entry.length(), 9, "Machine and User come from the session start");
            Assert.assertEquals(entry.getString("task"), "My Task");
        }
        Assert.assertEquals(array.getJSONObject(0).getString("level"), "INFO");
        Assert.assertEquals(array.getJSONObject(2).getString("level"), "ERROR");

        List<HeliosStubServer.Request> ends = callsEndingWith("/end");
        Assert.assertEquals(ends.size(), 1, "one end call");
        HeliosStubServer.Request endCall = ends.get(0);
        JSONObject end = endCall.json();
        Assert.assertEquals(endCall.path, "/api/ingest/sessions/" + SESSION_ID + "/end");
        Assert.assertEquals(key(endCall), KEY, "ingest key header on end");
        Assert.assertEquals(end.getString("status"), "ERROR", "worst level seen wins");
        Assert.assertTrue(end.getString("endedAt").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"));
        Assert.assertTrue(end.has("utcOffsetMinutes"));

        Assert.assertEquals(LogEntries.count(logFile), 3, "HTML log still holds every row");
        Assert.assertTrue(server.requests.stream().allMatch(call -> "application/json".equals(call.headers.getFirst("Content-Type"))),
                "start, entries and end keep the Helios JSON content type");
    }

    @Test
    public void mutableVariablesAreSnapshottedBeforeDelivery() throws Exception {
        String baseUrl = startStubServer();
        Path logFile = newLogFile();
        CustomLogger session = new CustomLogger("Snapshot_" + UUID.randomUUID(), logFile.toString(),
                1000, 0, new HashSet<>(), EncodingMode.FAST, config(baseUrl));
        StringValue value = new StringValue("before\n<&> $ \\ ñ");
        Map<String, Value> variables = new HashMap<>();
        variables.put("value", value);
        Map<String, Object> row = row("snapshot");
        row.put(CustomHTMLLayout.Columns.VARIABLES, variables);
        try {
            session.getLogger().info(row);
            value.set("after");
            session.getLogger().warn(row);
            variables.clear();
        } finally {
            session.close();
        }
        Map<Integer, JSONObject> stored = server.rows(SESSION_ID);
        Assert.assertEquals(stored.size(), 2);
        Assert.assertEquals(stored.get(0).getJSONArray("variables").getJSONObject(0).getString("value"),
                "before\n<&> $ \\ ñ");
        Assert.assertEquals(stored.get(1).getJSONArray("variables").getJSONObject(0).getString("value"), "after");
        JSONObject end = callsEndingWith("/end").get(0).json();
        Assert.assertEquals(end.getInt("expectedEntries"), 2);
        Assert.assertEquals(end.getString("status"), "WARN");
        Assert.assertEquals(LogEntries.count(logFile), 2);
    }

    @Test
    public void parentBotUriIsSentEmptyWhenTheLoggerRunsInTheMaster() throws Exception {
        String baseUrl = startStubServer();
        Path logFile = newLogFile();

        HeliosConfig noParent = new HeliosConfig(baseUrl, KEY, "execution-1", BOT_URI, null,
                "child-file", "test-machine", "test-user", null);
        CustomLogger session = new CustomLogger("CustomLogger_" + UUID.randomUUID(), logFile.toString(),
                1000, 0, new HashSet<>(), EncodingMode.FAST, noParent);
        session.getLogger().info(row("only info"));
        session.close();

        JSONObject start = callsEndingWith("/api/ingest/sessions").get(0).json();
        Assert.assertEquals(start.getString("parentBotUri"), "", "unknown master is sent as an empty string");
        Assert.assertEquals(start.getString("fileId"), "child-file", "the bot's own fileId is used instead");
    }

    @Test
    public void unreachableServerNeverThrowsAndKeepsTheHtmlLog() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Path logFile = newLogFile();

        long startedAt = System.currentTimeMillis();
        CustomLogger session = new CustomLogger("CustomLogger_" + UUID.randomUUID(), logFile.toString(),
                1000, 0, new HashSet<>(), EncodingMode.FAST,
                config("http://127.0.0.1:" + closedPort));
        logThreeRows(session);
        session.close();
        long elapsed = System.currentTimeMillis() - startedAt;

        Assert.assertTrue(elapsed < 20_000, "must not hang on a dead server, took " + elapsed + " ms");
        // Bot rows, one delay warning and the final pending warning all stay in HTML.
        Assert.assertEquals(LogEntries.count(logFile), 5, "bot rows, delay and pending warnings stay in HTML");
        String html = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
        Assert.assertTrue(html.contains("Helios delivery was delayed"),
                "one warning row explains why nothing was streamed");
    }

    @Test
    public void perLevelFilesAlsoStream() throws Exception {
        String baseUrl = startStubServer();
        Path dir = newLogFile().getParent();
        Map<org.apache.logging.log4j.Level, String> paths = new HashMap<>();
        paths.put(org.apache.logging.log4j.Level.INFO, dir.resolve("info.html").toString());
        paths.put(org.apache.logging.log4j.Level.WARN, dir.resolve("warn.html").toString());
        paths.put(org.apache.logging.log4j.Level.ERROR, dir.resolve("error.html").toString());

        CustomLogger session = new CustomLogger("CustomLogger_" + UUID.randomUUID(), paths,
                1000, 0, new HashSet<>(), EncodingMode.FAST, config(baseUrl));
        logThreeRows(session);
        session.close();

        Assert.assertEquals(callsEndingWith("/entries").get(0).json().getJSONArray("entries").length(), 3, "every level reaches Helios");
        Assert.assertEquals(LogEntries.count(Paths.get(paths.get(org.apache.logging.log4j.Level.INFO))), 1);
        Assert.assertEquals(LogEntries.count(Paths.get(paths.get(org.apache.logging.log4j.Level.ERROR))), 1);
    }

    @Test
    public void overloadRetriesTheSameBatchAndClosingRepairsAMiddleGap() throws Exception {
        String baseUrl = startStubServer();
        server.replyOnce("/entries", 503, "", "Retry-After", "1");
        server.omitOnce.set(9);
        Path logFile = newLogFile();
        CustomLogger session = new CustomLogger("CustomLogger_" + UUID.randomUUID(), logFile.toString(),
                1000, 0, new HashSet<>(), EncodingMode.FAST, config(baseUrl));
        for (int index = 0; index < 20; index++) session.getLogger().info(row("row " + index));
        session.close();
        Map<Integer, JSONObject> stored = server.rows(SESSION_ID);
        Assert.assertEquals(stored.size(), 20);
        Assert.assertEquals(stored.get(9).getString("message"), "row 9");
        List<HeliosStubServer.Request> batches = callsEndingWith("/entries");
        Assert.assertEquals(batches.size(), 3, "rejection, partial storage, then gap repair");
        Assert.assertEquals(batches.get(0).json().toString(), batches.get(2).json().toString(),
                "retry preserves every ordinal, value and timestamp");
        Assert.assertEquals(callsEndingWith("/end").size(), 2);
        Assert.assertEquals(LogEntries.count(logFile), 21, "one local retry warning stays out of the cloud rows");
        Assert.assertTrue(Files.readString(logFile).contains("Helios delivery was delayed"));
        Assert.assertTrue(callsEndingWith("/end").stream().allMatch(call -> call.json().getInt("expectedEntries") == 20
                && "OK".equals(call.json().getString("status"))), "local warnings do not change the cloud outcome");
        Assert.assertEquals(stored.keySet(), java.util.stream.IntStream.range(0, 20).boxed().collect(Collectors.toSet()));
        try (java.util.stream.Stream<Path> paths = Files.walk(logFile.getParent())) {
            Assert.assertFalse(paths.anyMatch(path -> path.toString().endsWith(".mp4")), "retry warnings do not record video");
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(outbox)) {
            Assert.assertFalse(paths.anyMatch(path -> path.toString().endsWith(".helios-journal")),
                    "delete only after the verified close");
        }
    }

    @Test
    public void localWarningsKeepTheRetryWarningAndNeverConsumeCloudOrdinals() throws Exception {
        String baseUrl = startStubServer();
        server.replyOnce("/entries", 503, "", "Retry-After", "1");
        Path logFile = newLogFile();
        CustomLogger session = new CustomLogger("LocalWarnings_" + UUID.randomUUID(), logFile.toString(),
                1000, 0, new HashSet<>(), EncodingMode.FAST, config(baseUrl));
        HeliosAppender.writeLocalWarning(session.getLogger(), "local owner warning");
        HeliosAppender.writeLocalWarning(session.getLogger(), "another local owner warning");
        session.getLogger().info(row("one cloud row"));
        session.close();
        Assert.assertEquals(LogEntries.count(logFile), 4, "two explicit local warnings plus one retry warning");
        Assert.assertTrue(Files.readString(logFile).contains("Helios delivery was delayed"));
        Map<Integer, JSONObject> stored = server.rows(SESSION_ID);
        Assert.assertEquals(stored.size(), 1);
        Assert.assertEquals(stored.get(0).getString("message"), "one cloud row");
        JSONObject end = callsEndingWith("/end").get(0).json();
        Assert.assertEquals(end.getInt("expectedEntries"), 1);
        Assert.assertEquals(end.getString("status"), "OK");
    }

    @Test
    public void fullBufferPreservesHtmlAndCannotReportCompleteDelivery() throws Exception {
        System.setProperty("helios.bufferMiB", "1");
        String baseUrl = startStubServer();
        Path logFile = newLogFile();
        CustomLogger session = new CustomLogger("CustomLogger_" + UUID.randomUUID(), logFile.toString(),
                1000, 0, new HashSet<>(), EncodingMode.FAST, config(baseUrl));
        session.getLogger().info(row("x".repeat(1024 * 1024)));
        session.getLogger().info(row("still running"));
        session.close();
        Assert.assertEquals(LogEntries.count(logFile), 6, "bot entries, disk failure, delay, incomplete and pending warnings stay in HTML");
        Assert.assertTrue(Files.readString(logFile).contains("Cloud logs are incomplete"));
        Map<Integer, JSONObject> stored = server.rows(SESSION_ID);
        Assert.assertEquals(stored.size(), 1);
        Assert.assertEquals(stored.get(1).getString("message"), "still running");
        Assert.assertTrue(callsEndingWith("/end").stream().allMatch(call -> call.json().getInt("expectedEntries") == 2));
        try (java.util.stream.Stream<Path> files = Files.walk(outbox)) {
            Assert.assertTrue(files.anyMatch(path -> path.toString().endsWith(".helios-journal")),
                    "unverified records remain on disk");
        }
    }

    @Test
    public void streamingStaysOffWhenNoConfigIsGiven() throws Exception {
        String baseUrl = startStubServer();
        Path logFile = newLogFile();

        CustomLogger session = new CustomLogger("CustomLogger_" + UUID.randomUUID(), logFile.toString(), 1000);
        logThreeRows(session);
        session.close();

        Assert.assertTrue(server.requests.isEmpty(), "nothing is sent when streaming is disabled");
        Assert.assertEquals(LogEntries.count(logFile), 3);
        Assert.assertTrue(baseUrl.startsWith("http://"));
    }
}
