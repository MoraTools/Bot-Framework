package com.automationanywhere.botcommand.utilities.helios;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one local Helios server for the tests. It starts when it is created and answers like a working Helios
 * (session start, entry batches, closing record, ERROR media) until a test changes it: with the fields below,
 * with {@link #reply} (a fixed HTTP answer for a path) or with {@link #respond} (test code that sees each request).
 * It records every request. Call {@link #close()} to stop it.
 */
public final class HeliosStubServer implements AutoCloseable {
    /** One request as the server received it. */
    public static final class Request {
        public final String method;
        public final String path;
        public final Headers headers = new Headers();
        public final byte[] content;

        Request(HttpExchange exchange, byte[] content) {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            headers.putAll(exchange.getRequestHeaders());
            this.content = content;
        }

        public String text() { return new String(content, StandardCharsets.UTF_8); }
        public JSONObject json() { return new JSONObject(text()); }
    }

    /** One answer. */
    public static final class Reply {
        /** Close the connection without an answer. */
        public static final Reply NONE = new Reply(0, "", new String[0]);
        private final int status;
        private final String body;
        private final String[] headers;

        private Reply(int status, String body, String[] headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        /** @param headers pairs of header name and value */
        public static Reply of(int status, String body, String... headers) { return new Reply(status, body, headers); }
    }

    /** Test code that answers a request. Return {@code null} to let the next rule, or Helios, answer. */
    public interface Responder {
        Reply respond(Request request) throws Exception;
    }

    // Protocol switches. The defaults are a current Helios.
    /** {@code true}: the session ID is the logger session ID of the start request, not one per AA execution. */
    public volatile boolean separateStreams;
    /** Fields that replace fields of the start reply. */
    public volatile JSONObject startOverride;
    /** The ERROR media capability in the start reply, or {@code null} for none. */
    public volatile JSONObject media;
    /** An entries request with a larger JSON body gets HTTP 413. */
    public volatile int maxEntriesBytes = Integer.MAX_VALUE;
    /** This ordinal is not stored by the next entries request, but the request is still answered as accepted. */
    public final AtomicInteger omitOnce = new AtomicInteger(-1);
    /** The next entries request is stored, but the connection closes without an answer. */
    public final AtomicBoolean loseResponse = new AtomicBoolean();

    // What the server received and stored.
    public final List<Request> requests = new CopyOnWriteArrayList<>();
    /** Stored entries by {@code <session ID>:<ordinal>}. */
    public final Map<String, JSONObject> rows = new ConcurrentHashMap<>();
    /** The last start body by AA execution ID. */
    public final Map<String, JSONObject> starts = new ConcurrentHashMap<>();
    public final Map<String, Integer> startAttempts = new ConcurrentHashMap<>();
    public final Map<String, Integer> ends = new ConcurrentHashMap<>();
    /** AA execution IDs whose end was complete. */
    public final List<String> verified = new CopyOnWriteArrayList<>();

    private final HttpServer server;
    private final Map<String, String> executions = new ConcurrentHashMap<>();
    private final List<Responder> responders = new CopyOnWriteArrayList<>();

    public HeliosStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ingest/sessions", this::handle);
        server.start();
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public HeliosConfig config(String execution) { return config(execution, "machine", "user"); }

    public HeliosConfig config(String execution, String machine, String user) {
        return new HeliosConfig(url(), "test-key", execution, "bot", "", "", machine, user, null);
    }

    public static JSONObject mediaCapability() {
        return new JSONObject().put("version", 1).put("maxScreenshotBytes", 1048576).put("maxVideoBytes", 8388608);
    }

    /** A stub with one stream per logger session that offers ERROR media. */
    public static HeliosStubServer withMedia() throws IOException {
        HeliosStubServer server = new HeliosStubServer();
        server.separateStreams = true;
        server.media = mediaCapability();
        return server;
    }

    /** The requests whose path ends with {@code suffix}: {@code /sessions}, {@code /entries}, {@code /end}. */
    public List<Request> requests(String suffix) {
        List<Request> result = new ArrayList<>();
        for (Request request : requests) if (request.path.endsWith(suffix)) result.add(request);
        return result;
    }

    /** The stored entries of one session, by ordinal. */
    public SortedMap<Integer, JSONObject> rows(String sessionId) {
        SortedMap<Integer, JSONObject> result = new TreeMap<>();
        String prefix = sessionId + ":";
        rows.forEach((key, entry) -> { if (key.startsWith(prefix)) result.put(Integer.parseInt(key.substring(prefix.length())), entry); });
        return result;
    }

    /** Answers every request whose path ends with {@code suffix}, until {@link #clearReplies()}. */
    public void reply(String suffix, int status, String body, String... headers) {
        Reply reply = Reply.of(status, body, headers);
        respond(request -> request.path.endsWith(suffix) ? reply : null);
    }

    /** Answers the next request whose path ends with {@code suffix}. */
    public void replyOnce(String suffix, int status, String body, String... headers) {
        Reply reply = Reply.of(status, body, headers);
        AtomicBoolean used = new AtomicBoolean();
        respond(request -> request.path.endsWith(suffix) && !used.getAndSet(true) ? reply : null);
    }

    /** Rules run in the order they were added. The first one that returns a reply wins. */
    public void respond(Responder responder) { responders.add(responder); }

    public void clearReplies() { responders.clear(); }

    /** The receipt Helios sends for a media request (the declaration or the bytes). */
    public static JSONObject receipt(Request request, String state) {
        String[] parts = request.path.split("/");
        String kind = parts.length == 9 ? parts[8] : request.json().getString("kind");
        return new JSONObject().put("sessionId", parts[4]).put("ordinal", Integer.parseInt(parts[6])).put("kind", kind)
                .put("state", state).put("expiresAtUtc", Instant.now().plusSeconds(86400).toString());
    }

    @Override public void close() { server.stop(0); }

    private void handle(HttpExchange exchange) throws IOException {
        Request request = new Request(exchange, exchange.getRequestBody().readAllBytes());
        requests.add(request);
        Reply reply;
        try {
            reply = answer(request);
        } catch (IOException | RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException(failure);
        }
        if (reply != Reply.NONE) {
            for (int index = 0; index + 1 < reply.headers.length; index += 2)
                exchange.getResponseHeaders().add(reply.headers[index], reply.headers[index + 1]);
            byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private Reply answer(Request request) throws Exception {
        for (Responder responder : responders) {
            Reply reply = responder.respond(request);
            if (reply != null) return reply;
        }
        String[] parts = request.path.split("/");
        if (parts.length == 4) return start(request.json());
        if (parts.length == 6 && "entries".equals(parts[5])) return entries(parts[4], request.json());
        if (parts.length == 6 && "end".equals(parts[5])) return end(parts[4], request.json());
        if (parts.length == 8) {
            JSONObject declaration = request.json();
            return json(200, receipt(request, declaration.getString("state")).put("reason", declaration.opt("reason")));
        }
        if (parts.length == 9) return json(200, receipt(request, "available")
                .put("sha256", request.headers.getFirst("X-Helios-Media-SHA256")).put("bytes", request.content.length));
        return Reply.of(404, "");
    }

    private Reply start(JSONObject body) {
        String execution = body.getString("executionId");
        String id = separateStreams && body.has("loggerSessionId") ? body.getString("loggerSessionId")
                : UUID.nameUUIDFromBytes(execution.getBytes(StandardCharsets.UTF_8)).toString();
        executions.put(id, execution);
        starts.put(execution, body);
        startAttempts.merge(execution, 1, Integer::sum);
        JSONObject reply = new JSONObject().put("sessionId", id)
                .put("executionId", execution.substring(0, Math.min(execution.length(), 256)));
        reply.put("executionSessionId",
                UUID.nameUUIDFromBytes(("execution:" + execution).getBytes(StandardCharsets.UTF_8)).toString());
        JSONObject override = startOverride;
        if (override != null) for (String key : override.keySet()) reply.put(key, override.get(key));
        if (media != null) reply.put("media", media);
        return json(200, reply);
    }

    private Reply entries(String id, JSONObject body) {
        if (body.toString().getBytes(StandardCharsets.UTF_8).length > maxEntriesBytes) return Reply.of(413, "");
        JSONArray entries = body.getJSONArray("entries");
        int first = body.getInt("firstOrdinal");
        int omitted = omitOnce.getAndSet(-1);
        int duplicates = 0;
        for (int index = 0; index < entries.length(); index++) {
            if (first + index == omitted) continue;
            if (rows.putIfAbsent(id + ":" + (first + index), entries.getJSONObject(index)) != null) duplicates++;
        }
        if (loseResponse.getAndSet(false)) return Reply.NONE;
        return json(200, new JSONObject().put("accepted", entries.length() - duplicates).put("duplicates", duplicates)
                .put("nextOrdinal", first + entries.length()));
    }

    private Reply end(String id, JSONObject body) {
        String execution = executions.get(id);
        ends.merge(execution, 1, Integer::sum);
        long count = rows.keySet().stream().filter(key -> key.startsWith(id + ":")).count();
        if (count != body.getInt("expectedEntries"))
            return json(409, new JSONObject().put("code", "missingEntries").put("storedEntries", count));
        verified.add(execution);
        return json(200, new JSONObject().put("complete", true).put("storedEntries", count));
    }

    private static Reply json(int status, JSONObject body) {
        return Reply.of(status, body.toString(), "Content-Type", "application/json");
    }
}
