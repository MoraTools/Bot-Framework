package com.automationanywhere.botcommand.utilities.logger;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.*;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.record.Record;
import com.automationanywhere.botcommand.data.model.table.Table;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.StringEscapeUtils;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import java.util.List;
import java.util.Locale;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @author Sumit Kumar
 */

public class HTMLGenerator {
    private static final String NULL = "NULL";
    // Start once per run so interior spaces do not cause quadratic backtracking.
    private static final Pattern TRAILING_SPACES = Pattern.compile("(?<! )( +)(?=\\n|$)", Pattern.MULTILINE);
    private static final String NL_MARKER = "<span class='ws-marker ws-nl' aria-hidden='true'>↵</span>\n";
    private static final String TAB_MARKER = "<span class='ws-marker ws-tab' aria-hidden='true'>→</span>\t";

    /**
     * HTML-escapes the input and inserts visible glyphs for newlines, tabs,
     * and trailing spaces so users can spot whitespace bugs in rendered
     * logs. Markers carry aria-hidden and rely on user-select: none in CSS
     * so they do not leak into copy/paste.
     */
    public static String escapeWithWhitespaceMarkers(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        String escaped = StringEscapeUtils.escapeHtml4(s);

        escaped = TRAILING_SPACES.matcher(escaped).replaceAll(match ->
                "<span class='ws-trail'><span class='ws-marker' aria-hidden='true'>"
                        + "·".repeat(match.end() - match.start()) + "</span>" + match.group() + "</span>");

        if (escaped.indexOf('\t') >= 0) {
            escaped = escaped.replace("\t", TAB_MARKER);
        }
        if (escaped.indexOf('\n') >= 0) {
            escaped = escaped.replace("\n", NL_MARKER);
        }
        return escaped;
    }

    /**
     * The type label shown next to a variable: the value class simple name without the
     * {@code Object} / {@code Value} suffix, upper-cased. {@code NULL} when the value is null.
     */
    public static String typeLabel(Value value) {
        if (value == null) {
            return NULL;
        }
        return value.getClass().getSimpleName().toLowerCase(Locale.ROOT)
                .replace("object", "")
                .replace("value", "").toUpperCase(Locale.ROOT);
    }

    /** Renders a complete variable snapshot inside its log entry, without file I/O. */
    public static String generateVariablesHTML(Map<String, Value> valueMap) {
        if (valueMap == null || valueMap.isEmpty()) return "";
        StringBuilder html = new StringBuilder("<details class='entry-variables'><summary>");
        html.append("<span class='variable-count'>").append(valueMap.size())
                .append(valueMap.size() == 1 ? " variable" : " variables")
                .append("</span></summary><div class='variable-list'>");
        for (Map.Entry<String, Value> entry : valueMap.entrySet()) {
            appendVariableCard(entry.getKey() == null ? NULL : entry.getKey(), entry.getValue(), html, true);
        }
        return html.append("</div></details>").toString();
    }

