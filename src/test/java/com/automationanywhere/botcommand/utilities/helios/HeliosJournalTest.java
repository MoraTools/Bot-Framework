package com.automationanywhere.botcommand.utilities.helios;

import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class HeliosJournalTest {
    private static JSONObject row(int ordinal, String message) {
        return new JSONObject().put("firstOrdinal", ordinal)
                .put("entries", new org.json.JSONArray().put(new JSONObject().put("message", message)));
    }

    @Test
    public void replayKeepsOrdinalsUtf8AndBatchBounds() throws Exception {
        Path path = Files.createTempDirectory("helios-journal-test").resolve("session.helios-journal");
        try (HeliosJournal journal = new HeliosJournal(path, new JSONObject().put("executionId", "test"))) {
            for (int index = 0; index < 60; index++) journal.append(row(index, "á漢字 " + index));
            JSONObject batch = journal.next();
            Assert.assertEquals(batch.getJSONArray("entries").length(), 50);
            Assert.assertEquals(batch.getJSONArray("entries").getJSONObject(9).getString("message"), "á漢字 9");
            Assert.assertEquals(journal.next().toString(), batch.toString(), "no cursor advance before ack");
            journal.acknowledge(batch.getLong("journalOffset"));
            Assert.assertEquals(journal.next().getInt("firstOrdinal"), 50);
        }
        try (HeliosJournal recovered = new HeliosJournal(path, null)) {
            Assert.assertEquals(recovered.next().getInt("firstOrdinal"), 0, "restart replays safely from disk");
        }
        Files.delete(path);
        Files.delete(path.getParent());
    }

    @Test
    public void gapsAndLargeEntriesDoNotChangeOtherOrdinals() throws Exception {
        Path path = Files.createTempDirectory("helios-gap-test").resolve("session.helios-journal");
        try (HeliosJournal journal = new HeliosJournal(path, new JSONObject())) {
            journal.append(row(0, "first"));
            journal.append(row(2, "x".repeat(HeliosJournal.BATCH_BYTES)));
            journal.append(row(3, "last"));
            JSONObject first = journal.next();
            Assert.assertEquals(first.getJSONArray("entries").length(), 1);
            journal.acknowledge(first.getLong("journalOffset"));
            JSONObject large = journal.next();
            Assert.assertEquals(large.getInt("firstOrdinal"), 2);
            Assert.assertEquals(large.getJSONArray("entries").length(), 1, "large event travels alone");
            journal.acknowledge(large.getLong("journalOffset"));
            Assert.assertEquals(journal.next().getInt("firstOrdinal"), 3);
            journal.rewind();
            Assert.assertEquals(journal.next().getInt("firstOrdinal"), 0);
        }
        Files.writeString(path, "{broken", StandardOpenOption.APPEND);
        try (HeliosJournal journal = new HeliosJournal(path, null)) {
            for (int index = 0; index < 2; index++) {
                JSONObject batch = journal.next();
                journal.acknowledge(batch.getLong("journalOffset"));
            }
            Assert.expectThrows(java.io.IOException.class, journal::next);
        }
        Assert.assertTrue(Files.exists(path), "a torn record must never be treated as verified");
        Files.delete(path);
        Files.delete(path.getParent());
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
