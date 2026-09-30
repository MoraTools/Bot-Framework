package logs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Expands actual on-disk snapshots so existing value assertions inspect decoded data. */
final class CompressedSnapshots {
    static String expand(String html) throws IOException {
        Matcher matcher = Pattern.compile("<button[^>]* data-vars='([A-Za-z0-9+/=]+)'[^>]*>[^<]*</button>").matcher(html);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(
                    Base64.getDecoder().decode(matcher.group(1))))) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(
                        new String(gzip.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        return matcher.appendTail(result).toString();
    }
}
