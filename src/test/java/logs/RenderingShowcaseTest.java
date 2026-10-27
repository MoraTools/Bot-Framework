package logs;

import com.automationanywhere.botcommand.actions.logs.StopLoggerSession;
import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.BooleanValue;
import com.automationanywhere.botcommand.data.impl.DateTimeValue;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.ListValue;
import com.automationanywhere.botcommand.data.impl.NumberValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.data.impl.TableValue;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import support.TempDirectories;
import support.TestLogMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * End-to-end rendering checks through Log Message: realistic variable shapes (long, multi-line,
 * HTML-special, Unicode, nested, wide and tall tables, empty containers), the whitespace markers,
 * and the viewer styles in the written header.
 */
@SuppressWarnings({"SpellCheckingInspection", "GrazieInspection"})
public class RenderingShowcaseTest {

    private static final String LEVEL_INFO = "INFO";
    private static final String LOG_VARIABLE = "YES";
    private static final String DO_NOT_LOG_VARIABLE = "NO";

    private final TempDirectories directories = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void deleteDirectories() {
        directories.deleteAll();
    }

    /** Writes one INFO entry through Log Message, without a screenshot, and returns the expanded log. */
    private String logOnce(String folder, String message, Map<String, Value> variables) throws Exception {
        Path dir = directories.create(folder);
        Path log = dir.resolve("log.html");
        CustomLogger logger = new CustomLogger(folder + "_" + UUID.randomUUID(), log.toString(), 10);
        TestLogMessage.create().action(logger, LEVEL_INFO, message, false,
                variables == null ? DO_NOT_LOG_VARIABLE : LOG_VARIABLE, variables);
        new StopLoggerSession().stop(logger);
        Assert.assertFalse(Files.exists(dir.resolve("variables")), "no variable sidecar files");
        return CompressedSnapshots.expand(Files.readString(log));
    }

