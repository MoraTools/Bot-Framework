package com.automationanywhere.botcommand.utilities.helios;

import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.filter.MarkerFilter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Snapshots log values to disk on the caller thread; HTTP never runs on that thread. */
public final class HeliosAppender extends AbstractAppender {
    private final HeliosJsonLayout layout = new HeliosJsonLayout(StandardCharsets.UTF_8, "");
    private final Logger logger;
    private static final String DIAGNOSTIC = "HELIOS_LOCAL_DIAGNOSTIC";
    private final AtomicBoolean warned = new AtomicBoolean();
    private HeliosSink sink;
    private int expectedEntries;
    private boolean finished;
    private boolean captureFailed;

    public HeliosAppender(String name, HeliosConfig config, Logger logger) {
        super(name, MarkerFilter.createFilter(DIAGNOSTIC, Filter.Result.DENY, Filter.Result.NEUTRAL),
                null, true, Property.EMPTY_ARRAY);
        this.logger = logger;
        try {
            sink = new HeliosSink(config, Paths.get(System.getProperty("helios.outbox.directory",
                    Paths.get(System.getProperty("user.home"), ".helios", "outbox").toString())), this::warn);
        } catch (Exception error) {
            captureFailed = true;
            warn("Cloud capture could not start. HTML logging continues; cloud logs are incomplete.");
        }
    }

    @Override public synchronized void append(LogEvent event) {
        if (finished) return;
        int ordinal = expectedEntries++;
        if (sink == null) return;
        try {
            JSONObject record = new JSONObject(layout.toSerializable(event)).put("firstOrdinal", ordinal);
            sink.append(record);
        } catch (Exception error) {
            if (!captureFailed) {
                captureFailed = true;
                warned.set(true);
                // This warning must remain visible even if a prior network warning was already emitted.
                writeWarning("Cloud logs are incomplete: a log entry could not be saved to the disk buffer. "
                        + "HTML logging continues and buffered entries are preserved.");
            }
        }
    }

    public void finish() {
        int count;
        String status;
        synchronized (this) {
            if (finished) return;
            finished = true;
            count = expectedEntries;
            status = layout.status();
        }
        // Never hold the appender lock while joining the worker; it may write a warning through us.
        if (sink != null) {
            try {
                if (!sink.finish(status, count)) warn("Helios delivery is pending. The disk journal is retained "
                        + "and will retry at the next logger startup with the same URL and ingest key.");
            } catch (Exception error) {
                warn("Helios delivery is pending. The closing record could not be saved; "
                        + "the journal is retained and HTML logging continues.");
            }
        }
    }

    private void warn(String message) {
        if (warned.compareAndSet(false, true)) writeWarning(message);
    }

    private void writeWarning(String message) {
        Map<String, Object> row = new HashMap<>();
        row.put(CustomHTMLLayout.Columns.MESSAGE, message);
        row.put(CustomHTMLLayout.Columns.SOURCE, "");
        logger.warn(MarkerManager.getMarker(DIAGNOSTIC), row);
    }
}
