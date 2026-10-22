package com.automationanywhere.botcommand.utilities.logger;

import com.automationanywhere.botcommand.utilities.helios.HeliosConfig;
import com.automationanywhere.botcommand.utilities.helios.CloudMediaMode;
import com.automationanywhere.botcommand.utilities.screen.recorder.CaptureResult;
import com.automationanywhere.botcommand.utilities.helios.HeliosAppender;
import com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode;
import com.automationanywhere.botcommand.utilities.screen.recorder.ScreenRecorder;
import com.automationanywhere.toolchain.runtime.session.CloseableSessionObject;
import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractManager;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.LayoutComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.LoggerComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.core.config.builder.impl.DefaultConfigurationBuilder;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * @author Sumit Kumar
 */
public class CustomLogger implements CloseableSessionObject {

    private final Logger logger;
    private final String loggerId;
    private final LoggerContext loggerContext;
    private final Map<Level, String> screenshotFolderPaths;
    private final ScreenRecorder recorder;
    private final Set<Level> localRecordingLevels;
    private final CloudMediaMode cloudMediaMode;
    private final Set<String> ownedFilePaths;
    private boolean closed;

    // Each Log4j manager can be shared by several sessions in this JVM.
    private static final Map<String, FileOwnership> FILE_OWNERS = new HashMap<>();

    private static final class FileOwnership {
        private final FileChannel channel;
        private final FileLock lock;
        private int users = 1;

        private FileOwnership(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        private void release() throws IOException {
            try { lock.release(); } finally { channel.close(); }
        }
    }

    /** Non-null only while Helios Cloud streaming is enabled for this session. */
    private HeliosAppender heliosAppender;

    // Constructor for a single log file for all levels (no video recording)
    public CustomLogger(String loggerName, String logFilePath, int maxLogEntries) throws IOException {
        this(loggerName, logFilePath, maxLogEntries, 0, new HashSet<>(), EncodingMode.FAST);
    }

    public CustomLogger(String loggerName, String logFilePath, int maxLogEntries,
                        int bufferSeconds, Set<Level> recordingLevels,
                        EncodingMode encodingMode) throws IOException {
        this(loggerName, logFilePath, maxLogEntries, bufferSeconds, recordingLevels, encodingMode, null);
    }

    /**
     * Constructor for a single log file for all levels, with optional screen recording.
     *
     * @param bufferSeconds   rolling-buffer length in seconds; ignored if {@code recordingLevels} is empty
     * @param recordingLevels levels at which video clips should be saved; empty disables recording
     * @param encodingMode    encoder used when finalizing per-error clips; ignored when recording is off
     * @param heliosConfig    Helios Cloud streaming settings, or {@code null} to keep streaming off
     */
    public CustomLogger(String loggerName, String logFilePath, int maxLogEntries,
                        int bufferSeconds, Set<Level> recordingLevels,
                        EncodingMode encodingMode, HeliosConfig heliosConfig) throws IOException {
        this(loggerName, Collections.singletonMap(Level.INFO, logFilePath), maxLogEntries,
                bufferSeconds, recordingLevels, encodingMode, heliosConfig, false);
    }

    private void createDirectories() throws IOException {
        // Create screenshot directories
        for (String path : screenshotFolderPaths.values()) {
            Path directoryPath = Paths.get(path);
            Files.createDirectories(directoryPath);
        }

    }

    private LoggerContext createNewLoggerContext() {
        // Creating a completely separate LoggerContext
        return new LoggerContext("Context-" + loggerId);
    }

    private void setupLoggerConfiguration(ConfigurationBuilder<BuiltConfiguration> builder) {
        builder.setConfigurationName("CustomLogger-" + loggerId);
        builder.setPackages("com.automationanywhere.botcommand.utilities.logger,"
                + "com.automationanywhere.botcommand.utilities.helios");
        builder.setMonitorInterval("30");
        builder.setStatusLevel(Level.ERROR);
    }

