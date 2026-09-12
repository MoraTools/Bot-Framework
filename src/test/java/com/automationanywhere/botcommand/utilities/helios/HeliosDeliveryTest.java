package com.automationanywhere.botcommand.utilities.helios;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
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
                Assert.assertFalse(Files.exists(healthy));
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
            server.maxRequestBytes = entry(0).toString().getBytes(StandardCharsets.UTF_8).length;
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
        final Map<String, Integer> ends = new ConcurrentHashMap<>();
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
                    String id = UUID.nameUUIDFromBytes(execution.getBytes(StandardCharsets.UTF_8)).toString();
                    executions.put(id, execution);
                    reply = new JSONObject().put("sessionId", id);
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
            return new HeliosConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-key",
                    execution, "bot", "", "", "machine", "user", null);
        }

        @Override public void close() { server.stop(0); }
    }
}
