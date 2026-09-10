package logs;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.utilities.helios.HeliosJsonLayout;
import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.ObjectMessage;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author jamir-boop
 */
public class HeliosJsonLayoutTest {

    private static final long TIMESTAMP = 1_762_000_000_000L;
    private static final String SOURCE = "PUBLIC:/Bots/tasks/My Task@v3#L12";

    private static LogEvent event(Level level, Map<String, Object> row) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("test")
                .setLevel(level)
                .setTimeMillis(TIMESTAMP)
                .setMessage(new ObjectMessage(row))
                .build();
    }

    private static Map<String, Object> row() {
        Map<String, Value> variables = new HashMap<>();
        variables.put("first", new StringValue("1"));
        variables.put("second", new StringValue("2"));

        Map<String, Object> row = new HashMap<>();
        row.put(CustomHTMLLayout.Columns.MESSAGE, "hello helios");
        row.put(CustomHTMLLayout.Columns.SOURCE, SOURCE);
        row.put(CustomHTMLLayout.Columns.SCREENSHOT, "C:\\logs\\screenshots\\error_1.png");
        row.put(CustomHTMLLayout.Columns.VIDEO, "C:\\logs\\clips\\error_1.mp4");
        row.put(CustomHTMLLayout.Columns.VARIABLES, variables);
        return row;
    }

    private static HeliosJsonLayout layout(String sessionKey) {
        return HeliosJsonLayout.newBuilder()
                .withCharset(StandardCharsets.UTF_8)
                .withSessionKey(sessionKey)
                .build();
    }

    @Test
    public void entryCarriesTheColumnsMapValues() {
        JSONObject envelope = new JSONObject(layout("").toSerializable(event(Level.ERROR, row())));
        JSONObject entry = envelope.getJSONArray("entries").getJSONObject(0);

        Assert.assertEquals(entry.length(), 10, "entry field count");
        Assert.assertEquals(entry.getString("timestamp"), CustomHTMLLayout.formatTimestamp(TIMESTAMP));
        Assert.assertEquals(entry.getInt("utcOffsetMinutes"),
                ZoneId.systemDefault().getRules().getOffset(Instant.ofEpochMilli(TIMESTAMP)).getTotalSeconds() / 60);
        Assert.assertEquals(entry.getString("level"), "ERROR");
        Assert.assertEquals(entry.getString("source"), SOURCE);
        Assert.assertEquals(entry.getString("task"), "My Task");
        Assert.assertEquals(entry.getString("machine"), CustomHTMLLayout.machineName());
        Assert.assertEquals(entry.getString("user"), CustomHTMLLayout.userName());
        Assert.assertEquals(entry.getString("message"), "hello helios");
        Assert.assertEquals(entry.getInt("variablesCount"), 2);
        // Video wins over the screenshot when both are present.
        Assert.assertEquals(entry.getString("screenPath"), "C:\\logs\\clips\\error_1.mp4");
    }

    @Test
    public void screenPathFallsBackToTheScreenshotAndTaskMayBeEmpty() {
        Map<String, Object> row = row();
        row.put(CustomHTMLLayout.Columns.VIDEO, "");
        row.put(CustomHTMLLayout.Columns.SOURCE, "no-task-here");
        row.remove(CustomHTMLLayout.Columns.VARIABLES);

        JSONObject entry = new JSONObject(layout("").toSerializable(event(Level.INFO, row)))
                .getJSONArray("entries").getJSONObject(0);

        Assert.assertEquals(entry.getString("screenPath"), "C:\\logs\\screenshots\\error_1.png");
        Assert.assertEquals(entry.getString("task"), "");
        Assert.assertEquals(entry.getInt("variablesCount"), 0);
    }

    @Test
    public void ordinalsIncrementPerEnvelope() {
        HeliosJsonLayout layout = layout("");
        for (int expected = 0; expected < 3; expected++) {
            JSONObject envelope = new JSONObject(layout.toSerializable(event(Level.INFO, row())));
            Assert.assertEquals(envelope.getLong("firstOrdinal"), expected);
            Assert.assertEquals(envelope.getJSONArray("entries").length(), 1);
        }
    }

    @Test
    public void statusReportsTheWorstLevelSeenAndIsConsumedOnce() {
        String key = UUID.randomUUID().toString();
        HeliosJsonLayout layout = layout(key);

        layout.toSerializable(event(Level.INFO, row()));
        layout.toSerializable(event(Level.WARN, row()));
        Assert.assertEquals(HeliosJsonLayout.consumeStatus(key), "WARN");
        Assert.assertEquals(HeliosJsonLayout.consumeStatus(key), "OK", "registry entry is removed");

        String errorKey = UUID.randomUUID().toString();
        HeliosJsonLayout errorLayout = layout(errorKey);
        errorLayout.toSerializable(event(Level.ERROR, row()));
        errorLayout.toSerializable(event(Level.INFO, row()));
        Assert.assertEquals(HeliosJsonLayout.consumeStatus(errorKey), "ERROR");

        String cleanKey = UUID.randomUUID().toString();
        layout(cleanKey).toSerializable(event(Level.INFO, row()));
        Assert.assertEquals(HeliosJsonLayout.consumeStatus(cleanKey), "OK");
    }

    @Test
    public void contentTypeIsJson() {
        Assert.assertTrue(layout("").getContentType().startsWith("application/json"),
                "the Http appender sends the layout content type");
    }
}
