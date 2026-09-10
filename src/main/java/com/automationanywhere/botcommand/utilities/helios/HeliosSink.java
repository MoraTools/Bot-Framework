package com.automationanywhere.botcommand.utilities.helios;

import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Session lifecycle calls against the Helios Cloud ingest API.
 *
 * <p>Only the two session-boundary calls go through here; the log entries themselves are
 * streamed by the Log4j2 {@code Http} appender wired up in {@code CustomLogger}.
 *
 * <p>Every failure is swallowed. At most one WARN row is written to the session's own HTML
 * log so the operator sees that streaming did not happen, and the bot never fails because
 * Helios Cloud is unreachable.
 *
 * @author jamir-boop
 */
public final class HeliosSink {

    /** Header carrying the ingest key on every request, including the appender's. */
    public static final String KEY_HEADER = "X-Helios-Ingest-Key";

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final HeliosConfig config;
    private final HttpClient http;

    private volatile Logger logger;
    private String pendingWarning;
    private boolean warned;
    private String sessionId;

    public HeliosSink(HeliosConfig config) {
        this.config = config;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(TIMEOUT);
        if (config.proxySelector != null) {
            builder.proxy(config.proxySelector);
        }
        this.http = builder.build();
    }

    /**
     * Opens the remote session.
     *
     * @return the server-assigned session id, or {@code null} when the call failed; the caller
     *         must not wire up the entries appender in that case
     */
    public String startSession() {
        try {
            ZonedDateTime now = ZonedDateTime.now();
            JSONObject body = new JSONObject()
                    .put("executionId", config.executionId)
                    .put("botUri", config.botUri)
                    .put("parentBotUri", config.parentBotUri)
                    .put("fileId", config.fileId)
                    .put("machine", config.machine)
                    .put("user", config.user)
                    .put("startedAt", LOCAL_TIME.format(now))
                    .put("utcOffsetMinutes", offsetMinutes(now));

            String response = post(config.baseUrl + "/api/ingest/sessions", body);
            String id = new JSONObject(response).optString("sessionId", "");
            if (id.isEmpty()) {
                throw new IOException("response carried no sessionId");
            }
            sessionId = id;
            return id;
        } catch (Exception e) {
            warnOnce("could not start the streaming session", e);
            return null;
        }
    }

    /** Closes the remote session. No-op when {@link #startSession()} never succeeded. */
    public void endSession(String status) {
        if (sessionId == null) {
            return;
        }
        try {
            ZonedDateTime now = ZonedDateTime.now();
            JSONObject body = new JSONObject()
                    .put("status", status)
                    .put("endedAt", LOCAL_TIME.format(now))
                    .put("utcOffsetMinutes", offsetMinutes(now));
            post(config.baseUrl + "/api/ingest/sessions/" + sessionId + "/end", body);
        } catch (Exception e) {
            warnOnce("could not close the streaming session", e);
        }
    }

    /** URL the entries appender posts to. Only valid after a successful {@link #startSession()}. */
    public String entriesUrl() {
        return config.baseUrl + "/api/ingest/sessions/" + sessionId + "/entries";
    }

    /**
     * Hands over the session logger once the Log4j2 context is running, and flushes a warning
     * that was raised before the logger existed (a failed {@code startSession}).
     */
    public synchronized void attachLogger(Logger sessionLogger) {
        this.logger = sessionLogger;
        if (pendingWarning != null) {
            write(pendingWarning);
            pendingWarning = null;
        }
    }

    private String post(String url, JSONObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header(KEY_HEADER, config.ingestKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    private synchronized void warnOnce(String what, Exception cause) {
        if (warned) {
            return;
        }
        warned = true;
        String text = "Helios Cloud streaming is disabled for this session: " + what + " (" + cause + ")";
        if (logger != null) {
            write(text);
        } else {
            pendingWarning = text;
        }
    }

    private void write(String text) {
        // Shaped like LogMessage's payload so CustomHTMLLayout renders it as a normal row.
        Map<String, Object> row = new HashMap<>();
        row.put(CustomHTMLLayout.Columns.MESSAGE, text);
        row.put(CustomHTMLLayout.Columns.SOURCE, "");
        logger.warn(row);
    }

    private static int offsetMinutes(ZonedDateTime at) {
        return at.getOffset().getTotalSeconds() / 60;
    }
}
