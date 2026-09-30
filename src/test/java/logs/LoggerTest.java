package logs;

import com.automationanywhere.botcommand.actions.logs.LogMessage;
import com.automationanywhere.botcommand.actions.logs.StartLoggerSession;
import com.automationanywhere.botcommand.actions.logs.StopLoggerSession;
import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.*;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import org.apache.commons.io.FileUtils;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Sumit Kumar
 */

public class LoggerTest {

    // Default log level constants
    private static final String COMMON_FILE_ALL_LEVEL = "COMMON_FILE";
    private static final String CONFIGURABLE_FILE_ALL_LEVEL = "CONFIGURABLE_FILE";
    private static final String LEVEL_INFO = "INFO";
    private static final String LEVEL_ERROR = "ERROR";
    private static final String LEVEL_WARN = "WARN";
    private static final String LOG_VARIABLE = "YES";
    private static final String DO_NOT_LOG_VARIABLE = "NO";

    private StartLoggerSession LoggerSession;
    private LogMessage logMessage;
    private StopLoggerSession stopLoggerSession;

    // Base paths for test artifacts
    private String baseTestPath;
    private String commonLogPath;
    private String infoLogPath;
    private String warnLogPath;
    private String errorLogPath;

    // Paths for multiple logger tests
    private String instance1Path;
    private String instance2Path;

    // Paths for different folder location tests
    private String nestedFolderPath;
    private String differentDrivePath;
    private String absolutePathLog;

    private Map<String, Value> sourceMap;
    private List<Value> entryList;

    @BeforeClass
    public void setUp() throws Exception {
        // Initialize test classes
        LoggerSession = new StartLoggerSession();
        logMessage = new LogMessage();
        stopLoggerSession = new StopLoggerSession();
        logMessage.setTestBotUri("Automation Anywhere/bots/path/to/test bot");

        // Create unique test directory with timestamp to avoid conflicts
        baseTestPath = "src/test/target/test-artifacts/logs-" + System.currentTimeMillis() + "/";

        // Ensure base directory exists
        Files.createDirectories(Paths.get(baseTestPath));

        // Set up file paths for testing
        commonLogPath = baseTestPath + "common_log.html";
        infoLogPath = baseTestPath + "info_log.html";
        warnLogPath = baseTestPath + "warn_log.html";
        errorLogPath = baseTestPath + "error_log.html";

        // Set up paths for multiple logger tests
        instance1Path = baseTestPath + "instance1.html";
        instance2Path = baseTestPath + "instance2.html";

        // Create nested folder structure for different location tests
        nestedFolderPath = baseTestPath + "nested/subfolder/";
        Files.createDirectories(Paths.get(nestedFolderPath));

        // For cross-drive testing, use a relative path that simulates a different location
        // (In a real environment, this might be a different drive like "D:/logs/")
        differentDrivePath = baseTestPath + "different_drive/logs/";
        Files.createDirectories(Paths.get(differentDrivePath));

        // Set up an absolute path log file
        absolutePathLog = baseTestPath + "absolute/path/log.html";
        Files.createDirectories(Paths.get(baseTestPath + "absolute/path/"));

        // Prepare test data
        setupTestData();
    }

