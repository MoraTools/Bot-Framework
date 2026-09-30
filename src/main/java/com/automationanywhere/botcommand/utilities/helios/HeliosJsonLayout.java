package com.automationanywhere.botcommand.utilities.helios;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;
import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serializes one log event into the Helios Cloud entries envelope.
 *
 * <p>Reads the same {@code CustomHTMLLayout.Columns} map that the HTML layout reads, so a
 * streamed row and an HTML row always describe the same event. The disk appender snapshots
 * this envelope on the caller thread, before mutable bot variables can change. The sender
 * later combines consecutive envelopes into HTTP batches.
 *
 * <p>The layout also tracks the worst level it has seen. The disk appender reads {@link #status()}
 * on close to decide the session outcome.
 *
 * @author jamir-boop
 */
public class HeliosJsonLayout {

    /** Same extraction Helios uses on parsed log files, so streamed rows group identically. */
    private static final Pattern TASK_PATTERN = Pattern.compile("[\\\\/]tasks[\\\\/](.*?)@", Pattern.CASE_INSENSITIVE);

    /** Server-side cap on streamed variables per entry; the rest are dropped. */
    private static final int MAX_VARIABLES = 100;

    /** Server-side cap on one rendered variable value; longer text is cut and flagged. */
    private static final int MAX_VALUE_CHARS = 8192;

    private final AtomicReference<Level> worst = new AtomicReference<>(Level.INFO);

    public String status() {
        Level level = worst.get();
        if (Level.ERROR.equals(level)) {
            return "ERROR";
        }
        return Level.WARN.equals(level) ? "WARN" : "OK";
    }

    /** The appender supplies the ordinal, including entries that fail to reach the journal. */
    @SuppressWarnings("unchecked")
    public JSONObject toJson(LogEvent event, int ordinal) {
        String message;
        String source = "";
        String screenPath = "";
        int variablesCount = 0;
        JSONArray variables = new JSONArray();

        Object[] parameters = event.getMessage().getParameters();
        if (parameters != null && parameters.length > 0 && parameters[0] instanceof Map) {
            Map<String, Object> messageObject = (Map<String, Object>) parameters[0];
            message = text(messageObject.get(CustomHTMLLayout.Columns.MESSAGE));
            source = text(messageObject.get(CustomHTMLLayout.Columns.SOURCE));

            String videoPath = text(messageObject.get(CustomHTMLLayout.Columns.VIDEO));
            screenPath = videoPath.isEmpty()
                    ? text(messageObject.get(CustomHTMLLayout.Columns.SCREENSHOT))
                    : videoPath;

            Object variableMap = messageObject.get(CustomHTMLLayout.Columns.VARIABLES);
            if (variableMap instanceof Map) {
                variablesCount = ((Map<?, ?>) variableMap).size();
                variables = variablesJson((Map<String, Value>) variableMap);
            }
        } else {
            message = event.getMessage().getFormattedMessage();
        }

        Level level = event.getLevel();
        worst.accumulateAndGet(level, (current, seen) -> current.isMoreSpecificThan(seen) ? current : seen);

        JSONObject entry = new JSONObject()
                .put("timestamp", CustomHTMLLayout.formatTimestamp(event.getTimeMillis()))
                .put("utcOffsetMinutes", offsetMinutes(event.getTimeMillis()))
                .put("level", level.toString())
                .put("source", source)
                .put("task", extractTask(source))
                .put("machine", CustomHTMLLayout.machineName())
                .put("user", CustomHTMLLayout.userName())
                .put("message", message)
                .put("variablesCount", variablesCount)
                .put("variables", variables)
                .put("screenPath", screenPath);

        return new JSONObject()
                .put("firstOrdinal", ordinal)
                .put("entries", new JSONArray().put(entry));
    }

    /**
     * The logged variables as the Helios entry array, in map order.
     *
     * <p>At most {@link #MAX_VARIABLES} entries are sent and each value is cut at
     * {@link #MAX_VALUE_CHARS} characters with {@code truncated} set, matching what the server
     * enforces. {@code variablesCount} still carries the count before the cap.
     */
    private static JSONArray variablesJson(Map<String, Value> variables) {
        JSONArray array = new JSONArray();
        for (Map.Entry<String, Value> variable : variables.entrySet()) {
            if (array.length() >= MAX_VARIABLES) {
                break;
            }
            String rendered = VariableText.render(variable.getValue());
            boolean truncated = rendered.length() > MAX_VALUE_CHARS;
            array.put(new JSONObject()
                    .put("name", variable.getKey())
                    .put("type", HTMLGenerator.typeLabel(variable.getValue()))
                    .put("value", truncated ? rendered.substring(0, MAX_VALUE_CHARS) : rendered)
                    .put("truncated", truncated));
        }
        return array;
    }

    /** Pulls {@code <name>} out of a {@code /tasks/<name>@} source path; empty when absent. */
    static String extractTask(String source) {
        if (source == null || source.trim().isEmpty()) {
            return "";
        }
        Matcher matcher = TASK_PATTERN.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String text(Object value) {
        return Optional.ofNullable(value).map(Object::toString).orElse("");
    }

    private static int offsetMinutes(long epochMilli) {
        Instant instant = Instant.ofEpochMilli(epochMilli);
        return ZoneId.systemDefault().getRules().getOffset(instant).getTotalSeconds() / 60;
    }
}
