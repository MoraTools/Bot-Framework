package logs;

import com.automationanywhere.botcommand.utilities.helios.HeliosConfig;
import com.automationanywhere.botcommand.utilities.helios.HeliosJsonLayout;
import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import com.automationanywhere.botcommand.utilities.screen.recorder.CaptureResult;
import com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.ObjectMessage;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Tests for {@code CustomLogger.logExternal}, the entry point for other packages. */
public class ExternalLogTest {
    private static final String HEADER_END = "<!-- a360-log-header:end -->\n";
    private static final String ENTRY_END = "<!-- a360-log-entry:end -->\n";
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private static final Pattern SCREENSHOT_LINK = Pattern.compile("<a href='([^']*)' target='_blank' class='img-link'");
    private static final Pattern ATTACHMENT_LINK =
            Pattern.compile("<div><a href='([^']*)' target='_blank' rel='noopener'>(.*?)</a></div>");

    private static Path png(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Assert.assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", file.toFile()));
        return file;
    }

    private static Path text(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** The entries of one log file, without the header and the entry end marker. */
    private static List<String> rows(Path log) throws IOException {
        String html = Files.readString(log, StandardCharsets.UTF_8);
        String entries = html.substring(html.indexOf(HEADER_END) + HEADER_END.length());
        List<String> rows = new ArrayList<>();
        for (String row : entries.split(Pattern.quote(ENTRY_END))) if (!row.isEmpty()) rows.add(row);
        return rows;
    }

    private static String withoutTimestamp(String row) {
        if (row.endsWith(ENTRY_END)) row = row.substring(0, row.length() - ENTRY_END.length());
        return row.replaceFirst("(<tr[^>]*><td>)[^<]*", "$1T");
    }

    /** The row that an ordinary entry with only a message and a source produces. */
    private static String plainRow(Level level, String message, String source) {
        Map<String, Object> data = new HashMap<>();
        data.put(CustomHTMLLayout.Columns.MESSAGE, message);
        data.put(CustomHTMLLayout.Columns.SOURCE, source);
        return withoutTimestamp(new CustomHTMLLayout(StandardCharsets.UTF_8).toSerializable(Log4jLogEvent.newBuilder()
                .setTimeMillis(0).setLevel(level).setMessage(new ObjectMessage(data)).build()));
    }

    private static Path resolveLink(Path logDirectory, String htmlHref) throws Exception {
        return logDirectory.resolve(new URI(StringEscapeUtils.unescapeHtml4(htmlHref)).getPath()).normalize();
    }

    private static Map<String, Object> ordinaryEntry(String message) {
        Map<String, Object> row = new HashMap<>();
        row.put(CustomHTMLLayout.Columns.MESSAGE, message);
        row.put(CustomHTMLLayout.Columns.SOURCE, "ordinary");
        row.put(CustomHTMLLayout.Columns.SCREENSHOT, "");
        row.put(CustomHTMLLayout.Columns.VARIABLES, null);
        return row;
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    public void reflectionSignatureUsesOnlyJdkTypesAndReturnsVoid() throws Exception {
        Method method = CustomLogger.class.getMethod("logExternal",
                String.class, String.class, String.class, String.class, String.class);
        Assert.assertEquals(method.getReturnType(), void.class);
        Assert.assertTrue(Modifier.isPublic(method.getModifiers()));
        Assert.assertEquals(Arrays.stream(CustomLogger.class.getMethods())
                .filter(candidate -> candidate.getName().equals("logExternal")).count(), 1);
    }

    @Test
    public void everyLevelIsWrittenInAnyLetterCase() throws Exception {
        Path log = Files.createTempDirectory("external-levels").resolve("log.html");
        CustomLogger logger = new CustomLogger("external-levels", log.toString(), 100);
        try {
            for (String level : List.of("INFO", "info", "Warn", "WARN", "error", "eRRoR")) {
                logger.logExternal(level, "level " + level, "source", null, null);
            }
        } finally { logger.close(); }
        List<String> rows = rows(log);
        Assert.assertEquals(rows.size(), 6);
        for (String level : new String[] {"INFO", "INFO", "WARN", "WARN", "ERROR", "ERROR"}) {
            String row = rows.remove(0);
            Assert.assertTrue(row.startsWith("<tr class='log-entry level-" + level + "'>"), row);
        }
    }

    @Test
    public void invalidLevelsAndNullMessageAreRejectedBeforeAnythingIsWritten() throws Exception {
        Path log = Files.createTempDirectory("external-invalid").resolve("log.html");
        CustomLogger logger = new CustomLogger("external-invalid", log.toString(), 100);
        try {
            for (String level : new String[] {null, "", " INFO", "INFO ", "DEBUG", "TRACE", "FATAL", "ALL"}) {
                Assert.expectThrows(IllegalArgumentException.class,
                        () -> logger.logExternal(level, "message", "source", null, null));
            }
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> logger.logExternal("INFO", null, "source", null, null));
            Assert.expectThrows(IllegalArgumentException.class, () -> logger.logExternal(null, null, null, null, null));
        } finally { logger.close(); }
        Assert.assertTrue(rows(log).isEmpty());
    }

    @Test
    public void messageAndSourceRenderLikeAnOrdinaryEntryAndOptionalInputsMayBeEmpty() throws Exception {
        Path log = Files.createTempDirectory("external-text").resolve("log.html");
        String hostile = "</td><td><script>alert(1)</script> & \" 日本語\n\ttrail  \n";
        CustomLogger logger = new CustomLogger("external-text", log.toString(), 100);
        try {
            logger.logExternal("INFO", hostile, "PUBLIC:/tasks/<b>x</b> & 日本語", null, null);
            logger.logExternal("WARN", "", "", "", "");
            logger.logExternal("ERROR", "no source", null, null, "");
            logger.logExternal("INFO", "   ", "kept", "", null);
        } finally { logger.close(); }
        List<String> rows = rows(log);
        Assert.assertEquals(rows.size(), 4);
        Assert.assertEquals(withoutTimestamp(rows.get(0)), plainRow(Level.INFO, hostile, "PUBLIC:/tasks/<b>x</b> & 日本語"));
        Assert.assertEquals(withoutTimestamp(rows.get(1)), plainRow(Level.WARN, "", ""));
        Assert.assertEquals(withoutTimestamp(rows.get(2)), plainRow(Level.ERROR, "no source", ""));
        Assert.assertEquals(withoutTimestamp(rows.get(3)), plainRow(Level.INFO, "   ", "kept"));
        Assert.assertFalse(rows.get(0).contains("<script"));
        Assert.assertTrue(rows.get(0).contains(HTMLGenerator.escapeWithWhitespaceMarkers(hostile)));
        for (String row : rows) Assert.assertEquals(count(row, "<td>"), 6, row);
    }

    @Test
    public void linksInsideTheLogFolderStayRelative() throws Exception {
        Path root = Files.createTempDirectory("external-inside");
        Path log = root.resolve("logs/log.html");
        Path shot = png(root.resolve("logs/screenshots/shot.png"));
        Path attachment = text(root.resolve("logs/attachments/report 1.txt"), "report");
        CustomLogger logger = new CustomLogger("external-inside", log.toString(), 100);
        try { logger.logExternal("INFO", "inside", "source", shot.toString(), attachment.toString()); }
        finally { logger.close(); }
        String row = rows(log).get(0);
        Assert.assertTrue(row.contains("<div><a href='attachments/report%201.txt' target='_blank' rel='noopener'>report 1.txt</a></div>"), row);
        Assert.assertTrue(row.contains("<a href='screenshots/shot.png' target='_blank' class='img-link'"), row);
    }

    @Test
    public void linksOutsideTheLogFolderQuoteSpecialCharactersAndLeaveSuppliedFilesUnchanged() throws Exception {
        Path root = Files.createTempDirectory("external-outside");
        Path log = root.resolve("logs/log.html");
        Path shot = png(root.resolve("ext dir/shot ' & # (1) 日本語.png"));
        Path attachment = text(root.resolve("ext dir/report ' & # (1).txt"), "attachment bytes");
        byte[] shotBytes = Files.readAllBytes(shot), attachmentBytes = Files.readAllBytes(attachment);
        long shotTime = Files.getLastModifiedTime(shot).toMillis(), attachmentTime = Files.getLastModifiedTime(attachment).toMillis();
        List<String> before = names(shot.getParent());
        CustomLogger logger = new CustomLogger("external-outside", log.toString(), 100);
        try { logger.logExternal("WARN", "outside", "source", shot.toString(), attachment.toString()); }
        finally { logger.close(); }
        String row = rows(log).get(0);
        Assert.assertTrue(row.contains("<div><a href='../ext%20dir/report%20%27%20&amp;%20%23%20%281%29.txt' "
                + "target='_blank' rel='noopener'>report ' &amp; # (1).txt</a></div>"), row);
        Matcher screenshotLink = SCREENSHOT_LINK.matcher(row);
        Assert.assertTrue(screenshotLink.find(), row);
        Assert.assertTrue(screenshotLink.group(1).startsWith("../ext%20dir/shot%20%27%20&amp;%20%23%20%281%29%20"), screenshotLink.group(1));
        Assert.assertEquals(resolveLink(log.getParent(), screenshotLink.group(1)), shot);
        Matcher attachmentLink = ATTACHMENT_LINK.matcher(row);
        Assert.assertTrue(attachmentLink.find());
        Assert.assertEquals(resolveLink(log.getParent(), attachmentLink.group(1)), attachment);
        Assert.assertEquals(StringEscapeUtils.unescapeHtml4(attachmentLink.group(2)), attachment.getFileName().toString());
        Assert.assertEquals(Files.readAllBytes(shot), shotBytes);
        Assert.assertEquals(Files.readAllBytes(attachment), attachmentBytes);
        Assert.assertEquals(Files.getLastModifiedTime(shot).toMillis(), shotTime);
        Assert.assertEquals(Files.getLastModifiedTime(attachment).toMillis(), attachmentTime);
        Assert.assertEquals(names(shot.getParent()), before, "no file is copied, moved, or deleted");
    }

    @Test
    public void hostileFileNameAndMessageNeverBecomeMarkup() throws Exception {
        Path root = Files.createTempDirectory("external-hostile");
        Path log = root.resolve("logs/log.html");
        String name = WINDOWS ? "report ' & #.txt" : "<script>alert('x')\"q\".txt";
        Path attachment = text(root.resolve("ext/" + name), "attachment");
        String message = "<script>alert(1)</script> <a href='x'>message</a>";
        CustomLogger logger = new CustomLogger("external-hostile", log.toString(), 100);
        try { logger.logExternal("ERROR", message, "source", null, attachment.toString()); }
        finally { logger.close(); }
        String row = rows(log).get(0);
        Assert.assertFalse(row.contains("<script"), row);
        Assert.assertEquals(count(row, "<a "), 1, "only the attachment is a link");
        Assert.assertEquals(count(row, "<td>"), 6);
        Assert.assertTrue(row.contains(HTMLGenerator.escapeWithWhitespaceMarkers(message) + "<div><a href='"), row);
        Matcher link = ATTACHMENT_LINK.matcher(row);
        Assert.assertTrue(link.find(), row);
        Assert.assertEquals(link.group(2), StringEscapeUtils.escapeHtml4(name));
        Assert.assertEquals(StringEscapeUtils.unescapeHtml4(link.group(2)), name);
        Assert.assertFalse(link.group(1).contains("'") || link.group(1).contains("\"") || link.group(1).contains("<"));
        Assert.assertEquals(resolveLink(log.getParent(), link.group(1)), attachment);
        // The viewer script only handles [data-vars] and .img-link, so a plain anchor keeps its native click.
        Assert.assertFalse(link.group(0).contains("img-link") || link.group(0).contains("data-vars"));
    }

    @Test
    public void aNameWithAColonBeforeTheFirstSlashCannotBecomeAUriScheme() throws Exception {
        if (WINDOWS) throw new SkipException("A colon cannot be part of a Windows file name");
        Path logDirectory = Files.createTempDirectory("external-colon");
        Path log = logDirectory.resolve("log.html");
        Path attachment = text(logDirectory.resolve("javascript:alert(1).txt"), "attachment");
        Path shot = png(logDirectory.resolve("data:x.png"));
        CustomLogger logger = new CustomLogger("external-colon", log.toString(), 100);
        try { logger.logExternal("INFO", "colon", "source", shot.toString(), attachment.toString()); }
        finally { logger.close(); }
        String row = rows(log).get(0);
        Assert.assertTrue(row.contains("<div><a href='./javascript:alert%281%29.txt' "), row);
        Assert.assertTrue(row.contains("<a href='./data:x.png' target='_blank' class='img-link'"), row);
        Assert.assertFalse(row.contains("href='javascript:") || row.contains("href='data:"), row);
        Assert.assertEquals(resolveLink(logDirectory, "./javascript:alert%281%29.txt"), attachment);
    }

    @Test
    public void differentRootUsesFileUrisWhenAnotherRootIsWritable() throws Exception {
        Path logDirectory = Files.createTempDirectory("external-root");
        Path logRoot = logDirectory.toAbsolutePath().getRoot();
        Path otherDirectory = null;
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            if (root.equals(logRoot)) continue;
            try { otherDirectory = Files.createTempDirectory(root, "external-root"); break; }
            catch (IOException | SecurityException unavailable) { /* try the next root */ }
        }
        if (otherDirectory == null) throw new SkipException("This host has no second writable file system root");
        Path shot = png(otherDirectory.resolve("shot ' #.png"));
        Path attachment = text(otherDirectory.resolve("report ' #.txt"), "attachment");
        try {
            Path log = logDirectory.resolve("log.html");
            CustomLogger logger = new CustomLogger("external-root", log.toString(), 100);
            try { logger.logExternal("INFO", "other root", "source", shot.toString(), attachment.toString()); }
            finally { logger.close(); }
            String row = rows(log).get(0);
            String expectedAttachment = attachment.toUri().toASCIIString().replace("'", "%27");
            Assert.assertTrue(row.contains("<div><a href='" + StringEscapeUtils.escapeHtml4(expectedAttachment) + "'"), row);
            Assert.assertTrue(row.contains("<a href='" + shot.toUri().toASCIIString().replace("'", "%27") + "'"), row);
        } finally {
            Files.deleteIfExists(shot);
            Files.deleteIfExists(attachment);
            Files.deleteIfExists(otherDirectory);
        }
    }