    private AppenderComponentBuilder getCustomAppenderBuilder(ConfigurationBuilder<BuiltConfiguration> builder,
                                                              String appenderName,
                                                              String filePath, int maxLogEntries) {
        filePath = normalizedLogPath(filePath);
        LayoutComponentBuilder layoutBuilder = builder.newLayout("CustomHTMLLayout")
                .addAttribute("charset", "UTF-8")
                .addAttribute("htmlDirectory", Paths.get(filePath).getParent().toString());

        // Simple sequential numbering: 1_log.html, 2_log.html, etc.
        // Active file remains as log.html
        String baseName = FilenameUtils.getBaseName(filePath);
        String baseDir = FilenameUtils.getFullPath(filePath);
        String ext = FilenameUtils.getExtension(filePath);

        // Pattern: %i_basename.extension (e.g., 1_log.html, 2_log.html)
        String filePattern = baseDir + "%i_" + baseName + "." + ext;

        return builder.newAppender(appenderName, "RollingFile")
                .addAttribute("fileName", filePath)
                .addAttribute("filePattern", filePattern)
                .addAttribute("immediateFlush", true)
                .addAttribute("append", true)
                .addComponent(layoutBuilder)
                .addComponent(builder.newComponent("Policies")
                        .addComponent(builder.newComponent("EntryCountBasedTriggeringPolicy")
                                .addAttribute("maxEntries", maxLogEntries)))
                .addComponent(builder.newComponent("DefaultRolloverStrategy")
                    .addAttribute("fileIndex", "nomax")
            );
    }

    private static String normalizedLogPath(String filePath) {
        return Paths.get(filePath).toAbsolutePath().normalize().toString();
    }

    private static synchronized BuiltConfiguration startContext(LoggerContext context,
            ConfigurationBuilder<BuiltConfiguration> builder, Set<String> filePaths) throws IOException {
        Set<String> acquired = new HashSet<>();
        try {
            // Acquire every target before migrating or appending any HTML file.
            for (String filePath : filePaths) {
                acquireFileOwnership(filePath);
                acquired.add(filePath);
            }
            for (String filePath : filePaths) prepareLogFile(filePath);
            BuiltConfiguration config = builder.build();
            context.start(config);
            for (String filePath : filePaths) {
                if (!AbstractManager.hasManager(filePath)) {
                    throw new IOException("Cannot start HTML log writer: " + filePath);
                }
            }
            return config;
        } catch (IOException | RuntimeException | Error failure) {
            try { context.stop(); } finally { releaseFileOwnership(acquired); }
            throw failure;
        }
    }

    private static Path ownershipSidecar(String filePath, String suffix) {
        Path path = Paths.get(filePath);
        return path.resolveSibling(path.getFileName() + suffix);
    }