    @Test
    public void testExhaustiveVariablesShowcase() throws Exception {
        Map<String, Value> sourceMap = new LinkedHashMap<>();

        // Short scalars
        sourceMap.put("Short String", new StringValue("Hello"));
        sourceMap.put("Empty String", new StringValue(""));
        sourceMap.put("Number Zero", new NumberValue(0));
        sourceMap.put("Number Large", new NumberValue(123456789.987654));
        sourceMap.put("Number Negative", new NumberValue(-2.5));
        sourceMap.put("Boolean True", new BooleanValue(true));
        sourceMap.put("Boolean False", new BooleanValue(false));
        sourceMap.put("DateTime Now", new DateTimeValue(ZonedDateTime.now()));
        sourceMap.put("Null Value", null);

        // Long single-line text (no newlines, normal word breaks)
        sourceMap.put("Long Single Line", new StringValue(
            "This is a single long line of text without any newlines. It is intentionally long enough to stress how the renderer handles wrapping versus explicit line breaks. " +
            "It continues with more content to ensure the line is well over two hundred characters and forces the layout engine to make decisions about where to wrap inside its own column."));

        // Very long URL (long but with break points at /, ., -)
        sourceMap.put("Long URL", new StringValue(
            "https://very-long-subdomain.example.company.org/path/with/many/segments/" +
            "and-very-long-identifier-1234567890123456789012345/profile/details.html" +
            "?query=value&another=value&yet-another=value&final-token=abcdef0123456789"));

        // Unbreakable long token (no spaces, no break opportunities)
        sourceMap.put("Unbreakable Token", new StringValue(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));

        // Multiline JSON-like content
        sourceMap.put("JSON Multiline", new StringValue(
            "{\n" +
            "  \"id\": 42,\n" +
            "  \"name\": \"Sample Record\",\n" +
            "  \"tags\": [\n" +
            "    \"alpha\",\n" +
            "    \"beta\",\n" +
            "    \"gamma\"\n" +
            "  ],\n" +
            "  \"nested\": {\n" +
            "    \"key\": \"value\",\n" +
            "    \"count\": 7\n" +
            "  }\n" +
            "}"));

        // Multi-paragraph text (newlines + blank lines)
        sourceMap.put("Multi Paragraph", new StringValue(
            "First paragraph describes the situation. It can be a long sentence that wraps around when displayed in a narrow column and demonstrates how soft wrapping interacts with explicit newlines.\n" +
            "\n" +
            "Second paragraph follows after a blank line. This pattern is common in formatted text and the renderer should preserve the blank line as visible vertical space.\n" +
            "\n" +
            "Final paragraph concludes the message and demonstrates that triple-newline handling works correctly for longer content."));

        // Code block with indentation
        sourceMap.put("Code Block", new StringValue(
            "function processData(input) {\n" +
            "    if (!input || input.length === 0) {\n" +
            "        return null;\n" +
            "    }\n" +
            "    return input\n" +
            "        .map(item => item.value)\n" +
            "        .filter(value => value != null);\n" +
            "}"));

        // HTML special characters that must be escaped
        sourceMap.put("HTML Special Chars", new StringValue(
            "Tags <script>alert('xss')</script> & ampersands & quotes \"double\" 'single' should all render as escaped text rather than markup"));

        // Unicode and emoji
        sourceMap.put("Unicode Mixed", new StringValue(
            "ASCII / 日本語 / العربية / Ελληνικά / 🎉 emoji / mathematical symbols ∑ ∫ ∂ √ ∞"));

        // Mixed: long line followed by newline followed by more text
        sourceMap.put("Mixed Long And Newlines", new StringValue(
            "First a long line of text with no breaks until we deliberately hit a newline character right after this final word.\n" +
            "Then a second line that is also reasonably long and should wrap independently of the first line above.\n" +
            "And finally a short third line."));

        // Simple flat list of strings
        ListValue flatStringList = new ListValue();
        flatStringList.set(Arrays.asList(
            new StringValue("apple"),
            new StringValue("banana"),
            new StringValue("cherry"),
            new StringValue("date with a much longer name that exercises wrapping inside a list item"),
            new StringValue("elderberry")
        ));
        sourceMap.put("Flat String List", flatStringList);

        // Mixed-type list
        ListValue mixedList = new ListValue();
        mixedList.set(Arrays.asList(
            new StringValue("first string"),
            new NumberValue(42),
            new BooleanValue(true),
            new StringValue("a multi-line entry\nwith its own newline\nspanning three lines")
        ));
        sourceMap.put("Mixed-Type List", mixedList);

        // Nested list (list of dictionaries)
        ListValue nestedList = new ListValue();
        Map<String, Value> dict1 = new LinkedHashMap<>();
        dict1.put("name", new StringValue("Alice"));
        dict1.put("score", new NumberValue(85));
        dict1.put("active", new BooleanValue(true));
        Map<String, Value> dict2 = new LinkedHashMap<>();
        dict2.put("name", new StringValue("Bob"));
        dict2.put("score", new NumberValue(92));
        dict2.put("active", new BooleanValue(false));
        Map<String, Value> dict3 = new LinkedHashMap<>();
        dict3.put("name", new StringValue("Charlie"));
        dict3.put("score", new NumberValue(78));
        dict3.put("active", new BooleanValue(true));
        nestedList.set(Arrays.asList(
            new DictionaryValue(dict1),
            new DictionaryValue(dict2),
            new DictionaryValue(dict3)
        ));
        sourceMap.put("List Of Dictionaries", nestedList);

        // Dictionary with mixed types
        Map<String, Value> configDict = new LinkedHashMap<>();
        configDict.put("retries", new NumberValue(3));
        configDict.put("timeout_seconds", new NumberValue(30));
        configDict.put("debug", new BooleanValue(true));
        configDict.put("endpoint", new StringValue("https://api.example.com/v2/resource"));
        configDict.put("description", new StringValue("Multi-line dictionary value:\n  - first line\n  - second line"));
        sourceMap.put("Configuration Dictionary", new DictionaryValue(configDict));

        // Small 2-column table
        TableValue smallTable = new TableValue();
        smallTable.set(new Table(
            Arrays.asList(new Schema("Field"), new Schema("Value")),
            Arrays.asList(
                new Row(new StringValue("API Endpoint"), new StringValue("/api/v1/users")),
                new Row(new StringValue("HTTP Method"), new StringValue("GET")),
                new Row(new StringValue("Auth Type"), new StringValue("Bearer Token")),
                new Row(new StringValue("Timeout"), new StringValue("30s"))
            )
        ));
        sourceMap.put("Small Table", smallTable);

        // Table with multi-line cell values (newlines preserved per cell)
        TableValue multilineTable = new TableValue();
        multilineTable.set(new Table(
            Arrays.asList(new Schema("Step"), new Schema("Input"), new Schema("Output")),
            Arrays.asList(
                new Row(
                    new StringValue("Parse JSON"),
                    new StringValue("{\n  \"a\": 1,\n  \"b\": 2\n}"),
                    new StringValue("Map<String, Number> {\n  a -> 1,\n  b -> 2\n}")
                ),
                new Row(
                    new StringValue("Validate"),
                    new StringValue("Required fields:\n  - name\n  - email\n  - phone"),
                    new StringValue("Result: PASS\nWarnings: 0\nErrors: 0")
                ),
                new Row(
                    new StringValue("Transform"),
                    new StringValue("uppercase all string fields\ntrim whitespace\nconvert dates to ISO 8601"),
                    new StringValue("12 fields modified")
                )
            )
        ));
        sourceMap.put("Multi-Line Cells Table", multilineTable);

        // Wide table with 10 columns
        List<Schema> wideSchemas = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            wideSchemas.add(new Schema("Column " + i));
        }
        List<Row> wideRows = new ArrayList<>();
        for (int r = 1; r <= 5; r++) {
            Value[] cells = new Value[10];
            for (int c = 0; c < 10; c++) {
                cells[c] = new StringValue("R" + r + "C" + (c + 1));
            }
            wideRows.add(new Row(cells));
        }
        TableValue wideTable = new TableValue();
        wideTable.set(new Table(wideSchemas, wideRows));
        sourceMap.put("Wide Table", wideTable);

