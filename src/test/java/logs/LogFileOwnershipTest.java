package logs;

import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import support.LogEntries;
import support.TempDirectories;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * How {@code CustomLogger} owns an HTML log: one owner for each real file, a lock file beside it, and a
 * notice file that a rejected process leaves for the owner. Tests that need a second process are in
 * {@code LoggerOutputIntegrityTest}.
 */
public class LogFileOwnershipTest {
    private static final String LOCK = ".a360.lock";
    private static final String NOTICE = ".a360-contention";
    private static final String WARNING = "Another process was rejected";

    private final TempDirectories directories = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void deleteDirectories() {
        directories.deleteAll();
    }

    private static Map<String, Object> entry(String message) {
        return LogEntries.entry(message, "ownership");
    }

    private static Path sidecar(Path log, String suffix) {
        return log.resolveSibling(log.getFileName() + suffix);
    }

    @Test
    public void aSymlinkedLogFolderSharesTheOwnerOfItsRealFolderAndKeepsMediaLinksRelative() throws Exception {
        Path root = directories.create("owner-symlink");
        Path real = Files.createDirectories(root.resolve("real"));
        Path alias = root.resolve("alias");
        try {
            Files.createSymbolicLink(alias, real);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            throw new SkipException("This host cannot create symbolic links: " + unavailable);
        }
        Path shot = ExternalLogTest.png(alias.resolve("screenshots/shot.png"));
        Path log = real.resolve("log.html");
        CustomLogger byRealPath = new CustomLogger("owner-real", log.toString(), 100);
        CustomLogger byAlias = new CustomLogger("owner-alias", alias.resolve("log.html").toString(), 100);
        try {
            byRealPath.logExternal("INFO", "written through the real path", "source", null, null);
            byAlias.logExternal("INFO", "written through the alias", "source", shot.toString(), null);
        } finally {
            byAlias.close();
            byRealPath.close();
        }
        List<String> rows = ExternalLogTest.rows(log);
        Assert.assertEquals(rows.size(), 2);
        Assert.assertTrue(rows.get(0).contains("written through the real path"));
        Assert.assertTrue(rows.get(1).contains("written through the alias"));
        // The link stays relative to the folder the caller named, so it works when the folder is moved.
        Assert.assertTrue(rows.get(1).contains("<a href='screenshots/shot.png' target='_blank' class='img-link'"), rows.get(1));
        Assert.assertFalse(Files.exists(sidecar(log, NOTICE)));
        Assert.assertFalse(Files.exists(real.resolve("1_log.html")));
        Assert.assertEquals(Files.readString(log).split("<!DOCTYPE html>", -1).length - 1, 1);
    }

    @Test
    public void dotAndDotDotSpellingsOfOneFileShareOneOwner() throws Exception {
        Path dir = directories.create("owner-dot");
        Path log = dir.resolve("log.html");
        CustomLogger plain = new CustomLogger("owner-plain", log.toString(), 100);
        CustomLogger dotted = new CustomLogger("owner-dot", dir.resolve(".").resolve("log.html").toString(), 100);
        CustomLogger parent = new CustomLogger("owner-parent", dir.resolve("other").resolve("..").resolve("log.html").toString(), 100);
        try {
            plain.getLogger().info(entry("plain"));
            dotted.getLogger().info(entry("dotted"));
            parent.getLogger().info(entry("parent"));
        } finally {
            parent.close();
            dotted.close();
            plain.close();
        }
        Assert.assertEquals(LogEntries.count(log), 3L);
        Assert.assertFalse(Files.exists(sidecar(log, NOTICE)));
        Assert.assertFalse(Files.exists(dir.resolve("1_log.html")));
    }

    @DataProvider
    public Object[][] archiveNumbers() {
        return new Object[][] {
                {"1234567890", "1_log.html"},          // ten digits: not one of the logger's archives
                {"2147483647", "1_log.html"},          // Integer.MAX_VALUE: one more would overflow
                {"99999999999", "1_log.html"},         // does not fit in an int
                {"999999999", "1000000000_log.html"},  // nine digits: still counted
        };
    }

    @Test(dataProvider = "archiveNumbers")
    public void onlyArchiveNumbersOfNineDigitsOrFewerAreCounted(String existing, String expectedArchive) throws Exception {
        Path dir = directories.create("owner-prefix");
        Path log = dir.resolve("log.html");
        byte[] legacy = "<!DOCTYPE html><html>legacy log</html>".getBytes(StandardCharsets.UTF_8);
        Files.write(log, legacy);
        Path other = Files.writeString(dir.resolve(existing + "_log.html"), "older file");
        CustomLogger logger = new CustomLogger("owner-prefix", log.toString(), 100);
        try { logger.getLogger().info(entry("after the move")); }
        finally { logger.close(); }
        Assert.assertEquals(Files.readAllBytes(dir.resolve(expectedArchive)), legacy);
        Assert.assertEquals(Files.readString(other), "older file");
        Assert.assertEquals(LogEntries.count(log), 1L);
    }

    @Test
    public void aNoticeLeftByAnEarlierRunIsDeletedAtStartAndNeverReported() throws Exception {
        Path dir = directories.create("owner-stale-notice");
        Path log = dir.resolve("log.html");
        Path notice = Files.createFile(sidecar(log, NOTICE));
        CustomLogger logger = new CustomLogger("owner-stale-notice", log.toString(), 100);
        try {
            Assert.assertFalse(Files.exists(notice), "the new owner holds the lock, so the old notice is not about it");
            logger.getLogger().info(entry("after start"));
        } finally { logger.close(); }
        Assert.assertFalse(Files.readString(log).contains(WARNING));
        Assert.assertEquals(LogEntries.count(log), 1L);
    }