    private void setupTestData() {
        // Create a source map with some variables representing variables sent via common variables
        sourceMap = new HashMap<>();
        sourceMap.put("string", new StringValue("  Hello, World! with spaces at end and front  "));
        sourceMap.put("number", new NumberValue(100));
        sourceMap.put("boolean", new BooleanValue(true));
        sourceMap.put("date", new DateTimeValue(ZonedDateTime.now()));

        // Create a list of dictionary values representing variables sent via entry list
        entryList = new ArrayList<>();

        // String variable
        Map<String, Value> variable1 = new HashMap<>();
        variable1.put("NAME", new StringValue("entrylist string"));
        variable1.put("VALUE", new StringValue("my string variable value from entry list"));
        entryList.add(new DictionaryValue(variable1));

        // Number variable
        Map<String, Value> variable2 = new HashMap<>();
        variable2.put("NAME", new StringValue("entrylist number"));
        variable2.put("VALUE", new NumberValue(456));
        entryList.add(new DictionaryValue(variable2));

        // List variable with nested dictionary
        Map<String, Value> variable3 = new HashMap<>();
        List<Value> listValue = new ArrayList<>();
        Map<String, Value> dictValue = new HashMap<>();
        dictValue.put("entrylist string in dictionary", new StringValue("dict string value"));
        dictValue.put("entrylist Date in dictionary", new DateTimeValue(ZonedDateTime.now()));
        dictValue.put("entrylist Number in dictionary", new NumberValue(100));
        ListValue lv = new ListValue();
        listValue.add(new StringValue("Item 1"));
        listValue.add(new StringValue("Item 2"));
        listValue.add(new StringValue("Item 3"));
        listValue.add(new DictionaryValue(dictValue));
        lv.set(listValue);
        variable3.put("NAME", new StringValue("entrylist list"));
        variable3.put("VALUE", lv);
        entryList.add(new DictionaryValue(variable3));

        // Table variable
        Map<String, Value> variable4 = new HashMap<>();
        List<Schema> schemalist = new ArrayList<>();
        List<Row> rowList = new ArrayList<>();
        schemalist.add(new Schema("col1 "));
        schemalist.add(new Schema("col2"));
        schemalist.add(new Schema("col 3"));

        rowList.add(new Row(new StringValue("r1c1"), new StringValue("r1c2"), new StringValue("r1c3")));
        rowList.add(new Row(new StringValue("r2c1"), new StringValue("r2c2"), new StringValue("r2c3")));
        rowList.add(new Row(new StringValue("r3c1"), new StringValue("r3c2"), new StringValue("r3c3")));
        rowList.add(new Row(new StringValue("r4c1"), new StringValue("r4c2"), new StringValue("r4c3")));
        rowList.add(new Row(new StringValue("r5c1"), new StringValue("r5c2"), new StringValue("r5c3")));
        rowList.add(new Row(new StringValue("r6c1"), new StringValue("r6c2"),
                new StringValue("r63            longer value with space at end    ")));
        rowList.add(new Row(new StringValue("r7c1"), new StringValue("r7c2"), new StringValue("r7c3")));
        TableValue tv = new TableValue();
        tv.set(new Table(schemalist, rowList));

        variable4.put("NAME", new StringValue("entrylist table variable"));
        variable4.put("VALUE", tv);
        entryList.add(new DictionaryValue(variable4));
    }

    @AfterClass
    public void tearDown() {
        // Add any cleanup code if needed
        System.out.println("Tests completed. Log files available at: " + baseTestPath);
    }

    /**
     * Returns the file count of a directory, treating non-existent directories
     * and I/O failures (where {@code listFiles()} returns null) as zero.
     */
    private static int countFiles(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        File[] files = dir.listFiles();
        return files != null ? files.length : 0;
    }

