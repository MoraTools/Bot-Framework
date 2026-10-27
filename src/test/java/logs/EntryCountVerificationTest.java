package logs;

import com.automationanywhere.botcommand.actions.logs.LogMessage;
import com.automationanywhere.botcommand.actions.logs.StopLoggerSession;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import org.apache.logging.log4j.Logger;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import org.testng.Assert;
import support.LogEntries;
import support.TempDirectories;
import support.TestLogMessage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Test to verify exact entry count before rotation
 */
public class EntryCountVerificationTest {

    private final TempDirectories directories = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void deleteDirectories() {
        directories.deleteAll();
    }

    @Test
    public void testExactEntryCount() throws IOException {
        Path testPath = directories.create("entry-count-verify");

        Path logFilePath = testPath.resolve("exact-count.html");
        int maxEntries = 5;

        CustomLogger customLogger = new CustomLogger("ExactCountLogger_" + UUID.randomUUID(),
                                                      logFilePath.toString(), maxEntries);
        Logger logger = customLogger.getLogger();

        // Log exactly maxEntries
        for (int i = 1; i <= maxEntries; i++) {
            logger.info(LogEntries.entry("Entry " + i + " of " + maxEntries, "Test/ExactCount"));
        }

        // At this point, file should NOT have rotated yet
        File originalFile = logFilePath.toFile();
        // New pattern: 1_basename.html
        File rotatedFile = testPath.resolve("1_exact-count.html").toFile();

        Assert.assertTrue(originalFile.exists(), "Original file should exist");
        Assert.assertFalse(rotatedFile.exists(),
            "Rotation should NOT occur yet - we've logged exactly " + maxEntries + " entries");

        // Log one more entry to trigger rotation
        logger.info(LogEntries.entry("Entry " + (maxEntries + 1) + " - This triggers rotation", "Test/ExactCount"));

        customLogger.close();

        // Now rotation should have occurred
        Assert.assertTrue(originalFile.exists(), "Original file should still exist");
        Assert.assertTrue(rotatedFile.exists(),
            "Rotation SHOULD occur after " + (maxEntries + 1) + " entries");

        // Verify the rotated file has exactly maxEntries entries
        String rotatedContent = new String(Files.readAllBytes(rotatedFile.toPath()));

        // Count the number of log entries in the rotated file
        int entryCount = 0;
        for (int i = 1; i <= maxEntries; i++) {
            if (rotatedContent.contains("Entry " + i + " of " + maxEntries)) {
                entryCount++;
            }
        }

        Assert.assertEquals(entryCount, maxEntries,
            "Rotated file should contain exactly " + maxEntries + " entries");
        Assert.assertEquals(LogEntries.count(rotatedFile.toPath()), maxEntries);

        // The new file should contain the trigger entry
        String currentContent = new String(Files.readAllBytes(originalFile.toPath()));
        Assert.assertTrue(currentContent.contains("This triggers rotation"),
            "Current file should contain the entry that triggered rotation");
    }

    @Test
    public void testScreenshotPathInHTML() throws Exception {
        // Drive the real production flow: LogMessage.action(captureScreenshot=true)
        // captures the desktop via CaptureScreen, writes the PNG, and emits the
        // screenshot column. The test asserts the captured file exists and the
        // generated HTML references it via the expected relative path.
        Path testPath = directories.create("screenshot-path");

        Path logFilePath = testPath.resolve("screenshot-test.html");
        CustomLogger customLogger = new CustomLogger("ScreenshotLogger_" + UUID.randomUUID(),
                                                      logFilePath.toString(), 10);

        LogMessage logMessage = TestLogMessage.create();

        logMessage.action(
            customLogger,
            "INFO",
            "Test message with screenshot",
            true,
            "NO",
            null
        );

        new StopLoggerSession().stop(customLogger);

        File[] screenshots = testPath.resolve("screenshots").toFile().listFiles((dir, name) -> name.endsWith(".png"));
        Assert.assertNotNull(screenshots, "Screenshots directory should contain a captured PNG");
        Assert.assertEquals(screenshots.length, 1, "Exactly one screenshot should have been captured");
        Assert.assertTrue(screenshots[0].length() > 0, "Captured screenshot file should have content");

        String capturedFileName = screenshots[0].getName();
        String htmlContent = new String(Files.readAllBytes(logFilePath));

        Assert.assertTrue(htmlContent.contains("screenshots/" + capturedFileName),
            "HTML should reference the captured screenshot via the relative 'screenshots/' path");
        Assert.assertFalse(htmlContent.contains(testPath.toString()),
            "the log has no absolute path, so it still works when its folder is moved");
        Assert.assertTrue(htmlContent.contains("class='img-link'"), "HTML should contain screenshot link");
        Assert.assertTrue(htmlContent.contains("<img"), "HTML should contain screenshot thumbnail image");
        Assert.assertTrue(htmlContent.contains("loading='lazy'"), "Screenshot image should lazy load");
        Assert.assertTrue(htmlContent.contains("decoding='async'"), "Screenshot image should decode asynchronously");
        Assert.assertTrue(htmlContent.contains("width='88'"), "Screenshot image should reserve thumbnail width");
        Assert.assertTrue(htmlContent.contains("height='50'"), "Screenshot image should reserve thumbnail height");
    }
}
