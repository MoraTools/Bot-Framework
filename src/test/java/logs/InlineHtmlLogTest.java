package logs;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.data.impl.TableValue;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode;
import org.apache.logging.log4j.Level;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import screen.process.RecorderTestSupport;
import support.ChildJvm;
import support.LogEntries;
import support.TempDirectories;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class InlineHtmlLogTest {
    private static final String END = "<!-- a360-log-entry:end -->\n";
    private final TempDirectories directories = new TempDirectories();
    private final List<Path> besideWorkingDirectory = new CopyOnWriteArrayList<>();

    @AfterClass(alwaysRun = true)
    public void deleteDirectories() {
        directories.deleteAll();
        besideWorkingDirectory.forEach(RecorderTestSupport::deleteRecursivelyQuietly);
    }

    /** A relative alias needs a folder on the drive of the working directory; the system temp folder can be on another. */
    private Path folderBesideWorkingDirectory(String prefix) throws Exception {
        Path folder = Files.createTempDirectory(Files.createDirectories(Path.of("build/tmp").toAbsolutePath()), prefix);
        besideWorkingDirectory.add(folder);
        return folder;
    }

    /** The five characters the log escapes, written out so that the production escaper is not its own oracle. */
    static String escaped(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static Map<String, Object> entry(String text, Map<String, Value> variables) {
        Map<String, Object> row = LogEntries.entry(text, "PUBLIC:/tasks/Invoice@v1#L8");
        row.put(CustomHTMLLayout.Columns.VARIABLES, variables);
        return row;
    }

    private static void write(Path path, int limit, Map<String, Object> row) throws Exception {
        CustomLogger logger = new CustomLogger(UUID.randomUUID().toString(), path.toString(), limit);
        try {
            if (row != null) logger.getLogger().info(row);
        } finally {
            logger.close();
        }
    }

    private static void assertSessionLayout(Path path) throws Exception {
        String html = Files.readString(path);
        Assert.assertEquals(html.split("<header class='session-context'>", -1).length - 1, 1);
        Assert.assertEquals(html.split("<h1>Bot Framework Logs</h1>", -1).length - 1, 1);
        Assert.assertTrue(html.contains("<dt>Machine</dt><dd>"
                + escaped(CustomHTMLLayout.machineName()) + "</dd>"));
        Assert.assertTrue(html.contains("<dt>User</dt><dd>"
                + escaped(CustomHTMLLayout.userName()) + "</dd>"));
        Assert.assertTrue(html.indexOf("</header>") < html.indexOf("<div class='log-scroll'"));
        Assert.assertEquals(html.split("<th scope='col'>", -1).length - 1, 6);
        for (String column : List.of("Datetime", "Level", "Source", "Message", "Variables", "Screen")) {
            Assert.assertTrue(html.contains("<th scope='col'>" + column + "</th>"));
        }
        java.util.regex.Matcher rows = java.util.regex.Pattern.compile("<tr class='log-entry [^']*'>(.*?)</tr>",
                java.util.regex.Pattern.DOTALL).matcher(html);
        while (rows.find()) {
            Assert.assertEquals(rows.group(1).split("<td(?: [^>]*)?>", -1).length - 1, 6);
        }
    }

    @Test
    public void publicOverloadsKeepRoutingScreenshotPathsAndUniqueSessionIds() throws Exception {
        Set<String> sessionIds = new HashSet<>();
        for (boolean split : new boolean[] { false, true }) {
            for (int overload = 0; overload < 3; overload++) {
                Path dir = directories.create("logger-constructor-parity");
                Map<Level, String> paths = Map.of(
                        Level.INFO, dir.resolve("info/log.html").toString(),
                        Level.WARN, dir.resolve(split ? "warn/log.html" : "info/log.html").toString(),
                        Level.ERROR, dir.resolve(split ? "error/log.html" : "info/log.html").toString());
                String path = paths.get(Level.INFO);
                String name = UUID.randomUUID().toString();
                EncodingMode mode = EncodingMode.COMPACT;
                CustomLogger logger = split
                        ? overload == 0 ? new CustomLogger(name, paths, 10)
                        : overload == 1 ? new CustomLogger(name, paths, 10, 0, null, mode, null)
                        : new CustomLogger(name, paths, 10, 0, Set.of(Level.ERROR), mode, null)
                        : overload == 0 ? new CustomLogger(name, path, 10)
                        : overload == 1 ? new CustomLogger(name, path, 10, 0, null, mode, null)
                        : new CustomLogger(name, path, 10, 0, Set.of(Level.ERROR), mode, null);
                try {
                    Assert.assertTrue(sessionIds.add(UUID.fromString(logger.getLoggerId()).toString()));
                    Assert.assertFalse(logger.isClosed());
                    for (Level level : paths.keySet()) {
                        String screenshots = org.apache.commons.io.FilenameUtils.getFullPath(paths.get(level)) + "screenshots";
                        Assert.assertEquals(logger.getScreenshotFolderPath(level), screenshots);
                        Assert.assertTrue(Files.isDirectory(Path.of(screenshots)));
                        Assert.assertFalse(logger.shouldRecordVideoFor(level));
                    }
                    Assert.assertEquals(logger.getScreenshotFolderPath(Level.DEBUG), logger.getScreenshotFolderPath(Level.INFO));
                    for (Level level : List.of(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR, Level.FATAL)) {
                        logger.getLogger().log(level, entry("constructor-event-" + level, null));
                    }
                } finally {
                    logger.close();
                }
                Assert.assertTrue(logger.isClosed());
                logger.close();
                for (Level level : paths.keySet()) {
                    String html = Files.readString(Path.of(paths.get(level)));
                    for (Level event : List.of(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR, Level.FATAL)) {
                        Assert.assertEquals(html.contains("constructor-event-" + event),
                                split ? event == level : event != Level.DEBUG);
                    }
                    Assert.assertEquals(LogEntries.count(Path.of(paths.get(level))), split ? 1L : 4L);
                    assertSessionLayout(Path.of(paths.get(level)));
                }
            }
        }
    }

    @Test
    public void localSnapshotsKeepAllValuesAndExternalMediaLinks() throws Exception {
        Path dir = directories.create("inline-values");
        Path log = dir.resolve("log.html");
        Map<String, Value> values = new LinkedHashMap<>();
        String longValue = "long-value-" + "x".repeat(25000) + "-end";
        values.put("Index literal name", new StringValue(longValue));
        values.put("<script>alert(1)</script>", new StringValue("日本語 <&> \" ' </script>"));
        values.put("null", null);
        for (int i = 0; i < 105; i++) values.put("value-" + i, new StringValue("saved-" + i));
        Map<String, Object> row = entry("message </article>\n" + END, values);
        row.put(CustomHTMLLayout.Columns.VIDEO, dir.resolve("clips/movie.mp4").toString());
        row.put(CustomHTMLLayout.Columns.VIDEO_POSTER, dir.resolve("screenshots/poster.png").toString());
        write(log, 10, row);
        String expected = com.automationanywhere.botcommand.utilities.logger.HTMLGenerator.generateVariablesHTML(values);
        String raw = Files.readString(log);
        Assert.assertTrue(raw.contains("data-vars='"));
        Assert.assertFalse(raw.contains(longValue));
        Assert.assertTrue(CompressedSnapshots.expand(raw).contains(expected), "compression is lossless");
        values.clear();
        String html = CompressedSnapshots.expand(Files.readString(log));
        Assert.assertTrue(html.contains(longValue), "local values do not use the Helios truncation limit");
        Assert.assertTrue(html.contains("saved-104"), "local snapshots are not capped at 100 variables");
        Assert.assertTrue(html.contains("Index literal name"));
        Assert.assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        Assert.assertFalse(html.contains("<script>alert(1)</script>"));
        Assert.assertTrue(html.contains("日本語 &lt;&amp;&gt;"));
        Assert.assertTrue(html.contains("clips/movie.mp4"));
        Assert.assertTrue(html.contains("screenshots/poster.png"));
        Assert.assertFalse(html.contains("data:image"));
        Assert.assertFalse(Files.exists(dir.resolve("variables")));
        Assert.assertEquals(LogEntries.count(log), 1L, "escaped data cannot create an entry marker");
    }

    @Test
    public void completeFormat6LogOfThisMachineAndUserIsArchived() throws Exception {
        Path dir = directories.create("format-6");
        Path log = dir.resolve("log.html");
        write(log, 10, entry("format 6 entry", null));
        String current = Files.readString(log);
        Assert.assertTrue(current.contains("<!-- a360-log-format:7 -->\n"), "the logger writes format 7");
        // The same complete file, identity and entry as the logger wrote it, but marked format 6.
        byte[] format6 = current.replace("<!-- a360-log-format:7 -->\n", "<!-- a360-log-format:6 -->\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(log, format6);
        write(log, 10, entry("format 7 entry", null));
        Assert.assertEquals(Files.readAllBytes(dir.resolve("1_log.html")), format6);
        String html = Files.readString(log);
        Assert.assertTrue(html.startsWith("<!DOCTYPE html>\n<!-- a360-log-format:7 -->\n"), html);
        Assert.assertEquals(LogEntries.count(log), 1L);
        Assert.assertTrue(html.contains("format 7 entry") && !html.contains("format 6 entry"));
        Assert.assertFalse(Files.exists(dir.resolve("2_log.html")));
    }

    @Test
    public void messageDivHoldsOnlyTheMessageTextAndTheAttachmentLinkFollowsIt() throws Exception {
        Path dir = directories.create("message-div");
        Path log = dir.resolve("log.html");
        Path attachment = Files.writeString(dir.resolve("report.txt"), "attachment");
        String text = "first line\nsecond <line>\t ";
        CustomLogger logger = new CustomLogger("message-div", log.toString(), 10);
        try {
            logger.getLogger().info(entry(text, null));
            logger.logExternal("INFO", text, "source", null, attachment.toString());
        } finally {
            logger.close();
        }
        String html = Files.readString(log);
        String cell = "<td><div class='message'>" + HTMLGenerator.escapeWithWhitespaceMarkers(text) + "</div>";
        Assert.assertTrue(html.contains(cell + "<td>"), html);
        Assert.assertTrue(html.contains(cell + "<div><a href='report.txt' target='_blank' rel='noopener'>report.txt</a></div><td>"), html);
        Assert.assertEquals(html.split("<div class='message'>", -1).length - 1, 2, "one message div per entry");
    }

    @Test
    public void nestedTablesDoNotAffectRolloverAcrossSessions() throws Exception {
        Path dir = directories.create("inline-rollover");
        Path log = dir.resolve("log.html");
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) rows.add(new Row(new StringValue("row-" + i)));
        TableValue table = new TableValue();
        table.set(new Table(List.of(new Schema("Column")), rows));
        for (int i = 0; i < 3; i++) write(log, 2, entry("event-" + i, Map.of("table", table)));
        Path archive = dir.resolve("1_log.html");
        Assert.assertEquals(LogEntries.count(archive), 2L);
        Assert.assertEquals(LogEntries.count(log), 1L);
        Assert.assertEquals(CompressedSnapshots.expand(Files.readString(archive)).split("row-29", -1).length - 1, 2);
        Assert.assertTrue(CompressedSnapshots.expand(Files.readString(log)).contains("row-29"));
        Assert.assertFalse(Files.exists(dir.resolve("2_log.html")));
        assertSessionLayout(archive);
        assertSessionLayout(log);
    }

    @Test
    public void reopeningCompleteAndHeaderOnlyLogsOnlyAppends() throws Exception {
        Path dir = directories.create("inline-reopen");
        Path log = dir.resolve("log.html");
        write(log, 10, null);
        byte[] header = Files.readAllBytes(log);
        write(log, 10, null);
        Assert.assertEquals(Files.readAllBytes(log), header, "closing does not rewrite the document");
        write(log, 10, entry("first", null));
        String before = Files.readString(log);
        write(log, 10, entry("second", null));
        String after = Files.readString(log);
        Assert.assertTrue(after.startsWith(before), "all existing bytes remain in place");
        Assert.assertEquals(LogEntries.count(log), 2L);
        Assert.assertEquals(after.split("<!DOCTYPE html>", -1).length - 1, 1);
        Assert.assertTrue(after.endsWith(END));
        Assert.assertFalse(after.contains("</body>"));
        Assert.assertFalse(Files.exists(dir.resolve("1_log.html")));
    }

    @Test
    public void legacyLogsAndTheirSidecarsArePreservedWithoutArchiveCollisions() throws Exception {
        Path dir = directories.create("inline-legacy");
        Path log = dir.resolve("log[1].html");
        String old = "<!DOCTYPE html><table><tbody><tr><td>old</td></tr></tbody></table></html>";
        Files.writeString(log, old);
        Files.writeString(dir.resolve("1_log[1].html"), "older archive");
        Path sidecar = dir.resolve("variables/variables_old.html");
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, "old variables");
        write(log, 1, entry("new", null));
        Assert.assertEquals(Files.readString(dir.resolve("2_log[1].html")), old);
        Assert.assertEquals(Files.readString(dir.resolve("1_log[1].html")), "older archive");
        Assert.assertEquals(Files.readString(sidecar), "old variables");
        write(log, 1, entry("next", null));
        Assert.assertTrue(Files.readString(dir.resolve("3_log[1].html")).contains("new"));
        Assert.assertEquals(LogEntries.count(log), 1L);
    }

    @DataProvider
    public Object[][] oldFormats() {
        return new Object[][] {{2}, {3}, {4}, {5}};
    }

    @Test(dataProvider = "oldFormats")
    public void earlierFormatsAreArchivedByteForByte(int format) throws Exception {
        Path dir = directories.create("old-format");
        Path log = dir.resolve("log.html");
        byte[] original = ("<!DOCTYPE html>\n<!-- a360-log-format:" + format + " -->\n"
                + "<!-- a360-log-header:end -->\n<article>日本語</article>\n" + END)
                .getBytes(StandardCharsets.UTF_8);
        Files.write(log, original);
        write(log, 10, entry("new table entry", null));
        Assert.assertEquals(Files.readAllBytes(dir.resolve("1_log.html")), original);
        String html = Files.readString(log);
        Assert.assertTrue(html.contains("<!-- a360-log-format:7 -->"));
        Assert.assertTrue(html.contains("<tr class='log-entry level-INFO'>"));
        Assert.assertFalse(html.contains("<article>"));
        assertSessionLayout(log);
        Assert.assertEquals(LogEntries.count(log), 1L);
    }

    @Test
    public void anotherJvmPreservesIdentityAndArchivesAnAccountChange() throws Exception {
        Path dir = directories.create("header-identity");
        Path log = dir.resolve("log.html");
        String account = "operator <script>unsafe()</script> & \" 日本語 {{machine}} $ \\";
        writeAsUser(log, account, "first JVM");
        String first = Files.readString(log);
        Assert.assertTrue(first.contains("<dt>User</dt><dd>"
                + "operator &lt;script&gt;unsafe()&lt;/script&gt; &amp; &quot; 日本語 {{machine}} $ \\</dd>"), first);
        Assert.assertFalse(first.contains("<script>unsafe()</script>"));
        writeAsUser(log, account, "same account JVM");
        Assert.assertTrue(Files.readString(log).startsWith(first));
        Assert.assertFalse(Files.exists(dir.resolve("1_log.html")));
        byte[] beforeChange = Files.readAllBytes(log);
        writeAsUser(log, "another account", "changed account JVM");
        Assert.assertEquals(Files.readAllBytes(dir.resolve("1_log.html")), beforeChange);
        String changed = Files.readString(log);
        Assert.assertTrue(changed.contains("<dt>User</dt><dd>another account</dd>"));
        Assert.assertFalse(changed.contains("same account JVM"));
        Assert.assertFalse(changed.contains("unsafe()"));
        Assert.assertEquals(LogEntries.count(log), 1L);
        writeAsUser(log, "another account", "reopened changed account");
        Assert.assertEquals(LogEntries.count(log), 2L);
        Assert.assertFalse(Files.exists(dir.resolve("2_log.html")));
        byte[] beforeMissingAccount = Files.readAllBytes(log);
        writeAsUser(log, null, "missing account JVM");
        Assert.assertEquals(Files.readAllBytes(dir.resolve("2_log.html")), beforeMissingAccount);
        Assert.assertTrue(Files.readString(log).contains("<dt>User</dt><dd>null</dd>"));
        Assert.assertEquals(LogEntries.count(log), 1L);
        writeAsUser(log, null, "reopened missing account");
        Assert.assertEquals(LogEntries.count(log), 2L);
        Assert.assertFalse(Files.exists(dir.resolve("3_log.html")));
    }

    private void writeAsUser(Path log, String user, String message) throws Exception {
        Set<String> classpath = new java.util.LinkedHashSet<>(ChildJvm.classPath(getClass(), CustomLogger.class, org.json.JSONObject.class, Assert.class,
                org.apache.logging.log4j.Logger.class, org.apache.logging.log4j.core.LoggerContext.class,
                org.apache.commons.io.FilenameUtils.class, com.automationanywhere.core.security.SecureString.class,
                com.automationanywhere.botcommand.data.impl.SessionValue.class,
                com.automationanywhere.toolchain.runtime.session.CloseableSessionObject.class, com.google.protobuf.Message.class));
        classpath.add(Path.of("build/resources/main").toAbsolutePath().toString());
        Path output = log.resolveSibling("identity-jvm.log");
        // A Windows command line changes quotes and non-ASCII text, so the account name goes in a file.
        Path account = log.resolveSibling("identity-user.txt");
        if (user != null) Files.writeString(account, user, StandardCharsets.UTF_8);
        Process child = new ProcessBuilder(ChildJvm.command(getClass(), classpath, List.of("-Djava.awt.headless=true"),
                log.toString(), message, user == null ? "clear-user-name" : account.toString()))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            Assert.fail("identity JVM did not complete");
        }
        Assert.assertEquals(child.exitValue(), 0, Files.readString(output));
    }

    public static void main(String[] args) throws Exception {
        boolean missingAccount = args[2].equals("clear-user-name");
        // Before any class reads it: the layout reads user.name once.
        if (missingAccount) System.clearProperty("user.name");
        else System.setProperty("user.name", Files.readString(Path.of(args[2]), StandardCharsets.UTF_8));
        write(Path.of(args[0]), 100, entry(args[1], null));
        if (missingAccount) Assert.assertNull(CustomHTMLLayout.userName());
    }

    @Test
    public void compressionPreservesEverySupportedValueKind() throws Exception {
        Map<String, Value> values = new LinkedHashMap<>();
        values.put("string", new StringValue("</script> \" ' & 日本語\n\tend  "));
        values.put("number", new com.automationanywhere.botcommand.data.impl.NumberValue(42));
        values.put("boolean", new com.automationanywhere.botcommand.data.impl.BooleanValue(true));
        values.put("date", new com.automationanywhere.botcommand.data.impl.DateTimeValue(java.time.ZonedDateTime.now()));
        values.put("null", null);
        com.automationanywhere.botcommand.data.impl.ListValue list = new com.automationanywhere.botcommand.data.impl.ListValue();
        list.set(java.util.Arrays.asList(new StringValue("nested"), null));
        values.put("list", list);
        values.put("dictionary", new com.automationanywhere.botcommand.data.impl.DictionaryValue(Map.of("key", list)));
        values.put("record", new com.automationanywhere.botcommand.data.impl.RecordValue(
                List.of(new Schema("Column")), List.of(new StringValue("record-value"))));
        TableValue table = new TableValue();
        table.set(new Table(List.of(new Schema("Column")), List.of(new Row(new StringValue("table-value")))));
        values.put("table", table);
        String expected = com.automationanywhere.botcommand.utilities.logger.HTMLGenerator.generateVariablesHTML(values);
        String button = com.automationanywhere.botcommand.utilities.logger.HTMLGenerator.generateVariablesButton(values);
        Assert.assertEquals(CompressedSnapshots.expand(button), expected);
        Assert.assertFalse(button.contains("</script>"));
        Assert.assertTrue(button.length() < expected.length());
        Assert.assertEquals(com.automationanywhere.botcommand.utilities.logger.HTMLGenerator.generateVariablesButton(Map.of()), "");
    }

    @DataProvider
    public Object[][] activeWriterPaths() {
        List<Object[]> cases = new ArrayList<>();
        for (String alias : List.of("absolute", "dot", "relative")) {
            for (boolean aliasFirst : new boolean[] { false, true }) {
                for (int perLevelWriter = 0; perLevelWriter <= 2; perLevelWriter++) {
                    cases.add(new Object[] { alias, aliasFirst, perLevelWriter });
                }
            }
        }
        return cases.toArray(new Object[0][]);
    }

    @Test(dataProvider = "activeWriterPaths")
    public void anActiveWriterIsNotMistakenForAnInterruptedFile(
            String aliasKind, boolean aliasFirst, int perLevelWriter) throws Exception {
        Path dir = folderBesideWorkingDirectory("inline-active");
        Path log = dir.resolve("log.html");
        Path alias = aliasKind.equals("dot") ? dir.resolve(".").resolve("log.html")
                : aliasKind.equals("relative") ? Path.of("").toAbsolutePath().relativize(dir)
                        .resolve("..").resolve(dir.getFileName()).resolve("log.html") : log;
        String firstPath = (aliasFirst ? alias : log).toString();
        String secondPath = (aliasFirst ? log : alias).toString();
        Map<Level, String> levelPaths = Map.of(
                Level.INFO, perLevelWriter == 1 ? firstPath : secondPath,
                Level.WARN, dir.resolve("warn.html").toString(),
                Level.ERROR, dir.resolve("error.html").toString());
        CustomLogger first = perLevelWriter == 1
                ? new CustomLogger("first-writer", levelPaths, 10)
                : new CustomLogger("first-writer", firstPath, 10);
        try {
            first.getLogger().info(entry("first", null));
            // Model a write paused before its final bytes reach the file.
            Files.writeString(log, "<!-- in-flight", StandardOpenOption.APPEND);
            CustomLogger second = perLevelWriter == 2
                    ? new CustomLogger("second-writer", levelPaths, 10)
                    : new CustomLogger("second-writer", secondPath, 10);
            try {
                Assert.assertFalse(Files.exists(dir.resolve("1_log.html")));
                Files.writeString(log, " -->\n", StandardOpenOption.APPEND);
                second.getLogger().info(entry("second", null));
            } finally {
                second.close();
            }
            first.getLogger().info(entry("third", null));
        } finally {
            first.close();
        }
        Assert.assertEquals(LogEntries.count(log), 3L);
        for (String message : List.of("first", "second", "third")) {
            Assert.assertTrue(Files.readString(log).contains(message));
        }
        Assert.assertFalse(Files.exists(dir.resolve("1_log.html")));
    }

    @Test
    public void aliasesAcrossLevelsShareRolloverCountsWithoutChangingCallerPaths() throws Exception {
        Path dir = folderBesideWorkingDirectory("inline-level-aliases");
        Path log = dir.resolve("log.html");
        Map<Level, String> paths = Map.of(
                Level.INFO, log.toString(),
                Level.WARN, dir.resolve(".").resolve("log.html").toString(),
                Level.ERROR, Path.of("").toAbsolutePath().relativize(log).toString());
        Map<Level, String> original = new LinkedHashMap<>(paths);
        CustomLogger logger = new CustomLogger("level-aliases", paths, 2);
        try {
            for (Level level : paths.keySet()) {
                Assert.assertEquals(logger.getScreenshotFolderPath(level),
                        org.apache.commons.io.FilenameUtils.getFullPath(paths.get(level)) + "screenshots");
            }
            Level[] levels = { Level.INFO, Level.WARN, Level.ERROR, Level.INFO, Level.WARN };
            for (int i = 0; i < levels.length; i++) {
                logger.getLogger().log(levels[i], entry("shared-event-" + i, null));
            }
        } finally {
            logger.close();
        }
        Assert.assertEquals(paths, original);
        write(Path.of(paths.get(Level.ERROR)), 2, entry("shared-event-5", null));
        Assert.assertEquals(LogEntries.count(log), 2L);
        Assert.assertFalse(Files.exists(dir.resolve("3_log.html")));
        write(Path.of(paths.get(Level.WARN)), 2, entry("shared-event-6", null));
        Assert.assertEquals(LogEntries.count(log), 1L);
        for (int i = 1; i <= 3; i++) {
            Path archive = dir.resolve(i + "_log.html");
            Assert.assertEquals(LogEntries.count(archive), 2L);
            for (int j = 0; j < 2; j++) {
                Assert.assertTrue(Files.readString(archive).contains("shared-event-" + ((i - 1) * 2 + j)));
            }
        }
        Assert.assertTrue(Files.readString(log).contains("shared-event-6"));
        Assert.assertFalse(Files.exists(dir.resolve("4_log.html")));
    }

    @Test
    public void incompleteWritesAreArchivedWithoutLosingAnyBytes() throws Exception {
        Path dir = directories.create("inline-partial");
        Path log = dir.resolve("log.html");
        write(log, 10, entry("complete event", null));
        Files.write(log, "<article class='log-entry'>partial 日本語".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(log);
        write(log, 10, entry("after restart", null));
        Assert.assertEquals(Files.readAllBytes(dir.resolve("1_log.html")), before);
        Assert.assertEquals(LogEntries.count(log), 1L);
        Assert.assertFalse(Files.readString(log).contains("partial 日本語"));
        Assert.assertTrue(Files.readString(log).contains("after restart"));

        Path partialHeader = dir.resolve("header.html");
        byte[] torn = "<!DOCTYPE html>\n<!-- a360-log-format:2 -->\n<html><head>".getBytes(StandardCharsets.UTF_8);
        Files.write(partialHeader, torn);
        write(partialHeader, 10, entry("new header", null));
        Assert.assertEquals(Files.readAllBytes(dir.resolve("1_header.html")), torn);
        Assert.assertEquals(LogEntries.count(partialHeader), 1L);
    }

    @Test
    public void simultaneousStartsMigrateOnceAndKeepEveryEntry() throws Exception {
        for (boolean incomplete : new boolean[] { false, true }) {
            Path dir = directories.create("inline-concurrent-start");
            Map<Level, String> paths = Map.of(
                    Level.INFO, dir.resolve("info.html").toString(),
                    Level.WARN, dir.resolve("warn.html").toString(),
                    Level.ERROR, dir.resolve("error.html").toString());
            Map<Path, byte[]> originals = new LinkedHashMap<>();
            for (String file : paths.values()) {
                Path path = Path.of(file);
                if (incomplete) {
                    write(path, 1000, entry("saved before interruption", null));
                    Files.writeString(path, "<article>incomplete", StandardOpenOption.APPEND);
                } else {
                    Files.writeString(path, "<!DOCTYPE html><html><body>legacy log</body></html>");
                }
                originals.put(path, Files.readAllBytes(path));
            }

            int workers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentLinkedQueue<CustomLogger> sessions = new ConcurrentLinkedQueue<>();
            List<Future<?>> tasks = new ArrayList<>();
            try {
                for (int i = 0; i < workers; i++) {
                    final int id = i;
                    tasks.add(pool.submit(() -> {
                        ready.countDown();
                        Assert.assertTrue(start.await(10, TimeUnit.SECONDS));
                        // Exercise both constructors with overlapping output files.
                        CustomLogger logger = id % 2 == 0
                                ? new CustomLogger("worker-" + id, paths.get(Level.INFO), 1000)
                                : new CustomLogger("worker-" + id, paths, 1000);
                        sessions.add(logger);
                        for (Level level : List.of(Level.INFO, Level.WARN, Level.ERROR)) {
                            logger.getLogger().log(level, entry("worker-" + id + "-" + level, null));
                        }
                        return null;
                    }));
                }
                Assert.assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
            } finally {
                start.countDown();
                pool.shutdownNow();
                pool.awaitTermination(20, TimeUnit.SECONDS);
                sessions.forEach(CustomLogger::close);
            }

            StringBuilder allEntries = new StringBuilder();
            for (Map.Entry<Path, byte[]> original : originals.entrySet()) {
                Path file = original.getKey();
                Assert.assertEquals(Files.readAllBytes(file.resolveSibling("1_" + file.getFileName())),
                        original.getValue(), "the original file is preserved byte-for-byte");
                Assert.assertFalse(Files.exists(file.resolveSibling("2_" + file.getFileName())),
                        "each file is migrated only once");
                String html = Files.readString(file);
                Assert.assertEquals(html.split("<!DOCTYPE html>", -1).length - 1, 1);
                allEntries.append(html);
            }
            Assert.assertEquals(LogEntries.count(Path.of(paths.get(Level.INFO))), 16L);
            Assert.assertEquals(LogEntries.count(Path.of(paths.get(Level.WARN))), 4L);
            Assert.assertEquals(LogEntries.count(Path.of(paths.get(Level.ERROR))), 4L);
            for (int i = 0; i < workers; i++) {
                for (Level level : List.of(Level.INFO, Level.WARN, Level.ERROR)) {
                    Assert.assertEquals(allEntries.toString().split("worker-" + i + "-" + level, -1).length - 1, 1,
                            "each message is written exactly once");
                }
            }
        }
    }
}
