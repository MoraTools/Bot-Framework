package logs;

import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.utilities.helios.HeliosConfig;
import com.automationanywhere.botcommand.utilities.helios.HeliosJsonLayout;
import com.automationanywhere.botcommand.utilities.helios.HeliosStubServer;
import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import com.automationanywhere.botcommand.utilities.screen.recorder.CaptureResult;
import com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.ObjectMessage;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import javax.imageio.ImageIO;
import support.TempDirectories;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static support.LogEntries.occurrences;

/** Tests for {@code CustomLogger.logExternal}, the entry point for other packages. */
public class ExternalLogTest {
    private static final String HEADER_END = "<!-- a360-log-header:end -->\n";
    private static final String ENTRY_END = "<!-- a360-log-entry:end -->\n";
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private static final Pattern SCREENSHOT_LINK = Pattern.compile("<a href='([^']*)' target='_blank' class='img-link'");
    private static final Pattern ATTACHMENT_LINK =
            Pattern.compile("<div><a href='([^']*)' target='_blank' rel='noopener'>(.*?)</a></div>");

    private final TempDirectories directories = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void deleteDirectories() {
        directories.deleteAll();
    }

    static Path png(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Assert.assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", file.toFile()));
        return file;
    }

    static Path text(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** The entries of one log file, without the header and the entry end marker. */
    static List<String> rows(Path log) throws IOException {
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

    private static Path resolveLink(Path logDirectory, String htmlHref) throws Exception {
        return logDirectory.resolve(new URI(StringEscapeUtils.unescapeHtml4(htmlHref)).getPath()).normalize();
    }

    /** An entry as the Log Message action writes it when it has only a message and a source. */
    private static Map<String, Object> ordinaryEntry(String message, String source) {
        Map<String, Object> row = new HashMap<>();
        row.put(CustomHTMLLayout.Columns.MESSAGE, message);
        row.put(CustomHTMLLayout.Columns.SOURCE, source);
        row.put(CustomHTMLLayout.Columns.SCREENSHOT, "");
        row.put(CustomHTMLLayout.Columns.VARIABLES, null);
        return row;
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
        Path log = directories.create("external-levels").resolve("log.html");
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
        Path log = directories.create("external-invalid").resolve("log.html");
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
        Path log = directories.create("external-text").resolve("log.html");
        String hostile = "</td><td><script>alert(1)</script> & \" 日本語\n\ttrail  \n";
        String hostileSource = "PUBLIC:/tasks/<b>x</b> & 日本語";
        CustomLogger logger = new CustomLogger("external-text", log.toString(), 100);
        try {
            // Each external entry is followed by the ordinary entry that must look the same.
            logger.logExternal("INFO", hostile, hostileSource, null, null);
            logger.getLogger().info(ordinaryEntry(hostile, hostileSource));
            logger.logExternal("WARN", "", "", "", "");
            logger.getLogger().warn(ordinaryEntry("", ""));
            logger.logExternal("ERROR", "no source", null, null, "");
            logger.getLogger().error(ordinaryEntry("no source", ""));
            logger.logExternal("INFO", "   ", "kept", "", null);
            logger.getLogger().info(ordinaryEntry("   ", "kept"));
        } finally { logger.close(); }
        List<String> rows = rows(log);
        Assert.assertEquals(rows.size(), 8);
        for (int pair = 0; pair < rows.size(); pair += 2) {
            Assert.assertEquals(withoutTimestamp(rows.get(pair)), withoutTimestamp(rows.get(pair + 1)));
        }
        Assert.assertFalse(rows.get(0).contains("<script"));
        Assert.assertTrue(rows.get(0).contains(HTMLGenerator.escapeWithWhitespaceMarkers(hostile)));
        for (String row : rows) Assert.assertEquals(occurrences(row, "<td>"), 6, row);
    }

    @Test
    public void linksInsideTheLogFolderStayRelative() throws Exception {
        Path root = directories.create("external-inside");
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
        Path root = directories.create("external-outside");
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
                + "target='_blank' rel='noopener'>report &#39; &amp; # (1).txt</a></div>"), row);
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
        Path root = directories.create("external-hostile");
        Path log = root.resolve("logs/log.html");
        String name = WINDOWS ? "report ' & #.txt" : "<script>alert('x')\"q\".txt";
        Path attachment = text(root.resolve("ext/" + name), "attachment");
        String message = "<script>alert(1)</script> <a href='x'>message</a>";
        CustomLogger logger = new CustomLogger("external-hostile", log.toString(), 100);
        try { logger.logExternal("ERROR", message, "source", null, attachment.toString()); }
        finally { logger.close(); }
        String row = rows(log).get(0);
        Assert.assertFalse(row.contains("<script"), row);
        Assert.assertEquals(occurrences(row, "<a "), 1, "only the attachment is a link");
        Assert.assertEquals(occurrences(row, "<td>"), 6);
        Assert.assertTrue(row.contains("<div class='message'>" + HTMLGenerator.escapeWithWhitespaceMarkers(message) + "</div><div><a href='"), row);
        Matcher link = ATTACHMENT_LINK.matcher(row);
        Assert.assertTrue(link.find(), row);
        Assert.assertEquals(link.group(2), WINDOWS ? "report &#39; &amp; #.txt" : "&lt;script&gt;alert(&#39;x&#39;)&quot;q&quot;.txt");
        Assert.assertEquals(StringEscapeUtils.unescapeHtml4(link.group(2)), name);
        Assert.assertFalse(link.group(1).contains("'") || link.group(1).contains("\"") || link.group(1).contains("<"));
        Assert.assertEquals(resolveLink(log.getParent(), link.group(1)), attachment);
        // The viewer script only handles [data-vars], .img-link and .more, so a plain anchor keeps its native click.
        // The row has no screenshot and no variables: any of these two names in it would come from the attachment.
        Assert.assertFalse(row.contains("img-link") || row.contains("data-vars"), row);
    }

