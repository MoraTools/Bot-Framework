package logs;

import com.automationanywhere.botcommand.actions.logs.LogMessage;
import com.automationanywhere.botcommand.actions.logs.StartLoggerSession;
import com.automationanywhere.botcommand.utilities.helios.CloudMediaMode;
import com.automationanywhere.botcommand.utilities.helios.HeliosConfig;
import com.automationanywhere.botcommand.utilities.helios.HeliosStubServer;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode;
import com.automationanywhere.core.security.SecureString;
import org.apache.logging.log4j.Level;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import support.ChildJvm;
import support.TempDirectories;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class InheritedErrorMediaTest {
    private final TempDirectories temp = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void deleteTemporaryFolders() { temp.deleteAll(); }

    @Test public void actualLoggerAndActionMatrixUsesSettingsEvenWhenNativeStartupFails() throws Exception {
        Path root = temp.create("inherited-error-media");
        Path blocked = root.resolve("blocked-native-root");
        Files.writeString(blocked, "native capture must not start in this test");
        Set<String> classpath = new java.util.LinkedHashSet<>(ChildJvm.classPath(getClass(), CustomLogger.class, JSONObject.class, Assert.class,
                org.apache.logging.log4j.Logger.class, org.apache.logging.log4j.core.LoggerContext.class,
                org.apache.commons.io.FilenameUtils.class, SecureString.class,
                com.automationanywhere.botcommand.data.impl.SessionValue.class,
                com.automationanywhere.toolchain.runtime.session.CloseableSessionObject.class, com.google.protobuf.Message.class));
        classpath.add(Path.of("build/resources/main").toAbsolutePath().toString());
        ProcessBuilder builder = new ProcessBuilder(ChildJvm.command(getClass(), classpath, List.of(
                "-Duser.home=" + blocked, "-Djava.io.tmpdir=" + blocked, "-Djava.awt.headless=true",
                "-Dhelios.outbox.directory=" + root.resolve("outbox")), root.toString()));
        builder.environment().remove("LOCALAPPDATA");
        builder.redirectErrorStream(true).redirectOutput(root.resolve("matrix.log").toFile());
        Process child = builder.start();
        if (!child.waitFor(60, TimeUnit.SECONDS)) { child.destroyForcibly(); Assert.fail("settings matrix did not complete"); }
        Assert.assertEquals(child.exitValue(), 0, Files.readString(root.resolve("matrix.log")));
        Assert.assertTrue(Files.readString(root.resolve("matrix.log")).contains("inherited_matrix_passed"));
    }

    @Test public void generatedCommandHasOnlyExistingControlsAndKeepsDefaults() throws Exception {
        JSONArray commands = new JSONArray(Files.readString(Path.of("build/generated/sources/annotationProcessor/java/main/tmp/commands.json")));
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
        Assert.assertEquals(java.util.Arrays.stream(StartLoggerSession.class.getMethods())
                .filter(method -> method.getName().equals("start")).mapToInt(java.lang.reflect.Method::getParameterCount)
                .boxed().collect(java.util.stream.Collectors.toList()), List.of(15));
        String generated = Files.readString(Path.of("build/generated/sources/annotationProcessor/java/main/com/automationanywhere/botcommand/actions/logs/StartLoggerSessionCommand.java"));
        Assert.assertFalse(generated.contains("heliosErrorMedia"));
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
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            for (boolean split : new boolean[] { false, true }) {
                for (int flags = 0; flags < 8; flags++) {
                    Set<Level> levels = new HashSet<>();
                    if ((flags & 1) != 0) levels.add(Level.INFO);
                    if ((flags & 2) != 0) levels.add(Level.WARN);
                    if ((flags & 4) != 0) levels.add(Level.ERROR);
                    for (int buffer : new int[] { 0, 5, 90 }) {
                        check(root, split, buffer, levels, flags % 2 == 0 ? EncodingMode.FAST : EncodingMode.COMPACT,
                                config(server), buffer > 0 && levels.contains(Level.ERROR) ? CloudMediaMode.VIDEO : CloudMediaMode.SCREENSHOT);
                    }
                    check(root, split, 90, levels, EncodingMode.COMPACT, null, CloudMediaMode.OFF);
                }
                check(root, split, 0, null, null, config(server), CloudMediaMode.SCREENSHOT);
                check(root, split, -1, Set.of(Level.ERROR), null, config(server), CloudMediaMode.SCREENSHOT);
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
            // Native capture cannot start in this JVM, so no recorder runs and no level can record.
            for (Level level : new Level[] { Level.INFO, Level.WARN, Level.ERROR })
                Assert.assertFalse(logger.shouldRecordVideoFor(level), "native failure has no extra or cloud-only recorder fallback for " + level);
        } finally { logger.close(); }
    }

    private static CustomLogger logger(Path root, boolean split, int buffer, Set<Level> levels, EncodingMode mode, HeliosConfig config) throws Exception {
        Path logs = Files.createDirectories(root.resolve(UUID.randomUUID().toString()));
        if (!split) return new CustomLogger("matrix", logs.resolve("log.html").toString(), 1000, buffer, levels, mode, config);
        return new CustomLogger("matrix", Map.of(Level.INFO, logs.resolve("info.html").toString(),
                Level.WARN, logs.resolve("warn.html").toString(), Level.ERROR, logs.resolve("error.html").toString()), 1000, buffer, levels, mode, config);
    }

    private static void actionCompatibility(Path root, HeliosStubServer server) throws Exception {
        StartLoggerSession action = new StartLoggerSession();
        SecureString key = new SecureString("test-key".toCharArray());
        CustomLogger plain = (CustomLogger) action.start("COMMON_FILE", root.resolve("plain.html").toString(), null, null, null,
                1000, "NO_VIDEO", true, true, true, 90, "COMPACT", "HELIOS_ENABLED", server.url(), key).getSession();
        try { Assert.assertEquals(plain.getCloudMediaMode(), CloudMediaMode.SCREENSHOT); } finally { plain.close(); }
        CustomLogger errorVideo = (CustomLogger) action.start("COMMON_FILE", root.resolve("error-video.html").toString(), null, null, null,
                1000, "VIDEO_ENABLED", false, false, true, 5, "FAST", "HELIOS_ENABLED", server.url(), key).getSession();
        try { Assert.assertEquals(errorVideo.getCloudMediaMode(), CloudMediaMode.VIDEO); } finally { errorVideo.close(); }
        CustomLogger nulls = (CustomLogger) action.start("COMMON_FILE", root.resolve("nulls.html").toString(), null, null, null,
                1000, "NO_VIDEO", null, null, null, null, null, "HELIOS_ENABLED", server.url(), key).getSession();
        try { Assert.assertEquals(nulls.getCloudMediaMode(), CloudMediaMode.SCREENSHOT); } finally { nulls.close(); }
        CustomLogger nullBuffer = (CustomLogger) action.start("COMMON_FILE", root.resolve("null-buffer.html").toString(), null, null, null,
                1000, "VIDEO_ENABLED", true, true, true, null, null, "HELIOS_ENABLED", server.url(), key).getSession();
        try { Assert.assertEquals(nullBuffer.getCloudMediaMode(), CloudMediaMode.SCREENSHOT); } finally { nullBuffer.close(); }
    }


    private static void logMessageEligibility(Path root, HeliosStubServer server) throws Exception {
        LogMessage action = new LogMessage();
        action.setTestBotUri("PUBLIC:/Bots/tasks/Matrix@v1");
        CustomLogger video = logger(root, false, 5, Set.of(Level.INFO, Level.WARN, Level.ERROR), EncodingMode.FAST, config(server));
        String id = video.getLoggerId();
        try {
            action.action(video, "INFO", "local INFO flag", false, "NO", null);
            action.action(video, "WARN", "local WARN flag", false, "NO", null);
            Assert.assertEquals(action.action(video, "ERROR", "first ERROR", false, "NO", null).get(), "");
            action.action(video, "INFO", "recovered", false, "NO", null);
            action.action(video, "ERROR", "second ERROR", false, "NO", null);
        } finally { video.close(); }
        List<JSONObject> rows = new ArrayList<>(server.rows(id).values());
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
        CustomLogger screenshot = logger(root, true, 90, Set.of(Level.INFO, Level.WARN), EncodingMode.COMPACT, config(server));
        String stillId = screenshot.getLoggerId();
        try { action.action(screenshot, "ERROR", "ERROR unchecked", false, "NO", null); }
        finally { screenshot.close(); }
        JSONArray media = server.rows(stillId).get(0).getJSONArray("media");
        Assert.assertEquals(media.length(), 1);
        Assert.assertEquals(media.getJSONObject(0).getString("kind"), "screenshot");
    }

    private static HeliosConfig config(HeliosStubServer server) {
        return new HeliosConfig(server.url(), "test-key", "matrix-aa", "bot", "master", "file", "machine", "user", null);
    }
}