    /** Stores one lossless snapshot; the shared dialog expands it only when requested. */
    public static String generateVariablesButton(Map<String, Value> values) {
        String html = generateVariablesHTML(values);
        if (html.isEmpty()) return "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(html.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot compress log variables", e);
        }
        return "<button type='button' class='vars-link' data-vars='"
                + Base64.getEncoder().encodeToString(bytes.toByteArray())
                + "'>" + values.size() + (values.size() == 1 ? " variable" : " variables") + "</button>";
    }

    private static void appendVariableCard(String name, Value value, StringBuilder htmlBuilder, boolean showNullType) {
        htmlBuilder.append("<div class='variable-card'>")
                .append("<div class='variable-name'>")
                .append(StringEscapeUtils.escapeHtml4(name))
                .append("</div>");

        if (value != null || showNullType) {
            htmlBuilder.append(" <div class='variable-type'>")
                    .append(StringEscapeUtils.escapeHtml4(typeLabel(value)))
                    .append("</div>");
        }
        appendVariableValueWithoutCard(value, htmlBuilder);
        htmlBuilder.append("</div>");
    }

    private static void appendListValue(ListValue listValue, StringBuilder htmlBuilder) {
        List<Value> list = listValue.get();

        htmlBuilder.append("<details>")
                .append("<summary>List: ")
                .append(list.size())
                .append(" items</summary>");

        if (!list.isEmpty()) {
            // Wrap children so a single continuous thread-line on the left
            // (rendered via summary::after) sits next to them with padding,
            // and the children push to the right of the line.
            htmlBuilder.append("<div class='nested-content'>");
            for (int i = 0; i < list.size(); i++) {
                appendVariableCard("Index " + i, list.get(i), htmlBuilder, false);
            }
            htmlBuilder.append("</div>");
        }

        htmlBuilder.append("</details>");
    }

    private static void appendVariableValueWithoutCard(Value value, StringBuilder htmlBuilder) {
        if (value == null || value.get() == null) {
            htmlBuilder.append("<div class='variable-value'>NULL</div>");
            return;
        }

        String variableType = typeLabel(value);

        switch (variableType) {
            case "LIST":
                appendListValue((ListValue) value, htmlBuilder);
                break;
            case "RECORD":
                appendRecordValue((RecordValue) value, htmlBuilder);
                break;
            case "TABLE":
                appendTableValue((TableValue) value, htmlBuilder);
                break;
            case "DICTIONARY":
                appendDictionaryValue((DictionaryValue) value, htmlBuilder);
                break;
            default:
                appendScalarValue(value, htmlBuilder);
                break;
        }
    }

    private static void appendDictionaryValue(DictionaryValue dictionaryValue, StringBuilder htmlBuilder) {
        Map<String, Value> dictionaryMap = dictionaryValue.get();

        htmlBuilder.append("<details>")
                .append("<summary>Dictionary: ")
                .append(dictionaryMap.size())
                .append(" entries</summary>");

        if (!dictionaryMap.isEmpty()) {
            htmlBuilder.append("<div class='nested-content'>");
            for (Map.Entry<String, Value> entry : dictionaryMap.entrySet()) {
                appendVariableCard(entry.getKey(), entry.getValue(), htmlBuilder, false);
            }
            htmlBuilder.append("</div>");
        }

        htmlBuilder.append("</details>");
    }

    private static void appendRecordValue(RecordValue recordValue, StringBuilder htmlBuilder) {
        Record record = recordValue.get();

        htmlBuilder.append("<details class='full-width'>")
                .append("<summary>Record: ")
                .append(record.getSchema().size())
                .append(" columns</summary>")
                .append("<div class='table-container' tabindex='0' role='region' aria-label='Record values'>")
                .append("<table>")
                .append("<tr>");

        appendSchemaHeaders(record.getSchema(), htmlBuilder);
        htmlBuilder.append("<tr>");
        appendCells(record.getValues(), htmlBuilder);

        htmlBuilder.append("</tr>")
                .append("</table>")
                .append("</div>")
                .append("</details>");
    }

    private static void appendTableValue(TableValue tableValue, StringBuilder htmlBuilder) {
        Table table = tableValue.get();

        htmlBuilder.append("<details class='full-width'>")
                .append("<summary>Table: ")
                .append(table.getRows().size())
                .append(" rows x ")
                .append(table.getSchema().size())
                .append(" columns</summary>")
                .append("<div class='table-container' tabindex='0' role='region' aria-label='Table values'>")
                .append("<table>")
                .append("<tr>");

        appendSchemaHeaders(table.getSchema(), htmlBuilder);

        // Add data rows with proper nested handling
        for (int i = 0; i < table.getRows().size(); i++) {
            htmlBuilder.append("<tr>");

            appendCells(table.getRows().get(i).getValues(), htmlBuilder);

            htmlBuilder.append("</tr>");
        }

        htmlBuilder.append("</table>")
                .append("</div>")
                .append("</details>");
    }

    private static void appendSchemaHeaders(List<Schema> schema, StringBuilder htmlBuilder) {
        for (Schema column : schema) {
            htmlBuilder.append("<th>")
                    .append(StringEscapeUtils.escapeHtml4(column.getName()))
                    .append("</th>");
        }
        htmlBuilder.append("</tr>");
    }

    private static void appendCells(List<Value> values, StringBuilder htmlBuilder) {
        for (Value value : values) {
            htmlBuilder.append("<td>");
            appendValueInCell(value, htmlBuilder);
            htmlBuilder.append("</td>");
        }
    }

    // Helper method for table and record cells
    private static void appendValueInCell(Value value, StringBuilder htmlBuilder) {
        if (value == null || value.get() == null) {
            htmlBuilder.append("NULL");
            return;
        }

        if (value instanceof ListValue) {
            appendListValue((ListValue) value, htmlBuilder);
        } else if (value instanceof DictionaryValue) {
            appendDictionaryValue((DictionaryValue) value, htmlBuilder);
        } else if (value instanceof RecordValue) {
            appendRecordValue((RecordValue) value, htmlBuilder);
        } else if (value instanceof TableValue) {
            appendTableValue((TableValue) value, htmlBuilder);
        } else {
            appendScalarValue(value, htmlBuilder);
        }
    }

    private static void appendScalarValue(Value scalarValue, StringBuilder htmlBuilder) {
        String objectStringValue = "";
        if (scalarValue instanceof CredentialObject) {
            objectStringValue = ((CredentialObject) scalarValue).get().getInsecureString();
        } else if (scalarValue instanceof NumberValue
                || scalarValue instanceof StringValue
                || scalarValue instanceof DateTimeValue
                || scalarValue instanceof BooleanValue) {
            objectStringValue = scalarValue.get().toString();
        }

        htmlBuilder.append("<div class='variable-value'>")
                .append(escapeWithWhitespaceMarkers(objectStringValue))
                .append("</div>");
    }

    public static String getScreenshotHTML(String screenshotPath) {
        return getScreenshotHTML(screenshotPath, null);
    }

    private static String getScreenshotHTML(String screenshotPath, Path htmlDirectory) {
        if (screenshotPath == null || screenshotPath.isEmpty()) {
            return "";
        }

        // Create a relative path to the screenshot
        String relativePath = mediaHref(screenshotPath, "screenshots", htmlDirectory);
        String escapedRelativePath = StringEscapeUtils.escapeHtml4(relativePath);

        // Return a link to the screenshot
        return "<a href='" + escapedRelativePath + "' target='_blank' class='img-link' " +
                "aria-label='Screenshot' title='Screenshot' " +
                "style='--screenshot-preview: url(\"" + escapedRelativePath + "\")'>" +
                "<img src='" + escapedRelativePath + "' loading='lazy' decoding='async' " +
                "width='88' height='50' alt=''>" +
                "</a>";
    }

    private static String mediaHref(String mediaPath, String folder, Path htmlDirectory) {
        if (htmlDirectory == null) return folder + "/" + FilenameUtils.getName(mediaPath);
        Path target = Paths.get(mediaPath).toAbsolutePath().normalize();
        try {
            String relative = htmlDirectory.toAbsolutePath().normalize().relativize(target)
                    .toString().replace(java.io.File.separatorChar, '/');
            // URL quoting also protects paths inside the CSS preview declaration.
            return new URI(null, null, relative, null).toASCIIString()
                    .replace("'", "%27").replace("(", "%28").replace(")", "%29");
        } catch (IllegalArgumentException differentRoot) {
            return target.toUri().toASCIIString().replace("'", "%27").replace("(", "%28").replace(")", "%29");
        } catch (URISyntaxException invalidPath) {
            throw new IllegalArgumentException("Cannot link log media: " + mediaPath, invalidPath);
        }
    }

    /**
     * Returns the HTML for the renamed "Screen" column cell.
     *
     * <p>Priority:
     * <ol>
     *   <li>Video link with poster as thumbnail (recording succeeded).</li>
     *   <li>Poster-only thumbnail with a "no video" badge (recording was
     *       requested for this level but the encoder pool rejected the task,
     *       the recorder failed, or the ring had nothing to snapshot). Click
     *       opens the screenshot itself.</li>
     *   <li>Plain screenshot link (the {@code captureScreenshot=true}
     *       branch when recording was not requested for this level).</li>
     * </ol>
     * <p>Empty input on all three produces an empty cell.
     */
    public static String getScreenCellHTML(String screenshotPath,
                                           String videoPath,
                                           String videoPosterPath) {
        return getScreenCellHTML(screenshotPath, videoPath, videoPosterPath, null);
    }

    public static String getScreenCellHTML(String screenshotPath, String videoPath,
                                           String videoPosterPath, Path htmlDirectory) {
        if (videoPath != null && !videoPath.isEmpty()) {
            String videoRel = mediaHref(videoPath, "clips", htmlDirectory);
            String posterRel = (videoPosterPath != null && !videoPosterPath.isEmpty())
                    ? mediaHref(videoPosterPath, "screenshots", htmlDirectory)
                    : "";
            String videoEsc = StringEscapeUtils.escapeHtml4(videoRel);
            String posterEsc = StringEscapeUtils.escapeHtml4(posterRel);

            StringBuilder sb = new StringBuilder(256);
            sb.append("<a href='").append(videoEsc).append("' target='_blank' class='img-link video-link' ")
              .append("aria-label='Recording' title='Open recording'");
            if (!posterEsc.isEmpty()) {
                sb.append(" style='--screenshot-preview: url(\"").append(posterEsc).append("\")'");
            }
            sb.append(">");
            if (!posterEsc.isEmpty()) {
                sb.append("<img src='").append(posterEsc)
                  .append("' loading='lazy' decoding='async' width='88' height='50' alt=''>");
            }
            sb.append("<span class='play-overlay' aria-hidden='true'>&#9654;</span>")
              .append("</a>");
            return sb.toString();
        }
        if (videoPosterPath != null && !videoPosterPath.isEmpty()) {
            // Recording was requested for this row but no clip is available.
            // Show the poster (a complete artifact in its own right) and a
            // small badge so the user knows why no play icon is present.
            String posterRel = mediaHref(videoPosterPath, "screenshots", htmlDirectory);
            String posterEsc = StringEscapeUtils.escapeHtml4(posterRel);
            return "<a href='" + posterEsc + "' target='_blank' class='img-link video-unavailable' "
                    + "aria-label='Screenshot (video unavailable)' "
                    + "title='Video unavailable; opens screenshot' "
                    + "style='--screenshot-preview: url(\"" + posterEsc + "\")'>"
                    + "<img src='" + posterEsc
                    + "' loading='lazy' decoding='async' width='88' height='50' alt=''>"
                    + "<span class='no-video-badge' aria-hidden='true'>no video</span>"
                    + "</a>";
        }
        return getScreenshotHTML(screenshotPath, htmlDirectory);
    }
}
