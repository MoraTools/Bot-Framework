package logs;

import com.automationanywhere.botcommand.utilities.helios.HeliosConfig;
import com.automationanywhere.botcommand.utilities.helios.HeliosSink;
import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Exercises the whole streaming path against a local {@link HttpServer} stub: session start,
 * entry envelopes, session end, and the behaviour when the server is not there at all.
 *
 * @author jamir-boop
 */
public class HeliosSinkTest {

    private static final String SESSION_ID = "11111111-2222-3333-4444-555555555555";
    private static final String KEY = "hik_test_secret";
    /** Bot that opens the logger session; its own fileId must lose to the master's. */
    private static final String BOT_URI =
            "file:///c/Automation Anywhere/tasks/My Task?workspace=PUBLIC&fileId=child-file";
    /** Master Task Bot that started the run; Helios Cloud identifies the bot by this one. */
    private static final String PARENT_BOT_URI =
            "file:///c/Automation Anywhere/tasks/Master Task?workspace=PUBLIC&fileId=abc";

    private HttpServer server;
    private Path outbox;
    private volatile boolean rejectOnce;
    private volatile boolean omitMiddleOnce;
    private final Map<Integer, JSONObject> stored = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeMethod
    public void isolateOutbox() throws IOException {
        outbox = Files.createTempDirectory("helios-sender-test");
        System.setProperty("helios.outbox.directory", outbox.toString());
    }


    /** One recorded request: path, ingest-key header and parsed JSON body. */
    private static final class Call {
        final String path;
        final String key;
        final JSONObject body;

        Call(String path, String key, String body) {
            this.path = path;
            this.key = key;
            this.body = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        }
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();

    @AfterMethod(alwaysRun = true)
    public void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        calls.clear();
        stored.clear();
        rejectOnce = false;
        omitMiddleOnce = false;
        System.clearProperty("helios.outbox.directory");
        System.clearProperty("helios.bufferMiB");
    }