    @Test
    public void namesKeepNonAsciiCharactersAndEscapeTheApostrophe() throws Exception {
        Path root = directories.create("external-utf8-names");
        Path log = root.resolve("logs/log.html");
        Path attachment = text(root.resolve("ext/Zo\u00eb's file.txt"), "attachment");
        Map<String, Object> entry = ordinaryEntry("variables", "Zo\u00eb's bot");
        entry.put(CustomHTMLLayout.Columns.VARIABLES, Map.of("Zo\u00eb's file", new StringValue("value")));
        CustomLogger logger = new CustomLogger("external-utf8-names", log.toString(), 100);
        try {
            logger.getLogger().info(entry);
            logger.logExternal("INFO", "attachment", "source", null, attachment.toString());
        } finally { logger.close(); }
        String html = CompressedSnapshots.expand(Files.readString(log, StandardCharsets.UTF_8));
        Assert.assertTrue(html.contains("<td>Zo\u00eb&#39;s bot<td>"), html);
        Assert.assertTrue(html.contains("<div class='variable-name'>Zo\u00eb&#39;s file</div>"), html);
        Assert.assertTrue(html.contains("rel='noopener'>Zo\u00eb&#39;s file.txt</a></div>"), html);
        Assert.assertFalse(Pattern.compile("&(?!amp;|lt;|gt;|quot;)[A-Za-z][A-Za-z0-9]*;").matcher(html).find(),
                "no named entity: " + html);
    }

