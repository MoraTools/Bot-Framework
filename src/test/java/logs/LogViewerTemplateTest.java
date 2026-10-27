package logs;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import org.apache.logging.log4j.Level;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import support.TempDirectories;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static support.LogEntries.occurrences;

/** The packaged viewer template and the markup the logger writes for it. */
public class LogViewerTemplateTest {
    private static final String NEWLINE = "<i class='ws-nl' aria-hidden='true'></i>";

    private final TempDirectories directories = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void deleteDirectories() {
        directories.deleteAll();
    }

    private static String template() throws IOException {
        try (InputStream input = CustomHTMLLayout.class.getResourceAsStream("/templates/log.html")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The template without white space: the checks do not depend on how the file is formatted. */
    private static String compactTemplate() throws IOException {
        return template().replaceAll("\\s+", "");
    }

    @Test
    public void templateKeepsTheBoundariesTheLoggerRecognizesItsFilesBy() throws Exception {
        // A Windows checkout can give the file CR LF line ends. The layout turns them into LF.
        String html = template().replace("\r\n", "\n");
        Assert.assertTrue(html.startsWith("<!DOCTYPE html>\n<!-- a360-log-format:7 -->\n{{identity}}\n"));
        Assert.assertTrue(html.endsWith("\n<!-- a360-log-header:end -->\n"));
        Assert.assertEquals(occurrences(html, "{{identity}}"), 1);
        Assert.assertEquals(occurrences(html, "<dd>{{machine}}</dd>"), 1);
        Assert.assertEquals(occurrences(html, "<dd>{{user}}</dd>"), 1);
        List<String> placeholders = new ArrayList<>();
        java.util.regex.Matcher found = Pattern.compile("\\{\\{[a-z]+}}").matcher(html);
        while (found.find()) placeholders.add(found.group());
        Assert.assertEquals(placeholders, List.of("{{identity}}", "{{machine}}", "{{user}}"), "no other placeholder");
        // The same boundaries in a file the logger really writes: every placeholder is filled in.
        Path log = directories.create("viewer-boundaries").resolve("log.html");
        new CustomLogger("viewer-boundaries", log.toString(), 100).close();
        String header = Files.readString(log, StandardCharsets.UTF_8);
        Assert.assertTrue(header.startsWith("<!DOCTYPE html>\n<!-- a360-log-format:7 -->\n<!-- a360-log-identity:"), header);
        Assert.assertTrue(header.endsWith("\n<!-- a360-log-header:end -->\n"));
        Assert.assertFalse(header.contains("\r"), "the header has LF line ends, whatever the checkout used");
        Assert.assertFalse(header.contains("{{"));
        Assert.assertTrue(header.contains("<dd>" + InlineHtmlLogTest.escaped(CustomHTMLLayout.machineName()) + "</dd>"));
        Assert.assertTrue(header.contains("<dd>" + InlineHtmlLogTest.escaped(CustomHTMLLayout.userName()) + "</dd>"));
    }

    @Test
    public void viewerStylesDrawTheWhitespaceMarkers() throws Exception {
        String css = compactTemplate();
        Assert.assertTrue(css.contains(".ws-tab::before{content:'→'"), "the tab glyph comes from CSS");
        Assert.assertTrue(css.contains(".ws-nl::before{content:'↵'"), "the newline glyph comes from CSS");
    }

    @Test
    public void viewerStylesAvoidHasSelectorsWhereOlderChromiumWouldDropTheRule() throws Exception {
        String css = compactTemplate();
        // Chromium before 105 ignores a whole rule that has :has(). These two rules decide whether
        // "No entries yet." goes away and whether a video link without a poster can be clicked.
        Assert.assertFalse(css.contains("body:has(.log-entry).empty-log"));
        Assert.assertFalse(css.contains(".video-link:not(:has(img))"));
        Assert.assertTrue(css.contains(".empty-log:not(:last-child){display:none"),
                "the note is hidden as soon as a row follows it");
        Assert.assertTrue(css.contains(".video-link{min-width:88px;min-height:50px"),
                "every video link has a size, with or without a poster");
        Assert.assertTrue(css.contains(".video-link.play-overlay:first-child{background:"),
                "without a poster the overlay is the first child and gets the dark background");
        // The note is a table row inside the header, so the first entry row is its next sibling.
        Assert.assertTrue(Pattern.compile("<tbody>\\s*<tr class='empty-log'><td colspan='6'>No entries yet\\.</td></tr>"
                + "\\s*<!-- a360-log-header:end -->\\s*$").matcher(template()).find());
        Assert.assertFalse(css.contains("<pclass='empty-log'>"));
        Assert.assertTrue(css.contains(".log-entry:nth-child(odd){background:"), "the note row is child 1 of the body");
    }

    @Test
    public void newlineMarkerSitsBeforeEveryKindOfLineTerminator() {
        // A browser reads CR LF and a lone CR as one line break, so the marker goes before the terminator.
        Assert.assertEquals(HTMLGenerator.escapeWithWhitespaceMarkers("a\r\nb"), "a" + NEWLINE + "\r\nb");
        Assert.assertEquals(HTMLGenerator.escapeWithWhitespaceMarkers("a\rb"), "a" + NEWLINE + "\rb");
        Assert.assertEquals(HTMLGenerator.escapeWithWhitespaceMarkers("a\nb"), "a" + NEWLINE + "\nb");
        Assert.assertEquals(HTMLGenerator.escapeWithWhitespaceMarkers("a\r\n\r\nb\n\rc"),
                "a" + NEWLINE + "\r\n" + NEWLINE + "\r\nb" + NEWLINE + "\n" + NEWLINE + "\rc");
        // After the browser reads CR LF as one break, the number of markers equals the number of line breaks.
        String text = HTMLGenerator.escapeWithWhitespaceMarkers("1\r\n2\r3\n4");
        Assert.assertEquals(occurrences(text, NEWLINE), 3);
        Assert.assertEquals(text.replace(NEWLINE, "").replace("\r\n", "\n").replace('\r', '\n').split("\n", -1).length - 1, 3);
    }

    @Test
    public void escaperChangesOnlyTheFiveMarkupCharactersAndMarkersAreShort() {
        Assert.assertEquals(HTMLGenerator.escapeWithWhitespaceMarkers("<a href=\"x\">'&'</a>"),
                "&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;");
        // escapeHtml4 would write &eacute; &copy; and &yen; here; UTF-8 carries them in 2 bytes each.
        Assert.assertEquals(HTMLGenerator.escapeWithWhitespaceMarkers("é © ¥ 日本語 𠮷"), "é © ¥ 日本語 𠮷");
        String trace = "at line\n".repeat(40);
        Assert.assertTrue(HTMLGenerator.escapeWithWhitespaceMarkers(trace).getBytes(StandardCharsets.UTF_8).length
                - trace.getBytes(StandardCharsets.UTF_8).length <= 40 * 45, "a newline marker costs at most 45 bytes");
        String tabs = "a\t".repeat(40);
        Assert.assertTrue(HTMLGenerator.escapeWithWhitespaceMarkers(tabs).getBytes(StandardCharsets.UTF_8).length
                - tabs.getBytes(StandardCharsets.UTF_8).length <= 40 * 45, "a tab marker costs at most 45 bytes");
    }

    @Test
    public void optionalCellEndTagsKeepSixCellsWithHostileTextVariablesAndMedia() throws Exception {
        Path dir = directories.create("viewer-cells");
        Path log = dir.resolve("log.html");
        Path video = ExternalLogTest.text(dir.resolve("clips/movie&日本語.mp4"), "clip");
        Path poster = ExternalLogTest.png(dir.resolve("screenshots/poster&日本語.png"));
        String source = "PUBLIC:/tasks/<td>fake</td> & 日本語";
        String message = "hostile </td><td>fake & \" 日本語\n\ttrail  ";
        Map<String, Value> values = Map.of("nested", new DictionaryValue(Map.of(
                "</td><td>fake", new StringValue("<table><tr><td>日本語\n\ttrail  "))));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(CustomHTMLLayout.Columns.SOURCE, source);
        data.put(CustomHTMLLayout.Columns.MESSAGE, message);
        data.put(CustomHTMLLayout.Columns.VARIABLES, values);
        data.put(CustomHTMLLayout.Columns.VIDEO, video.toString());
        data.put(CustomHTMLLayout.Columns.VIDEO_POSTER, poster.toString());
        List<Level> levels = List.of(Level.INFO, Level.WARN, Level.ERROR);
        CustomLogger logger = new CustomLogger("viewer-cells", log.toString(), 100);
        try { for (Level level : levels) logger.getLogger().log(level, data); }
        finally { logger.close(); }
        List<String> rows = ExternalLogTest.rows(log);
        Assert.assertEquals(rows.size(), levels.size());
        for (int index = 0; index < levels.size(); index++) {
            String row = rows.get(index);
            Assert.assertTrue(row.endsWith("</tr>\n"), "the row ends with its tag, then the entry marker");
            Assert.assertTrue(row.contains(HTMLGenerator.escapeWithWhitespaceMarkers(message)));
            Assert.assertTrue(CompressedSnapshots.expand(row).contains(HTMLGenerator.generateVariablesHTML(values)));
            Assert.assertTrue(row.contains("class='img-link video-link'"));
            Assert.assertTrue(row.contains("href='clips/movie&amp;%E6%97%A5%E6%9C%AC%E8%AA%9E.mp4'"), row);
            Assert.assertTrue(row.contains("src='screenshots/poster&amp;%E6%97%A5%E6%9C%AC%E8%AA%9E.png'"), row);
            List<StringBuilder> cells = new ArrayList<>();
            new ParserDelegator().parse(new StringReader("<table>" + row + "</table>"),
                    new HTMLEditorKit.ParserCallback() {
                        private StringBuilder current;
                        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                            if (tag == HTML.Tag.TD) { current = new StringBuilder(); cells.add(current); }
                        }
                        public void handleEndTag(HTML.Tag tag, int position) {
                            if (tag == HTML.Tag.TD) current = null;
                        }
                        public void handleText(char[] text, int position) {
                            if (current != null) current.append(text);
                        }
                    }, true);
            Assert.assertEquals(cells.size(), 6);
            Assert.assertTrue(cells.get(0).toString().matches("\\d{4}-[A-Z][a-z]{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4}"), cells.get(0).toString());
            Assert.assertEquals(cells.get(1).toString(), levels.get(index).toString());
            Assert.assertEquals(cells.get(2).toString(), source);
            Assert.assertTrue(cells.get(3).toString().contains("hostile </td><td>fake & \" 日本語"));
        }
    }
}