    private static void acquireFileOwnership(String filePath) throws IOException {
        FileOwnership shared = FILE_OWNERS.get(filePath);
        if (shared != null) {
            shared.users++;
            return;
        }
        FileChannel channel = FileChannel.open(ownershipSidecar(filePath, ".a360.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException contention) {
            lock = null;
        } catch (IOException | RuntimeException | Error failure) {
            channel.close();
            throw failure;
        }
        if (lock == null) {
            channel.close();
            IOException contention = new IOException("Another process owns HTML log: " + filePath
                    + ". Close its logger session before starting this session.");
            try {
                Files.createFile(ownershipSidecar(filePath, ".a360-contention"));
            } catch (FileAlreadyExistsException alreadyReported) {
                // One pending warning is sufficient for repeated rejected starts.
            } catch (IOException noticeFailure) {
                contention.addSuppressed(noticeFailure);
            }
            throw contention;
        }
        FILE_OWNERS.put(filePath, new FileOwnership(channel, lock));
    }

    private static synchronized void releaseFileOwnership(Set<String> filePaths) {
        for (String filePath : filePaths) {
            FileOwnership owner = FILE_OWNERS.get(filePath);
            if (owner != null && --owner.users == 0) {
                FILE_OWNERS.remove(filePath);
                try { owner.release(); } catch (IOException failure) {
                    org.apache.logging.log4j.status.StatusLogger.getLogger().error(
                            "Cannot release HTML log ownership: " + filePath, failure);
                }
            }
        }
    }

    private void reportContention() {
        for (String filePath : ownedFilePaths) {
            try {
                if (Files.deleteIfExists(ownershipSidecar(filePath, ".a360-contention"))) {
                    HeliosAppender.writeLocalWarning(logger,
                            "Another process was rejected because this session owns HTML log: " + filePath);
                }
            } catch (IOException failure) {
                org.apache.logging.log4j.status.StatusLogger.getLogger().error(
                        "Cannot read HTML log contention notice: " + filePath, failure);
            }
        }
    }

    /**
     * Append only to a recognized file with the same machine/user identity and a complete header or entry.
     * Legacy, different-identity and interrupted files are moved intact beside the active file,
     * keeping their relative media/variable links and every original byte.
     */
    private static void prepareLogFile(String filePath) throws IOException {
        filePath = normalizedLogPath(filePath);
        // Another local session may be part-way through writing a complete entry.
        if (AbstractManager.hasManager(filePath)) return;
        Path path = Paths.get(filePath);
        if (!Files.exists(path) || Files.size(path) == 0) return;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            byte[] prefix = new byte[(int) Math.min(128, file.length())];
            file.readFully(prefix);
            byte[] tail = new byte[(int) Math.min(64, file.length())];
            file.seek(file.length() - tail.length);
            file.readFully(tail);
            String start = new String(prefix, StandardCharsets.UTF_8);
            String end = new String(tail, StandardCharsets.UTF_8);
            if (start.startsWith("<!DOCTYPE html>\n" + CustomHTMLLayout.FORMAT_MARKER + "\n"
                    + CustomHTMLLayout.IDENTITY_MARKER + "\n")
                    && (end.endsWith(CustomHTMLLayout.HEADER_END) || end.endsWith(CustomHTMLLayout.ENTRY_END))) {
                return;
            }
        }
        Pattern pattern = Pattern.compile("(\\d+)_" + Pattern.quote(path.getFileName().toString()));
        int lastIndex;
        try (Stream<Path> files = Files.list(path.getParent())) {
            lastIndex = files.map(file -> pattern.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches).mapToInt(match -> Integer.parseInt(match.group(1)))
                    .max().orElse(0);
        }
        Path archive = path.resolveSibling(Math.addExact(lastIndex, 1) + "_" + path.getFileName());
        // No REPLACE_EXISTING: a conflicting archive must never be overwritten.
        Files.move(path, archive);
    }

    // Constructor for multiple log files based on the level (no video recording)
    public CustomLogger(String loggerName, Map<Level, String> levelFilePathMap, int maxLogEntries) throws IOException {
        this(loggerName, levelFilePathMap, maxLogEntries, 0, new HashSet<>(), EncodingMode.FAST);
    }

    public CustomLogger(String loggerName, Map<Level, String> levelFilePathMap, int maxLogEntries,
                        int bufferSeconds, Set<Level> recordingLevels,
                        EncodingMode encodingMode) throws IOException {
        this(loggerName, levelFilePathMap, maxLogEntries, bufferSeconds, recordingLevels, encodingMode, null);
    }

    /**
     * Constructor for multiple log files based on level, with optional screen recording.
     *
     * @param bufferSeconds   rolling-buffer length in seconds; ignored if {@code recordingLevels} is empty
     * @param recordingLevels levels at which video clips should be saved; empty disables recording
     * @param encodingMode    encoder used when finalizing per-error clips; ignored when recording is off
     * @param heliosConfig    Helios Cloud streaming settings, or {@code null} to keep streaming off
     */
    public CustomLogger(String loggerName, Map<Level, String> levelFilePathMap, int maxLogEntries,
                        int bufferSeconds, Set<Level> recordingLevels,
                        EncodingMode encodingMode, HeliosConfig heliosConfig) throws IOException {
        this(loggerName, levelFilePathMap, maxLogEntries, bufferSeconds, recordingLevels, encodingMode,
                heliosConfig, true);
    }

