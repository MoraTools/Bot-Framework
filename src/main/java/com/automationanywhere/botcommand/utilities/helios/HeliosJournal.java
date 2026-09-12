package com.automationanywhere.botcommand.utilities.helios;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InvalidObjectException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
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
    private final FileChannel file;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(8192);
    private long readPosition;
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
        file = start == null
                ? FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)
                : FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
        FileLock acquired = null;
        try {
            acquired = file.tryLock();
            if (acquired == null) throw new BusyException();
            if (start != null) {
                write(start);
            }
            seek(0);
            String header = readLine();
            if (header == null) throw new InvalidObjectException("Incomplete journal header");
            this.start = new JSONObject(header);
            entriesOffset = readPosition;
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
        if (record.has("entries") && file.size() + bytes.length > maxBytes)
            throw new IOException("Helios journal reached its disk buffer limit");
        write(record);
    }

    private void write(JSONObject record) throws IOException {
        long before = file.size();
        try {
            file.position(before);
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(record.toString() + "\n");
            while (bytes.hasRemaining()) file.write(bytes);
            file.force(false);
        } catch (IOException error) {
            // A short write must not hide all subsequent records behind a broken JSON line.
            file.truncate(before);
            throw error;
        }
    }

    /** Peeks a batch or the closing record. Only acknowledge() advances the delivery cursor. */
    synchronized JSONObject next() throws IOException { return next(BATCH_ENTRIES); }

    synchronized JSONObject next(int limit) throws IOException {
        seek(cursor);
        JSONArray entries = new JSONArray();
        int first = 0;
        int bytes = 64;
        long next = cursor;
        boolean full = false;
        while (entries.length() < limit) {
            try {
                long before = readPosition;
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
                next = readPosition;
                if (next <= before) throw new InvalidObjectException("Journal did not advance");
            } catch (InvalidObjectException | org.json.JSONException corrupt) {
                // Return the saved prefix first. The next read will report the damaged record.
                if (entries.isEmpty()) throw new InvalidObjectException(corrupt.getMessage());
                full = true;
                break;
            }
        }
        if (entries.isEmpty()) return null;
        return new JSONObject().put("firstOrdinal", first).put("entries", entries).put("journalOffset", next).put("journalFull", full);
    }

    synchronized void acknowledge(long offset) { cursor = offset; }
    synchronized void rewind() { cursor = entriesOffset; }

    private void seek(long position) {
        readPosition = position;
        readBuffer.limit(0);
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            if (!readBuffer.hasRemaining()) {
                readBuffer.clear();
                int count = file.read(readBuffer, readPosition);
                readBuffer.flip();
                if (count == 0) continue;
                if (count < 0) {
                    if (line.size() == 0) return null;
                    throw new InvalidObjectException("Incomplete journal record; delivery remains unverified");
                }
            }
            byte value = readBuffer.get();
            readPosition++;
            if (value == '\n') return line.toString(StandardCharsets.UTF_8.name());
            line.write(value);
        }
    }

    static final class BusyException extends IOException {
        BusyException() { super("Journal is in use"); }
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
