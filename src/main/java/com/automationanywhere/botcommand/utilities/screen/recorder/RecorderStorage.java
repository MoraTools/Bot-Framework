package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Byte admission for new cloud-owned capture bytes, never for requested local rings or jobs. */
final class RecorderStorage {
    static final long MAX_BYTES = 512L * 1024 * 1024;
    static final long RING_BYTES = 128L * 1024 * 1024;
    private static final Object MUTEX = new Object();
    private final Path root;
    private final Path reservation;

    RecorderStorage(Path appDataRoot, String sessionId) throws IOException {
        this(appDataRoot, sessionId, RING_BYTES);
    }

    RecorderStorage(Path appDataRoot, String sessionId, long ringBytes) throws IOException {
        root = appDataRoot.resolve("cloud-capture-admission");
        Files.createDirectories(root);
        reservation = root.resolve(sessionId + ".json");
        if (!reserve("ring", ringBytes)) throw new IOException("Cloud recorder storage budget is full");
    }

    boolean reserve(String name, long bytes) throws IOException {
        synchronized (MUTEX) {
            try (FileChannel channel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                JSONObject own = Files.exists(reservation) ? new JSONObject(Files.readString(reservation)) : new JSONObject();
                long used = 0;
                try (Stream<Path> files = Files.list(root)) {
                    for (Path path : files.filter(p -> p.toString().endsWith(".json")).collect(Collectors.toList())) {
                        JSONObject value = new JSONObject(Files.readString(path));
                        for (String key : value.keySet()) used = Math.addExact(used, value.getLong(key));
                    }
                }
                if (used - own.optLong(name) + bytes > MAX_BYTES) return false;
                if (bytes == 0) own.remove(name); else own.put(name, bytes);
                Path temporary = reservation.resolveSibling(reservation.getFileName() + ".tmp");
                Files.writeString(temporary, own.toString());
                try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
                Files.move(temporary, reservation, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (org.json.JSONException | ArithmeticException damaged) {
                throw new IOException("Cloud capture reservation could not be read", damaged);
            }
        }
    }

    void release(String name) {
        try { reserve(name, 0); } catch (IOException ignored) { }
    }

    void close() {
        synchronized (MUTEX) {
            try (FileChannel channel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) { Files.deleteIfExists(reservation); }
            catch (IOException ignored) { }
        }
    }

    static void recovered(Path appDataRoot, String sessionId) {
        synchronized (MUTEX) {
            Path root = appDataRoot.resolve("cloud-capture-admission");
            if (!Files.isDirectory(root)) return;
            try (FileChannel channel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) { Files.deleteIfExists(root.resolve(sessionId + ".json")); }
            catch (IOException ignored) { }
        }
    }
}