    @Test
    public void aNameWithAColonBeforeTheFirstSlashCannotBecomeAUriScheme() throws Exception {
        if (WINDOWS) throw new SkipException("A colon cannot be part of a Windows file name");
        Path logDirectory = directories.create("external-colon");
        Path log = logDirectory.resolve("log.html");
        // The first two names are legal schemes; the others are not (a digit first, a space and a percent sign).
        String[] names = {"javascript:alert(1).txt", "1:x.txt", "12:30.txt", "Invoice 42: final.pdf"};
        String[] hrefs = {"./javascript:alert%281%29.txt", "./1:x.txt", "./12:30.txt", "./Invoice%2042:%20final.pdf"};
        Path shot = png(logDirectory.resolve("data:x.png"));
        CustomLogger logger = new CustomLogger("external-colon", log.toString(), 100);
        try {
            for (String name : names) {
                logger.logExternal("INFO", name, "source", shot.toString(), text(logDirectory.resolve(name), "attachment").toString());
            }
        } finally { logger.close(); }
        List<String> rows = rows(log);
        Assert.assertEquals(rows.size(), names.length, "a name that is not a legal scheme must not lose its entry");
        for (int index = 0; index < names.length; index++) {
            String row = rows.get(index);
            Assert.assertTrue(row.contains("<div><a href='" + hrefs[index] + "' "), row);
            Assert.assertTrue(row.contains("<a href='./data:x.png' target='_blank' class='img-link'"), row);
            Assert.assertFalse(row.contains("href='" + names[index].substring(0, names[index].indexOf(':'))), row);
            Assert.assertFalse(row.contains("href='data:"), row);
            Assert.assertEquals(resolveLink(logDirectory, hrefs[index]), logDirectory.resolve(names[index]));
        }
    }

    @Test
    public void aFileNameKeepsItsUnicodeFormInTheHref() throws Exception {
        // The same letter in two forms: a precomposed e-acute and an e followed by a combining accent.
        // They are two different files on most file systems, so each link must keep its own bytes.
        Path dir = directories.create("external-unicode");
        Path composed = dir.resolve("\u00e9.txt"), decomposed = dir.resolve("e\u0301.txt");
        Assert.assertTrue(HTMLGenerator.getAttachmentHTML(composed.toString(), dir).contains("<a href='%C3%A9.txt' "));
        Assert.assertTrue(HTMLGenerator.getAttachmentHTML(decomposed.toString(), dir).contains("<a href='e%CC%81.txt' "));
        // Through a real log entry: the written file name and the link name are the same bytes.
        Path log = dir.resolve("log.html");
        Path attachment = text(decomposed, "attachment");
        CustomLogger logger = new CustomLogger("external-unicode", log.toString(), 100);
        try { logger.logExternal("INFO", "decomposed", "source", null, attachment.toString()); }
        finally { logger.close(); }
        String row = rows(log).get(0);
        Assert.assertTrue(row.contains("<div><a href='e%CC%81.txt' "), row);
        Assert.assertEquals(resolveLink(dir, "e%CC%81.txt"), attachment);
    }

    @Test
    public void anAttachmentOnAnotherDriveBecomesAFileUri() {
        if (!WINDOWS) throw new SkipException("Drive letters exist only on Windows");
        // getAttachmentHTML does no file I/O, so the drives do not have to exist.
        String html = HTMLGenerator.getAttachmentHTML("Z:\\ext dir\\report ' #.txt", Path.of("C:\\logs"));
        Assert.assertTrue(html.startsWith("<div><a href='file:///Z:/ext%20dir/report%20%27%20%23.txt' "), html);
        Assert.assertTrue(html.endsWith(">report &#39; #.txt</a></div>"), html);
    }

