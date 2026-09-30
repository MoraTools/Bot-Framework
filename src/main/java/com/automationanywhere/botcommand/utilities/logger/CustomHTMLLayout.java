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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * @author Sumit Kumar
 */
@Plugin(name = "CustomHTMLLayout", category = Node.CATEGORY, elementType = Layout.ELEMENT_TYPE, printObject = true)
public class CustomHTMLLayout extends AbstractStringLayout {
    static final String FORMAT_MARKER = "<!-- a360-log-format:3 -->";
    static final String HEADER_END = "<!-- a360-log-header:end -->\n";
    static final String ENTRY_END = "<!-- a360-log-entry:end -->\n";
    private static final String templatePath = "/templates/log.html";
    private static final byte[] header;
    private static final String machine;
    private static final String user;

    static {
        try (InputStream template = Objects.requireNonNull(CustomHTMLLayout.class.getResourceAsStream(templatePath))) {
            // Normalize marker newlines and omit static-template indentation to reduce file size.
            header = new String(template.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replaceAll("(?m)^ +", "").getBytes(StandardCharsets.UTF_8);
            machine = InetAddress.getLocalHost().getHostName();
            user = System.getProperty("user.name");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final DateTimeFormatter dateFormat = DateTimeFormatter
            .ofPattern("yyyy-MMM-dd HH:mm:ss Z")
            .withZone(ZoneId.systemDefault());

    public CustomHTMLLayout(Charset charset) {
        super(charset);
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

    /** Host name shown in the Machine column. */
    public static String machineName() {
        return machine;
    }

    /** Account name shown in the User column. */
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
            screenCell = HTMLGenerator.getScreenCellHTML(screenshotPath, videoPath, videoPosterPath);
        } else {
            message = event.getMessage().getFormattedMessage();
        }

        // Get the level as a CSS class for styling
        String levelClass = "level-" + event.getLevel().toString();

        formattedContent = String.format(
                "<tr class='log-entry %s'>"
                        + "<td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td>"
                        + "<td class='message-cell'>%s</td><td>%s</td><td>%s</td></tr>\n" + ENTRY_END,
                StringEscapeUtils.escapeHtml4(levelClass),
                StringEscapeUtils.escapeHtml4(formatTimestamp(event.getTimeMillis())),
                StringEscapeUtils.escapeHtml4(event.getLevel().toString()),
                StringEscapeUtils.escapeHtml4(sourceBotPath),
                StringEscapeUtils.escapeHtml4(machine),
                StringEscapeUtils.escapeHtml4(user),
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

        private Builder() {
        }

        public Builder withCharset(Charset charset) {
            this.charset = charset;
            return this;
        }

        @Override
        public CustomHTMLLayout build() {
            return new CustomHTMLLayout(charset);
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
