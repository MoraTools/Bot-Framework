package com.automationanywhere.botcommand.utilities.helios;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class HeliosDeliveryTest {
    @Test
    public void capableReceiverGetsCompactEntriesWithOneSavedIdentity() throws Exception {
        try (Server server = new Server()) {
            server.sessionIdentity = true;
            Path root = Files.createTempDirectory("helios-compact-identity");
            HeliosSink sink = new HeliosSink(server.config("compact"), root, ignored -> { });
            JSONObject record = entry(0);
            record.getJSONArray("entries").getJSONObject(0)
                    .put("variables", new JSONArray().put(new JSONObject().put("name", "saved").put("value", "before")))
                    .put("media", new JSONArray().put(new JSONObject().put("kind", "screenshot").put("state", "pending")));
            sink.append(record);
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                Path journal = paths.filter(path -> path.toString().endsWith(".helios-journal")).findFirst().orElseThrow();
                java.util.List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
                JSONObject start = new JSONObject(lines.get(0));
                Assert.assertTrue(start.getBoolean("sessionIdentity"));
                Assert.assertEquals(start.getString("machine"), "machine");
                Assert.assertEquals(start.getString("user"), "user");
                Assert.assertEquals(lines.get(1), record.toString(), "capture does not depend on the start response");
            }
            Assert.assertTrue(sink.finish("ERROR", 1));
            JSONObject sent = new JSONObject(server.bodies.get(0)).getJSONArray("entries").getJSONObject(0);
            Assert.assertFalse(sent.has("machine"));
            Assert.assertFalse(sent.has("user"));
            Assert.assertTrue(sent.similar(record.getJSONArray("entries").getJSONObject(0)),
                    "the variable snapshot and optional media declaration stay intact");
        }
    }

    @DataProvider
    public Object[][] legacyCapabilities() {
        return new Object[][] { { null }, { false }, { JSONObject.NULL }, { "true" }, { 1 },
                { new JSONObject().put("version", 1) } };
    }

    @Test(dataProvider = "legacyCapabilities")
    public void absentFalseOrUnknownCapabilityGetsLegacyEntryIdentity(Object capability) throws Exception {
        try (Server server = new Server()) {
            server.sessionIdentity = capability;
            HeliosSink sink = new HeliosSink(server.config("legacy"),
                    Files.createTempDirectory("helios-legacy-identity"), ignored -> { });
            JSONObject record = entry(0);
            sink.append(record);
            Assert.assertTrue(sink.finish("OK", 1));
            JSONObject sent = new JSONObject(server.bodies.get(0)).getJSONArray("entries").getJSONObject(0);
            Assert.assertEquals(sent.getString("machine"), "machine");
            Assert.assertEquals(sent.getString("user"), "user");
            sent.remove("machine");
            sent.remove("user");
            Assert.assertTrue(sent.similar(record.getJSONArray("entries").getJSONObject(0)));
            Assert.assertFalse(record.getJSONArray("entries").getJSONObject(0).has("machine"));
            Assert.assertFalse(record.getJSONArray("entries").getJSONObject(0).has("user"));
        }
    }

    @DataProvider
    public Object[][] recoveryCapabilities() { return new Object[][] { { false }, { true } }; }

    @Test
    public void preStreamReceiverRetainsSeparateLoggerJournalsWithoutSendingRowsOrEnds() throws Exception {
        try (Server server = new Server()) {
            server.streamContract = false;
            Path root = Files.createTempDirectory("helios-pre-stream");
            java.util.List<String> parentWarnings = new CopyOnWriteArrayList<>();
            java.util.List<String> childWarnings = new CopyOnWriteArrayList<>();
            HeliosConfig config = server.config("same-aa-run");
            HeliosSink parent = new HeliosSink(config, root, parentWarnings::add, UUID.randomUUID().toString());
            HeliosSink child = new HeliosSink(config, root, childWarnings::add, UUID.randomUUID().toString());
            JSONObject parentEntry = entry(0);
            JSONObject childEntry = entry(0);
            childEntry.getJSONArray("entries").getJSONObject(0).put("message", "child ERROR");
            parent.append(parentEntry);
            child.append(childEntry);
            Assert.assertFalse(parent.finish("OK", 1));
            Assert.assertFalse(child.finish("ERROR", 1));
            Assert.assertTrue(server.bodies.isEmpty(), "no colliding ordinal is sent");
            Assert.assertTrue(server.ends.isEmpty(), "unsupported streams cannot claim a verified end");
            Assert.assertEquals(parentWarnings.size(), 1);
            Assert.assertEquals(childWarnings.size(), 1);
            Assert.assertTrue(parentWarnings.get(0).contains("did not confirm separate logger streams"));
            java.util.Set<String> savedMessages = new java.util.HashSet<>();
            java.util.Set<String> ids = new java.util.HashSet<>();
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(file -> file.toString().endsWith(".helios-journal"))
                        .collect(java.util.stream.Collectors.toList())) {
                    java.util.List<String> saved = Files.readAllLines(path, StandardCharsets.UTF_8);
                    Assert.assertEquals(saved.size(), 3, "start, exact row, and closing record all survive");
                    JSONObject start = new JSONObject(saved.get(0));
                    Assert.assertEquals(start.getString("executionId"), "same-aa-run");
                    ids.add(start.getString("loggerSessionId"));
                    JSONObject savedEntry = new JSONObject(saved.get(1));
                    Assert.assertEquals(savedEntry.getInt("firstOrdinal"), 0);
                    savedMessages.add(savedEntry.getJSONArray("entries").getJSONObject(0).getString("message"));
                    Assert.assertEquals(new JSONObject(saved.get(2)).getInt("expectedEntries"), 1);
                }
            }
            Assert.assertEquals(ids.size(), 2);
            Assert.assertEquals(savedMessages, java.util.Set.of("entry 0", "child ERROR"));
        }
    }

    @DataProvider
    public Object[][] invalidStreamResponses() {
        String id = UUID.nameUUIDFromBytes("invalid".getBytes(StandardCharsets.UTF_8)).toString();
        return new Object[][] {
                { new JSONObject().put("executionSessionId", JSONObject.NULL) },
                { new JSONObject().put("executionSessionId", "1-2-3-4-5") },
                { new JSONObject().put("executionSessionId", id) },
                { new JSONObject().put("executionId", "another-aa-run") }
        };
    }

    @Test(dataProvider = "invalidStreamResponses")
    public void invalidStreamReceiptCannotSendOrVerifyANewJournal(JSONObject response) throws Exception {
        try (Server server = new Server()) {
            server.startOverride = response;
            java.util.List<String> warnings = new CopyOnWriteArrayList<>();
            Path root = Files.createTempDirectory("helios-invalid-stream");
            HeliosSink sink = new HeliosSink(server.config("invalid"), root, warnings::add);
            sink.append(entry(0));
            Assert.assertFalse(sink.finish("OK", 1));
            Assert.assertTrue(server.bodies.isEmpty());
            Assert.assertTrue(server.ends.isEmpty());
            Assert.assertEquals(warnings.size(), 1);
            Assert.assertTrue(warnings.get(0).contains("did not confirm separate logger streams"));
            Assert.assertEquals(server.startAttempts.get("invalid").intValue(), 1);
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                Assert.assertEquals(paths.filter(path -> path.toString().endsWith(".helios-journal")).count(), 1L);
            }
        }
    }

    @Test
    public void preStreamReceiverStillRecoversLegacyJournals() throws Exception {
        try (Server server = new Server()) {
            server.streamContract = false;
            Path root = Files.createTempDirectory("helios-pre-stream-recovery");
            Path legacy = seed(root, server.config("legacy-saved"), "a", 1, false);
            HeliosSink current = new HeliosSink(server.config("new"), root, ignored -> { });
            try {
                await(() -> !Files.exists(legacy));
                Assert.assertTrue(server.verified.contains("legacy-saved"));
                Assert.assertFalse(server.starts.get("legacy-saved").has("loggerSessionId"));
                Assert.assertEquals(server.bodies.size(), 1);
            } finally { Assert.assertFalse(current.finish("OK", 0)); }
        }
    }

    @Test
    public void supportedStreamsKeepIndependentOrdinalsAndEndsForOneAaRun() throws Exception {
        try (Server server = new Server()) {
            server.separateStreams = true;
            Path root = Files.createTempDirectory("helios-independent-streams");
            String parentId = UUID.randomUUID().toString();
            String childId = UUID.randomUUID().toString();
            HeliosSink parent = new HeliosSink(server.config("same-aa-run"), root, ignored -> { }, parentId);
            HeliosSink child = new HeliosSink(server.config("same-aa-run"), root, ignored -> { }, childId);
            JSONObject childEntry = entry(0);
            childEntry.getJSONArray("entries").getJSONObject(0).put("message", "child ERROR");
            parent.append(entry(0));
            child.append(childEntry);
            Assert.assertTrue(parent.finish("OK", 1));
            Assert.assertTrue(child.finish("ERROR", 1));
            Assert.assertEquals(server.rows.size(), 2);
            Assert.assertEquals(server.rows.get(parentId + ":0").getString("message"), "entry 0");
            Assert.assertEquals(server.rows.get(childId + ":0").getString("message"), "child ERROR");
            Assert.assertEquals(server.ends.get("same-aa-run").intValue(), 2);
            Assert.assertEquals(server.startAttempts.get("same-aa-run").intValue(), 2);
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                Assert.assertFalse(paths.anyMatch(path -> path.toString().endsWith(".helios-journal")));
            }
        }
    }

    @Test
    public void supportedResponseAllowsUppercaseUuidsAndTheServersExecutionIdLimit() throws Exception {
        try (Server server = new Server()) {
            String execution = "activity-" + "x".repeat(300);
            String id = UUID.nameUUIDFromBytes(execution.getBytes(StandardCharsets.UTF_8)).toString();
            server.startOverride = new JSONObject().put("sessionId", id.toUpperCase(java.util.Locale.ROOT))
                    .put("executionSessionId", UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT));
            HeliosSink sink = new HeliosSink(server.config(execution), Files.createTempDirectory("helios-normalized-response"),
                    ignored -> { });
            sink.append(entry(0));
            Assert.assertTrue(sink.finish("OK", 1));
            Assert.assertEquals(server.starts.get(execution).getString("executionId"), execution,
                    "the request keeps the exact AA execution ID");
            Assert.assertEquals(server.rows.get(id + ":0").getString("machine"), "machine");
            Assert.assertEquals(server.rows.get(id + ":0").getString("user"), "user");
        }
    }

    @Test
    public void legacyEndResponseRetainsTheCompactJournal() throws Exception {
        try (Server server = new Server()) {
            server.legacyEnd = true;
            Path root = Files.createTempDirectory("helios-legacy-end");
            HeliosSink sink = new HeliosSink(server.config("legacy-end"), root, ignored -> { });
            sink.append(entry(0));
            Assert.assertFalse(sink.finish("OK", 1), "an empty 204 cannot verify delivery");
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                Path journal = paths.filter(path -> path.toString().endsWith(".helios-journal")).findFirst().orElseThrow();
                JSONObject saved = new JSONObject(Files.readAllLines(journal, StandardCharsets.UTF_8).get(1));
                Assert.assertFalse(saved.getJSONArray("entries").getJSONObject(0).has("machine"));
                Assert.assertFalse(saved.getJSONArray("entries").getJSONObject(0).has("user"));
            }
            Assert.assertFalse(server.verified.contains("legacy-end"));
        }
    }

    @Test(dataProvider = "recoveryCapabilities")
    public void recoveryNegotiatesAgainAndUsesTheOriginalJournalIdentity(boolean capability) throws Exception {
        try (Server server = new Server()) {
            Path root = Files.createTempDirectory("helios-recovered-identity");
            server.sessionIdentity = true;
            server.busy.set(true);
            HeliosSink first = new HeliosSink(server.config("original", "old machine á", "old user 漢"), root, ignored -> { });
            first.append(entry(0));
            Assert.assertFalse(first.finish("OK", 1));
            Path journal;
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                journal = paths.filter(path -> path.toString().endsWith(".helios-journal")).findFirst().orElseThrow();
            }
            java.util.List<String> saved = Files.readAllLines(journal, StandardCharsets.UTF_8);
            JSONObject originalStart = new JSONObject(saved.get(0));
            Assert.assertTrue(originalStart.getBoolean("sessionIdentity"));
            JSONObject savedEntry = new JSONObject(saved.get(1)).getJSONArray("entries").getJSONObject(0);
            Assert.assertFalse(savedEntry.has("machine"));
            Assert.assertFalse(savedEntry.has("user"));
            server.sessionIdentity = capability;
            server.busy.set(false);
            HeliosSink next = new HeliosSink(server.config("current", "new machine", "new user"), root, ignored -> { });
            try {
                await(() -> server.verified.contains("original"));
                await(() -> !Files.exists(journal));
                JSONObject recoveredStart = server.starts.get("original");
                Assert.assertTrue(recoveredStart.similar(originalStart), "recovery keeps the saved UUID and start metadata");
                Assert.assertEquals(server.startAttempts.get("original").intValue(), 2);
                String id = UUID.nameUUIDFromBytes("original".getBytes(StandardCharsets.UTF_8)).toString();
                JSONObject sent = server.rows.get(id + ":0");
                if (capability) {
                    Assert.assertFalse(sent.has("machine"));
                    Assert.assertFalse(sent.has("user"));
                } else {
                    Assert.assertEquals(sent.getString("machine"), "old machine á");
                    Assert.assertEquals(sent.getString("user"), "old user 漢");
                }
                Assert.assertEquals(sent.getString("timestamp"), "original 0");
            } finally { Assert.assertTrue(next.finish("OK", 0)); }
        }
    }

    @Test(dataProvider = "recoveryCapabilities")
    public void oldJournalExplicitOverridesAndEmptyValuesStayIntact(boolean capability) throws Exception {
        try (Server server = new Server()) {
            server.sessionIdentity = capability;
            Path root = Files.createTempDirectory("helios-old-identity");
            HeliosConfig original = server.config("old");
            Path path = seed(root, original, "a", 2, false);
            JSONObject start = new JSONObject().put("executionId", "old")
                    .put("machine", "old machine").put("user", "old user");
            JSONObject override = entry(0);
            override.getJSONArray("entries").getJSONObject(0).put("machine", "row machine").put("user", "");
            JSONObject blank = entry(1);
            blank.getJSONArray("entries").getJSONObject(0).put("machine", "").put("user", "row user");
            String saved = start + "\n" + override + "\n" + blank + "\n"
                    + new JSONObject().put("status", "OK").put("expectedEntries", 3) + "\n";
            Files.writeString(path, saved, StandardCharsets.UTF_8);
            HeliosSink next = new HeliosSink(server.config("current", "new machine", "new user"), root, ignored -> { });
            try {
                await(() -> server.ends.getOrDefault("old", 0) == 2);
                Assert.assertEquals(Files.readString(path, StandardCharsets.UTF_8), saved,
                        "fallback and failed end replay must not alter durable records");
                String id = UUID.nameUUIDFromBytes("old".getBytes(StandardCharsets.UTF_8)).toString();
                Assert.assertTrue(server.rows.get(id + ":0").similar(override.getJSONArray("entries").getJSONObject(0)));
                Assert.assertTrue(server.rows.get(id + ":1").similar(blank.getJSONArray("entries").getJSONObject(0)));
                Assert.assertFalse(server.starts.get("old").has("sessionIdentity"), "an old journal is not opted in during recovery");
                Assert.assertFalse(server.verified.contains("old"));
            } finally { Assert.assertTrue(next.finish("OK", 0)); }
        }
    }

    @Test
    public void lostResponseAndMiddleGapAreReplayedBeforeDeletion() throws Exception {
        try (Server server = new Server()) {
            Path root = Files.createTempDirectory("helios-delivery");
            server.loseResponse.set(true);
            server.omitMiddle.set(true);
            HeliosSink sink = new HeliosSink(server.config("first"), root, ignored -> { });
            for (int index = 0; index < 20; index++) sink.append(entry(index));
            Assert.assertTrue(sink.finish("OK", 20));
            Assert.assertEquals(server.rows.size(), 20);
            Assert.assertTrue(server.verified.contains("first"));
            Assert.assertTrue(server.bodies.size() >= 2);
            Assert.assertEquals(server.bodies.get(0), server.bodies.get(1), "retry sends the exact batch");
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                Assert.assertFalse(files.anyMatch(path -> path.toString().endsWith(".helios-journal")));
            }
        }
    }

    @Test
    public void pendingCloseSurvivesRestartAndRetryAfterDoesNotBusyLoop() throws Exception {
        try (Server server = new Server()) {
            Path root = Files.createTempDirectory("helios-recovery");
            server.busy.set(true);
            HeliosSink first = new HeliosSink(server.config("first"), root, ignored -> { });
            first.append(entry(0));
            Assert.assertFalse(first.finish("OK", 1));
            Assert.assertEquals(server.bodies.size(), 1, "Retry-After: 60 prevents repeated requests");
            server.busy.set(false);
            HeliosSink next = new HeliosSink(server.config("next"), root, ignored -> { });
            next.append(entry(0));
            try {
                await(() -> server.verified.contains("first"));
            } finally {
                Assert.assertTrue(next.finish("OK", 1));
            }
            Assert.assertTrue(server.verified.contains("next"));
        }
    }

    @Test
    public void countAndTimerFlushWithoutClosingTheLogger() throws Exception {
        try (Server server = new Server()) {
            HeliosSink sink = new HeliosSink(server.config("timer"),
                    Files.createTempDirectory("helios-batching"), ignored -> { });
            try {
                for (int index = 0; index < 50; index++) sink.append(entry(index));
                await(() -> server.rows.size() == 50);
                Assert.assertEquals(new JSONObject(server.bodies.get(0)).getJSONArray("entries").length(), 50);
                sink.append(entry(50));
                await(() -> server.rows.size() == 51);
            } finally {
                Assert.assertTrue(sink.finish("OK", 51));
            }
        }
    }

    @Test
    public void incompleteAndDamagedJournalsDoNotBlockLaterRecovery() throws Exception {
        try (Server server = new Server()) {
            Path root = Files.createTempDirectory("helios-recovery-gaps");
            Path gap = seed(root, server.config("gap"), "a", 2, false);
            Path torn = seed(root, server.config("torn"), "b", 1, true);
            Path healthy = seed(root, server.config("healthy"), "c", 1, false);
            HeliosSink sink = new HeliosSink(server.config("current"), root, ignored -> { });
            try {
                await(() -> server.verified.contains("healthy"));
                Assert.assertEquals(server.ends.get("gap").intValue(), 2, "only one full repair pass");
                String tornId = UUID.nameUUIDFromBytes("torn".getBytes(StandardCharsets.UTF_8)).toString();
                Assert.assertTrue(server.rows.containsKey(tornId + ":0"), "saved prefix must reach Helios");
                Assert.assertFalse(server.verified.contains("gap"));
                Assert.assertFalse(server.verified.contains("torn"));
                Assert.assertTrue(Files.exists(gap));
                Assert.assertTrue(Files.exists(torn));
                // The server flag precedes response delivery and the client's durable receipt handoff.
                await(() -> !Files.exists(healthy));
            } finally { Assert.assertTrue(sink.finish("OK", 0)); }
        }
    }

    @Test
    public void oversizedEntryDoesNotBlockFollowingValidEntries() throws Exception {
        try (Server server = new Server()) {
            server.maxRequestBytes = 256;
            Path root = Files.createTempDirectory("helios-oversized");
            HeliosSink sink = new HeliosSink(server.config("large"), root, ignored -> { });
            JSONObject large = entry(0);
            large.getJSONArray("entries").getJSONObject(0).put("message", "x".repeat(1024));
            sink.append(large);
            sink.append(entry(1));
            Assert.assertFalse(sink.finish("OK", 2));
            String id = UUID.nameUUIDFromBytes("large".getBytes(StandardCharsets.UTF_8)).toString();
            Assert.assertEquals(server.rows.get(id + ":1").getString("message"), "entry 1");
            Assert.assertFalse(server.verified.contains("large"));
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                Assert.assertTrue(files.anyMatch(path -> path.toString().endsWith(".helios-journal")));
            }
        }
    }

    @Test
    public void rejectedMultiEntryBatchesCanSplitWithoutLosingEntries() throws Exception {
        try (Server server = new Server()) {
            JSONObject single = entry(0);
            single.getJSONArray("entries").getJSONObject(0).put("machine", "machine").put("user", "user");
            server.maxRequestBytes = single.toString().getBytes(StandardCharsets.UTF_8).length;
            HeliosSink sink = new HeliosSink(server.config("split"),
                    Files.createTempDirectory("helios-split"), ignored -> { });
            sink.append(entry(0));
            sink.append(entry(1));
            Assert.assertTrue(sink.finish("OK", 2));
            Assert.assertEquals(server.rows.size(), 2);
        }
    }

    @Test
    public void aJournalLockedByAnotherProcessDoesNotEmitAWarning() throws Exception {
        try (Server server = new Server()) {
            Path root = Files.createTempDirectory("helios-other-process");
            Path held = seed(root, server.config("held"), "a", 1, false);
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            java.util.List<String> classpath = new java.util.ArrayList<>();
            for (Class<?> type : new Class<?>[] { HeliosJournalTest.class, HeliosJournal.class, JSONObject.class, Assert.class })
                classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            Process child = new ProcessBuilder(javaExecutable, "-cp", String.join(java.io.File.pathSeparator, classpath),
                    HeliosJournalTest.class.getName(), held.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try {
                java.io.BufferedReader output = new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream()));
                Assert.assertEquals(output.readLine(), "locked");
                java.util.List<String> warnings = new CopyOnWriteArrayList<>();
                HeliosSink sink = new HeliosSink(server.config("current"), root, warnings::add);
                Assert.assertTrue(sink.finish("OK", 0));
                Assert.assertTrue(warnings.isEmpty(), warnings.toString());
                Assert.assertTrue(Files.exists(held));
            } finally {
                child.getOutputStream().close();
                if (!child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly();
            }
        }
    }

    private static Path seed(Path root, HeliosConfig config, String name, int expected, boolean torn) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest((config.baseUrl + "\0" + config.ingestKey).getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte value : digest) hash.append(String.format("%02x", value));
        Path directory = root.resolve(hash.toString());
        Files.createDirectories(directory);
        Path path = directory.resolve(name + ".helios-journal");
        try (HeliosJournal journal = new HeliosJournal(path, new JSONObject().put("executionId", config.executionId))) {
            journal.append(entry(0));
            if (!torn) journal.append(new JSONObject().put("status", "OK").put("expectedEntries", expected));
        }
        if (torn) Files.writeString(path, "{partial", java.nio.file.StandardOpenOption.APPEND);
        return path;
    }

    private static JSONObject entry(int index) {
        return new JSONObject().put("firstOrdinal", index).put("entries", new JSONArray()
                .put(new JSONObject().put("message", "entry " + index).put("timestamp", "original " + index)));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        Assert.assertTrue(condition.getAsBoolean(), "delivery did not arrive");
    }

    private static final class Server implements AutoCloseable {
        final HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        volatile int maxRequestBytes = Integer.MAX_VALUE;
        volatile Object sessionIdentity;
        volatile boolean legacyEnd;
        volatile boolean streamContract = true;
        volatile boolean separateStreams;
        volatile JSONObject startOverride;
        final Map<String, Integer> ends = new ConcurrentHashMap<>();
        final Map<String, JSONObject> starts = new ConcurrentHashMap<>();
        final Map<String, Integer> startAttempts = new ConcurrentHashMap<>();
        final AtomicBoolean busy = new AtomicBoolean();
        final AtomicBoolean loseResponse = new AtomicBoolean();
        final AtomicBoolean omitMiddle = new AtomicBoolean();
        final Map<String, JSONObject> rows = new ConcurrentHashMap<>();
        final Map<String, String> executions = new ConcurrentHashMap<>();
        final java.util.List<String> bodies = new CopyOnWriteArrayList<>();
        final java.util.List<String> verified = new CopyOnWriteArrayList<>();

        Server() throws Exception {
            server.createContext("/api/ingest/sessions", exchange -> {
                JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String path = exchange.getRequestURI().getPath();
                int status = 200;
                JSONObject reply;
                if (path.endsWith("/sessions")) {
                    String execution = body.getString("executionId");
                    String id = separateStreams && body.has("loggerSessionId") ? body.getString("loggerSessionId")
                            : UUID.nameUUIDFromBytes(execution.getBytes(StandardCharsets.UTF_8)).toString();
                    executions.put(id, execution);
                    starts.put(execution, body);
                    startAttempts.merge(execution, 1, Integer::sum);
                    reply = new JSONObject().put("sessionId", id)
                            .put("executionId", execution.substring(0, Math.min(execution.length(), 256)));
                    if (streamContract) reply.put("executionSessionId",
                            UUID.nameUUIDFromBytes(("execution:" + execution).getBytes(StandardCharsets.UTF_8)).toString());
                    if (startOverride != null) {
                        for (String key : startOverride.keySet()) reply.put(key, startOverride.get(key));
                    }
                    if (sessionIdentity != null && body.optBoolean("sessionIdentity"))
                        reply.put("sessionIdentity", sessionIdentity);
                } else {
                    String id = path.split("/")[4];
                    if (path.endsWith("/entries")) {
                        bodies.add(body.toString());
                        if (body.toString().getBytes(StandardCharsets.UTF_8).length > maxRequestBytes) {
                            exchange.sendResponseHeaders(413, -1);
                            exchange.close();
                            return;
                        }
                        if (busy.get()) {
                            exchange.getResponseHeaders().set("Retry-After", "60");
                            exchange.sendResponseHeaders(503, -1);
                            exchange.close();
                            return;
                        }
                        JSONArray entries = body.getJSONArray("entries");
                        int first = body.getInt("firstOrdinal");
                        boolean omit = omitMiddle.getAndSet(false);
                        for (int i = 0; i < entries.length(); i++)
                            if (!omit || first + i != 9) rows.putIfAbsent(id + ":" + (first + i), entries.getJSONObject(i));
                        reply = new JSONObject().put("accepted", entries.length()).put("duplicates", 0)
                                .put("nextOrdinal", first + entries.length());
                        if (loseResponse.getAndSet(false)) {
                            exchange.close(); // storage succeeded, but the caller received no receipt
                            return;
                        }
                    } else {
                        ends.merge(executions.get(id), 1, Integer::sum);
                        if (legacyEnd) {
                            exchange.sendResponseHeaders(204, -1);
                            exchange.close();
                            return;
                        }
                        int expected = body.getInt("expectedEntries");
                        long count = rows.keySet().stream().filter(key -> key.startsWith(id + ":")).count();
                        if (count == expected) {
                            verified.add(executions.get(id));
                            reply = new JSONObject().put("complete", true).put("storedEntries", count);
                        } else {
                            status = 409;
                            reply = new JSONObject().put("code", "missingEntries");
                        }
                    }
                }
                byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        HeliosConfig config(String execution) {
            return config(execution, "machine", "user");
        }

        HeliosConfig config(String execution, String machine, String user) {
            return new HeliosConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key",
                    execution, "bot", "", "", machine, user, null);
        }

        @Override public void close() { server.stop(0); }
    }
}