    @Test
    public void invalidPathsAreRejectedWithoutWritingOrChangingFiles() throws Exception {
        Path root = directories.create("external-paths");
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
        Path root = directories.create("external-closed");
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
    public void splitLogsReceiveEachExternalLevelInTheirOwnFileWithLinksRelativeToIt() throws Exception {
        Path root = directories.create("external-split");
        Map<Level, String> outputs = Map.of(Level.INFO, root.resolve("info/log.html").toString(),
                Level.WARN, root.resolve("warn/log.html").toString(),
                Level.ERROR, root.resolve("error/log.html").toString());
        Path shot = png(root.resolve("ext/shot.png"));
        Path attachment = text(root.resolve("ext/report.txt"), "attachment");
        CustomLogger logger = new CustomLogger("external-split", outputs, 100);
        try {
            logger.logExternal("INFO", "info entry", "source", shot.toString(), attachment.toString());
            logger.logExternal("warn", "warn entry", "source", null, attachment.toString());
            logger.logExternal("ERROR", "error entry", "source", shot.toString(), null);
            logger.logExternal("INFO", "second info entry", "source", null, null);
        } finally { logger.close(); }
        List<String> info = rows(Path.of(outputs.get(Level.INFO)));
        List<String> warn = rows(Path.of(outputs.get(Level.WARN)));
        List<String> error = rows(Path.of(outputs.get(Level.ERROR)));
        Assert.assertEquals(info.size(), 2);
        Assert.assertEquals(warn.size(), 1);
        Assert.assertEquals(error.size(), 1);
        Assert.assertTrue(info.get(0).contains("info entry") && info.get(1).contains("second info entry"));
        Assert.assertTrue(warn.get(0).startsWith("<tr class='log-entry level-WARN'>") && warn.get(0).contains("warn entry"));
        Assert.assertTrue(error.get(0).startsWith("<tr class='log-entry level-ERROR'>") && error.get(0).contains("error entry"));
        // Each file links relative to its own folder, not to the first file's folder.
        Assert.assertTrue(info.get(0).contains("<a href='../ext/shot.png' target='_blank' class='img-link'"), info.get(0));
        Assert.assertTrue(info.get(0).contains("<div><a href='../ext/report.txt' "), info.get(0));
        Assert.assertTrue(warn.get(0).contains("<div><a href='../ext/report.txt' "), warn.get(0));
        Assert.assertTrue(error.get(0).contains("<a href='../ext/shot.png' target='_blank' class='img-link'"), error.get(0));
        for (Level level : List.of(Level.INFO, Level.WARN, Level.ERROR)) {
            String other = Files.readString(Path.of(outputs.get(level)));
            for (String message : List.of("info entry", "warn entry", "error entry")) {
                Assert.assertEquals(other.contains(message), message.startsWith(level.name().toLowerCase()), level + " " + message);
            }
        }
    }

    @Test
    public void closeWaitingForHeliosDoesNotBlockLoggingCallsOnOtherThreads() throws Exception {
        Path root = directories.create("external-close-race");
        Path log = root.resolve("log.html");
        String previous = System.getProperty("helios.outbox.directory");
        System.setProperty("helios.outbox.directory", Files.createDirectories(root.resolve("outbox")).toString());
        List<Socket> accepted = new CopyOnWriteArrayList<>();
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            // A server that takes every connection and never answers: close() has to wait for Helios.
            Thread acceptor = new Thread(() -> {
                try { while (true) accepted.add(silent.accept()); }
                catch (IOException closed) { /* the test is over */ }
            }, "silent-helios");
            acceptor.setDaemon(true);
            acceptor.start();
            HeliosConfig config = new HeliosConfig("http://127.0.0.1:" + silent.getLocalPort(), "test-key", "external-aa",
                    "bot", "master", "file", "machine", "user", null);
            CustomLogger logger = new CustomLogger("external-close-race", log.toString(), 100, 0, new HashSet<>(),
                    EncodingMode.FAST, config);
            Thread closer = new Thread(logger::close, "closer");
            try {
                closer.start();
                // close() sets its closed flag first and then waits for the Helios sender (Thread.join with a limit).
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (closer.getState() != Thread.State.TIMED_WAITING) {
                    Assert.assertTrue(closer.isAlive() && System.nanoTime() < deadline, "close() never started to wait for Helios");
                    Thread.sleep(10);
                }
                long start = System.nanoTime();
                Assert.assertNotNull(logger.getLogger());
                IllegalStateException refused = Assert.expectThrows(IllegalStateException.class,
                        () -> logger.logExternal("INFO", "late entry", "source", null, null));
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                Assert.assertEquals(refused.getMessage(), "Logger session is closed");
                Assert.assertTrue(closer.isAlive(), "close() was still waiting while these calls ran");
                // close() waits about 5 s for the sender. A call that waited for close() would take about that long.
                Assert.assertTrue(elapsedMillis < 2500, "the calls waited " + elapsedMillis + " ms for close()");
            } finally {
                closer.join(TimeUnit.SECONDS.toMillis(60));
                for (Socket socket : accepted) socket.close();
            }
            Assert.assertFalse(closer.isAlive(), "close() ended");
            Assert.assertTrue(logger.isClosed());
        } finally {
            if (previous == null) System.clearProperty("helios.outbox.directory");
            else System.setProperty("helios.outbox.directory", previous);
        }
        Assert.assertFalse(Files.readString(log).contains("late entry"));
    }

