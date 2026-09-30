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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
                .addAttribute("charset", "UTF-8");

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

    // ponytail: serialize startup in this JVM; use per-path locks if independent startups contend.
    private static synchronized BuiltConfiguration startContext(LoggerContext context,
            ConfigurationBuilder<BuiltConfiguration> builder, Set<String> filePaths) throws IOException {
        for (String filePath : filePaths) {
            prepareLogFile(filePath);
        }
        // Keep the lock until Log4j has registered the managers checked by prepareLogFile.
        BuiltConfiguration config = builder.build();
        context.start(config);
        return config;
    }

    /**
     * Append only to a recognized file ending at a complete header or entry.
     * Legacy and interrupted files are moved intact beside the active file,
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
            if (start.startsWith("<!DOCTYPE html>\n" + CustomHTMLLayout.FORMAT_MARKER + "\n")
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

        BuiltConfiguration config = startContext(context, builder, new HashSet<>(levelFilePathMap.values()));
        this.loggerContext = context;
        this.logger = context.getLogger(loggerName);
        if (heliosConfig != null) {
            heliosAppender = new HeliosAppender("HELIOS_" + loggerId, heliosConfig, this.logger, loggerId);
            heliosAppender.start();
            config.addAppender(heliosAppender);
            config.getLoggerConfig(loggerName).addAppender(heliosAppender, Level.INFO, heliosAppender.getFilter());
            context.updateLoggers();
        }

        // Recording artifacts use the INFO log directory for relative HTML media paths.
        Path recorderLogDir = Paths.get(FilenameUtils.getFullPath(
                levelFilePathMap.getOrDefault(Level.INFO, levelFilePathMap.values().iterator().next())));
        this.recorder = startRecorder(recorderLogDir, bufferSeconds, encodingMode);
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

    public Logger getLogger() {
        return logger;
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
        if (!isClosed()) {
            // Freeze remote capture and check delivery while HTML logging can still record a warning.
            if (heliosAppender != null) heliosAppender.finish();
            loggerContext.stop();

            // Stop the screen recorder (if any). Order matters within the recorder: stage-1
            // stops writing to the ring before pending stage-2 encodes drain, and the
            // session folder is deleted only after both are quiescent.
            recorder.close();
            if (heliosAppender != null) heliosAppender.stopMedia();
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
