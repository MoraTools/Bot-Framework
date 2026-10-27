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
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import support.TempDirectories;
import support.TestLogMessage;

import java.io.File;
import java.nio.file.Files;
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

    private final TempDirectories directories = new TempDirectories();

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

    private Map<String, Value> sourceMap;

    @BeforeClass
    public void setUp() throws Exception {
        // Initialize test classes
        LoggerSession = new StartLoggerSession();
        logMessage = TestLogMessage.create();
        stopLoggerSession = new StopLoggerSession();

        baseTestPath = directories.create("logger-test") + File.separator;

        // Set up file paths for testing
        commonLogPath = baseTestPath + "common_log.html";
        infoLogPath = baseTestPath + "info_log.html";
        warnLogPath = baseTestPath + "warn_log.html";
        errorLogPath = baseTestPath + "error_log.html";

        // Set up paths for multiple logger tests
        instance1Path = baseTestPath + "instance1.html";
        instance2Path = baseTestPath + "instance2.html";

        // Prepare test data
        setupTestData();
    }

    private void setupTestData() {
        // Create the selected variable map.
        sourceMap = new HashMap<>();
        sourceMap.put("string", new StringValue("  Hello, World! with spaces at end and front  "));
        sourceMap.put("number", new NumberValue(100));
        sourceMap.put("boolean", new BooleanValue(true));
        sourceMap.put("date", new DateTimeValue(ZonedDateTime.now()));

        // String variable
        sourceMap.put("entrylist string", new StringValue("my string variable value from entry list"));

        // Number variable
        sourceMap.put("entrylist number", new NumberValue(456));

        // List variable with nested dictionary
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
        sourceMap.put("entrylist list", lv);

        // Table variable
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

        sourceMap.put("entrylist table variable", tv);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        directories.deleteAll();
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
        logMessage.action(logger, LEVEL_INFO, "Common logger: INFO message", true, LOG_VARIABLE, sourceMap);
        logMessage.action(logger, LEVEL_WARN, "Common logger: WARN message", true, LOG_VARIABLE, sourceMap);
        logMessage.action(logger, LEVEL_ERROR, "Common logger: ERROR message", true, LOG_VARIABLE,
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
        logMessage.action(logger, LEVEL_INFO, "Separate loggers: INFO message", true, LOG_VARIABLE,
                sourceMap);
        logMessage.action(logger, LEVEL_WARN, "Separate loggers: WARN message", true, LOG_VARIABLE,
                sourceMap);
        logMessage.action(logger, LEVEL_ERROR, "Separate loggers: ERROR message", true, LOG_VARIABLE,
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
        logMessage.action(logger1, LEVEL_INFO, "Logger 1: First message", true, LOG_VARIABLE, sourceMap);
        logMessage.action(logger1, LEVEL_WARN, "Logger 1: Second message", true, LOG_VARIABLE, sourceMap);

        // Log to the second logger instance
        logMessage.action(logger2, LEVEL_INFO, "Logger 2: First message", true, LOG_VARIABLE, sourceMap);
        logMessage.action(logger2, LEVEL_ERROR, "Logger 2: Second message", true, LOG_VARIABLE, sourceMap);

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
        logMessage.action(logger, LEVEL_INFO, "No screenshot: INFO message", false, LOG_VARIABLE, sourceMap);
        logMessage.action(logger, LEVEL_WARN, "No screenshot: WARN message", false, LOG_VARIABLE, sourceMap);
        logMessage.action(logger, LEVEL_ERROR, "No screenshot: ERROR message", false, LOG_VARIABLE,
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
        Map<String, Value> selected = new HashMap<>(sourceMap);
        selected.put("nestedSelection", new DictionaryValue(VariableCardParityTest.snapshotValues()));
        selected.put("falseBoolean", new BooleanValue(false));
        try {
            Assert.assertEquals(logMessage.action(logger, LEVEL_INFO, "With variables", false, LOG_VARIABLE, selected).get(), "");
            logMessage.action(logger, LEVEL_INFO, "Without variables", false, DO_NOT_LOG_VARIABLE, selected);
            logMessage.action(logger, LEVEL_INFO, "Null selection", false, LOG_VARIABLE, null);
            logMessage.action(logger, LEVEL_INFO, "Empty selection", false, LOG_VARIABLE, Map.of());
        } finally {
            logger.close();
        }
        String content = CompressedSnapshots.expand(Files.readString(Paths.get(path)));
        Assert.assertTrue(content.contains("With variables"));
        Assert.assertTrue(content.contains("Without variables"));
        Assert.assertEquals(content.split("<details class='entry-variables'>", -1).length - 1, 1);
        Assert.assertTrue(content.contains("my string variable value from entry list"));
        Assert.assertTrue(content.contains("Null selection"));
        Assert.assertTrue(content.contains("Empty selection"));
        Assert.assertTrue(content.contains("<div class='variable-type'>BOOLEAN</div><div class='variable-value'>true</div>"));
        Assert.assertTrue(content.contains("<div class='variable-type'>BOOLEAN</div><div class='variable-value'>false</div>"));
        Assert.assertTrue(content.contains("<div class='variable-type'>DICTIONARY</div>"));
        Assert.assertTrue(content.contains("<div class='variable-type'>LIST</div>"));
        Assert.assertTrue(content.contains("<div class='variable-type'>RECORD</div>"));
        Assert.assertTrue(content.contains("<div class='variable-type'>TABLE</div>"));
        Assert.assertTrue(content.contains("Record: 2 columns"));
        Assert.assertTrue(content.contains("Table: 2 rows x 2 columns"));
        Assert.assertTrue(content.contains("key&lt;&amp;&gt;"));
        Assert.assertTrue(content.contains("list&lt;&amp;&gt;"));
        Assert.assertTrue(content.contains("dictionary<i class='ws-tab' aria-hidden='true'></i>\tvalue"));
        Assert.assertFalse(Files.exists(Paths.get(baseTestPath, "variables")));
    }
}
