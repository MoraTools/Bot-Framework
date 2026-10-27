package logs;

import com.automationanywhere.botcommand.actions.logs.LogMessage;
import com.automationanywhere.botcommand.actions.logs.StopLoggerSession;
import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.*;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.Level;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.testng.Assert;
import support.TempDirectories;
import support.TestLogMessage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test class to verify entry-count based log rotation
 */
public class EntryCountRotationTest {

    private static final String LEVEL_INFO = "INFO";
    private static final String LEVEL_ERROR = "ERROR";
    private static final String LEVEL_WARN = "WARN";
    private static final String LOG_VARIABLE = "YES";
    private static final String DO_NOT_LOG_VARIABLE = "NO";

    private final TempDirectories directories = new TempDirectories();
    private String baseTestPath;
    private Map<String, Value> sourceMap;
    private LogMessage logMessage;
    private StopLoggerSession stopLoggerSession;

    @BeforeClass
    public void setUp() throws IOException {
        baseTestPath = directories.create("entry-rotation") + File.separator;

        // Initialize LogMessage action
        logMessage = TestLogMessage.create();
        stopLoggerSession = new StopLoggerSession();

        // Setup test variables similar to LoggerTest
        setupTestVariables();
    }

    private void setupTestVariables() {
        // Create the selected variable map.
        sourceMap = new HashMap<>();
        sourceMap.put("stringVar", new StringValue("Entry-based rotation test"));
        sourceMap.put("numberVar", new NumberValue(42));
        sourceMap.put("booleanVar", new BooleanValue(true));
        sourceMap.put("dateVar", new DateTimeValue(ZonedDateTime.now()));

        // String variable entry
        sourceMap.put("Test String", new StringValue("Entry rotation variable"));

        // Number variable entry
        sourceMap.put("Entry Count", new NumberValue(5));

        // List variable with nested values
        List<Value> listValues = new ArrayList<>();
        listValues.add(new StringValue("First"));
        listValues.add(new StringValue("Second"));
        listValues.add(new NumberValue(100));
        ListValue listValue = new ListValue();
        listValue.set(listValues);
        sourceMap.put("List Data", listValue);

        // Table variable
        List<Schema> schemas = new ArrayList<>();
        schemas.add(new Schema("ID"));
        schemas.add(new Schema("Type"));
        schemas.add(new Schema("Status"));

        List<Row> rows = new ArrayList<>();
        rows.add(new Row(new StringValue("1"), new StringValue("Entry"), new StringValue("Active")));
        rows.add(new Row(new StringValue("2"), new StringValue("Size"), new StringValue("Deprecated")));

        TableValue tableValue = new TableValue();
        tableValue.set(new Table(schemas, rows));
        sourceMap.put("Rotation Types", tableValue);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        directories.deleteAll();
    }

    @Test
    public void testMultipleLevelRotation() throws Exception {
        // Use subdirectory in target for multi-level test
        String multiLevelPath = baseTestPath + "multi-level/";
        Files.createDirectories(Paths.get(multiLevelPath));

        Map<Level, String> levelFilePathMap = new HashMap<>();
        levelFilePathMap.put(Level.INFO, multiLevelPath + "info.html");
        levelFilePathMap.put(Level.WARN, multiLevelPath + "warn.html");
        levelFilePathMap.put(Level.ERROR, multiLevelPath + "error.html");

        // Create logger with entry-based rotation
        int maxEntries = 3;
        CustomLogger customLogger = new CustomLogger("TestLogger_" + UUID.randomUUID(),
                                                      levelFilePathMap, maxEntries);

        // Verify directories are created
        String screenshotsDir = multiLevelPath + "screenshots";
        String variablesDir = multiLevelPath + "variables";
        Assert.assertTrue(Files.exists(Paths.get(screenshotsDir)), "Screenshots directory should be created for multi-level");
        Assert.assertFalse(Files.exists(Paths.get(variablesDir)), "Multi-level logs keep variables inline");

        // Log to different levels with proper LogMessage.action()
        for (int i = 1; i <= 5; i++) {
            // INFO message - screenshot on iteration 2, variables on iteration 3
            logMessage.action(
                customLogger,
                LEVEL_INFO,
                "INFO: Application started with entry-based rotation - iteration " + i,
                i == 2,  // capture screenshot on iteration 2
                i == 3 ? LOG_VARIABLE : DO_NOT_LOG_VARIABLE,
                sourceMap
            );

            // WARN message - always with screenshot, no variables
            logMessage.action(
                customLogger,
                LEVEL_WARN,
                "WARN: Memory usage above threshold - iteration " + i,
                true,  // always capture screenshot for warnings
                DO_NOT_LOG_VARIABLE,
                null
            );

            // ERROR message - screenshot on even iterations, variables on iteration 4
            logMessage.action(
                customLogger,
                LEVEL_ERROR,
                "ERROR: Failed to process request - iteration " + i,
                i % 2 == 0,  // screenshot on even iterations
                i == 4 ? LOG_VARIABLE : DO_NOT_LOG_VARIABLE,
                sourceMap
            );
        }

        // Close the logger
        stopLoggerSession.stop(customLogger);

        // Verify rotation for each level
        for (Level level : levelFilePathMap.keySet()) {
            String filePath = levelFilePathMap.get(level);
            File originalFile = new File(filePath);

            // Check for rotated files with pattern: 1_info.html, 2_info.html, etc.
            String baseName = FilenameUtils.getBaseName(filePath);
            String ext = FilenameUtils.getExtension(filePath);
            File rotatedFile = new File(multiLevelPath + "1_" + baseName + "." + ext);

            Assert.assertTrue(originalFile.exists(),
                            level + " log file should exist");
            Assert.assertTrue(rotatedFile.exists(),
                            level + " should have at least one rotated file (1_" + baseName + "." + ext + ")");
        }
    }

