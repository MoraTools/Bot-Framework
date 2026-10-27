package com.automationanywhere.botcommand.utilities.helios;

import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import support.TempDirectories;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

public class HeliosJournalTest {
    private final TempDirectories temp = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void deleteTemporaryFolders() { temp.deleteAll(); }

    private static JSONObject row(int ordinal, String message) {
        return new JSONObject().put("firstOrdinal", ordinal)
                .put("entries", new org.json.JSONArray().put(new JSONObject().put("message", message)));
    }

    @Test
    public void replayKeepsOrdinalsUtf8AndBatchBounds() throws Exception {
        Path path = temp.create("helios-journal-test").resolve("session.helios-journal");
        try (HeliosJournal journal = new HeliosJournal(path, new JSONObject().put("executionId", "test"))) {
            for (int index = 0; index < 60; index++) journal.append(row(index, "á漢字 " + index));
            JSONObject batch = journal.next(HeliosJournal.BATCH_ENTRIES);
            Assert.assertEquals(batch.getJSONArray("entries").length(), 50);
            Assert.assertEquals(batch.getJSONArray("entries").getJSONObject(9).getString("message"), "á漢字 9");
            Assert.assertEquals(journal.next(HeliosJournal.BATCH_ENTRIES).toString(), batch.toString(), "no cursor advance before ack");
            journal.acknowledge(batch.getLong("journalOffset"));
            Assert.assertEquals(journal.next(HeliosJournal.BATCH_ENTRIES).getInt("firstOrdinal"), 50);
        }
        try (HeliosJournal recovered = new HeliosJournal(path, null)) {
            Assert.assertEquals(recovered.next(HeliosJournal.BATCH_ENTRIES).getInt("firstOrdinal"), 0, "restart replays safely from disk");
        }
    }

    @Test
    public void writesOneUtf8SnapshotAtTheLimitAndStillSavesTheClosingRecord() throws Exception {
        Path path = temp.create("helios-encoded-record").resolve("session.helios-journal");
        AtomicInteger serializations = new AtomicInteger();
        JSONObject record = new JSONObject() {
            @Override public String toString() {
                serializations.incrementAndGet();
                return super.toString();
            }
        };
        String message = "á漢字 😀 \n\t\"\\";
        record.put("firstOrdinal", 0).put("entries", row(0, message).getJSONArray("entries"));
        JSONObject start = new JSONObject().put("executionId", "utf8-test");
        JSONObject end = new JSONObject().put("status", "OK").put("expectedEntries", 1);
        String savedPrefix = start + "\n" + record + "\n";
        long limit = savedPrefix.getBytes(StandardCharsets.UTF_8).length;
        serializations.set(0);
        try (HeliosJournal journal = new HeliosJournal(path, start, limit)) {
            journal.append(record);
            Assert.assertEquals(serializations.get(), 1, "size check and write use the same serialization");
            Assert.assertEquals(Files.size(path), limit, "an entry at the exact byte limit is accepted");
            Assert.expectThrows(java.io.IOException.class, () -> journal.append(row(1, "over limit")));
            Assert.assertEquals(Files.size(path), limit, "a rejected entry leaves the saved bytes intact");
            journal.append(end);
        }
        Assert.assertEquals(Files.readAllBytes(path),
                (savedPrefix + end + "\n").getBytes(StandardCharsets.UTF_8));
        try (HeliosJournal recovered = new HeliosJournal(path, null)) {
            JSONObject batch = recovered.next(HeliosJournal.BATCH_ENTRIES);
            Assert.assertEquals(batch.getInt("firstOrdinal"), 0);
            Assert.assertEquals(batch.getJSONArray("entries").length(), 1);
            Assert.assertEquals(batch.getJSONArray("entries").getJSONObject(0).getString("message"), message);
            recovered.acknowledge(batch.getLong("journalOffset"));
            Assert.assertEquals(recovered.next(HeliosJournal.BATCH_ENTRIES).toString(), end.toString());
        }
    }

