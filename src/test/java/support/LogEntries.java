package support;

import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** The entries of an HTML log: a minimal entry to write, the complete entries in a file, and a fragment count. */
public final class LogEntries {
    private LogEntries() { }

    public static Map<String, Object> entry(String message, String source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(CustomHTMLLayout.Columns.MESSAGE, message);
        row.put(CustomHTMLLayout.Columns.SOURCE, source);
        return row;
    }

    public static long count(Path log) throws IOException {
        return Files.readString(log, StandardCharsets.UTF_8).lines()
                .filter(line -> line.equals("<!-- a360-log-entry:end -->")).count();
    }

    /** How often a fragment occurs in an HTML text. */
    public static int occurrences(String html, String part) {
        return html.split(Pattern.quote(part), -1).length - 1;
    }
}