        // Tall table to exercise sticky thead during page scroll
        List<Row> tallRows = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            tallRows.add(new Row(
                new StringValue("ROW-" + String.format("%03d", i)),
                new StringValue("Item " + i),
                new StringValue(i % 2 == 0 ? "Active" : "Pending"),
                new StringValue("Description for item " + i + " with some additional context.")
            ));
        }
        TableValue tallTable = new TableValue();
        tallTable.set(new Table(
            Arrays.asList(new Schema("ID"), new Schema("Name"), new Schema("Status"), new Schema("Description")),
            tallRows
        ));
        sourceMap.put("Tall Table (30 rows)", tallTable);

        // Table with nested complex values inside cells
        ListValue nestedCellList = new ListValue();
        nestedCellList.set(Arrays.asList(new StringValue("alpha"), new StringValue("beta"), new StringValue("gamma")));
        Map<String, Value> nestedCellDict = new LinkedHashMap<>();
        nestedCellDict.put("score", new NumberValue(95));
        nestedCellDict.put("rank", new StringValue("A+"));
        TableValue nestedCellsTable = new TableValue();
        nestedCellsTable.set(new Table(
            Arrays.asList(new Schema("Identifier"), new Schema("Tags"), new Schema("Grade")),
            Arrays.asList(
                new Row(new StringValue("rec-001"), nestedCellList, new DictionaryValue(nestedCellDict)),
                new Row(new StringValue("rec-002"), new StringValue("simple"), new NumberValue(42))
            )
        ));
        sourceMap.put("Table With Nested Cells", nestedCellsTable);

        // Add one richly nested variable to the selection.
        Map<String, Value> richDict = new LinkedHashMap<>();
        richDict.put("source", new StringValue("s3://bucket/raw-data/2026/"));
        richDict.put("destination", new StringValue("s3://bucket/processed/2026/"));
        richDict.put("steps", flatStringList);
        richDict.put("retry_policy", new DictionaryValue(configDict));
        sourceMap.put("Pipeline Configuration", new DictionaryValue(richDict));

        String variablesHtml = logOnce("showcase-variables", "Exhaustive variables showcase entry", sourceMap);
        // Spot-check that representative variables, types, and rare characters survive
        Assert.assertTrue(variablesHtml.contains("Long URL"), "should include 'Long URL' name");
        Assert.assertTrue(variablesHtml.contains("JSON Multiline"), "should include 'JSON Multiline' name");
        Assert.assertTrue(variablesHtml.contains("Wide Table"), "should include 'Wide Table' name");
        Assert.assertTrue(variablesHtml.contains("Tall Table (30 rows)"), "should include 'Tall Table' name");
        Assert.assertTrue(variablesHtml.contains("Multi-Line Cells Table"), "should include multi-line cells table name");
        Assert.assertTrue(variablesHtml.contains("Pipeline Configuration"), "should include selected variable name");
        Assert.assertTrue(variablesHtml.contains("&lt;script&gt;"), "HTML special characters must be escaped");
        Assert.assertTrue(variablesHtml.contains("日本語"), "Unicode characters should pass through");
        Assert.assertTrue(variablesHtml.contains("AAAAAAAAAAAAAAAAAAAAAAAAA"), "Unbreakable token should survive verbatim");
        Assert.assertTrue(variablesHtml.contains("ROW-030"), "Tall table should include all 30 rows");
        Assert.assertTrue(variablesHtml.contains("Table: 5 rows x 10 columns"), "Wide table summary line");
        Assert.assertTrue(variablesHtml.contains(">Column 10<"), "Wide table keeps its last column header");
    }

    @Test
    public void testWhitespaceMarkersKeepEscapingAndLineEndings() {
        String trail = "<span class='ws-trail'><span class='ws-marker' aria-hidden='true'>··</span>  </span>";
        String newline = "<i class='ws-nl' aria-hidden='true'></i>";
        String tab = "<i class='ws-tab' aria-hidden='true'></i>\t";
        Assert.assertEquals(HTMLGenerator.escapeWithWhitespaceMarkers(null), "");
        Assert.assertEquals(HTMLGenerator.escapeWithWhitespaceMarkers(""), "");
        Assert.assertEquals(HTMLGenerator.escapeWithWhitespaceMarkers("<&> $ \\  \r\n\tend  "),
                "&lt;&amp;&gt; $ \\" + trail + newline + "\r\n" + tab + "end" + trail);
        Assert.assertEquals(HTMLGenerator.escapeWithWhitespaceMarkers("a  b  \n  "),
                "a  b" + trail + newline + "\n" + trail);
    }

    @Test(timeOut = 5000)
    public void testLongSpaceRunsKeepTheirValuesWithoutRepeatedMatching() {
        String spaces = " ".repeat(65536);
        String interior = "left" + spaces + "right";
        Assert.assertEquals(HTMLGenerator.escapeWithWhitespaceMarkers(interior), interior);
        String marked = "left<span class='ws-trail'><span class='ws-marker' aria-hidden='true'>"
                + "·".repeat(spaces.length()) + "</span>" + spaces + "</span>";
        Assert.assertEquals(HTMLGenerator.escapeWithWhitespaceMarkers("left" + spaces), marked);
        Assert.assertEquals(HTMLGenerator.escapeWithWhitespaceMarkers("left" + spaces + "\r\nright"),
                marked + "<i class='ws-nl' aria-hidden='true'></i>\r\nright");
    }

    @Test
    public void testNewlineGlyphInLogMessage() throws Exception {
        // Multi-line messages must render as visible breaks with a dim glyph
        // marker so users can spot embedded newlines instead of seeing them
        // collapse into a single line.
        String logHtml = logOnce("newline-log", "Step 1: Read input\nStep 2: Validate\nStep 3: Done", null);
        Assert.assertTrue(logHtml.replaceAll("\\s+", "").contains(".log-entry>td:nth-child(4){white-space:pre-wrap;"),
            "The fourth column preserves message whitespace without a per-row class");
        Assert.assertTrue(logHtml.contains("Step 1: Read input<i class='ws-nl' aria-hidden='true'></i>\n"
                + "Step 2: Validate<i class='ws-nl' aria-hidden='true'></i>\nStep 3: Done"),
            "Multi-line message should emit a newline marker before each line break");
    }

    @Test
    public void testStickyTableSummaryStyleEmitted() throws Exception {
        // The inline variable styles carries the CSS rule that pins the
        // <summary> of details.full-width while the table is in view. We
        // assert the rule is present in the rendered output.
        TableValue table = new TableValue();
        table.set(new Table(
            Arrays.asList(new Schema("A"), new Schema("B")),
            java.util.Collections.singletonList(new Row(new StringValue("1"), new StringValue("2")))
        ));
        Map<String, Value> vars = new java.util.LinkedHashMap<>();
        vars.put("smallTable", table);
        String variablesHtml = logOnce("sticky-summary", "Sticky summary check", vars);
        Assert.assertTrue(variablesHtml.replaceAll("\\s+", "").contains("details.full-width>summary"),
            "Sticky summary CSS rule should be present in the variables template");
        Assert.assertTrue(variablesHtml.replaceAll("\\s+", "").contains("position:sticky"),
            "Sticky positioning should be declared in the variables template");
        Assert.assertTrue(variablesHtml.replaceAll("\\s+", "").contains("overflow:auto"),
            "Wide tables have a local scroll area");
        Assert.assertTrue(variablesHtml.contains("<details class='full-width'>"),
            "Tables should be wrapped in details.full-width to receive sticky behavior");
    }

    @Test
    public void testEmptyContainersRender() throws Exception {
        // Empty list, empty dict, and zero-row table should render their
        // summary lines without throwing or producing malformed HTML.
        ListValue emptyList = new ListValue();
        emptyList.set(new ArrayList<>());

        DictionaryValue emptyDict = new DictionaryValue(new LinkedHashMap<>());

        TableValue emptyTable = new TableValue();
        emptyTable.set(new Table(
            Arrays.asList(new Schema("A"), new Schema("B")),
            new ArrayList<>()
        ));

        Map<String, Value> vars = new LinkedHashMap<>();
        vars.put("emptyList", emptyList);
        vars.put("emptyDict", emptyDict);
        vars.put("emptyTable", emptyTable);
        String html = logOnce("empty-containers", "Empty containers test", vars);
        Assert.assertTrue(html.contains("List: 0 items"), "Empty list summary line");
        Assert.assertTrue(html.contains("Dictionary: 0 entries"), "Empty dict summary line");
        Assert.assertTrue(html.contains("Table: 0 rows x 2 columns"), "Empty table summary line");
        Assert.assertTrue(html.contains(">A<"), "Empty table column headers should still render");
        Assert.assertTrue(html.contains(">B<"), "Empty table column headers should still render");
    }

    @Test
    public void testFlatLayoutAppliedNoOuterRadius() throws Exception {
        // The log and variable dialog share the flat, edge-to-edge stylesheet.
        Map<String, Value> vars = new java.util.LinkedHashMap<>();
        vars.put("k", new StringValue("v"));
        String css = logOnce("flat-layout", "flat", vars).replaceAll("\\s+", "");
        Assert.assertFalse(css.contains("border-radius:var(--radius)"),
            "the log should no longer round outer corners with --radius");
        Assert.assertTrue(css.contains("body{margin:0auto;"),
            "The body has no outer horizontal margin");
        Assert.assertTrue(css.contains("overflow:hidden;"),
            "The table scroll area keeps the session header visible");
    }
}