    @Test
    public void gapsAndLargeEntriesDoNotChangeOtherOrdinals() throws Exception {
        Path path = temp.create("helios-gap-test").resolve("session.helios-journal");
        try (HeliosJournal journal = new HeliosJournal(path, new JSONObject())) {
            journal.append(row(0, "first"));
            journal.append(row(2, "x".repeat(HeliosJournal.BATCH_BYTES)));
            journal.append(row(3, "last"));
            JSONObject first = journal.next(HeliosJournal.BATCH_ENTRIES);
            Assert.assertEquals(first.getJSONArray("entries").length(), 1);
            journal.acknowledge(first.getLong("journalOffset"));
            JSONObject large = journal.next(HeliosJournal.BATCH_ENTRIES);
            Assert.assertEquals(large.getInt("firstOrdinal"), 2);
            Assert.assertEquals(large.getJSONArray("entries").length(), 1, "large event travels alone");
            journal.acknowledge(large.getLong("journalOffset"));
            Assert.assertEquals(journal.next(HeliosJournal.BATCH_ENTRIES).getInt("firstOrdinal"), 3);
            journal.rewind();
            Assert.assertEquals(journal.next(HeliosJournal.BATCH_ENTRIES).getInt("firstOrdinal"), 0);
        }
        Files.writeString(path, "{broken", StandardOpenOption.APPEND);
        try (HeliosJournal journal = new HeliosJournal(path, null)) {
            for (int index = 0; index < 2; index++) {
                JSONObject batch = journal.next(HeliosJournal.BATCH_ENTRIES);
                journal.acknowledge(batch.getLong("journalOffset"));
            }
            JSONObject savedPrefix = journal.next(HeliosJournal.BATCH_ENTRIES);
            Assert.assertEquals(savedPrefix.getInt("firstOrdinal"), 3);
            journal.acknowledge(savedPrefix.getLong("journalOffset"));
            Assert.expectThrows(java.io.InvalidObjectException.class, () -> journal.next(HeliosJournal.BATCH_ENTRIES));
        }
        Assert.assertTrue(Files.exists(path), "a torn record must never be treated as verified");
    }

    @Test
    public void recoveryNeverRecreatesADeletedJournal() throws Exception {
        Path directory = temp.create("helios-deleted-test");
        Path missing = directory.resolve("deleted.helios-journal");
        Assert.expectThrows(java.nio.file.NoSuchFileException.class, () -> new HeliosJournal(missing, null));
        Assert.assertFalse(Files.exists(missing));
    }

    /** Separate-process lock holder used by the recovery test on Linux and Windows. */
    public static void main(String[] args) throws Exception {
        try (HeliosJournal journal = new HeliosJournal(Path.of(args[0]), null)) {
            System.out.println("locked");
            System.out.flush();
            System.in.read();
        }
    }

    @Test
    public void bufferSizeCanBeRaisedAndInvalidValuesUseTheDefault() {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        try {
            System.setProperty("helios.bufferMiB", "1024");
            Assert.assertEquals(HeliosSink.bufferBytes(warnings::add), 1024L * 1024 * 1024);
            System.setProperty("helios.bufferMiB", "0");
            Assert.assertEquals(HeliosSink.bufferBytes(warnings::add), HeliosJournal.MAX_BYTES);
            Assert.assertEquals(warnings.size(), 1);
        } finally {
            System.clearProperty("helios.bufferMiB");
        }
    }

    @Test
    public void retryAfterAcceptsSecondsAndHttpDates() {
        Assert.assertEquals(HeliosSink.retryAfterMillis("2"), 2000L);
        Assert.assertEquals(HeliosSink.retryAfterMillis("invalid"), 0L);
        Assert.assertEquals(HeliosSink.retryAfterMillis("-1"), 0L);
        long delay = HeliosSink.retryAfterMillis(ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(10)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME));
        Assert.assertTrue(delay > 8000 && delay <= 10000);
    }
}