    private CustomLogger(String loggerName, Map<Level, String> levelFilePathMap, int maxLogEntries,
                         int bufferSeconds, Set<Level> recordingLevels, EncodingMode encodingMode,
                         HeliosConfig heliosConfig, boolean splitFiles) throws IOException {
        this.loggerId = UUID.randomUUID().toString();
        this.localRecordingLevels = recordingLevels == null ? new HashSet<>() : new HashSet<>(recordingLevels);
        this.cloudMediaMode = inheritedMediaMode(heliosConfig, bufferSeconds);
        this.screenshotFolderPaths = new HashMap<>();

        for (Map.Entry<Level, String> entry : levelFilePathMap.entrySet()) {
            this.screenshotFolderPaths.put(entry.getKey(), FilenameUtils.getFullPath(entry.getValue()) + "screenshots");
        }
        if (!splitFiles) {
            this.screenshotFolderPaths.put(Level.WARN, this.screenshotFolderPaths.get(Level.INFO));
            this.screenshotFolderPaths.put(Level.ERROR, this.screenshotFolderPaths.get(Level.INFO));
        }
        createDirectories();

        LoggerContext context = createNewLoggerContext();
        ConfigurationBuilder<BuiltConfiguration> builder = new DefaultConfigurationBuilder();
        setupLoggerConfiguration(builder);

        for (Map.Entry<Level, String> entry : levelFilePathMap.entrySet()) {
            String appenderName = (splitFiles ? entry.getKey().name() : "COMBINED") + "_" + loggerId;
            AppenderComponentBuilder appenderBuilder = getCustomAppenderBuilder(builder, appenderName,
                    entry.getValue(), maxLogEntries);
            if (splitFiles) {
                appenderBuilder.add(builder.newFilter("LevelMatchFilter", "ACCEPT", "DENY")
                        .addAttribute("level", entry.getKey()));
            }
            builder.add(appenderBuilder);
        }

        LoggerComponentBuilder loggerBuilder = builder.newLogger(loggerName, Level.INFO);
        if (splitFiles) {
            loggerBuilder.add(builder.newAppenderRef(Level.INFO.name() + "_" + loggerId))
                    .add(builder.newAppenderRef(Level.WARN.name() + "_" + loggerId))
                    .add(builder.newAppenderRef(Level.ERROR.name() + "_" + loggerId));
        } else {
            loggerBuilder.add(builder.newAppenderRef("COMBINED_" + loggerId));
        }
        builder.add(loggerBuilder);
        builder.add(builder.newRootLogger(Level.INFO));

        this.ownedFilePaths = new HashSet<>();
        for (String path : levelFilePathMap.values()) ownedFilePaths.add(normalizedLogPath(path));
        BuiltConfiguration config = startContext(context, builder, ownedFilePaths);
        this.loggerContext = context;
        this.logger = context.getLogger(loggerName);
        try {
            if (heliosConfig != null) {
                heliosAppender = new HeliosAppender("HELIOS_" + loggerId, heliosConfig, this.logger, loggerId);
                heliosAppender.start();
                config.addAppender(heliosAppender);
                config.getLoggerConfig(loggerName).addAppender(heliosAppender, Level.INFO, heliosAppender.getFilter());
                context.updateLoggers();
            }

            // Keep one recorder; each HTML layout links to these actual artifact paths.
            Path recorderLogDir = Paths.get(FilenameUtils.getFullPath(
                    levelFilePathMap.getOrDefault(Level.INFO, levelFilePathMap.values().iterator().next())));
            this.recorder = startRecorder(recorderLogDir, bufferSeconds, encodingMode);
        } catch (RuntimeException | Error failure) {
            try { context.stop(); } finally { releaseFileOwnership(ownedFilePaths); }
            throw failure;
        }
    }

    private CloudMediaMode inheritedMediaMode(HeliosConfig config, int bufferSeconds) {
        if (config == null) return CloudMediaMode.OFF;
        return bufferSeconds > 0 && localRecordingLevels.contains(Level.ERROR)
                ? CloudMediaMode.VIDEO : CloudMediaMode.SCREENSHOT;
    }

    private ScreenRecorder startRecorder(Path directory, int seconds, EncodingMode mode) {
        return ScreenRecorder.start(loggerId, directory, seconds, localRecordingLevels, mode, false,
                cloudMediaMode == CloudMediaMode.VIDEO);
    }

    public CloudMediaMode getCloudMediaMode() { return cloudMediaMode; }

    public CaptureResult snapshotForError(String captureId, boolean localRequired,
            java.util.function.Consumer<CaptureResult> register) {
        return recorder.snapshotForError(captureId, localRequired, register);
    }

    public synchronized Logger getLogger() {
        if (!isClosed()) reportContention();
        return logger;
    }