    @Test
    public void testCommonLoggerForAllLevels() throws Exception {
        // Test case: Common logger for all levels
        SessionValue sessionValue = LoggerSession.start(
                COMMON_FILE_ALL_LEVEL,
                commonLogPath,
                null, // These are not used for COMMON_FILE_ALL_LEVEL
                null,
                null,
                10,
                "NO_VIDEO", false, false, false, 30, "FAST", "HELIOS_DISABLED", null, null
        );

        // Ensure the session was initialized properly
        Assert.assertNotNull(sessionValue);
        CustomLogger logger = (CustomLogger) sessionValue.getSession();
        Assert.assertNotNull(logger);

        // Verify screenshots directory is created
        String screenshotsDir = Paths.get(baseTestPath, "screenshots").toString();
        Assert.assertTrue(Files.exists(Paths.get(screenshotsDir)), "Screenshots directory should be created");

        // Verify no variable directory is needed
        String variablesDir = Paths.get(baseTestPath, "variables").toString();
        Assert.assertFalse(Files.exists(Paths.get(variablesDir)), "Variables stay inside the log");

        // Log messages of different levels
        logMessage.action(logger, LEVEL_INFO, "Common logger: INFO message", true, LOG_VARIABLE, entryList, sourceMap);
        logMessage.action(logger, LEVEL_WARN, "Common logger: WARN message", true, LOG_VARIABLE, entryList, sourceMap);
        logMessage.action(logger, LEVEL_ERROR, "Common logger: ERROR message", true, LOG_VARIABLE, entryList,
                sourceMap);

        // Ensure log file is created
        Assert.assertTrue(Files.exists(Paths.get(commonLogPath)), "Log file should be created");

        // Read the log file content
        String content = CompressedSnapshots.expand(new String(Files.readAllBytes(Paths.get(commonLogPath))));

        // Verify screenshots were created
        File screenshotDir = new File(screenshotsDir);
        Assert.assertTrue(screenshotDir.exists() && screenshotDir.isDirectory());
        Assert.assertTrue(countFiles(screenshotDir) >= 3, "Should have at least 3 screenshots");

        Assert.assertFalse(Files.exists(Paths.get(variablesDir)));

        // Check for log messages
        Assert.assertTrue(content.contains("Common logger: INFO message"), "Log should contain INFO message");
        Assert.assertTrue(content.contains("Common logger: WARN message"), "Log should contain WARN message");
        Assert.assertTrue(content.contains("Common logger: ERROR message"), "Log should contain ERROR message");

        // Check if relative screenshot paths with appropriate level prefixes are used in HTML
        Assert.assertTrue(content.contains("screenshots/info_"), "Log should reference INFO screenshots with relative" +
                " paths");
        Assert.assertTrue(content.contains("screenshots/warn_"), "Log should reference WARN screenshots with relative" +
                " paths");
        Assert.assertTrue(content.contains("screenshots/error_"), "Log should reference ERROR screenshots with " +
                "relative paths");

        Assert.assertTrue(content.contains("<details class='entry-variables'>"));
        Assert.assertTrue(content.contains("<details class='full-width'>"), "table values are inline");
        Assert.assertTrue(content.contains("r7c3"), "last table cell is preserved");

        // Stop the logger
        stopLoggerSession.stop(logger);
    }