    private String startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ingest/sessions", this::handle);
        server.setExecutor(null);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = IOUtils.toString(in, StandardCharsets.UTF_8);
        }
        String path = exchange.getRequestURI().getPath();
        calls.add(new Call(path, exchange.getRequestHeaders().getFirst(HeliosSink.KEY_HEADER), body));

        JSONObject request = new JSONObject(body);
        int status = 200;
        String response;
        if (rejectOnce && path.endsWith("/entries")) {
            rejectOnce = false;
            exchange.getResponseHeaders().add("Retry-After", "1");
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        if (path.endsWith("/end")) {
            int expected = request.getInt("expectedEntries");
            if (stored.size() != expected) {
                status = 409;
                response = new JSONObject().put("code", "missingEntries").put("storedEntries", stored.size()).toString();
            } else {
                response = new JSONObject().put("complete", true).put("storedEntries", expected).toString();
            }
        } else if (path.endsWith("/entries")) {
            int first = request.getInt("firstOrdinal");
            JSONArray entries = request.getJSONArray("entries");
            int duplicates = 0;
            for (int i = 0; i < entries.length(); i++) {
                if (omitMiddleOnce && first + i == 9) continue;
                if (stored.putIfAbsent(first + i, entries.getJSONObject(i)) != null) duplicates++;
            }
            omitMiddleOnce = false;
            response = new JSONObject().put("accepted", entries.length() - duplicates)
                    .put("duplicates", duplicates).put("nextOrdinal", first + entries.length()).toString();
        } else {
            response = new JSONObject().put("sessionId", SESSION_ID).put("executionId", "exec").toString();
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static HeliosConfig config(String baseUrl) {
        // fileId is the master's, the way StartLoggerSession resolves it.
        return new HeliosConfig(baseUrl + "/", KEY, "execution-1", BOT_URI, PARENT_BOT_URI,
                "abc", "test-machine", "test-user", null);
    }

    private static Path newLogFile() throws IOException {
        Path dir = Paths.get("src/test/target/test-artifacts/helios-" + System.nanoTime());
        Files.createDirectories(dir);
        return dir.resolve("log.html");
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

    /** Counts data rows only; the template's own header row carries no level class. */
    private static int countRows(Path logFile) throws IOException {
        String html = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
        return html.split("class='level-", -1).length - 1;
    }

    private List<Call> callsEndingWith(String suffix) {
        return calls.stream().filter(call -> call.path.endsWith(suffix)).collect(Collectors.toList());
    }

    @Test
    public void streamsStartEntriesAndEnd() throws Exception {
        String baseUrl = startStubServer();
        Path logFile = newLogFile();

        CustomLogger session = new CustomLogger("CustomLogger_" + UUID.randomUUID(), logFile.toString(),
                1000, 0, new HashSet<>(), EncodingMode.FAST, config(baseUrl));
        logThreeRows(session);
        session.close();

        List<Call> starts = callsEndingWith("/api/ingest/sessions");
        Assert.assertEquals(starts.size(), 1, "one start call");
        Call start = starts.get(0);
        Assert.assertEquals(start.key, KEY, "ingest key header");
        Assert.assertEquals(start.body.getString("executionId"), "execution-1");
        Assert.assertEquals(start.body.getString("fileId"), "abc",
                "fileId is the master bot URI's, not the child's");
        Assert.assertEquals(start.body.getString("machine"), "test-machine");
        Assert.assertEquals(start.body.getString("user"), "test-user");
        Assert.assertTrue(start.body.getString("startedAt").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"),
                "startedAt is local time yyyy-MM-dd'T'HH:mm:ss but was " + start.body.getString("startedAt"));
        Assert.assertTrue(start.body.has("utcOffsetMinutes"));
        Assert.assertEquals(start.body.getString("botUri"), BOT_URI);
        Assert.assertEquals(start.body.getString("parentBotUri"), PARENT_BOT_URI,
                "the master Task Bot travels next to the bot URI");

        List<Call> entries = callsEndingWith("/entries");
        Assert.assertEquals(entries.size(), 1, "three entries share one request");
        Call batch = entries.get(0);
        Assert.assertEquals(batch.path, "/api/ingest/sessions/" + SESSION_ID + "/entries");
        Assert.assertEquals(batch.key, KEY);
        Assert.assertEquals(batch.body.getInt("firstOrdinal"), 0);
        JSONArray array = batch.body.getJSONArray("entries");
        Assert.assertEquals(array.length(), 3);
        for (int index = 0; index < array.length(); index++) {
            JSONObject entry = array.getJSONObject(index);
            Assert.assertEquals(entry.length(), 11);
            Assert.assertEquals(entry.getString("task"), "My Task");
            Assert.assertEquals(entry.getString("machine"), CustomHTMLLayout.machineName());
        }
        Assert.assertEquals(array.getJSONObject(0).getString("level"), "INFO");
        Assert.assertEquals(array.getJSONObject(2).getString("level"), "ERROR");

        List<Call> ends = callsEndingWith("/end");
        Assert.assertEquals(ends.size(), 1, "one end call");
        Call end = ends.get(0);
        Assert.assertEquals(end.path, "/api/ingest/sessions/" + SESSION_ID + "/end");
        Assert.assertEquals(end.key, KEY, "ingest key header on end");
        Assert.assertEquals(end.body.getString("status"), "ERROR", "worst level seen wins");
        Assert.assertTrue(end.body.getString("endedAt").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"));
        Assert.assertTrue(end.body.has("utcOffsetMinutes"));

        Assert.assertEquals(countRows(logFile), 3, "HTML log still holds every row");
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

        JSONObject start = callsEndingWith("/api/ingest/sessions").get(0).body;
        Assert.assertEquals(start.getString("parentBotUri"), "", "unknown master is sent as an empty string");
        Assert.assertEquals(start.getString("fileId"), "child-file", "the bot's own fileId is used instead");
    }

    @Test
    public void statusIsOkWhenNothingWorseThanInfoWasLogged() throws Exception {
        String baseUrl = startStubServer();
        Path logFile = newLogFile();

        CustomLogger session = new CustomLogger("CustomLogger_" + UUID.randomUUID(), logFile.toString(),
                1000, 0, new HashSet<>(), EncodingMode.FAST, config(baseUrl));
        session.getLogger().info(row("only info"));
        session.close();

        Assert.assertEquals(callsEndingWith("/end").get(0).body.getString("status"), "OK");
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
        // Three logged rows plus the single WARN row explaining that streaming is off.
        Assert.assertEquals(countRows(logFile), 4, "HTML log still holds every row");
        String html = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
        Assert.assertTrue(html.contains("Helios delivery is pending"),
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

        Assert.assertEquals(callsEndingWith("/entries").get(0).body.getJSONArray("entries").length(), 3, "every level reaches Helios");
        Assert.assertEquals(countRows(Paths.get(paths.get(org.apache.logging.log4j.Level.INFO))), 1);
        Assert.assertEquals(countRows(Paths.get(paths.get(org.apache.logging.log4j.Level.ERROR))), 1);
    }

    @Test
    public void overloadRetriesTheSameBatchAndClosingRepairsAMiddleGap() throws Exception {
        String baseUrl = startStubServer();
        rejectOnce = true;
        omitMiddleOnce = true;
        Path logFile = newLogFile();
        CustomLogger session = new CustomLogger("CustomLogger_" + UUID.randomUUID(), logFile.toString(),
                1000, 0, new HashSet<>(), EncodingMode.FAST, config(baseUrl));
        for (int index = 0; index < 20; index++) session.getLogger().info(row("row " + index));
        session.close();
        Assert.assertEquals(stored.size(), 20);
        Assert.assertEquals(stored.get(9).getString("message"), "row 9");
        List<Call> batches = callsEndingWith("/entries");
        Assert.assertEquals(batches.size(), 3, "rejection, partial storage, then gap repair");
        Assert.assertEquals(batches.get(0).body.toString(), batches.get(2).body.toString(),
                "retry preserves every ordinal, value and timestamp");
        Assert.assertEquals(callsEndingWith("/end").size(), 2);
        try (java.util.stream.Stream<Path> paths = Files.walk(outbox)) {
            Assert.assertFalse(paths.anyMatch(path -> path.toString().endsWith(".helios-journal")),
                    "delete only after the verified close");
        }
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
        Assert.assertEquals(countRows(logFile), 3, "both bot entries and the disk warning stay in HTML");
        Assert.assertTrue(Files.readString(logFile).contains("Cloud logs are incomplete"));
        Assert.assertEquals(stored.size(), 1);
        Assert.assertEquals(stored.get(1).getString("message"), "still running");
        Assert.assertTrue(callsEndingWith("/end").stream().allMatch(call -> call.body.getInt("expectedEntries") == 2));
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

        Assert.assertEquals(calls, Collections.emptyList(), "nothing is sent when streaming is disabled");
        Assert.assertEquals(countRows(logFile), 3);
        Assert.assertTrue(baseUrl.startsWith("http://"));
    }
}
