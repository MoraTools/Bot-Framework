package com.automationanywhere.botcommand.utilities.helios;

import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import support.ChildJvm;
import support.TempDirectories;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static support.Polling.await;

public class HeliosDeliveryTest {
    private final TempDirectories temp = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void deleteTemporaryFolders() { temp.deleteAll(); }

    @Test
    public void savedEntryIsSentUnchangedAndTheStartCarriesTheIdentity() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            Path root = temp.create("helios-compact-identity");
            HeliosSink sink = sink(server.config("compact"), root, ignored -> { });
            JSONObject record = entry(0);
            record.getJSONArray("entries").getJSONObject(0)
                    .put("variables", new JSONArray().put(new JSONObject().put("name", "saved").put("value", "before")))
                    .put("media", new JSONArray().put(new JSONObject().put("kind", "screenshot").put("state", "pending")));
            sink.append(record);
            // The sink locks its journal until finish(), and a verified journal is deleted: check what the server got.
            Assert.assertTrue(sink.finish("ERROR", 1));
            JSONObject start = server.starts.get("compact");
            Assert.assertTrue(start.getBoolean("sessionIdentity"));
            Assert.assertEquals(start.getString("machine"), "machine");
            Assert.assertEquals(start.getString("user"), "user");
            JSONObject sent = server.requests("/entries").get(0).json().getJSONArray("entries").getJSONObject(0);
            Assert.assertTrue(sent.similar(record.getJSONArray("entries").getJSONObject(0)),
                    "the variable snapshot and optional media declaration stay intact");
        }
    }

    @Test
    public void supportedStreamsKeepIndependentOrdinalsAndEndsForOneAaRun() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            server.separateStreams = true;
            Path root = temp.create("helios-independent-streams");
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
            parent.stopMedia();
            child.stopMedia();
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
        try (HeliosStubServer server = new HeliosStubServer()) {
            String execution = "activity-" + "x".repeat(300);
            String id = UUID.nameUUIDFromBytes(execution.getBytes(StandardCharsets.UTF_8)).toString();
            server.startOverride = new JSONObject().put("sessionId", id.toUpperCase(java.util.Locale.ROOT))
                    .put("executionSessionId", UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT));
            HeliosSink sink = sink(server.config(execution), temp.create("helios-normalized-response"), ignored -> { });
            sink.append(entry(0));
            Assert.assertTrue(sink.finish("OK", 1));
            Assert.assertEquals(server.starts.get(execution).getString("executionId"), execution,
                    "the request keeps the exact AA execution ID");
            Assert.assertEquals(server.rows.get(id + ":0").getString("message"), "entry 0");
        }
    }

    @Test
    public void emptyEndReplyIsRetriedAndKeepsTheJournal() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            server.reply("/end", 204, "");
            Path root = temp.create("helios-empty-end");
            HeliosSink sink = sink(server.config("empty-end"), root, ignored -> { });
            sink.append(entry(0));
            Assert.assertFalse(sink.finish("OK", 1), "an empty 204 cannot verify delivery");
            Assert.assertTrue(server.requests("/end").size() >= 2, "an empty reply is retried");
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                Assert.assertTrue(paths.anyMatch(path -> path.toString().endsWith(".helios-journal")));
            }
            Assert.assertFalse(server.verified.contains("empty-end"));
        }
    }

    @Test
    public void recoveryUsesTheOriginalJournalStart() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            Path root = temp.create("helios-recovered-identity");
            server.reply("/entries", 503, "", "Retry-After", "60");
            HeliosSink first = sink(server.config("original", "old machine á", "old user 漢"), root, ignored -> { });
            first.append(entry(0));
            Assert.assertFalse(first.finish("OK", 1));
            Path journal;
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                journal = paths.filter(path -> path.toString().endsWith(".helios-journal")).findFirst().orElseThrow();
            }
            JSONObject originalStart = new JSONObject(Files.readAllLines(journal, StandardCharsets.UTF_8).get(0));
            server.clearReplies();
            HeliosSink next = sink(server.config("current", "new machine", "new user"), root, ignored -> { });
            try {
                await(() -> server.verified.contains("original"));
                await(() -> !Files.exists(journal));
                JSONObject recoveredStart = server.starts.get("original");
                Assert.assertTrue(recoveredStart.similar(originalStart), "recovery keeps the saved UUID and start metadata");
                Assert.assertEquals(recoveredStart.getString("machine"), "old machine á");
                Assert.assertEquals(server.startAttempts.get("original").intValue(), 2);
                String id = UUID.nameUUIDFromBytes("original".getBytes(StandardCharsets.UTF_8)).toString();
                Assert.assertEquals(server.rows.get(id + ":0").getString("timestamp"), "original 0");
            } finally { Assert.assertTrue(next.finish("OK", 0)); }
        }
    }

    @Test
    public void lostResponseAndMiddleGapAreReplayedBeforeDeletion() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            Path root = temp.create("helios-delivery");
            server.loseResponse.set(true);
            server.omitOnce.set(9);
            HeliosSink sink = sink(server.config("first"), root, ignored -> { });
            for (int index = 0; index < 20; index++) sink.append(entry(index));
            Assert.assertTrue(sink.finish("OK", 20));
            Assert.assertEquals(server.rows.size(), 20);
            Assert.assertTrue(server.verified.contains("first"));
            java.util.List<HeliosStubServer.Request> batches = server.requests("/entries");
            Assert.assertTrue(batches.size() >= 2);
            Assert.assertEquals(batches.get(0).json().toString(), batches.get(1).json().toString(), "retry sends the exact batch");
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                Assert.assertFalse(files.anyMatch(path -> path.toString().endsWith(".helios-journal")));
            }
        }
    }

    @Test
    public void pendingCloseSurvivesRestartAndRetryAfterDoesNotBusyLoop() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            Path root = temp.create("helios-recovery");
            server.reply("/entries", 503, "", "Retry-After", "60");
            HeliosSink first = sink(server.config("first"), root, ignored -> { });
            first.append(entry(0));
            Assert.assertFalse(first.finish("OK", 1));
            Assert.assertEquals(server.requests("/entries").size(), 1, "Retry-After: 60 prevents repeated requests");
            server.clearReplies();
            HeliosSink next = sink(server.config("next"), root, ignored -> { });
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
        try (HeliosStubServer server = new HeliosStubServer()) {
            HeliosSink sink = sink(server.config("timer"), temp.create("helios-batching"), ignored -> { });
            try {
                for (int index = 0; index < 50; index++) sink.append(entry(index));
                await(() -> server.rows.size() == 50);
                Assert.assertEquals(server.requests("/entries").get(0).json().getJSONArray("entries").length(), 50);
                sink.append(entry(50));
                await(() -> server.rows.size() == 51);
            } finally {
                Assert.assertTrue(sink.finish("OK", 51));
            }
        }
    }

    @Test
    public void incompleteAndDamagedJournalsDoNotBlockLaterRecovery() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            Path root = temp.create("helios-recovery-gaps");
            Path gap = seed(root, server.config("gap"), "a", 2, false);
            Path torn = seed(root, server.config("torn"), "b", 1, true);
            Path healthy = seed(root, server.config("healthy"), "c", 1, false);
            HeliosSink sink = sink(server.config("current"), root, ignored -> { });
            try {
                await(() -> server.verified.contains("healthy"));
                Assert.assertEquals(server.ends.get("gap").intValue(), 2, "only one full repair pass");
                String tornId = UUID.nameUUIDFromBytes("torn".getBytes(StandardCharsets.UTF_8)).toString();
                Assert.assertTrue(server.rows.containsKey(tornId + ":0"), "saved prefix must reach Helios");
                Assert.assertFalse(server.verified.contains("gap"));
                Assert.assertFalse(server.verified.contains("torn"));
                await(() -> !Files.exists(gap) && Files.exists(unverified(gap)));
                await(() -> !Files.exists(torn) && Files.exists(unverified(torn)));
                // The server flag precedes response delivery and the client's durable receipt handoff.
                await(() -> !Files.exists(healthy));
            } finally { Assert.assertTrue(sink.finish("OK", 0)); }
        }
    }

    @Test
    public void oversizedEntryDoesNotBlockFollowingValidEntries() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            server.maxEntriesBytes = 256;
            Path root = temp.create("helios-oversized");
            HeliosSink sink = sink(server.config("large"), root, ignored -> { });
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
        try (HeliosStubServer server = new HeliosStubServer()) {
            server.maxEntriesBytes = entry(0).toString().getBytes(StandardCharsets.UTF_8).length;
            HeliosSink sink = sink(server.config("split"), temp.create("helios-split"), ignored -> { });
            sink.append(entry(0));
            sink.append(entry(1));
            Assert.assertTrue(sink.finish("OK", 2));
            Assert.assertEquals(server.rows.size(), 2);
        }
    }

    @Test
    public void aJournalLockedByAnotherProcessDoesNotEmitAWarning() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            Path root = temp.create("helios-other-process");
            Path held = seed(root, server.config("held"), "a", 1, false);
            java.util.List<String> classpath = ChildJvm.classPath(HeliosJournalTest.class, HeliosJournal.class, JSONObject.class, Assert.class);
            Process child = new ProcessBuilder(ChildJvm.command(HeliosJournalTest.class, classpath, java.util.List.of(), held.toString()))
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try {
                java.io.BufferedReader output = new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream()));
                Assert.assertEquals(output.readLine(), "locked");
                java.util.List<String> warnings = new CopyOnWriteArrayList<>();
                HeliosSink sink = sink(server.config("current"), root, warnings::add);
                Assert.assertTrue(sink.finish("OK", 0));
                Assert.assertTrue(warnings.isEmpty(), warnings.toString());
                Assert.assertTrue(Files.exists(held));
            } finally {
                child.getOutputStream().close();
                if (!child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly();
            }
        }
    }

    @Test
    public void crashedJournalIsSentOnceThenKeptAsUnverified() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            Path root = temp.create("helios-crashed-once");
            Path crashed = seed(root, server.config("crashed"), "d", -1, false);
            java.util.List<String> warnings = new CopyOnWriteArrayList<>();
            HeliosSink sink = sink(server.config("current"), root, warnings::add);
            try {
                String id = UUID.nameUUIDFromBytes("crashed".getBytes(StandardCharsets.UTF_8)).toString();
                await(() -> server.rows.containsKey(id + ":0"));
                await(() -> !Files.exists(crashed) && Files.exists(unverified(crashed)));
            } finally { Assert.assertTrue(sink.finish("OK", 0)); }
            Assert.assertTrue(sink(server.config("later"), root, ignored -> { }).finish("OK", 0));
            Assert.assertEquals(server.startAttempts.get("crashed").intValue(), 1, "a later start does not send it again");
            Assert.assertTrue(server.requests("/end").stream().noneMatch(request -> request.path.contains(
                    UUID.nameUUIDFromBytes("crashed".getBytes(StandardCharsets.UTF_8)).toString())));
            Assert.assertTrue(Files.exists(unverified(crashed)));
            Assert.assertFalse(warnings.stream().anyMatch(text -> text.contains("was renamed to")));
        }
    }

    @Test
    public void crashedJournalWhoseEntriesHeliosRefusesKeepsItsName() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            server.reply("/entries", 503, "", "Retry-After", "60");
            Path root = temp.create("helios-crashed-unsent");
            Path crashed = seed(root, server.config("crashed"), "d", -1, false);
            HeliosSink sink = sink(server.config("current"), root, ignored -> { });
            try {
                await(() -> server.requests("/entries").stream().anyMatch(request -> request.path.contains(
                        UUID.nameUUIDFromBytes("crashed".getBytes(StandardCharsets.UTF_8)).toString())));
            } finally { sink.finish("OK", 0); }
            Assert.assertTrue(Files.exists(crashed));
            Assert.assertFalse(Files.exists(unverified(crashed)));
        }
    }

    @Test
    public void crashedJournalWithAnOversizedEntryIsRenamedAfterItsOtherRecordsAreStored() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            server.maxEntriesBytes = 256;
            Path root = temp.create("helios-crashed-oversized");
            Path crashed = seed(root, server.config("crashed"), "e", -1, false);
            try (HeliosJournal journal = new HeliosJournal(crashed, null)) {
                JSONObject large = entry(1);
                large.getJSONArray("entries").getJSONObject(0).put("message", "x".repeat(1024));
                journal.append(large);
                journal.append(entry(2));
            }
            java.util.List<String> warnings = new CopyOnWriteArrayList<>();
            HeliosSink sink = sink(server.config("current"), root, warnings::add);
            String id = UUID.nameUUIDFromBytes("crashed".getBytes(StandardCharsets.UTF_8)).toString();
            try {
                await(() -> !Files.exists(crashed) && Files.exists(unverified(crashed)));
                await(() -> warnings.stream().anyMatch(text -> text.contains("was renamed to")));
                Assert.assertEquals(server.rows(id).keySet(), new java.util.TreeSet<>(java.util.List.of(0, 2)));
                Assert.assertEquals(warnings.stream().filter(text -> text.contains("was renamed to")).collect(
                        java.util.stream.Collectors.toList()), java.util.List.of(
                        "An older Helios journal was renamed to e.helios-journal.unverified and kept for inspection because "
                                + "an oversized entry was retained. It will not be sent again."));
                Assert.assertTrue(server.requests("/end").stream().noneMatch(request -> request.path.contains(id)));
            } finally { Assert.assertTrue(finishAndStop(sink, "OK", 0)); }
            Assert.assertTrue(finishAndStop(sink(server.config("later"), root, ignored -> { }), "OK", 0));
            Assert.assertEquals(server.startAttempts.get("crashed").intValue(), 1);
        }
    }

    @Test
    public void tornJournalWithAnOversizedEntryIsRenamed() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            server.maxEntriesBytes = 256;
            Path root = temp.create("helios-torn-oversized");
            Path torn = seed(root, server.config("torn"), "e", -1, false);
            try (HeliosJournal journal = new HeliosJournal(torn, null)) {
                JSONObject large = entry(1);
                large.getJSONArray("entries").getJSONObject(0).put("message", "x".repeat(1024));
                journal.append(large);
                journal.append(entry(2));
            }
            Files.writeString(torn, "{partial", java.nio.file.StandardOpenOption.APPEND);
            java.util.List<String> warnings = new CopyOnWriteArrayList<>();
            HeliosSink sink = sink(server.config("current"), root, warnings::add);
            String id = UUID.nameUUIDFromBytes("torn".getBytes(StandardCharsets.UTF_8)).toString();
            try {
                await(() -> !Files.exists(torn) && Files.exists(unverified(torn)));
                await(() -> warnings.stream().anyMatch(text -> text.contains("was renamed to")));
                Assert.assertEquals(server.rows(id).keySet(), new java.util.TreeSet<>(java.util.List.of(0, 2)));
                Assert.assertTrue(warnings.contains("A damaged journal was retained after sending its complete records."));
                Assert.assertEquals(warnings.stream().filter(text -> text.contains("was renamed to")).collect(
                        java.util.stream.Collectors.toList()), java.util.List.of(
                        "An older Helios journal was renamed to e.helios-journal.unverified and kept for inspection because "
                                + "an oversized entry was retained. It will not be sent again."));
                Assert.assertTrue(server.requests("/end").stream().noneMatch(request -> request.path.contains(id)));
            } finally { Assert.assertTrue(finishAndStop(sink, "OK", 0)); }
            Assert.assertTrue(finishAndStop(sink(server.config("later"), root, ignored -> { }), "OK", 0));
            Assert.assertEquals(server.startAttempts.get("torn").intValue(), 1);
        }
    }

    @Test
    public void recoveryJournalWithAnOversizedEntryIsRenamedAfterOneReplayPassAndNotSentAgain() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            server.maxEntriesBytes = 256;
            Path root = temp.create("helios-recovery-oversized");
            Path saved = seed(root, server.config("oversized"), "a", -1, false);
            try (HeliosJournal journal = new HeliosJournal(saved, null)) {
                JSONObject large = entry(1);
                large.getJSONArray("entries").getJSONObject(0).put("message", "x".repeat(1024));
                journal.append(large);
                journal.append(entry(2));
                journal.append(closing(3));
            }
            java.util.List<String> warnings = new CopyOnWriteArrayList<>();
            HeliosSink sink = sink(server.config("current"), root, warnings::add);
            String id = UUID.nameUUIDFromBytes("oversized".getBytes(StandardCharsets.UTF_8)).toString();
            try {
                await(() -> !Files.exists(saved) && Files.exists(unverified(saved)));
                await(() -> warnings.stream().anyMatch(text -> text.contains("was renamed to")));
                Assert.assertEquals(server.rows(id).keySet(), new java.util.TreeSet<>(java.util.List.of(0, 2)));
                Assert.assertEquals(server.ends.get("oversized").intValue(), 2, "only one replay pass");
                for (HeliosStubServer.Request request : server.requests("/end"))
                    if (request.path.contains(id)) Assert.assertEquals(request.json().getInt("expectedEntries"), 3);
                Assert.assertEquals(warnings.stream().filter(text -> text.contains("was renamed to")).collect(
                        java.util.stream.Collectors.toList()), java.util.List.of(
                        "An older Helios journal was renamed to a.helios-journal.unverified and kept for inspection because "
                                + "an oversized entry was retained. It will not be sent again."));
                Assert.assertFalse(warnings.contains("Delivery is incomplete after replay; the journal was retained for inspection."));
            } finally { Assert.assertTrue(finishAndStop(sink, "OK", 0)); }
            Assert.assertTrue(finishAndStop(sink(server.config("later"), root, ignored -> { }), "OK", 0));
            Assert.assertEquals(server.startAttempts.get("oversized").intValue(), 1);
            Assert.assertEquals(server.ends.get("oversized").intValue(), 2);
            Assert.assertTrue(Files.exists(unverified(saved)));
        }
    }

    @Test
    public void recoveryJournalsThatStayIncompleteAfterReplayAreRenamed() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            Path root = temp.create("helios-recovery-incomplete");
            Path first = seed(root, server.config("first-gap"), "a", 2, false);
            Path second = seed(root, server.config("second-gap"), "b", 2, false);
            java.util.List<String> warnings = new CopyOnWriteArrayList<>();
            HeliosSink sink = sink(server.config("current"), root, warnings::add);
            try {
                await(() -> !Files.exists(first) && Files.exists(unverified(first))
                        && !Files.exists(second) && Files.exists(unverified(second)));
                await(() -> warnings.stream().filter(text -> text.contains("was renamed to")).count() == 2);
                Assert.assertEquals(server.ends.get("first-gap").intValue(), 2);
                Assert.assertEquals(server.ends.get("second-gap").intValue(), 2);
                Assert.assertEquals(warnings.stream().filter(text -> text.contains("was renamed to")).collect(
                        java.util.stream.Collectors.toList()), java.util.List.of(
                        "An older Helios journal was renamed to a.helios-journal.unverified and kept for inspection because "
                                + "delivery is incomplete after replay. It will not be sent again.",
                        "An older Helios journal was renamed to b.helios-journal.unverified and kept for inspection because "
                                + "delivery is incomplete after replay. It will not be sent again."));
                Assert.assertFalse(warnings.contains("Delivery is incomplete after replay; the journal was retained for inspection."));
            } finally { Assert.assertTrue(finishAndStop(sink, "OK", 0)); }
            Assert.assertTrue(finishAndStop(sink(server.config("later"), root, ignored -> { }), "OK", 0));
            Assert.assertEquals(server.startAttempts.get("first-gap").intValue(), 1);
            Assert.assertEquals(server.startAttempts.get("second-gap").intValue(), 1);
        }
    }

    @Test
    public void theCurrentSessionsIncompleteJournalKeepsItsNameUntilTheNextStart() throws Exception {
        try (HeliosStubServer server = new HeliosStubServer()) {
            server.maxEntriesBytes = 256;
            Path root = temp.create("helios-current-incomplete");
            java.util.List<String> warnings = new CopyOnWriteArrayList<>();
            HeliosSink first = sink(server.config("incomplete"), root, warnings::add);
            first.append(entry(0));
            JSONObject large = entry(1);
            large.getJSONArray("entries").getJSONObject(0).put("message", "x".repeat(1024));
            first.append(large);
            first.append(entry(2));
            Assert.assertFalse(finishAndStop(first, "OK", 3));
            Path saved;
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                saved = files.filter(path -> path.toString().endsWith(".helios-journal")).findFirst().orElseThrow();
            }
            Assert.assertTrue(Files.exists(saved));
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                Assert.assertFalse(files.anyMatch(path -> path.toString().endsWith(".unverified")));
            }
            Assert.assertFalse(warnings.stream().anyMatch(text -> text.contains("was renamed to")));
            Assert.assertTrue(warnings.contains("Delivery is incomplete after replay; the journal was retained for inspection."));
            java.util.List<String> recoveryWarnings = new CopyOnWriteArrayList<>();
            HeliosSink second = sink(server.config("next"), root, recoveryWarnings::add);
            try {
                await(() -> !Files.exists(saved) && Files.exists(unverified(saved)));
                await(() -> recoveryWarnings.stream().anyMatch(text -> text.contains("was renamed to")));
                Assert.assertEquals(recoveryWarnings.stream().filter(text -> text.contains("was renamed to")).count(), 1L);
            } finally { Assert.assertTrue(finishAndStop(second, "OK", 0)); }
            int ends = server.ends.get("incomplete");
            Assert.assertTrue(finishAndStop(sink(server.config("later"), root, ignored -> { }), "OK", 0));
            Assert.assertEquals(server.ends.get("incomplete").intValue(), ends);
        }
    }

    private static JSONObject closing(int expected) {
        return new JSONObject().put("status", "OK").put("expectedEntries", expected);
    }

    /** A finished sink keeps its media worker for a moment: stop it, so that it cannot recreate the folder that the class deletes. */
    private static boolean finishAndStop(HeliosSink sink, String status, int expected) throws Exception {
        try { return sink.finish(status, expected); }
        finally { sink.stopMedia(); }
    }

    private static Path unverified(Path journal) { return journal.resolveSibling(journal.getFileName() + ".unverified"); }

    private static HeliosSink sink(HeliosConfig config, Path root, java.util.function.Consumer<String> warning) throws Exception {
        return new HeliosSink(config, root, warning, UUID.randomUUID().toString());
    }

    private static Path seed(Path root, HeliosConfig config, String name, int expected, boolean torn) throws Exception {
        Path directory = root.resolve(HeliosSink.hex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((config.baseUrl + "\0" + config.ingestKey).getBytes(StandardCharsets.UTF_8))));
        Files.createDirectories(directory);
        Path path = directory.resolve(name + ".helios-journal");
        JSONObject start = new JSONObject().put("executionId", config.executionId)
                .put("loggerSessionId", UUID.randomUUID().toString()).put("sessionIdentity", true);
        try (HeliosJournal journal = new HeliosJournal(path, start)) {
            journal.append(entry(0));
            if (!torn && expected >= 0) journal.append(closing(expected));
        }
        if (torn) Files.writeString(path, "{partial", java.nio.file.StandardOpenOption.APPEND);
        return path;
    }

    private static JSONObject entry(int index) {
        return new JSONObject().put("firstOrdinal", index).put("entries", new JSONArray()
                .put(new JSONObject().put("message", "entry " + index).put("timestamp", "original " + index)));
    }
}