    @Test
    public void testSeparateLoggersForDifferentLevels() throws Exception {
        // Test case: Separate loggers for different levels
        SessionValue sessionValue = LoggerSession.start(
                CONFIGURABLE_FILE_ALL_LEVEL,
                null,
                infoLogPath,
                warnLogPath,
                errorLogPath,
                10,
                "NO_VIDEO", false, false, false, 30, "FAST", "HELIOS_DISABLED", null, null
        );

        // Ensure the session was initialized properly
        Assert.assertNotNull(sessionValue);
        CustomLogger logger = (CustomLogger) sessionValue.getSession();
        Assert.assertNotNull(logger);

        // Verify screenshots and variables directories are created for each log file location
        String infoScreenshotsDir = Paths.get(baseTestPath, "screenshots").toString();
        Assert.assertTrue(Files.exists(Paths.get(infoScreenshotsDir)), "Screenshots directory for INFO should be " +
                "created");

        String infoVariablesDir = Paths.get(baseTestPath, "variables").toString();
        Assert.assertFalse(Files.exists(Paths.get(infoVariablesDir)), "INFO variables stay inside the log");

        // Log messages of different levels
        logMessage.action(logger, LEVEL_INFO, "Separate loggers: INFO message", true, LOG_VARIABLE, entryList,
                sourceMap);
        logMessage.action(logger, LEVEL_WARN, "Separate loggers: WARN message", true, LOG_VARIABLE, entryList,
                sourceMap);
        logMessage.action(logger, LEVEL_ERROR, "Separate loggers: ERROR message", true, LOG_VARIABLE, entryList,
                sourceMap);

        // Ensure all log files are created
        Assert.assertTrue(Files.exists(Paths.get(infoLogPath)), "INFO log file should be created");
        Assert.assertTrue(Files.exists(Paths.get(warnLogPath)), "WARN log file should be created");
        Assert.assertTrue(Files.exists(Paths.get(errorLogPath)), "ERROR log file should be created");

        // Wait a moment for file writing to complete
        Thread.sleep(500);

        // Verify the INFO log file contains only INFO messages
        String infoContent = CompressedSnapshots.expand(new String(Files.readAllBytes(Paths.get(infoLogPath))));
        Assert.assertTrue(infoContent.contains("Separate loggers: INFO message"), "INFO log should contain INFO " +
                "message");
        Assert.assertFalse(infoContent.contains("Separate loggers: WARN message"), "INFO log should not contain WARN " +
                "message");
        Assert.assertFalse(infoContent.contains("Separate loggers: ERROR message"), "INFO log should not contain " +
                "ERROR message");

        // Verify the WARN log file contains only WARN messages
        String warnContent = CompressedSnapshots.expand(new String(Files.readAllBytes(Paths.get(warnLogPath))));
        Assert.assertFalse(warnContent.contains("Separate loggers: INFO message"), "WARN log should not contain INFO " +
                "message");
        Assert.assertTrue(warnContent.contains("Separate loggers: WARN message"), "WARN log should contain WARN " +
                "message");
        Assert.assertFalse(warnContent.contains("Separate loggers: ERROR message"), "WARN log should not contain " +
                "ERROR message");

        // Verify the ERROR log file contains only ERROR messages
        String errorContent = CompressedSnapshots.expand(new String(Files.readAllBytes(Paths.get(errorLogPath))));
        Assert.assertFalse(errorContent.contains("Separate loggers: INFO message"), "ERROR log should not contain " +
                "INFO message");
        Assert.assertFalse(errorContent.contains("Separate loggers: WARN message"), "ERROR log should not contain " +
                "WARN message");
        Assert.assertTrue(errorContent.contains("Separate loggers: ERROR message"), "ERROR log should contain ERROR " +
                "message");

        // Verify variable values in each file
        Assert.assertTrue(infoContent.contains("my string variable value from entry list"), "variable value is inline");
        Assert.assertTrue(warnContent.contains("my string variable value from entry list"), "variable value is inline");
        Assert.assertTrue(errorContent.contains("my string variable value from entry list"), "variable value is inline");

        // Stop the logger
        stopLoggerSession.stop(logger);
    }

