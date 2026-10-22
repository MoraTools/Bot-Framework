package com.automationanywhere.botcommand.utilities.logger;

import com.automationanywhere.botcommand.data.Value;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.layout.AbstractStringLayout;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.Locale;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Sumit Kumar
 */
@Plugin(name = "CustomHTMLLayout", category = Node.CATEGORY, elementType = Layout.ELEMENT_TYPE, printObject = true)
public class CustomHTMLLayout extends AbstractStringLayout {
    static final String FORMAT_MARKER = "<!-- a360-log-format:6 -->";
    static final String IDENTITY_MARKER;
    static final String HEADER_END = "<!-- a360-log-header:end -->\n";
    static final String ENTRY_END = "<!-- a360-log-entry:end -->\n";
    private static final String templatePath = "/templates/log.html";
    private static final byte[] header;
    private static final String machine;
    private static final String user;

    static {
        try (InputStream template = Objects.requireNonNull(CustomHTMLLayout.class.getResourceAsStream(templatePath))) {
            machine = InetAddress.getLocalHost().getHostName();
            user = System.getProperty("user.name");
            IDENTITY_MARKER = "<!-- a360-log-identity:" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest((machine + "\0" + user).getBytes(StandardCharsets.UTF_8))) + " -->";
            // The packaged resource is minified at build time. Replace placeholders in one pass.
            String html = new String(template.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("{{identity}}", IDENTITY_MARKER);
            header = Pattern.compile("\\{\\{(machine|user)\\}\\}").matcher(html).replaceAll(match ->
                    Matcher.quoteReplacement(StringEscapeUtils.escapeHtml4(
                            String.valueOf(match.group(1).equals("machine") ? machine : user)))).getBytes(StandardCharsets.UTF_8);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static final DateTimeFormatter dateFormat = DateTimeFormatter
            .ofPattern("yyyy-MMM-dd HH:mm:ss Z", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    private final Path htmlDirectory;

    public CustomHTMLLayout(Charset charset) {
        this(charset, null);
    }

    private CustomHTMLLayout(Charset charset, String htmlDirectory) {
        super(charset);
        this.htmlDirectory = htmlDirectory == null ? null : Paths.get(htmlDirectory);
    }

    @PluginBuilderFactory
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Renders an event timestamp exactly as it appears in the first column of the HTML log.
     * Shared so streamed rows carry the same text as the file they mirror.
     */
    public static String formatTimestamp(long epochMilli) {
        return dateFormat.format(Instant.ofEpochMilli(epochMilli));
    }

    /** Host name shown in the session header. */
    public static String machineName() {
        return machine;
    }

    /** Account name shown in the session header. */
    public static String userName() {
        return user;
    }

    @Override
    public String toSerializable(LogEvent event) {
        String formattedContent;
        String message;
        String sourceBotPath = "";
        String variablesHTML = "";
        String screenCell = "";

        // Parse the message and extract column details for current row
        Object[] parameters = event.getMessage().getParameters();
        if (parameters != null && parameters.length > 0 && parameters[ 0 ] instanceof Map) {
            Map<String, Object> messageObject = (Map<String, Object>) parameters[ 0 ];
            message = Optional.ofNullable(messageObject.get(Columns.MESSAGE)).map(Object::toString).orElse("");
            sourceBotPath = Optional.ofNullable(messageObject.get(Columns.SOURCE)).map(Object::toString).orElse("");
            String screenshotPath =
                    Optional.ofNullable(messageObject.get(Columns.SCREENSHOT)).map(Object::toString).orElse("");
            String videoPath =
                    Optional.ofNullable(messageObject.get(Columns.VIDEO)).map(Object::toString).orElse("");
            String videoPosterPath =
                    Optional.ofNullable(messageObject.get(Columns.VIDEO_POSTER)).map(Object::toString).orElse("");

            if (messageObject.get(Columns.VARIABLES) instanceof Map) {
                variablesHTML = HTMLGenerator.generateVariablesButton(
                        (Map<String, Value>) messageObject.get(Columns.VARIABLES));
            }

            // Render the renamed "Screen" column cell. Video takes priority when present;
            // otherwise the existing screenshot path is used.
            screenCell = HTMLGenerator.getScreenCellHTML(screenshotPath, videoPath, videoPosterPath, htmlDirectory);
        } else {
            message = event.getMessage().getFormattedMessage();
        }

        // Get the level as a CSS class for styling
        String levelClass = "level-" + event.getLevel().toString();

        formattedContent = String.format(
                "<tr class='log-entry %s'>"
                        + "<td>%s<td>%s<td>%s<td>%s<td>%s<td>%s</tr>\n" + ENTRY_END,
                StringEscapeUtils.escapeHtml4(levelClass),
                StringEscapeUtils.escapeHtml4(formatTimestamp(event.getTimeMillis())),
                StringEscapeUtils.escapeHtml4(event.getLevel().toString()),
                StringEscapeUtils.escapeHtml4(sourceBotPath),
                HTMLGenerator.escapeWithWhitespaceMarkers(message),
                variablesHTML,
                screenCell
        );

        return formattedContent;
    }

    @Override
    public byte[] getFooter() {
        // Keep the table open so every completed row remains appendable.
        return new byte[0];
    }

    @Override
    public byte[] getHeader() {
        return header;
    }

    /**
     * Built reflectively by Log4j2 via the @PluginBuilderFactory entry point.
     * Static analysis cannot see the framework instantiation.
     */
    @SuppressWarnings("unused")
    public static class Builder implements org.apache.logging.log4j.core.util.Builder<CustomHTMLLayout> {

        @PluginBuilderAttribute
        private Charset charset;

        @PluginBuilderAttribute
        private String htmlDirectory;

        private Builder() {
        }

        public Builder withCharset(Charset charset) {
            this.charset = charset;
            return this;
        }

        public Builder withHtmlDirectory(String htmlDirectory) {
            this.htmlDirectory = htmlDirectory;
            return this;
        }

        @Override
        public CustomHTMLLayout build() {
            return new CustomHTMLLayout(charset, htmlDirectory);
        }
    }

    public static class Columns {
        public static final String SOURCE = "Source";
        public static final String MESSAGE = "Message";
        public static final String VARIABLES = "Variables";
        public static final String SCREENSHOT = "Screenshot";
        public static final String VIDEO = "Video";
        public static final String VIDEO_POSTER = "VideoPoster";
    }
}
