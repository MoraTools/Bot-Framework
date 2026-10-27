package logs;

import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import org.apache.logging.log4j.Logger;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import support.LogEntries;
import support.TempDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests that entry counting works correctly across logger sessions when appending to existing files.
 * Verifies that rollover happens at the correct count when combining existing + new entries.
 *
 * @author Sumit Kumar
 */
public class EntryCountWithExistingFileTest {

    private final TempDirectories directories = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void cleanup() {
        directories.deleteAll();
    }

    @Test(description = "Sessions that append to one file count its existing entries and roll over after the limit")
    public void testMultipleSessionsExactLimit() throws Exception {
        int maxEntries = 10;
        Path dir = directories.create("existing-file");
        Path logFilePath = dir.resolve("exact_limit.html");

        // Session 1: Write 4 entries
        writeEntries(logFilePath, maxEntries, 4, "Session1");
        Assert.assertEquals(LogEntries.count(logFilePath), 4, "After session 1: should have 4 entries");
        assertWellFormedStructure(logFilePath);

        // Session 2: Write 3 entries (total 7)
        writeEntries(logFilePath, maxEntries, 3, "Session2");
        Assert.assertEquals(LogEntries.count(logFilePath), 7, "After session 2: should have 7 entries");
        // Appending across sessions once left the previous session's
        // </tbody></table></body></html> in the middle of the file with
        // new entries orphaned after it. assertWellFormedStructure is the
        // regression guard.
        assertWellFormedStructure(logFilePath);

        // Session 3: Write 3 entries (total 10 - exactly at limit)
        writeEntries(logFilePath, maxEntries, 3, "Session3");
        Assert.assertEquals(LogEntries.count(logFilePath), 10, "After session 3: should have 10 entries");

        // Session 4: Write 1 entry (total 11 - should trigger rollover)
        writeEntries(logFilePath, maxEntries, 1, "Session4");

        // Check rollover
        Path rolledFile = dir.resolve("1_exact_limit.html");

        Assert.assertTrue(Files.exists(rolledFile), "Rollover should occur when exceeding limit by 1");
        Assert.assertEquals(LogEntries.count(rolledFile), 10, "the archive keeps the entries of the first three sessions");
        Assert.assertEquals(LogEntries.count(logFilePath), 1, "the new file starts with the entry that exceeded the limit");
        assertWellFormedStructure(rolledFile);
        assertWellFormedStructure(logFilePath);
    }

    /**
     * Helper method to write entries to a log file
     */
    private void writeEntries(Path logFilePath, int maxEntries, int count, String source) throws Exception {
        CustomLogger customLogger = new CustomLogger(
                "test-logger-" + source,
                logFilePath.toString(),
                maxEntries
        );

        Logger logger = customLogger.getLogger();

        for (int i = 1; i <= count; i++) {
            logger.info(LogEntries.entry("Entry " + i + " from " + source, "Test/" + source));
        }

        customLogger.close();
    }

    private int countHeaders(Path filePath) throws IOException {
        return (int) Files.readString(filePath).lines()
                .filter(line -> line.equals("<!-- a360-log-format:7 -->")).count();
    }

    private void assertWellFormedStructure(Path filePath) throws IOException {
        String html = Files.readString(filePath);
        Assert.assertEquals(countHeaders(filePath), 1, "one document header");
        Assert.assertTrue(html.endsWith("</tr>\n<!-- a360-log-entry:end -->\n"),
                "the final entry must be complete");
        Assert.assertEquals(html.split("<tr class='log-entry ", -1).length - 1, LogEntries.count(filePath));
        Assert.assertFalse(html.contains("</body>"), "no closing footer to trap later entries");
    }
}