    @Test
    public void onlyExternalErrorEntriesCaptureAndAFailedStillDoesNotThrow() throws Exception {
        Path root = directories.create("external-capture");
        Path log = root.resolve("logs/log.html");
        Path shot = png(root.resolve("ext/shot.png"));
        Path attachment = text(root.resolve("ext/file.txt"), "attachment");
        RecordingLogger logger = new RecordingLogger("external-capture", log.toString(), root.resolve("logs/clips/clip.mp4"));
        try {
            // A file in place of the screenshot folder makes every desktop still fail.
            Path screenshots = Path.of(logger.getScreenshotFolderPath(Level.ERROR));
            Files.delete(screenshots);
            Files.writeString(screenshots, "not a folder");
            for (String level : new String[] {"INFO", "WARN", "ERROR"}) {
                logger.logExternal(level, level + " entry", "source", shot.toString(), attachment.toString());
            }
        } finally { logger.close(); }
        Assert.assertEquals(logger.recorderCalls, List.of("shouldRecordVideoFor(ERROR)", "snapshotForError"),
                "only the ERROR entry asks the recorder");
        List<String> rows = rows(log);
        Assert.assertEquals(rows.size(), 3);
        for (String row : rows.subList(0, 2)) {
            Assert.assertTrue(row.contains("<a href='../ext/shot.png' target='_blank' class='img-link'"), row);
            Assert.assertFalse(row.contains("video-link") || row.contains("video-unavailable"), row);
        }
        // The clip wins over the supplied screenshot. The still failed, so the clip has no poster.
        Assert.assertTrue(rows.get(2).contains("<a href='clips/clip.mp4' target='_blank' class='img-link video-link' "), rows.get(2));
        Assert.assertFalse(rows.get(2).contains("shot.png"), rows.get(2));
        Assert.assertTrue(rows.get(2).contains("<div><a href='../ext/file.txt' "), rows.get(2));
    }