    /**
     * Writes one entry for another package. The signature has only JDK types, so a package that loads in
     * another class loader can call it by reflection. This call never captures the screen, records video,
     * or copies, moves, or deletes a file.
     *
     * @param level          {@code INFO}, {@code WARN}, or {@code ERROR}, in any letter case
     * @param message        text for the Message column; may be empty
     * @param source         text for the Source column; may be {@code null} or empty
     * @param screenshotPath {@code null}, empty, or an absolute path to an existing image; shown in the Screen column
     * @param attachmentPath {@code null}, empty, or an absolute path to an existing file; linked under the message
     * @throws IllegalArgumentException if the level, the message, or a path is not valid
     * @throws IllegalStateException    if this session is closed
     */
    public synchronized void logExternal(String level, String message, String source,
                                         String screenshotPath, String attachmentPath) {
        if (closed || isClosed()) throw new IllegalStateException("Logger session is closed");
        Level log4jLevel;
        if ("INFO".equalsIgnoreCase(level)) log4jLevel = Level.INFO;
        else if ("WARN".equalsIgnoreCase(level)) log4jLevel = Level.WARN;
        else if ("ERROR".equalsIgnoreCase(level)) log4jLevel = Level.ERROR;
        else throw new IllegalArgumentException("Log level must be INFO, WARN, or ERROR");
        if (message == null) throw new IllegalArgumentException("Log message must not be null");

        Map<String, Object> row = new HashMap<>();
        row.put(CustomHTMLLayout.Columns.MESSAGE, message);
        row.put(CustomHTMLLayout.Columns.SOURCE, source == null ? "" : source);
        row.put(CustomHTMLLayout.Columns.SCREENSHOT, externalFile(screenshotPath, "Screenshot path", true));
        row.put(CustomHTMLLayout.Columns.ATTACHMENT, externalFile(attachmentPath, "Attachment path", false));
        row.put(CustomHTMLLayout.Columns.EXTERNAL, Boolean.TRUE);
        getLogger().log(log4jLevel, row);
    }

    /** Returns the path when it is empty or names an existing file (an image when required); never touches the file. */
    private static String externalFile(String path, String name, boolean image) {
        if (path == null || path.isEmpty()) return "";
        try {
            Path file = Paths.get(path);
            if (file.isAbsolute() && Files.isRegularFile(file) && (!image || isImage(file))) return path;
        } catch (InvalidPathException | IOException unreadable) {
            // Rejected below, like any other path that is not usable.
        }
        throw new IllegalArgumentException(name + " must be an absolute path to an existing "
                + (image ? "image" : "file") + ": " + path);
    }

    private static boolean isImage(Path file) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            return input != null && ImageIO.getImageReaders(input).hasNext();
        }
    }

    /** True when the supplied level is in the configured recording-levels set and recorder is healthy. */
    public boolean shouldRecordVideoFor(Level level) {
        return localRecordingLevels.contains(level) && recorder.shouldRecordFor(level);
    }

    /**
     * Snapshots the rolling buffer and queues an async stage-2 encode. Returns the
     * eventual mp4 path (which may not exist until the encode finishes), or null
     * if the encode could not be queued (recorder failed, queue full, ring empty).
     */
    public java.nio.file.Path snapshotForError(String errorUuid) {
        return recorder.snapshotForError(errorUuid);
    }


    /** UUID identifying this session; useful for tests and salvage path naming. */
    public String getLoggerId() {
        return loggerId;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            // Freeze remote capture and check delivery while HTML logging can still record a warning.
            try {
                if (heliosAppender != null) heliosAppender.finish();
            } finally {
                try { reportContention(); } finally { loggerContext.stop(); }
            }
        } finally {
            try {
                // Drain recorder output before the last session releases file ownership.
                try { recorder.close(); } finally {
                    if (heliosAppender != null) heliosAppender.stopMedia();
                }
            } finally {
                releaseFileOwnership(ownedFilePaths);
            }
        }
    }

    @Override
    public boolean isClosed() {
        return loggerContext.isStopped();
    }

    public String getScreenshotFolderPath(Level level) {
        return screenshotFolderPaths.getOrDefault(level, screenshotFolderPaths.get(Level.INFO));
    }

}