    @Test
    public void testRotationWithRichContent() throws Exception {
        // Test that rotation preserves screenshots and variables references correctly
        String richContentPath = baseTestPath + "rich-content/";
        Files.createDirectories(Paths.get(richContentPath));

        String logFilePath = richContentPath + "rich.html";

        // Very small limit to ensure rotation happens multiple times
        int maxEntries = 2;
        CustomLogger customLogger = new CustomLogger("RichLogger_" + UUID.randomUUID(),
                                                      logFilePath, maxEntries);

        // Verify directories
        String screenshotsDir = richContentPath + "screenshots";
        String variablesDir = richContentPath + "variables";
        Assert.assertTrue(Files.exists(Paths.get(screenshotsDir)), "Screenshots directory should exist");
        Assert.assertFalse(Files.exists(Paths.get(variablesDir)), "Variables stay inside each log entry");

        // Log entries with rich content - all with screenshots and variables
        for (int i = 1; i <= 6; i++) {
            logMessage.action(
                customLogger,
                LEVEL_INFO,
                "Rich content entry " + i + " - Testing rotation with screenshots and variables",
                true,  // always capture screenshot
                LOG_VARIABLE,
                sourceMap
            );
        }

        stopLoggerSession.stop(customLogger);

        // Verify multiple rotation files were created
        File originalFile = new File(logFilePath);

        String baseName = FilenameUtils.getBaseName(logFilePath);
        String ext = FilenameUtils.getExtension(logFilePath);
        File rotation1 = new File(richContentPath + "1_" + baseName + "." + ext);
        File rotation2 = new File(richContentPath + "2_" + baseName + "." + ext);

        Assert.assertTrue(originalFile.exists(), "Original file should exist");
        Assert.assertTrue(rotation1.exists(), "First rotation file (1_" + baseName + "." + ext + ") should exist");
        Assert.assertTrue(rotation2.exists(), "Second rotation file (2_" + baseName + "." + ext + ") should exist");

        // Verify HTML content structure is maintained
        String originalContent = CompressedSnapshots.expand(new String(Files.readAllBytes(originalFile.toPath())));
        Assert.assertTrue(originalContent.contains("class='img-link'"),
            "Screenshot references should be preserved");
        Assert.assertTrue(originalContent.contains("class='entry-variables'"),
            "Inline variables should be preserved");

        // Check that actual screenshot files were created
        File screenshotDirectory = new File(screenshotsDir);
        File[] screenshots = screenshotDirectory.listFiles((dir, name) -> name.endsWith(".png"));
        Assert.assertNotNull(screenshots, "Screenshot directory should contain files");
        Assert.assertTrue(screenshots.length > 0, "At least one screenshot should have been captured");

        Assert.assertFalse(Files.exists(Paths.get(variablesDir)));
        Assert.assertTrue(originalContent.contains("Rotation Types"), "table variable is inline");
        Assert.assertTrue(originalContent.contains("Deprecated"), "table values are inline");
    }
}