    @Test
    public void heliosGetsExternalWarnAndErrorButNotExternalInfoWithContiguousOrdinals() throws Exception {
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            Path log = directories.create("external-helios").resolve("log.html");
            CustomLogger logger = new CustomLogger("external-helios", log.toString(), 100, 0, new HashSet<>(),
                    EncodingMode.FAST, server.config("external-helios"));
            try {
                logger.getLogger().info(ordinaryEntry("own info 1", "bot"));
                logger.logExternal("INFO", "external info 1", "other", null, null);
                logger.logExternal("WARN", "external warn", "other", null, null);
                logger.getLogger().warn(ordinaryEntry("own warn", "bot"));
                logger.logExternal("info", "external info 2", "other", null, null);
                logger.logExternal("ERROR", "external error", "other", null, null);
                logger.getLogger().info(ordinaryEntry("own info 2", "bot"));
                logger.logExternal("INFO", "external info 3", "other", null, null);
            } finally { logger.close(); }
            Map<Integer, JSONObject> sent = server.rows(logger.getLoggerId());
            Assert.assertEquals(new ArrayList<>(sent.keySet()), List.of(0, 1, 2, 3, 4));
            Assert.assertEquals(sent.values().stream().map(row -> row.getString("message")).collect(Collectors.toList()),
                    List.of("own info 1", "external warn", "own warn", "external error", "own info 2"));
            Assert.assertEquals(sent.get(1).getString("source"), "other");
            Assert.assertEquals(server.requests("/end").get(0).json().getInt("expectedEntries"), 5);
            Assert.assertEquals(server.verified, List.of("external-helios"), "Helios confirmed every ordinal");
            Assert.assertEquals(rows(log).size(), 8, "the HTML log keeps every entry");
        }
    }

    @Test
    public void externalErrorSendsAScreenshotDeclarationToHeliosAndWarnSendsNone() throws Exception {
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            Path root = directories.create("external-error-media");
            Path log = root.resolve("logs/log.html");
            Path shot = png(root.resolve("ext/shot.png"));
            CustomLogger logger = new CustomLogger("external-error-media", log.toString(), 100, 0, new HashSet<>(),
                    EncodingMode.FAST, server.config("external-error-media"));
            try {
                logger.logExternal("WARN", "external warn", "other", shot.toString(), null);
                logger.logExternal("ERROR", "external error", "other", shot.toString(), null);
            } finally { logger.close(); }
            Map<Integer, JSONObject> sent = server.rows(logger.getLoggerId());
            Assert.assertEquals(sent.size(), 2);
            Assert.assertFalse(sent.get(0).has("media"), "WARN never captures");
            JSONObject error = sent.get(1);
            Assert.assertEquals(error.getString("screenPath"), "");
            Assert.assertEquals(error.getJSONArray("media").length(), 1);
            Assert.assertEquals(error.getJSONArray("media").getJSONObject(0).getString("kind"), "screenshot");
            Assert.assertTrue(rows(log).get(1).contains("<a href='../ext/shot.png' target='_blank' class='img-link'"));
        }
    }

    @Test
    public void externalErrorWithAFailedStillIsWrittenAndHeliosGetsTheFailureNotTheCallerFile() throws Exception {
        try (HeliosStubServer server = HeliosStubServer.withMedia()) {
            Path root = directories.create("external-error-failure");
            Path log = root.resolve("logs/log.html");
            Path shot = png(root.resolve("ext/shot.png"));
            CustomLogger logger = new CustomLogger("external-error-failure", log.toString(), 100, 0, new HashSet<>(),
                    EncodingMode.FAST, server.config("external-error-failure"));
            try {
                Path screenshots = Path.of(logger.getScreenshotFolderPath(Level.ERROR));
                Files.delete(screenshots);
                Files.writeString(screenshots, "not a folder");
                logger.logExternal("ERROR", "external error", "other", shot.toString(), null);
            } finally { logger.close(); }
            // The caller's screenshot is never staged for Helios, so the failed still is all Helios hears of.
            JSONObject media = server.rows(logger.getLoggerId()).get(0).getJSONArray("media").getJSONObject(0);
            Assert.assertEquals(media.getString("kind"), "screenshot");
            Assert.assertEquals(media.getString("state"), "unavailable");
            Assert.assertEquals(media.getString("reason"), "captureFailed");
            Assert.assertEquals(rows(log).size(), 1);
            Assert.assertTrue(rows(log).get(0).contains("<a href='../ext/shot.png' target='_blank' class='img-link'"));
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

    private static Log4jLogEvent event(Map<String, Object> row) {
        return Log4jLogEvent.newBuilder().setLoggerName("test").setLevel(Level.ERROR).setTimeMillis(1_762_000_000_000L)
                .setMessage(new ObjectMessage(row)).build();
    }

    /**
     * A logger set up to record video at every level whose recorder always looks healthy. The recorder behind it is
     * disabled on a host without a desktop, so the calls that reach the recorder API are counted here instead,
     * and each snapshot gives the same finished clip.
     */
    private static final class RecordingLogger extends CustomLogger {
        final List<String> recorderCalls = new CopyOnWriteArrayList<>();
        private final Path clip;

        RecordingLogger(String name, String path, Path clip) throws IOException {
            super(name, path, 100, 0, Set.of(Level.INFO, Level.WARN, Level.ERROR), EncodingMode.FAST, null);
            this.clip = clip;
        }

        @Override public boolean shouldRecordVideoFor(Level level) {
            recorderCalls.add("shouldRecordVideoFor(" + level + ")");
            return true;
        }

        @Override public CaptureResult snapshotForError(String captureId, Consumer<CaptureResult> register) {
            recorderCalls.add("snapshotForError");
            try {
                Constructor<CaptureResult> constructor = CaptureResult.class.getDeclaredConstructor(
                        String.class, Path.class, Path.class, String.class);
                constructor.setAccessible(true);
                CaptureResult capture = constructor.newInstance(captureId, clip, null, null);
                register.accept(capture);
                return capture;
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }
    }
}