    @Test
    public void testMultipleLoggerInstances() throws Exception {
        // Create two separate logger instances
        SessionValue session1 = LoggerSession.start(COMMON_FILE_ALL_LEVEL, instance1Path, null, null, null, 10, "NO_VIDEO", false, false, false, 30, "FAST", "HELIOS_DISABLED", null, null);
        SessionValue session2 = LoggerSession.start(COMMON_FILE_ALL_LEVEL, instance2Path, null, null, null, 10, "NO_VIDEO", false, false, false, 30, "FAST", "HELIOS_DISABLED", null, null);

        // Ensure both sessions were initialized properly
        Assert.assertNotNull(session1);
        Assert.assertNotNull(session2);
        CustomLogger logger1 = (CustomLogger) session1.getSession();
        CustomLogger logger2 = (CustomLogger) session2.getSession();
        Assert.assertNotNull(logger1);
        Assert.assertNotNull(logger2);

        // Log to the first logger instance
        logMessage.action(logger1, LEVEL_INFO, "Logger 1: First message", true, LOG_VARIABLE, entryList, sourceMap);
        logMessage.action(logger1, LEVEL_WARN, "Logger 1: Second message", true, LOG_VARIABLE, entryList, sourceMap);

        // Log to the second logger instance
        logMessage.action(logger2, LEVEL_INFO, "Logger 2: First message", true, LOG_VARIABLE, entryList, sourceMap);
        logMessage.action(logger2, LEVEL_ERROR, "Logger 2: Second message", true, LOG_VARIABLE, entryList, sourceMap);

        // Wait a moment for file writing to complete
        Thread.sleep(500);

        // Ensure log files are created
        Assert.assertTrue(Files.exists(Paths.get(instance1Path)), "Logger 1 file should be created");
        Assert.assertTrue(Files.exists(Paths.get(instance2Path)), "Logger 2 file should be created");

        // Read file contents
        String content1 = CompressedSnapshots.expand(new String(Files.readAllBytes(Paths.get(instance1Path))));
        String content2 = CompressedSnapshots.expand(new String(Files.readAllBytes(Paths.get(instance2Path))));

        // Verify content of the first logger's file
        Assert.assertTrue(content1.contains("Logger 1: First message"), "Logger 1 should contain its first message");
        Assert.assertTrue(content1.contains("Logger 1: Second message"), "Logger 1 should contain its second message");
        Assert.assertFalse(content1.contains("Logger 2: First message"), "Logger 1 should not contain Logger 2's " +
                "messages");
        Assert.assertFalse(content1.contains("Logger 2: Second message"), "Logger 1 should not contain Logger 2's " +
                "messages");

        // Verify content of the second logger's file
        Assert.assertTrue(content2.contains("Logger 2: First message"), "Logger 2 should contain its first message");
        Assert.assertTrue(content2.contains("Logger 2: Second message"), "Logger 2 should contain its second message");
        Assert.assertFalse(content2.contains("Logger 1: First message"), "Logger 2 should not contain Logger 1's " +
                "messages");
        Assert.assertFalse(content2.contains("Logger 1: Second message"), "Logger 2 should not contain Logger 1's " +
                "messages");

        // Verify each logger contains its own variable snapshot
        Assert.assertTrue(content1.contains("my string variable value from entry list"), "variable value is inline");
        Assert.assertTrue(content2.contains("my string variable value from entry list"), "variable value is inline");

        // Stop both loggers
        stopLoggerSession.stop(logger1);
        stopLoggerSession.stop(logger2);
    }

    @Test
    public void testLogMessageWithoutScreenshot() throws Exception {
        // Test logging without capturing screenshots
        SessionValue sessionValue = LoggerSession.start(COMMON_FILE_ALL_LEVEL, commonLogPath, null, null, null, 10, "NO_VIDEO", false, false, false, 30, "FAST", "HELIOS_DISABLED", null, null);

        CustomLogger logger = (CustomLogger) sessionValue.getSession();

        // Get initial count of screenshots
        String screenshotsDir = Paths.get(baseTestPath, "screenshots").toString();
        File screenshotDir = new File(screenshotsDir);
        int initialScreenshotCount = countFiles(screenshotDir);

        // Log without taking screenshots
        logMessage.action(logger, LEVEL_INFO, "No screenshot: INFO message", false, LOG_VARIABLE, entryList, sourceMap);
        logMessage.action(logger, LEVEL_WARN, "No screenshot: WARN message", false, LOG_VARIABLE, entryList, sourceMap);
        logMessage.action(logger, LEVEL_ERROR, "No screenshot: ERROR message", false, LOG_VARIABLE, entryList,
                sourceMap);

        // Wait a moment for file writing to complete
        Thread.sleep(500);

        // Verify no new screenshots were created
        int newScreenshotCount = countFiles(screenshotDir);
        Assert.assertEquals(newScreenshotCount, initialScreenshotCount, "No new screenshots should be created");

        // Verify log content contains the messages
        String content = CompressedSnapshots.expand(new String(Files.readAllBytes(Paths.get(commonLogPath))));
        Assert.assertTrue(content.contains("No screenshot: INFO message"), "Log should contain INFO message");
        Assert.assertTrue(content.contains("No screenshot: WARN message"), "Log should contain WARN message");
        Assert.assertTrue(content.contains("No screenshot: ERROR message"), "Log should contain ERROR message");

        // Stop the logger
        stopLoggerSession.stop(logger);
    }

