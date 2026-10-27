package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/**
 * Locates and extracts the bundled ffmpeg.exe to a per-user, content-addressed
 * cache under %LOCALAPPDATA%\A360-BotFramework\ffmpeg\&lt;hash&gt;\ffmpeg.exe.
 *
 * @author Sumit Kumar
 */
public final class FfmpegBinary {

    private static final Logger LOGGER = LogManager.getLogger(FfmpegBinary.class);
    private static final String JAR_RESOURCE_PATH = "/ffmpeg/ffmpeg.exe";
    private static final String APP_FOLDER_NAME = "A360-BotFramework";
    private static final Object EXTRACTION_LOCK = new Object();

    /**
     * Full SHA-256 of the bundled ffmpeg.exe. Compile-time constant; the build
     * verifies the bundled resource hashes to this value via FfmpegBinaryTest.
     *
     * <p>Current bundle: ffmpeg n6.0 cross-compiled via {@code tools/ffmpeg-build}
     * with libx264 (GPL) and libaom-av1 enabled. Encoders, decoders, muxers,
     * demuxers, parsers, filters, and bitstream filters are all disabled by
     * default and individually re-enabled only for what the recorder
     * pipelines need. {@code FAST} mode uses libx264 -preset ultrafast;
     * {@code COMPACT} uses libaom-av1 -cpu-used 8.
     */
    public static final String BUNDLED_SHA256 =
            "10e59216cc6439f5bfdf0fe778c2538d0c14f8c2106c5e9fc38591981494bf59";

    /** First 12 hex chars of {@link #BUNDLED_SHA256} - used as the cache subfolder. */
    public static final String BUNDLED_HASH_PREFIX = BUNDLED_SHA256.substring(0, 12);

    private FfmpegBinary() {
    }

    /**
     * Returns the path to a working ffmpeg.exe, extracting it from the package jar
     * to %LOCALAPPDATA%\A360-BotFramework\ffmpeg\&lt;hash&gt;\ on first call per-user.
     *
     * @throws IOException if the binary cannot be extracted to any writable location
     */
    public static Path locate() throws IOException {
        IOException firstFailure = null;
        for (Path root : candidateRoots()) {
            try {
                return locateAt(root);
            } catch (IOException e) {
                LOGGER.debug("ffmpeg cache root {} unusable: {}", root, e.getMessage());
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        throw new IOException("Could not extract bundled ffmpeg.exe to any cache root",
                firstFailure);
    }

    /**
     * Returns the directory under which session.meta + session.active + ring/ for
     * each ScreenRecorder session live. Same root as {@link #locate()} cache.
     */
    public static Path appDataRoot() throws IOException {
        for (Path root : candidateRoots()) {
            try {
                Files.createDirectories(root);
                return root;
            } catch (IOException e) {
                LOGGER.debug("appData root {} unusable: {}", root, e.getMessage());
            }
        }
        throw new IOException("No writable application-data root for screen recorder");
    }

    private static Path locateAt(Path root) throws IOException {
        Path target = root.resolve("ffmpeg")
                .resolve(BUNDLED_HASH_PREFIX)
                .resolve("ffmpeg.exe");

        if (Files.exists(target) && BUNDLED_SHA256.equalsIgnoreCase(sha256Hex(target))) return target;
        Files.createDirectories(target.getParent());
        synchronized (EXTRACTION_LOCK) {
            // Keep the lock file so independent JVMs always lock the same file instance.
            try (FileChannel channel = FileChannel.open(target.resolveSibling(".extract.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                if (Files.exists(target) && BUNDLED_SHA256.equalsIgnoreCase(sha256Hex(target))) return target;
                Files.deleteIfExists(target);
                // Fixed name, used only under the locks above: a copy left by a killed process is replaced here.
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.deleteIfExists(tmp);
                try {
                    try (InputStream in = Objects.requireNonNull(
                            FfmpegBinary.class.getResourceAsStream(JAR_RESOURCE_PATH),
                            "bundled ffmpeg.exe resource not found at " + JAR_RESOURCE_PATH)) {
                        Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (!BUNDLED_SHA256.equalsIgnoreCase(sha256Hex(tmp)))
                        throw new IOException("Bundled ffmpeg.exe does not match its SHA-256");
                    try { Files.move(tmp, target); }
                    catch (IOException race) {
                        if (!Files.exists(target) || !BUNDLED_SHA256.equalsIgnoreCase(sha256Hex(target))) throw race;
                    }
                    return target;
                } finally {
                    // A failed delete must not replace the exception that is already on its way out.
                    try { Files.deleteIfExists(tmp); }
                    catch (IOException staleCopy) { LOGGER.debug("Could not delete {}: {}", tmp, staleCopy.toString()); }
                }
            }
        }
    }

    private static Iterable<Path> candidateRoots() {
        java.util.List<Path> roots = new java.util.ArrayList<>(3);
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isEmpty()) {
            roots.add(Paths.get(localAppData, APP_FOLDER_NAME));
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isEmpty()) {
            roots.add(Paths.get(userHome, "AppData", "Local", APP_FOLDER_NAME));
        }
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isEmpty()) {
            roots.add(Paths.get(tmp, APP_FOLDER_NAME));
        }
        return roots;
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available in this JVM", e);
        }
    }
}
