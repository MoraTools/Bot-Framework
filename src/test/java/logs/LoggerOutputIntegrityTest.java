package logs;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.*;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import org.apache.logging.log4j.Level;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import support.ChildJvm;
import support.TempDirectories;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoggerOutputIntegrityTest {
    private final TempDirectories directories = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void deleteDirectories() {
        directories.deleteAll();
    }

    private static Map<String, Object> entry(String message, Map<String, Value> values) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(CustomHTMLLayout.Columns.MESSAGE, message);
        row.put(CustomHTMLLayout.Columns.VARIABLES, values);
        return row;
    }

    @Test
    public void unsetScalarValuesKeepTheErrorRowAndRenderNullInEveryContainer() throws Exception {
        DateTimeValue unset = new DateTimeValue();
        Assert.assertNull(unset.get());
        ListValue list = new ListValue();
        list.set(List.of(unset));
        DictionaryValue dictionary = new DictionaryValue(Map.of("unset", unset));
        List<Schema> schema = List.of(new Schema("unset"));
        RecordValue record = new RecordValue(schema, List.of(unset));
        TableValue table = new TableValue();
        table.set(new Table(schema, List.of(new Row(unset))));
        Map<String, Value> values = new LinkedHashMap<>();
        values.put("unset", unset);
        values.put("list", list);
        values.put("dictionary", dictionary);
        values.put("record", record);
        values.put("table", table);
        values.put("file", new FileValue("C:\\data\\report<&>.csv"));
        Path log = directories.create("logger-null-scalar").resolve("log.html");
        CustomLogger logger = new CustomLogger("null-scalar", log.toString(), 100);
        try { logger.getLogger().error(entry("retained ERROR row", values)); }
        finally { logger.close(); }
        String html = CompressedSnapshots.expand(Files.readString(log));
        Assert.assertTrue(html.contains("retained ERROR row"));
        Assert.assertEquals(html.split("<div class='variable-value'>NULL</div>", -1).length - 1, 3);
        Assert.assertEquals(html.split("<td>NULL</td>", -1).length - 1, 2);
        Assert.assertTrue(html.contains("<div class='variable-type'>DATETIME</div>"));
        Assert.assertTrue(html.contains("<div class='variable-type'>FILE</div>"));
        Assert.assertTrue(html.contains("C:\\data\\report&lt;&amp;&gt;.csv"));
        Assert.assertTrue(html.endsWith("<!-- a360-log-entry:end -->\n"));
    }

    @Test
    public void localeIndependentTypesAndEnglishMonthsWorkInFreshJvms() throws Exception {
        Path output = directories.create("logger-locale").resolve("unused.html");
        for (String locale : List.of("tr-TR", "de-DE", "fr-FR")) runChild(output, "locale", locale);
    }

    @Test
    public void separateLevelFoldersLinkToOneRecorderFolderAndTheirOwnScreenshots() throws Exception {
        Path root = directories.create("logger-media-folders");
        Map<Level, String> outputs = Map.of(Level.INFO, root.resolve("info/log.html").toString(),
                Level.WARN, root.resolve("warn/log.html").toString(),
                Level.ERROR, root.resolve("error/log.html").toString());
        Path video = root.resolve("info/clips/clip ' & # 日本語.mp4");
        Path poster = root.resolve("info/screenshots/poster ' & # 日本語.png");
        Files.createDirectories(video.getParent());
        Files.createDirectories(poster.getParent());
        Files.writeString(video, "clip");
        Files.writeString(poster, "poster");
        Path screenshot = root.resolve("error/screenshots/error.png");
        CustomLogger logger = new CustomLogger("split-media", outputs, 100);
        try {
            Files.writeString(screenshot, "screenshot");
            Map<String, Object> row = entry("one recorded event", null);
            row.put(CustomHTMLLayout.Columns.VIDEO, video.toString());
            row.put(CustomHTMLLayout.Columns.VIDEO_POSTER, poster.toString());
            logger.getLogger().warn(row);
            row = entry("manual screenshot", null);
            row.put(CustomHTMLLayout.Columns.SCREENSHOT, screenshot.toString());
            logger.getLogger().error(row);
        } finally { logger.close(); }
        String warn = Files.readString(Path.of(outputs.get(Level.WARN)));
        Matcher href = Pattern.compile("<a href='([^']+)' target='_blank' class='img-link video-link'").matcher(warn);
        Assert.assertTrue(href.find());
        String link = org.apache.commons.text.StringEscapeUtils.unescapeHtml4(href.group(1));
        Assert.assertEquals(Path.of(outputs.get(Level.WARN)).getParent().resolve(new URI(link).getPath()).normalize(), video);
        Assert.assertTrue(link.startsWith("../info/clips/"));
        Assert.assertTrue(link.contains("%27") && link.contains("%23"));
        Assert.assertTrue(warn.contains("../info/screenshots/"));
        Assert.assertTrue(Files.readString(Path.of(outputs.get(Level.ERROR))).contains("href='screenshots/error.png'"));
        Assert.assertFalse(Files.exists(root.resolve("warn/clips")));
        Assert.assertFalse(Files.exists(root.resolve("error/clips")));
    }

    @Test
    public void windowsDifferentDriveLinksUseFileUris() throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            throw new org.testng.SkipException("Different drive roots require Windows");
        }
        String html = HTMLGenerator.getScreenCellHTML("D:\\media\\still ' #.png",
                "D:\\media\\clip ' #.mp4", "D:\\media\\poster ' #.png", Path.of("C:\\logs"));
        Assert.assertTrue(html.contains("href='file:///D:/media/clip%20%27%20%23.mp4'"));
        Assert.assertTrue(html.contains("src='file:///D:/media/poster%20%27%20%23.png'"));
    }

    @Test
    public void independentProcessCannotAppendOrMigrateAnOwnedFileAndOwnerReportsIt() throws Exception {
        Path log = directories.create("logger-process-owner").resolve("log.html");
        CustomLogger owner = new CustomLogger("owner", log.toString(), 100);
        try {
            owner.getLogger().info(entry("before contention", null));
            byte[] before = Files.readAllBytes(log);
            runChild(log, "reject", "");
            Path warn = log.resolveSibling("warn.html");
            Path error = log.resolveSibling("error.html");
            byte[] legacy = "legacy log bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(warn, legacy);
            Files.write(error, legacy);
            runChild(log, "reject-split", "");
            Assert.assertEquals(Files.readAllBytes(warn), legacy);
            Assert.assertEquals(Files.readAllBytes(error), legacy);
            Assert.assertFalse(Files.exists(log.resolveSibling("1_warn.html")));
            Assert.assertFalse(Files.exists(log.resolveSibling("1_error.html")));
            Assert.assertEquals(Files.readAllBytes(log), before);
            Assert.assertFalse(Files.exists(log.resolveSibling("1_log.html")));
            // getLogger() looks for the notice at most once per second: ask until the owner has reported it.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.readString(log).contains("Another process was rejected")) {
                Assert.assertTrue(System.nanoTime() < deadline, "the owner never reported the rejected process");
                owner.getLogger();
                Thread.sleep(50);
            }
            owner.getLogger().info(entry("after contention", null));
            Assert.assertTrue(Files.readString(log).contains("after contention"));
        } finally { owner.close(); }
        runChild(log, "write", "");
        Assert.assertTrue(Files.readString(log).contains("child acquired writer"));
    }

    @Test
    public void ownershipLastsUntilTheLastSameJvmSessionClosesAndCloseReportsContention() throws Exception {
        Path log = directories.create("logger-shared-owner").resolve("log.html");
        CustomLogger first = new CustomLogger("first-owner", log.toString(), 100);
        CustomLogger second = new CustomLogger("second-owner", log.getParent().resolve(".").resolve("log.html").toString(), 100);
        try {
            first.getLogger().info(entry("first same JVM", null));
            second.getLogger().info(entry("second same JVM", null));
            first.close();
            runChild(log, "reject", "");
            second.close();
            second.close();
            Assert.assertTrue(Files.readString(log).contains("Another process was rejected"));
            runChild(log, "write", "");
        } finally { first.close(); second.close(); }
        Assert.assertFalse(Files.exists(log.resolveSibling("1_log.html")));
    }

    private void runChild(Path log, String mode, String locale) throws Exception {
        Set<String> classpath = new LinkedHashSet<>(ChildJvm.classPath(getClass(), CustomLogger.class, org.json.JSONObject.class, Assert.class,
                org.apache.logging.log4j.Logger.class, org.apache.logging.log4j.core.LoggerContext.class,
                org.apache.commons.io.FilenameUtils.class, com.automationanywhere.core.security.SecureString.class,
                com.automationanywhere.botcommand.data.impl.SessionValue.class,
                com.automationanywhere.toolchain.runtime.session.CloseableSessionObject.class, com.google.protobuf.Message.class));
        classpath.add(Path.of("build/resources/main").toAbsolutePath().toString());
        Path output = Files.createTempFile(log.getParent(), "child-" + mode, ".txt");
        Process child = new ProcessBuilder(ChildJvm.command(getClass(), classpath, List.of("-Djava.awt.headless=true"),
                mode, log.toString(), locale))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            Assert.fail("logger test JVM did not complete: " + mode);
        }
        Assert.assertEquals(child.exitValue(), 0, Files.readString(output));
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("locale")) {
            Locale.setDefault(Locale.forLanguageTag(args[2]));
            Assert.assertEquals(HTMLGenerator.typeLabel(new ListValue()), "LIST");
            Assert.assertEquals(HTMLGenerator.typeLabel(new DictionaryValue()), "DICTIONARY");
            Assert.assertEquals(HTMLGenerator.typeLabel(new StringValue()), "STRING");
            ListValue list = new ListValue();
            list.set(List.of(new StringValue("nested locale value")));
            String html = HTMLGenerator.generateVariablesHTML(Map.of("list", list,
                    "dictionary", new DictionaryValue(Map.of("key", new StringValue("dictionary locale value")))));
            Assert.assertTrue(html.contains("nested locale value") && html.contains("dictionary locale value"));
            long instant = 1700000000123L;
            String timestamp = CustomHTMLLayout.formatTimestamp(instant);
            Assert.assertTrue(timestamp.contains("-Nov-"), timestamp);
            Assert.assertEquals(ZonedDateTime.parse(timestamp,
                    DateTimeFormatter.ofPattern("yyyy-MMM-dd HH:mm:ss Z", Locale.ENGLISH)).toInstant(),
                    Instant.ofEpochSecond(instant / 1000));
            return;
        }
        Path log = Path.of(args[1]);
        if (args[0].startsWith("reject")) {
            try {
                CustomLogger contender = args[0].equals("reject-split")
                        ? new CustomLogger(UUID.randomUUID().toString(), Map.of(Level.INFO, log.toString(),
                                Level.WARN, log.resolveSibling("warn.html").toString(),
                                Level.ERROR, log.resolveSibling("error.html").toString()), 1)
                        : new CustomLogger(UUID.randomUUID().toString(), log.toString(), 1);
                contender.close();
                Assert.fail("independent process acquired an owned HTML file");
            } catch (IOException expected) {
                Assert.assertTrue(expected.getMessage().contains("Another process owns HTML log"), expected.toString());
            }
            return;
        }
        CustomLogger writer = new CustomLogger(UUID.randomUUID().toString(), log.toString(), 100);
        try { writer.getLogger().info(entry("child acquired writer", null)); }
        finally { writer.close(); }
    }
}