    @Test
    public void testLogVariableHandling() throws Exception {
        String path = baseTestPath + "variable-flag.html";
        CustomLogger logger = new CustomLogger("variable-flag", path, 10);
        try {
            logMessage.action(logger, LEVEL_INFO, "With variables", false, LOG_VARIABLE, entryList, sourceMap);
            logMessage.action(logger, LEVEL_INFO, "Without variables", false, DO_NOT_LOG_VARIABLE, entryList, sourceMap);
        } finally {
            logger.close();
        }
        String content = CompressedSnapshots.expand(Files.readString(Paths.get(path)));
        Assert.assertTrue(content.contains("With variables"));
        Assert.assertTrue(content.contains("Without variables"));
        Assert.assertEquals(content.split("<details class='entry-variables'>", -1).length - 1, 1);
        Assert.assertTrue(content.contains("my string variable value from entry list"));
        Assert.assertFalse(Files.exists(Paths.get(baseTestPath, "variables")));
    }

    @Test
    public void testLogFileSize() throws Exception {
        String path = baseTestPath + "size_test.html";
        CustomLogger logger = new CustomLogger("single-template", path, 10);
        try {
            for (int i = 0; i < 3; i++) {
                logMessage.action(logger, LEVEL_INFO, "Log with complex variables", false,
                        LOG_VARIABLE, entryList, sourceMap);
            }
        } finally {
            logger.close();
        }
        String content = CompressedSnapshots.expand(Files.readString(Paths.get(path)));
        Assert.assertEquals(content.split("<!DOCTYPE html>", -1).length - 1, 1, "one page header");
        Assert.assertEquals(content.split("<style>", -1).length - 1, 1, "styles are stored once");
        Assert.assertEquals(content.split("<details class='entry-variables'>", -1).length - 1, 3);
        Assert.assertEquals(content.split("r7c3", -1).length - 1, 3, "every snapshot keeps all table rows");
        Assert.assertFalse(Files.exists(Paths.get(baseTestPath, "variables")));
    }

    @Test
    public void testLoggerInNestedFolder() throws Exception {
        // Test logger in a nested subfolder location
        String nestedLogFile = nestedFolderPath + "nested_log.html";

        // Create the logger session
        SessionValue sessionValue = LoggerSession.start(
                COMMON_FILE_ALL_LEVEL,
                nestedLogFile,
                null,
                null,
                null,
                10,
                "NO_VIDEO", false, false, false, 30, "FAST", "HELIOS_DISABLED", null, null
        );

        CustomLogger logger = (CustomLogger) sessionValue.getSession();
        Assert.assertNotNull(logger, "Logger should be initialized properly");

        // Log messages with screenshots and variables
        logMessage.action(logger, LEVEL_INFO, "Nested folder INFO message", true, LOG_VARIABLE, entryList, sourceMap);
        logMessage.action(logger, LEVEL_ERROR, "Nested folder ERROR message", true, LOG_VARIABLE, entryList, sourceMap);

        // Wait for file operations to complete
        Thread.sleep(500);

        // Verify log file was created
        Assert.assertTrue(Files.exists(Paths.get(nestedLogFile)), "Log file should be created in nested folder");

        // Check that screenshots and variables folders were created in the correct relative location
        Path screenshotsPath = Paths.get(nestedFolderPath, "screenshots");
        Path variablesPath = Paths.get(nestedFolderPath, "variables");

        Assert.assertTrue(Files.exists(screenshotsPath), "Screenshots folder should be created relative to log file");
        Assert.assertFalse(Files.exists(variablesPath), "Variables stay inside the log");

        // Check that screenshots were created
        File screenshotsDir = screenshotsPath.toFile();

        Assert.assertTrue(countFiles(screenshotsDir) >= 2, "Should have at least 2 screenshots");

        // Verify log content contains correct relative paths
        String logContent = CompressedSnapshots.expand(new String(Files.readAllBytes(Paths.get(nestedLogFile))));

        // Paths should be relative to the log file location
        Assert.assertTrue(logContent.contains("screenshots/"), "Log should reference screenshots with relative paths");
        Assert.assertTrue(logContent.contains("my string variable value from entry list"), "variable value is inline");

        // Make sure there are no absolute paths
        Assert.assertFalse(logContent.contains(baseTestPath), "Log should not contain absolute paths");

        // Stop the logger
        stopLoggerSession.stop(logger);
    }

