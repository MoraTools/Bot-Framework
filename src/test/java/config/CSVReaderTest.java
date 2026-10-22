package config;

import com.automationanywhere.botcommand.actions.config.CSVReader;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author Sumit Kumar
 */

public class CSVReaderTest {
    private static final String COLUMN_INDEX = "INDEX";
    private static final String COLUMN_HEADER = "HEADER";
    private static final String sampleTestFilePath = "src/test/sample/test.csv";
    private static final String semicolonDelimiterFilePath = "src/test/sample/test_semicolon.csv";
    private static final String CHARSET_UTF_8 = "UTF-8";
    private static final String DELIMITER_COMMA = ",";
    private static final String DELIMITER_SEMICOLON = ";";
    private CSVReader csvReader;

    @BeforeMethod
    public void setUp() {
        csvReader = new CSVReader();
    }

    @AfterMethod
    public void tearDown() {
        csvReader = null;
    }

    @Test
    public void testReadCSVWithHeaderSuccessfully() {
        DictionaryValue result = csvReader.action(sampleTestFilePath, COLUMN_HEADER,
                null, null, "Name", "Age",
                CHARSET_UTF_8,
                false, DELIMITER_COMMA);
        Assert.assertNotNull(result, "The result should not be null.");
        Assert.assertEquals(result.get("John Doe").toString(), "25", "The age of John Doe should be 25.");
    }

    @Test
    public void testReadCSVWithIndexSuccessfully() {
        DictionaryValue result = csvReader.action(sampleTestFilePath, COLUMN_INDEX,
                0, 1, null, null,
                CHARSET_UTF_8, false, DELIMITER_COMMA);
        Assert.assertNotNull(result, "The result should not be null.");
        Assert.assertEquals(result.get("John Doe").toString(), "25", "The age of John Doe should be 25.");
    }

    @Test(expectedExceptions = BotCommandException.class)
    public void testReadCSVWithInvalidFilePath() {
        csvReader.action("invalid/path.csv", COLUMN_HEADER,
                null, null, "Name", "Age",
                CHARSET_UTF_8, false, DELIMITER_COMMA);
    }

    @Test
    public void testReadCSVWithCustomDelimiter() {
        DictionaryValue result = csvReader.action(semicolonDelimiterFilePath, COLUMN_HEADER,
                null, null, "Name", "Age",
                CHARSET_UTF_8, false, DELIMITER_SEMICOLON);
        Assert.assertNotNull(result, "The result should not be null.");
        Assert.assertEquals(result.get("John Doe").toString(), "25", "The age of John Doe should be 25.");
    }

    @Test
    public void testReadCSVWithTrimValuesEnabled() {
        DictionaryValue result = csvReader.action(sampleTestFilePath, COLUMN_HEADER,
                null, null, "Name", "Age", CHARSET_UTF_8,
                true, DELIMITER_COMMA);
        Assert.assertNotNull(result, "The result should not be null.");
        Assert.assertEquals(result.get("Alice").toString(), "22", "The age of Alice should be 22, with spaces trimmed" +
                ".");
    }

    @DataProvider
    public Object[][] missingHeaders() {
        return new Object[][]{
                {"Key", "Age", "Key"},
                {"Name", "Value", "Value"}
        };
    }

    @Test(dataProvider = "missingHeaders")
    public void testMissingHeaderFailsBeforeDataRows(String keyHeader, String valueHeader, String missingHeader)
            throws Exception {
        Path file = Files.createTempFile("csv-missing-header-", ".csv");
        try {
            for (String contents : new String[]{"Name,Age\n", "Name,Age\nAlice,22\n"}) {
                Files.writeString(file, contents);
                BotCommandException error = Assert.expectThrows(BotCommandException.class,
                        () -> csvReader.action(file.toString(), COLUMN_HEADER, null, null,
                                keyHeader, valueHeader, CHARSET_UTF_8, false, DELIMITER_COMMA));
                Assert.assertTrue(error.getMessage().contains("Column '" + missingHeader + "'"));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void testHeadersIgnoreCaseAndSkipIncompleteRows() throws Exception {
        Path file = Files.createTempFile("csv-header-", ".csv");
        try {
            Files.writeString(file, "Name,Age\nAlice,22\nIncomplete\nBob, 30 \n");
            DictionaryValue result = csvReader.action(file.toString(), COLUMN_HEADER, null, null,
                    "name", "AGE", CHARSET_UTF_8, true, DELIMITER_COMMA);
            Assert.assertEquals(result.get().size(), 2);
            Assert.assertEquals(result.get("Alice").get(), "22");
            Assert.assertEquals(result.get("Bob").get(), "30");

            Files.writeString(file, "Name,Age\n");
            Assert.assertTrue(csvReader.action(file.toString(), COLUMN_HEADER, null, null,
                    "name", "AGE", CHARSET_UTF_8, false, DELIMITER_COMMA).get().isEmpty());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
