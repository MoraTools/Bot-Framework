package com.automationanywhere.botcommand.utilities.helios;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Append-only UTF-8 journal. No credentials. Kept until the server verifies the whole session. */
final class HeliosJournal implements AutoCloseable {
    static final int BATCH_ENTRIES = 50;
    static final int BATCH_BYTES = 256 * 1024;
    static final long MAX_BYTES = 256L * 1024 * 1024;
    final Path path;
    final JSONObject start;
    private final RandomAccessFile file;
    private final FileLock lock;
    private final long entriesOffset;
    private final long maxBytes;
    private long cursor;
    private boolean closed;

    HeliosJournal(Path path, JSONObject start) throws IOException {
        this(path, start, MAX_BYTES);
    }

    HeliosJournal(Path path, JSONObject start, long maxBytes) throws IOException {
        this.path = path;
        this.maxBytes = maxBytes;
        file = new RandomAccessFile(path.toFile(), "rw");
        FileLock acquired = null;
        try {
            acquired = file.getChannel().tryLock();
            if (acquired == null) throw new IOException("Journal is in use");
            if (start != null) {
                if (file.length() != 0) throw new IOException("Journal already exists");
                write(start);
            }
            file.seek(0);
            String header = readLine();
            if (header == null) throw new IOException("Incomplete journal header");
            this.start = new JSONObject(header);
            entriesOffset = file.getFilePointer();
            cursor = entriesOffset;
            lock = acquired;
        } catch (IOException | RuntimeException error) {
            if (acquired != null) acquired.release();
            file.close();
            throw error;
        }
    }

    synchronized void append(JSONObject record) throws IOException {
        // Leave room for the closing record even when event capture reaches its limit.
        byte[] bytes = (record.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (record.has("entries") && file.length() + bytes.length > maxBytes)
            throw new IOException("Helios journal reached its disk buffer limit");
        write(record);
    }

    private void write(JSONObject record) throws IOException {
        long before = file.length();
        try {
            file.seek(before);
            file.write((record.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            file.getChannel().force(false);
        } catch (IOException error) {
            // A short write must not hide all subsequent records behind a broken JSON line.
            file.setLength(before);
            throw error;
        }
    }

    /** Peeks a batch or the closing record. Only acknowledge() advances the delivery cursor. */
    synchronized JSONObject next() throws IOException {
        file.seek(cursor);
        JSONArray entries = new JSONArray();
        int first = 0;
        int bytes = 64;
        long next = cursor;
        boolean full = false;
        while (entries.length() < BATCH_ENTRIES) {
            long before = file.getFilePointer();
            String line = readLine();
            if (line == null) break;
            JSONObject record = new JSONObject(line);
            if (!record.has("entries")) {
                if (entries.isEmpty()) return record;
                break;
            }
            int ordinal = record.getInt("firstOrdinal");
            JSONObject entry = record.getJSONArray("entries").getJSONObject(0);
            int size = entry.toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (!entries.isEmpty() && (ordinal != first + entries.length() || bytes + size > BATCH_BYTES)) {
                full = true;
                break;
            }
            if (entries.isEmpty()) first = ordinal;
            entries.put(entry);
            bytes += size;
            next = file.getFilePointer();
            if (next <= before) throw new IOException("Journal did not advance");
        }
        if (entries.isEmpty()) return null;
        return new JSONObject().put("firstOrdinal", first).put("entries", entries).put("journalOffset", next).put("journalFull", full);
    }

    synchronized void acknowledge(long offset) { cursor = offset; }
    synchronized void rewind() { cursor = entriesOffset; }

    /** RandomAccessFile decodes as Latin-1; restore the original UTF-8 bytes. */
    private String readLine() throws IOException {
        long before = file.getFilePointer();
        String line = file.readLine();
        if (line == null) return null;
        long after = file.getFilePointer();
        file.seek(after - 1);
        if (file.read() != '\n') {
            file.seek(before);
            throw new IOException("Incomplete journal record; delivery remains unverified");
        }
        return new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    synchronized void deleteVerified() throws IOException {
        close();
        Files.deleteIfExists(path);
    }

    @Override public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            lock.release();
            file.close();
        }
    }
}