    @Test
    public void testLoggerInDifferentDrive() throws Exception {
        // Test logger in a location that simulates a different drive
        String differentDriveLogFile = differentDrivePath + "different_drive_log.html";

        // Create the logger session
        SessionValue sessionValue = LoggerSession.start(
                COMMON_FILE_ALL_LEVEL,
                differentDriveLogFile,
                null,
                null,
                null,
                10,
                "NO_VIDEO", false, false, false, 30, "FAST", "HELIOS_DISABLED", null, null
        );

        CustomLogger logger = (CustomLogger) sessionValue.getSession();
        Assert.assertNotNull(logger, "Logger should be initialized properly");

        // Log messages with screenshots and variables
        logMessage.action(logger, LEVEL_INFO, "Different drive INFO message", true, LOG_VARIABLE, entryList, sourceMap);
        logMessage.action(logger, LEVEL_WARN, "Different drive WARN message", true, DO_NOT_LOG_VARIABLE, null, null);

        // Wait for file operations to complete
        Thread.sleep(500);

        // Verify log file was created
        Assert.assertTrue(Files.exists(Paths.get(differentDriveLogFile)), "Log file should be created in different " +
                "drive folder");

        // Check that screenshots and variables folders were created in the correct relative location
        Path screenshotsPath = Paths.get(differentDrivePath, "screenshots");
        Path variablesPath = Paths.get(differentDrivePath, "variables");

        Assert.assertTrue(Files.exists(screenshotsPath), "Screenshots folder should be created relative to log file");
        Assert.assertFalse(Files.exists(variablesPath), "Variables stay inside the log");

        // Check that screenshots were created
        File screenshotsDir = screenshotsPath.toFile();

        Assert.assertTrue(countFiles(screenshotsDir) >= 2, "Should have at least 2 screenshots");

        // Verify log content contains correct relative paths
        String logContent = CompressedSnapshots.expand(new String(Files.readAllBytes(Paths.get(differentDriveLogFile))));

        // Paths should be relative to the log file location
        Assert.assertTrue(logContent.contains("screenshots/"), "Log should reference screenshots with relative paths");
        Assert.assertTrue(logContent.contains("my string variable value from entry list"), "variable value is inline");

        // Make sure there are no absolute paths
        Assert.assertFalse(logContent.contains(baseTestPath), "Log should not contain absolute paths");

        // Stop the logger
        stopLoggerSession.stop(logger);
    }

    @Test
    public void testLoggerWithAbsolutePath() throws Exception {
        // Test logger with an absolute path

        // Create the logger session
        SessionValue sessionValue = LoggerSession.start(
                COMMON_FILE_ALL_LEVEL,
                absolutePathLog,
                null,
                null,
                null,
                10,
                "NO_VIDEO", false, false, false, 30, "FAST", "HELIOS_DISABLED", null, null
        );

        CustomLogger logger = (CustomLogger) sessionValue.getSession();
        Assert.assertNotNull(logger, "Logger should be initialized properly");

        // Log messages with screenshots and variables
        logMessage.action(logger, LEVEL_INFO, "Absolute path INFO message", true, LOG_VARIABLE, entryList, sourceMap);

        // Wait for file operations to complete
        Thread.sleep(500);

        // Verify log file was created
        Assert.assertTrue(Files.exists(Paths.get(absolutePathLog)), "Log file should be created at absolute path");

        // Check that screenshots and variables folders were created in the correct relative location
        Path logFolder = Paths.get(absolutePathLog).getParent();
        Path screenshotsPath = logFolder.resolve("screenshots");
        Path variablesPath = logFolder.resolve("variables");

        Assert.assertTrue(Files.exists(screenshotsPath), "Screenshots folder should be created relative to log file");
        Assert.assertFalse(Files.exists(variablesPath), "Variables stay inside the log");

        // Check that screenshots were created
        File screenshotsDir = screenshotsPath.toFile();

        Assert.assertTrue(countFiles(screenshotsDir) >= 1, "Should have at least 1 screenshot");

        // Verify log content contains correct relative paths
        String logContent = CompressedSnapshots.expand(new String(Files.readAllBytes(Paths.get(absolutePathLog))));

        // Paths should be relative to the log file location
        Assert.assertTrue(logContent.contains("screenshots/"), "Log should reference screenshots with relative paths");
        Assert.assertTrue(logContent.contains("my string variable value from entry list"), "variable value is inline");

        // Make sure there are no absolute paths in the HTML links
        Assert.assertFalse(logContent.contains(baseTestPath), "Log should not contain absolute paths");

        // Stop the logger
        stopLoggerSession.stop(logger);
    }

