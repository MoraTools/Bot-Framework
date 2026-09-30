package logs;

import com.automationanywhere.botcommand.actions.logs.LogMessage;
import com.automationanywhere.botcommand.actions.logs.StartLoggerSession;
import com.automationanywhere.botcommand.utilities.helios.CloudMediaMode;
import com.automationanywhere.botcommand.utilities.helios.HeliosConfig;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode;
import com.automationanywhere.botcommand.utilities.screen.recorder.ScreenRecorder;
import com.automationanywhere.core.security.SecureString;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.Level;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class InheritedErrorMediaTest {
    @Test public void actualLoggerAndActionMatrixUsesSettingsEvenWhenNativeStartupFails() throws Exception {
        Path root = Files.createTempDirectory("inherited-error-media");
        Path blocked = root.resolve("blocked-native-root");
        Files.writeString(blocked, "native capture must not start in this test");
        Set<String> classpath = new java.util.LinkedHashSet<>();
        for (Class<?> type : new Class<?>[] { getClass(), CustomLogger.class, JSONObject.class, Assert.class,
                org.apache.logging.log4j.Logger.class, org.apache.logging.log4j.core.LoggerContext.class,
                org.apache.commons.io.FilenameUtils.class, org.apache.commons.lang3.StringUtils.class,
                SecureString.class, com.automationanywhere.botcommand.data.impl.SessionValue.class,
                com.automationanywhere.toolchain.runtime.session.CloseableSessionObject.class, com.google.protobuf.Message.class })
            classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        classpath.add(Path.of("build/resources/main").toAbsolutePath().toString());
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + blocked, "-Djava.io.tmpdir=" + blocked, "-Djava.awt.headless=true",
                "-Dhelios.outbox.directory=" + root.resolve("outbox"), "-cp", String.join(java.io.File.pathSeparator, classpath),
                getClass().getName(), root.toString());
        builder.environment().remove("LOCALAPPDATA");
        builder.redirectErrorStream(true).redirectOutput(root.resolve("matrix.log").toFile());
        Process child = builder.start();
        if (!child.waitFor(60, TimeUnit.SECONDS)) { child.destroyForcibly(); Assert.fail("settings matrix did not complete"); }
        Assert.assertEquals(child.exitValue(), 0, Files.readString(root.resolve("matrix.log")));
        Assert.assertTrue(Files.readString(root.resolve("matrix.log")).contains("inherited_matrix_passed"));
    }

    @Test public void generatedCommandHasOnlyExistingControlsAndKeepsDefaults() throws Exception {
        JSONArray commands = new JSONArray(Files.readString(Path.of("build/generated/sources/annotationProcessor/java/main/tmp/commands.json")));
        Assert.assertEquals(commands.length(), 14);
        JSONObject start = null;
        int starts = 0;
        for (int index = 0; index < commands.length(); index++) {
            if ("logs_start_session".equals(commands.getJSONObject(index).getString("name"))) {
                start = commands.getJSONObject(index);
                starts++;
            }
        }
        Assert.assertNotNull(start);
        Assert.assertEquals(starts, 1);
        Map<String, JSONObject> attributes = new HashMap<>();
        collect(start.getJSONArray("attributes"), attributes);
        Assert.assertFalse(attributes.containsKey("heliosErrorMedia"));
        Assert.assertFalse(attributes.containsKey("ignoredHeliosErrorMedia"));
        Assert.assertEquals(attributes.get("captureScreenRecording").getJSONObject("defaultValue").getString("string"), "NO_VIDEO");
        Assert.assertEquals(attributes.get("heliosStreaming").getJSONObject("defaultValue").getString("string"), "HELIOS_DISABLED");
        Assert.assertFalse(attributes.get("videoOnInfo").getJSONObject("defaultValue").getBoolean("boolean"));
        Assert.assertFalse(attributes.get("videoOnWarn").getJSONObject("defaultValue").getBoolean("boolean"));
        Assert.assertTrue(attributes.get("videoOnError").getJSONObject("defaultValue").getBoolean("boolean"));
        Assert.assertEquals(attributes.get("videoBufferSeconds").getJSONObject("defaultValue").getString("number"), "30");
        Assert.assertEquals(attributes.get("encodingMode").getJSONObject("defaultValue").getString("string"), "FAST");
        Assert.assertEquals(attributes.get("encodingMode").getJSONArray("options").length(), 2);
        JSONArray rules = attributes.get("videoBufferSeconds").getJSONArray("rules");
        Assert.assertTrue(rules.toString().contains("GREATER_THAN_EQUALS"));
        Assert.assertTrue(rules.toString().contains("LESS_THAN_EQUALS"));
        Assert.assertTrue(rules.toString().contains("\"5\""));
        Assert.assertTrue(rules.toString().contains("\"90\""));
        String source = Files.readString(Path.of("src/main/java/com/automationanywhere/botcommand/actions/logs/StartLoggerSession.java"));
        Assert.assertEquals(source.split("@Execute", -1).length - 1, 1);
        Assert.assertTrue(java.util.Arrays.stream(StartLoggerSession.class.getMethods())
                .anyMatch(method -> method.getName().equals("start") && method.getParameterCount() == 15));
        Assert.assertTrue(java.util.Arrays.stream(StartLoggerSession.class.getMethods())
                .anyMatch(method -> method.getName().equals("start") && method.getParameterCount() == 16));
        String generated = Files.readString(Path.of("build/generated/sources/annotationProcessor/java/main/com/automationanywhere/botcommand/actions/logs/StartLoggerSessionCommand.java"));
        Assert.assertFalse(generated.contains("heliosErrorMedia"));
        Assert.assertFalse(generated.contains("ignoredHeliosErrorMedia"));
    }

    private static void collect(JSONArray items, Map<String, JSONObject> output) {
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            output.put(item.getString("name"), item);
            if (item.has("attributes")) collect(item.getJSONArray("attributes"), output);
            if (item.has("options")) for (int option = 0; option < item.getJSONArray("options").length(); option++) {
                JSONObject choice = item.getJSONArray("options").getJSONObject(option);
                if (choice.has("attributes")) collect(choice.getJSONArray("attributes"), output);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        try (Server server = new Server()) {
            for (boolean split : new boolean[] { false, true }) {
                for (int flags = 0; flags < 8; flags++) {
                    Set<Level> levels = new HashSet<>();
                    if ((flags & 1) != 0) levels.add(Level.INFO);
                    if ((flags & 2) != 0) levels.add(Level.WARN);
                    if ((flags & 4) != 0) levels.add(Level.ERROR);
                    for (int buffer : new int[] { 0, 5, 90 }) {
                        check(root, split, buffer, levels, flags % 2 == 0 ? EncodingMode.FAST : EncodingMode.COMPACT,
                                server.config(), buffer > 0 && levels.contains(Level.ERROR) ? CloudMediaMode.VIDEO : CloudMediaMode.SCREENSHOT);
                    }
                    check(root, split, 90, levels, EncodingMode.COMPACT, null, CloudMediaMode.OFF);
                }
                for (CloudMediaMode old : new CloudMediaMode[] { null, CloudMediaMode.OFF, CloudMediaMode.SCREENSHOT, CloudMediaMode.VIDEO }) {
                    HeliosConfig legacy = new HeliosConfig(server.url(), "test-key", "matrix-aa", "bot", "master", "file", "machine", "user", null, old);
                    check(root, split, 5, Set.of(Level.ERROR), EncodingMode.FAST, legacy, CloudMediaMode.VIDEO);
                    check(root, split, 90, Set.of(Level.INFO, Level.WARN), EncodingMode.COMPACT, legacy, CloudMediaMode.SCREENSHOT);
                    check(root, split, 0, null, null, legacy, CloudMediaMode.SCREENSHOT);
                }
                check(root, split, -1, Set.of(Level.ERROR), null, server.config(), CloudMediaMode.SCREENSHOT);
            }
            actionCompatibility(root, server);
            logMessageEligibility(root, server);
            System.out.println("inherited_matrix_passed");
        }
    }

    private static void check(Path root, boolean split, int buffer, Set<Level> levels, EncodingMode mode,
                              HeliosConfig config, CloudMediaMode expected) throws Exception {
        CustomLogger logger = logger(root, split, buffer, levels, mode, config);
        try {
            Assert.assertEquals(logger.getCloudMediaMode(), expected);
            java.lang.reflect.Field configured = CustomLogger.class.getDeclaredField("localRecordingLevels");
            configured.setAccessible(true);
            Assert.assertEquals(configured.get(logger), levels == null ? Set.of() : levels, "no level is forced into the local recorder");
            java.lang.reflect.Field recorder = CustomLogger.class.getDeclaredField("recorder");
            recorder.setAccessible(true);
            Assert.assertSame(recorder.get(logger), ScreenRecorder.DISABLED, "native failure has no extra or cloud-only recorder fallback");
            if (expected == CloudMediaMode.VIDEO) Assert.assertFalse(logger.shouldRecordVideoFor(Level.ERROR), "requested VIDEO does not depend on recorder health");
        } finally { logger.close(); }
    }

    private static CustomLogger logger(Path root, boolean split, int buffer, Set<Level> levels, EncodingMode mode, HeliosConfig config) throws Exception {
        Path logs = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
        if (!split) return new CustomLogger("matrix", logs.resolve("log.html").toString(), 1000, buffer, levels, mode, config);
        return new CustomLogger("matrix", Map.of(Level.INFO, logs.resolve("info.html").toString(),
                Level.WARN, logs.resolve("warn.html").toString(), Level.ERROR, logs.resolve("error.html").toString()), 1000, buffer, levels, mode, config);
    }

    private static void actionCompatibility(Path root, Server server) throws Exception {
        StartLoggerSession action = new StartLoggerSession();
        SecureString key = new SecureString("test-key".toCharArray());
        CustomLogger plain = (CustomLogger) action.start("COMMON_FILE", root.resolve("plain.html").toString(), null, null, null,
                1000, "NO_VIDEO", true, true, true, 90, "COMPACT", "HELIOS_ENABLED", server.url(), key).getSession();
        try { Assert.assertEquals(plain.getCloudMediaMode(), CloudMediaMode.SCREENSHOT); } finally { plain.close(); }
        CustomLogger oldOff = (CustomLogger) action.start("COMMON_FILE", root.resolve("old-off.html").toString(), null, null, null,
                1000, "VIDEO_ENABLED", false, false, true, 5, "FAST", "HELIOS_ENABLED", server.url(), key, "OFF").getSession();
        try { Assert.assertEquals(oldOff.getCloudMediaMode(), CloudMediaMode.VIDEO); } finally { oldOff.close(); }
        CustomLogger oldVideo = (CustomLogger) action.start("COMMON_FILE", root.resolve("old-video.html").toString(), null, null, null,
                1000, "NO_VIDEO", null, null, null, null, null, "HELIOS_ENABLED", server.url(), key, "VIDEO").getSession();
        try { Assert.assertEquals(oldVideo.getCloudMediaMode(), CloudMediaMode.SCREENSHOT); } finally { oldVideo.close(); }
        CustomLogger nullBuffer = (CustomLogger) action.start("COMMON_FILE", root.resolve("null-buffer.html").toString(), null, null, null,
                1000, "VIDEO_ENABLED", true, true, true, null, null, "HELIOS_ENABLED", server.url(), key).getSession();
        try { Assert.assertEquals(nullBuffer.getCloudMediaMode(), CloudMediaMode.SCREENSHOT); } finally { nullBuffer.close(); }
    }


    private static void logMessageEligibility(Path root, Server server) throws Exception {
        LogMessage action = new LogMessage();
        action.setTestBotUri("PUBLIC:/Bots/tasks/Matrix@v1");
        CustomLogger video = logger(root, false, 5, Set.of(Level.INFO, Level.WARN, Level.ERROR), EncodingMode.FAST, server.config());
        String id = video.getLoggerId();
        try {
            action.action(video, "INFO", "local INFO flag", false, "NO", null, null);
            action.action(video, "WARN", "local WARN flag", false, "NO", null, null);
            Assert.assertEquals(action.action(video, "ERROR", "first ERROR", false, "NO", null, null).get(), "");
            action.action(video, "INFO", "recovered", false, "NO", null, null);
            action.action(video, "ERROR", "second ERROR", false, "NO", null, null);
        } finally { video.close(); }
        List<JSONObject> rows = server.rows.get(id);
        Assert.assertEquals(rows.size(), 5);
        for (JSONObject row : rows) {
            if ("ERROR".equals(row.getString("level"))) {
                JSONArray media = row.getJSONArray("media");
                Assert.assertEquals(media.length(), 2);
                Assert.assertEquals(media.getJSONObject(1).getString("kind"), "video");
                Assert.assertEquals(media.getJSONObject(1).getString("state"), "unavailable");
                Assert.assertEquals(media.getJSONObject(1).getString("reason"), "videoUnavailable");
            } else Assert.assertFalse(row.has("media"), "INFO/WARN recording flags do not make cloud media eligible");
        }
        CustomLogger screenshot = logger(root, true, 90, Set.of(Level.INFO, Level.WARN), EncodingMode.COMPACT, server.config());
        String stillId = screenshot.getLoggerId();
        try { action.action(screenshot, "ERROR", "ERROR unchecked", false, "NO", null, null); }
        finally { screenshot.close(); }
        JSONArray media = server.rows.get(stillId).get(0).getJSONArray("media");
        Assert.assertEquals(media.length(), 1);
        Assert.assertEquals(media.getJSONObject(0).getString("kind"), "screenshot");
    }

    private static final class Server implements AutoCloseable {
        final HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        final Map<String, List<JSONObject>> rows = new ConcurrentHashMap<>();
        Server() throws Exception {
            server.createContext("/api/ingest/sessions", exchange -> {
                JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String[] path = exchange.getRequestURI().getPath().split("/");
                JSONObject reply;
                if (path.length == 4) {
                    String id = body.getString("loggerSessionId");
                    rows.putIfAbsent(id, new CopyOnWriteArrayList<>());
                    reply = new JSONObject().put("sessionId", id).put("media", new JSONObject().put("version", 1)
                            .put("maxScreenshotBytes", 1048576).put("maxVideoBytes", 8388608));
                } else if (path.length == 8) {
                    reply = new JSONObject(body.toString()).put("sessionId", path[4]).put("ordinal", Integer.parseInt(path[6]))
                            .put("expiresAtUtc", Instant.now().plusSeconds(86400).toString());
                } else if ("entries".equals(path[5])) {
                    JSONArray entries = body.getJSONArray("entries");
                    for (int i = 0; i < entries.length(); i++) rows.get(path[4]).add(entries.getJSONObject(i));
                    reply = new JSONObject().put("accepted", entries.length()).put("duplicates", 0)
                            .put("nextOrdinal", body.getInt("firstOrdinal") + entries.length());
                } else reply = new JSONObject().put("complete", true).put("storedEntries", body.getInt("expectedEntries"));
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        HeliosConfig config() { return new HeliosConfig(url(), "test-key", "matrix-aa", "bot", "master", "file", "machine", "user", null); }
        @Override public void close() { server.stop(0); }
    }
}
