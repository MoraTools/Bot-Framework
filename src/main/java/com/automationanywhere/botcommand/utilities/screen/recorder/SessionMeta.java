package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * @author Sumit Kumar
 */
public final class SessionMeta {

    public final String sessionId;
    public final String logDir;
    public final int bufferSeconds;
    public final Instant startedAt;
    public final long jvmPid;
    public final EncodingMode encodingMode;

    public SessionMeta(String sessionId, String logDir, int bufferSeconds, Instant startedAt, long jvmPid,
                       EncodingMode encodingMode) {
        this.sessionId = sessionId;
        this.logDir = logDir;
        this.bufferSeconds = bufferSeconds;
        this.startedAt = startedAt;
        this.jvmPid = jvmPid;
        this.encodingMode = encodingMode != null ? encodingMode : EncodingMode.FAST;
    }

    private SessionMeta(JSONObject json) {
        this.sessionId = json.getString("sessionId");
        this.logDir = json.getString("logDir");
        this.bufferSeconds = json.getInt("bufferSeconds");
        this.startedAt = Instant.parse(json.getString("startedAt"));
        this.jvmPid = json.getLong("jvmPid");
        this.encodingMode = EncodingMode.valueOf(json.getString("encodingMode"));
    }

    public static SessionMeta read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return new SessionMeta(new JSONObject(text));
    }

    public void write(Path file) throws IOException {
        JSONObject json = new JSONObject();
        json.put("sessionId", sessionId);
        json.put("logDir", logDir);
        json.put("bufferSeconds", bufferSeconds);
        json.put("startedAt", startedAt.toString());
        json.put("jvmPid", jvmPid);
        json.put("encodingMode", encodingMode.name());

        Files.createDirectories(file.getParent());
        Files.write(file, json.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