    @Test
    public void testLoggerMovedToNewLocation() throws Exception {
        // Test that a logger still works when the log file is moved to a new location
        String originalLogPath = baseTestPath + "original_location.html";
        String newLocationPath = baseTestPath + "moved/new_location.html";

        // Create directory for the new location
        Files.createDirectories(Paths.get(baseTestPath + "moved/"));

        // Create the logger session at the original location
        SessionValue sessionValue = LoggerSession.start(
                COMMON_FILE_ALL_LEVEL,
                originalLogPath,
                null,
                null,
                null,
                10,
                "NO_VIDEO", false, false, false, 30, "FAST", "HELIOS_DISABLED", null, null
        );

        CustomLogger logger = (CustomLogger) sessionValue.getSession();
        Assert.assertNotNull(logger, "Logger should be initialized properly");

        // Log a message with screenshot and variables
        logMessage.action(logger, LEVEL_INFO, "Original location message", true, LOG_VARIABLE, entryList, sourceMap);

        // Wait for file operations to complete
        Thread.sleep(500);

        // Verify original folders were created
        String originalScreenshotsDir = Paths.get(baseTestPath, "screenshots").toString();
        String originalVariablesDir = Paths.get(baseTestPath, "variables").toString();

        Assert.assertTrue(Files.exists(Paths.get(originalScreenshotsDir)), "Original screenshots directory should " +
                "exist");
        Assert.assertFalse(Files.exists(Paths.get(originalVariablesDir)), "Variables stay inside the log");

        // Stop the logger
        stopLoggerSession.stop(logger);

        // Move the log file and create new directories for the moved file
        Files.createDirectories(Paths.get(baseTestPath + "moved/screenshots"));

        // Copy the log file to the new location
        FileUtils.copyFile(new File(originalLogPath), new File(newLocationPath));

        // Copy the screenshots to the new location
        File[] screenshots = new File(originalScreenshotsDir).listFiles();
        if (screenshots != null) {
            for (File screenshot : screenshots) {
                FileUtils.copyFile(screenshot, new File(baseTestPath + "moved/screenshots/" + screenshot.getName()));
            }
        }

        // Read the log file from the new location
        String movedLogContent = CompressedSnapshots.expand(new String(Files.readAllBytes(Paths.get(newLocationPath))));

        // Verify that the log file at the new location can still resolve the relative paths
        Assert.assertTrue(movedLogContent.contains("screenshots/"), "Moved log should reference screenshots with " +
                "relative paths");
        Assert.assertTrue(movedLogContent.contains("my string variable value from entry list"), "variable value is inline");

        // Since relative paths are used, the links should still work in the new location
        Assert.assertTrue(Files.exists(Paths.get(baseTestPath + "moved/screenshots")), "Screenshots should exist in " +
                "new location");
        Assert.assertFalse(Files.exists(Paths.get(baseTestPath + "moved/variables")),
                "moving the HTML carries every variable without another folder");
    }
}
