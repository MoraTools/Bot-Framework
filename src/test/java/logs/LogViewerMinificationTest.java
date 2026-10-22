package logs;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;
import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.ObjectMessage;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LogViewerMinificationTest {
    @Test
    public void packagedViewerIsCompactAndKeepsModernCssAndBoundaries() throws Exception {
        byte[] packaged;
        try (java.io.InputStream input = CustomHTMLLayout.class.getResourceAsStream("/templates/log.html")) {
            packaged = input.readAllBytes();
        }
        String html = new String(packaged, StandardCharsets.UTF_8);
        Assert.assertTrue(packaged.length < Files.size(Path.of("src/main/resources/templates/log.html")) * .75);
        Assert.assertTrue(html.startsWith("<!DOCTYPE html>\n<!-- a360-log-format:6 -->\n{{identity}}\n"));
        Assert.assertTrue(html.endsWith("\n<!-- a360-log-header:end -->\n"));
        Assert.assertTrue(html.contains("<dd>{{machine}}</dd>"));
        Assert.assertTrue(html.contains("<dd>{{user}}</dd>"));
        Assert.assertTrue(html.contains("height:100vh;height:100dvh;"), "both viewport fallback declarations survive");
        Assert.assertTrue(html.contains("body:has(.log-entry) .empty-log{display:none}"));
        Assert.assertTrue(html.contains(".video-link:not(:has(img))"));
        Assert.assertTrue(html.contains(".log-entry>td:nth-child(4){white-space:pre-wrap;"));
        Assert.assertTrue(html.contains("outline:2px solid var(--primary);"));
        Assert.assertTrue(html.contains("outline-color:var(--warning)"));
        Assert.assertTrue(html.contains("outline-color:var(--danger)"));
        Assert.assertNull(getClass().getResource("/com/google/javascript/jscomp/Compiler.class"));
        Assert.assertNull(getClass().getResource("/com/helger/css/reader/CSSReader.class"));
    }

    @Test
    public void optionalCellEndTagsKeepSixCellsWithHostileTextVariablesAndMedia() throws Exception {
        long timestamp = 1700000000123L;
        String source = "PUBLIC:/tasks/<td>fake</td> & 日本語";
        String message = "hostile </td><td>fake & \" 日本語\n\ttrail  ";
        Map<String, Value> values = Map.of("nested", new DictionaryValue(Map.of(
                "</td><td>fake", new StringValue("<table><tr><td>日本語\n\ttrail  "))));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(CustomHTMLLayout.Columns.SOURCE, source);
        data.put(CustomHTMLLayout.Columns.MESSAGE, message);
        data.put(CustomHTMLLayout.Columns.VARIABLES, values);
        data.put(CustomHTMLLayout.Columns.VIDEO, "clips/movie&日本語.mp4");
        data.put(CustomHTMLLayout.Columns.VIDEO_POSTER, "screenshots/poster&日本語.png");
        StringBuilder artifact = new StringBuilder(new String(
                new CustomHTMLLayout(StandardCharsets.UTF_8).getHeader(), StandardCharsets.UTF_8));
        for (Level level : List.of(Level.INFO, Level.WARN, Level.ERROR)) {
            String row = new CustomHTMLLayout(StandardCharsets.UTF_8).toSerializable(Log4jLogEvent.newBuilder()
                    .setTimeMillis(timestamp).setLevel(level).setMessage(new ObjectMessage(data)).build());
            artifact.append(row);
            Assert.assertTrue(row.endsWith("</tr>\n<!-- a360-log-entry:end -->\n"));
            Assert.assertTrue(row.contains(HTMLGenerator.escapeWithWhitespaceMarkers(message)));
            Assert.assertTrue(CompressedSnapshots.expand(row).contains(HTMLGenerator.generateVariablesHTML(values)));
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
            Assert.assertEquals(cells.get(0).toString(), CustomHTMLLayout.formatTimestamp(timestamp));
            Assert.assertEquals(cells.get(1).toString(), level.toString());
            Assert.assertEquals(cells.get(2).toString(), source);
            Assert.assertTrue(cells.get(3).toString().contains("hostile </td><td>fake & \" 日本語"));
            Assert.assertTrue(row.contains("class='img-link video-link'"));
        }
        Path output = Path.of("src/test/target/test-artifacts/minified-cells.html");
        Files.createDirectories(output.getParent());
        Files.writeString(output, artifact, StandardCharsets.UTF_8);
    }
}