    @Test
    public void invalidPathsAreRejectedWithoutWritingOrChangingFiles() throws Exception {
        Path root = Files.createTempDirectory("external-paths");
        Path log = root.resolve("logs/log.html");
        Path shot = png(root.resolve("ext/shot.png"));
        Path notImage = text(root.resolve("ext/notes.png"), "this text is not an image");
        Path file = text(root.resolve("ext/file.txt"), "plain file");
        Path directory = Files.createDirectories(root.resolve("ext/folder"));
        Path missing = root.resolve("ext/missing.png");
        List<String> before = names(root.resolve("ext"));
        byte[] notImageBytes = Files.readAllBytes(notImage);
        CustomLogger logger = new CustomLogger("external-paths", log.toString(), 100);
        try {
            for (String bad : new String[] {"shot.png", "ext/shot.png", "./shot.png", "../ext/shot.png", "nul\0.png",
                    missing.toString(), directory.toString(), notImage.toString(), file.toString()}) {
                Assert.expectThrows(IllegalArgumentException.class,
                        () -> logger.logExternal("INFO", "bad screenshot", "source", bad, null));
            }
            for (String bad : new String[] {"file.txt", "ext/file.txt", "./file.txt", "../ext/file.txt", "nul\0.txt",
                    root.resolve("ext/missing.txt").toString(), directory.toString()}) {
                Assert.expectThrows(IllegalArgumentException.class,
                        () -> logger.logExternal("INFO", "bad attachment", "source", null, bad));
            }
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> logger.logExternal("INFO", "one bad path", "source", shot.toString(), missing.toString()));
            // A valid path is not a reason to accept the other one; an image is a valid attachment.
            logger.logExternal("INFO", "image attachment", "source", null, shot.toString());
        } finally { logger.close(); }
        Assert.assertEquals(rows(log).size(), 1);
        Assert.assertEquals(names(root.resolve("ext")), before);
        Assert.assertEquals(Files.readAllBytes(notImage), notImageBytes);
    }

    @Test
    public void closedSessionThrowsTheExactException() throws Exception {
        Path root = Files.createTempDirectory("external-closed");
        Path log = root.resolve("log.html");
        CustomLogger logger = new CustomLogger("external-closed", log.toString(), 100);
        logger.logExternal("INFO", "before close", "source", null, null);
        logger.close();
        IllegalStateException failure = Assert.expectThrows(IllegalStateException.class,
                () -> logger.logExternal("INFO", "after close", "source", null, null));
        Assert.assertEquals(failure.getMessage(), "Logger session is closed");
        Assert.assertEquals(rows(log).size(), 1);
        Assert.assertFalse(Files.readString(log).contains("after close"));
    }

    @Test
    public void externalEntriesNeverUseCaptureRecordingOrSnapshotApis() throws Exception {
        Path root = Files.createTempDirectory("external-no-capture");
        Path log = root.resolve("logs/log.html");
        Path shot = png(root.resolve("ext/shot.png"));
        Path attachment = text(root.resolve("ext/file.txt"), "attachment");
        FailFastLogger logger = new FailFastLogger("external-no-capture", log.toString());
        try {
            for (String level : new String[] {"INFO", "WARN", "ERROR"}) {
                logger.logExternal(level, level + " entry", "source", shot.toString(), attachment.toString());
            }
            Assert.assertEquals(rows(log).size(), 3);
        } finally { logger.close(); }
        Assert.assertEquals(logger.forbiddenCalls.size(), 0, logger.forbiddenCalls.toString());
        for (Level level : List.of(Level.INFO, Level.WARN, Level.ERROR)) {
            Path screenshots = Path.of(logger.getScreenshotFolderPath(level));
            if (Files.isDirectory(screenshots)) Assert.assertEquals(names(screenshots), List.of(), "no still was captured");
        }
        Assert.assertFalse(Files.exists(root.resolve("logs/clips")));
    }

    @Test
    public void heliosGetsLevelMessageAndSourceButNoMediaOrSuppliedPaths() throws Exception {
        Path root = Files.createTempDirectory("external-helios");
        Path outbox = Files.createDirectories(root.resolve("outbox"));
        Path shot = png(root.resolve("secret-ext/secret-shot-name.png"));
        Path attachment = text(root.resolve("secret-ext/secret-attachment-name.txt"), "SECRET-ATTACHMENT-BYTES");
        byte[] shotBytes = Files.readAllBytes(shot);
        String previous = System.getProperty("helios.outbox.directory");
        System.setProperty("helios.outbox.directory", outbox.toString());
        try (Server server = new Server()) {
            Path log = root.resolve("logs/log.html");
            CustomLogger logger = new CustomLogger("external-helios", log.toString(), 100, 0, new HashSet<>(),
                    EncodingMode.FAST, server.config());
            try {
                logger.logExternal("INFO", "streamed info", "PUBLIC:/Bots/tasks/Ext@v1#L1", shot.toString(), attachment.toString());
                logger.logExternal("warn", "streamed warn", "", shot.toString(), null);
                logger.logExternal("ERROR", "streamed error ' & <b>", null, null, attachment.toString());
            } finally { logger.close(); }
            Assert.assertEquals(server.rows.size(), 3, server.requests.toString());
            String[][] expected = {{"INFO", "streamed info", "PUBLIC:/Bots/tasks/Ext@v1#L1"},
                    {"WARN", "streamed warn", ""}, {"ERROR", "streamed error ' & <b>", ""}};
            for (int index = 0; index < 3; index++) {
                JSONObject entry = server.rows.get(index);
                Assert.assertEquals(entry.getString("level"), expected[index][0]);
                Assert.assertEquals(entry.getString("message"), expected[index][1]);
                Assert.assertEquals(entry.getString("source"), expected[index][2]);
                Assert.assertEquals(entry.getString("screenPath"), "");
                Assert.assertEquals(entry.getInt("variablesCount"), 0);
                Assert.assertFalse(entry.has("media"), "no ERROR media is requested for an external entry");
            }
            for (String request : server.requests) {
                Assert.assertFalse(request.contains("secret-"), request);
                Assert.assertFalse(request.contains("SECRET-ATTACHMENT-BYTES"), request);
                Assert.assertFalse(request.contains(root.toString()), request);
                Assert.assertFalse(request.contains("/media"), request);
            }
            try (Stream<Path> files = Files.walk(outbox)) {
                for (Path file : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                    String stored = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    Assert.assertFalse(stored.contains("secret-"), file.toString());
                    Assert.assertFalse(stored.contains("SECRET-ATTACHMENT-BYTES"), file.toString());
                }
            }
            // The local HTML log still shows the supplied files.
            String html = Files.readString(log);
            Assert.assertTrue(html.contains("secret-attachment-name.txt"));
            Assert.assertTrue(html.contains("secret-shot-name.png"));
            Assert.assertEquals(Files.readAllBytes(shot), shotBytes);
        } finally {
            if (previous == null) System.clearProperty("helios.outbox.directory");
            else System.setProperty("helios.outbox.directory", previous);
        }
    }

    @Test
    public void heliosLayoutLeavesScreenPathEmptyOnlyForExternalEntries() {
        Map<String, Object> row = new HashMap<>();
        row.put(CustomHTMLLayout.Columns.MESSAGE, "message");
        row.put(CustomHTMLLayout.Columns.SCREENSHOT, "C:\\logs\\screenshots\\one.png");
        row.put(CustomHTMLLayout.Columns.ATTACHMENT, "C:\\logs\\report.txt");
        JSONObject ordinary = new HeliosJsonLayout().toJson(event(row), 0).getJSONArray("entries").getJSONObject(0);
        Assert.assertEquals(ordinary.getString("screenPath"), "C:\\logs\\screenshots\\one.png");
        row.put(CustomHTMLLayout.Columns.VIDEO, "C:\\logs\\clips\\one.mp4");
        Assert.assertEquals(new HeliosJsonLayout().toJson(event(row), 0).getJSONArray("entries")
                .getJSONObject(0).getString("screenPath"), "C:\\logs\\clips\\one.mp4");
        row.put(CustomHTMLLayout.Columns.EXTERNAL, Boolean.TRUE);
        JSONObject external = new HeliosJsonLayout().toJson(event(row), 0).getJSONArray("entries").getJSONObject(0);
        Assert.assertEquals(external.getString("screenPath"), "");
        Assert.assertEquals(external.length(), ordinary.length(), "an external entry has the same fields");
        Assert.assertFalse(external.toString().contains("report.txt"));
    }

    @Test
    public void existingFormat6LogIsAppendedWithoutMigrationAndOldEntriesRenderAsBefore() throws Exception {
        Path root = Files.createTempDirectory("external-append");
        Path log = root.resolve("log.html");
        CustomLogger first = new CustomLogger("external-append-1", log.toString(), 100);
        try {
            first.getLogger().info(ordinaryEntry("old one"));
            first.getLogger().error(ordinaryEntry("old two"));
        } finally { first.close(); }
        byte[] old = Files.readAllBytes(log);
        Assert.assertTrue(new String(old, StandardCharsets.UTF_8).startsWith("<!DOCTYPE html>\n<!-- a360-log-format:6 -->\n"));
        Path attachment = text(root.resolve("ext/new.txt"), "attachment");
        CustomLogger second = new CustomLogger("external-append-2", log.toString(), 100);
        try {
            second.logExternal("WARN", "new external", "source", null, attachment.toString());
            second.getLogger().info(ordinaryEntry("new ordinary"));
        } finally { second.close(); }
        byte[] now = Files.readAllBytes(log);
        Assert.assertEquals(Arrays.copyOf(now, old.length), old, "existing bytes are not rewritten");
        Assert.assertFalse(Files.exists(root.resolve("1_log.html")), "no migration archive");
        String html = new String(now, StandardCharsets.UTF_8);
        Assert.assertEquals(count(html, "<!-- a360-log-format:6 -->"), 1);
        Assert.assertEquals(count(html, "<!-- a360-log-header:end -->"), 1);
        List<String> rows = rows(log);
        Assert.assertEquals(rows.size(), 4);
        Assert.assertTrue(rows.get(2).contains("<div><a href='ext/new.txt'"), rows.get(2));
        Assert.assertEquals(count(rows.get(3), "<a "), 0, "an ordinary entry has no attachment link");
        Assert.assertTrue(html.endsWith(ENTRY_END));
    }

    @Test
    public void concurrentExternalEntriesKeepEveryCompleteEntry() throws Exception {
        int threads = 8, perThread = 50;
        Path root = Files.createTempDirectory("external-concurrent");
        Path log = root.resolve("log.html");
        Path attachment = text(root.resolve("ext/shared.txt"), "attachment");
        Path shot = png(root.resolve("ext/shot.png"));
        CustomLogger logger = new CustomLogger("external-concurrent", log.toString(), 10_000);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> work = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                int id = thread;
                work.add(pool.submit(() -> {
                    start.await();
                    for (int index = 0; index < perThread; index++) {
                        logger.logExternal(new String[] {"INFO", "WARN", "ERROR"}[index % 3], "[t=" + id + " i=" + index + "]",
                                "thread " + id, index % 2 == 0 ? shot.toString() : null, index % 5 == 0 ? attachment.toString() : null);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> done : work) done.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            logger.close();
        }
        List<String> rows = rows(log);
        Assert.assertEquals(rows.size(), threads * perThread);
        Set<String> seen = new TreeSet<>();
        for (String row : rows) {
            Assert.assertEquals(count(row, "<td>"), 6, row);
            Assert.assertTrue(row.startsWith("<tr class='log-entry level-") && row.endsWith("</tr>\n"), row);
            Matcher id = Pattern.compile("\\[t=(\\d+) i=(\\d+)]").matcher(row);
            Assert.assertTrue(id.find(), row);
            seen.add(id.group());
        }
        Assert.assertEquals(seen.size(), threads * perThread, "each entry is written once");
        Assert.assertTrue(Files.readString(log).endsWith(ENTRY_END));
    }

    private static Log4jLogEvent event(Map<String, Object> row) {
        return Log4jLogEvent.newBuilder().setLoggerName("test").setLevel(Level.ERROR).setTimeMillis(1_762_000_000_000L)
                .setMessage(new ObjectMessage(row)).build();
    }

    /** Fails the test thread if the external entry point reaches capture, recording, or snapshot code. */
    private static final class FailFastLogger extends CustomLogger {
        final List<String> forbiddenCalls = new CopyOnWriteArrayList<>();

        FailFastLogger(String name, String path) throws IOException { super(name, path, 100); }

        @Override public boolean shouldRecordVideoFor(Level level) {
            forbiddenCalls.add("shouldRecordVideoFor");
            throw new AssertionError("shouldRecordVideoFor must not be called");
        }

        @Override public Path snapshotForError(String errorUuid) {
            forbiddenCalls.add("snapshotForError(String)");
            throw new AssertionError("snapshotForError must not be called");
        }

        @Override public CaptureResult snapshotForError(String captureId, boolean localRequired, Consumer<CaptureResult> register) {
            forbiddenCalls.add("snapshotForError(String, boolean, Consumer)");
            throw new AssertionError("snapshotForError must not be called");
        }
    }

    /** Minimal Helios ingest server: it keeps the entries and every request it receives. */
    private static final class Server implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final List<JSONObject> rows = new CopyOnWriteArrayList<>();
        final List<String> requests = new CopyOnWriteArrayList<>();

        Server() throws Exception {
            server.createContext("/api/ingest/sessions", exchange -> {
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(exchange.getRequestURI().getPath() + " " + raw);
                JSONObject body = new JSONObject(raw);
                String[] path = exchange.getRequestURI().getPath().split("/");
                JSONObject reply;
                if (path.length == 4) {
                    reply = new JSONObject().put("sessionId", body.getString("loggerSessionId"))
                            .put("executionSessionId", "66666666-7777-8888-9999-000000000000")
                            .put("executionId", body.getString("executionId"))
                            .put("media", new JSONObject().put("version", 1)
                                    .put("maxScreenshotBytes", 1048576).put("maxVideoBytes", 8388608));
                } else if (path.length == 6 && "entries".equals(path[5])) {
                    JSONArray entries = body.getJSONArray("entries");
                    for (int i = 0; i < entries.length(); i++) rows.add(entries.getJSONObject(i));
                    reply = new JSONObject().put("accepted", entries.length()).put("duplicates", 0)
                            .put("nextOrdinal", body.getInt("firstOrdinal") + entries.length());
                } else if (path.length > 6) {
                    reply = new JSONObject();
                } else {
                    reply = new JSONObject().put("complete", true).put("storedEntries", body.getInt("expectedEntries"));
                }
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        HeliosConfig config() {
            return new HeliosConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "external-aa",
                    "bot", "master", "file", "machine", "user", null);
        }

        @Override public void close() { server.stop(0); }
    }
}