    @Test
    public void aLockFileProblemThatIsNotContentionStillStartsTheLoggerAndIsReported() throws Exception {
        Path dir = directories.create("owner-lock-error");
        Path log = dir.resolve("log.html");
        // A folder cannot be opened as a lock file. That is an error, but no other process owns the log.
        Files.createDirectory(sidecar(log, LOCK));
        StatusCapture status = new StatusCapture(dir.getFileName().toString());
        StatusLogger.getLogger().registerListener(status);
        CustomLogger first = null, second = null;
        try {
            first = new CustomLogger("owner-lock-error-1", log.toString(), 100);
            // Without a lock file this JVM still keeps one owner for the real file.
            second = new CustomLogger("owner-lock-error-2", log.toString(), 100);
            first.getLogger().info(entry("first"));
            second.getLogger().info(entry("second"));
        } finally {
            StatusLogger.getLogger().removeListener(status);
            if (second != null) second.close();
            if (first != null) first.close();
        }
        Assert.assertEquals(LogEntries.count(log), 2L);
        Assert.assertEquals(status.messages.size(), 1, "reported once, by the session that tried to lock: " + status.messages);
        Assert.assertTrue(status.messages.get(0).startsWith("ERROR Cannot lock HTML log "), status.messages.get(0));
        Assert.assertTrue(status.messages.get(0).contains("Another process can write it at the same time."));
        Assert.assertFalse(Files.exists(sidecar(log, NOTICE)));
        Assert.assertTrue(Files.isDirectory(sidecar(log, LOCK)));
    }

    @Test
    public void aLockHeldInsideThisProcessIsNotReportedAsAnotherProcess() throws Exception {
        Path dir = directories.create("owner-overlap");
        Path log = dir.resolve("log.html");
        // A lock this JVM holds in a way the logger cannot see, for example from another copy of its class.
        try (FileChannel foreign = FileChannel.open(sidecar(log, LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock held = foreign.tryLock()) {
            Assert.assertNotNull(held);
            IOException failure = Assert.expectThrows(IOException.class,
                    () -> new CustomLogger("owner-overlap", log.toString(), 100));
            Assert.assertTrue(failure.getMessage().contains("already open in this process"), failure.getMessage());
            Assert.assertFalse(failure.getMessage().contains("Another process"), failure.getMessage());
            Assert.assertFalse(Files.exists(sidecar(log, NOTICE)), "no other process was rejected, so no notice is left");
        }
    }

    @Test
    public void aSplitStartThatFailsAfterTakingItsLocksReleasesEveryOne() throws Exception {
        Path dir = directories.create("owner-failed-split");
        Path info = dir.resolve("info.html"), warn = dir.resolve("warn.html");
        // A folder cannot be an HTML log. The start takes the locks of all three files first, then fails here.
        Path error = Files.createDirectory(dir.resolve("error.html"));
        Map<Level, String> outputs = Map.of(Level.INFO, info.toString(), Level.WARN, warn.toString(), Level.ERROR, error.toString());
        Assert.expectThrows(IOException.class, () -> new CustomLogger("owner-failed-split", outputs, 100));
        for (Path file : List.of(info, warn, error)) {
            // This JVM refuses a second lock on a file it still holds one on.
            try (FileChannel probe = FileChannel.open(sidecar(file, LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = probe.tryLock()) {
                Assert.assertNotNull(lock, file + " is locked by another process");
            } catch (OverlappingFileLockException stillHeld) {
                Assert.fail("The failed start kept the lock of " + file);
            }
        }
        Assert.assertFalse(Files.exists(sidecar(info, NOTICE)));
    }

    @Test
    public void aNoticeIsLookedForAtMostOncePerSecond() throws Exception {
        Path dir = directories.create("owner-throttle");
        Path log = dir.resolve("log.html");
        Path notice = sidecar(log, NOTICE);
        CustomLogger logger = new CustomLogger("owner-throttle", log.toString(), 100);
        try {
            // The constructor starts a one-second window. Wait it out: the next call looks and finds nothing.
            Thread.sleep(2000);
            long start = System.nanoTime();
            logger.getLogger();
            Files.createFile(notice);
            for (int call = 0; call < 1000; call++) logger.getLogger();
            long windowMillis = (System.nanoTime() - start) / 1_000_000;
            if (windowMillis >= 800) throw new SkipException("This host needed " + windowMillis + " ms for 1000 calls");
            Assert.assertTrue(Files.exists(notice), "calls inside one second do not look for the notice again");
        } finally { logger.close(); }
    }

    @Test
    public void closeAlwaysLooksForANotice() throws Exception {
        Path dir = directories.create("owner-close-notice");
        Path log = dir.resolve("log.html");
        Path notice = sidecar(log, NOTICE);
        CustomLogger logger = new CustomLogger("owner-close-notice", log.toString(), 100);
        Files.createFile(notice);
        logger.close();
        Assert.assertFalse(Files.exists(notice));
        Assert.assertTrue(Files.readString(log).contains(WARNING));
    }

    /** Collects the status messages that name a log in one folder. */
    private static final class StatusCapture implements StatusListener {
        final List<String> messages = new CopyOnWriteArrayList<>();
        private final String folderName;

        StatusCapture(String folderName) { this.folderName = folderName; }

        @Override public void log(StatusData data) {
            String message = data.getMessage().getFormattedMessage();
            if (message.contains(folderName)) messages.add(data.getLevel() + " " + message);
        }

        @Override public Level getStatusLevel() { return Level.ERROR; }

        @Override public void close() { }
    }
}
