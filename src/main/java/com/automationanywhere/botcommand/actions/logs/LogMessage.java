package com.automationanywhere.botcommand.actions.logs;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.FileValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcommand.utilities.logger.CustomHTMLLayout;
import com.automationanywhere.botcommand.utilities.logger.CustomLogger;
import com.automationanywhere.botcommand.utilities.screen.CaptureScreen;
import com.automationanywhere.botcommand.utilities.helios.CloudMediaMode;
import com.automationanywhere.botcommand.utilities.helios.ErrorMedia;
import com.automationanywhere.botcommand.utilities.screen.recorder.CaptureResult;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.annotations.rules.SelectModes;
import com.automationanywhere.commandsdk.annotations.rules.SessionObject;
import com.automationanywhere.commandsdk.annotations.rules.VariableMapAllowedDataTypes;
import com.automationanywhere.commandsdk.model.AttributeType;
import com.automationanywhere.commandsdk.model.DataType;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author Sumit Kumar
 */

@BotCommand
@CommandPkg(label = "Log Message",
        node_label = "{{logLevel}} : {{logMessage}}",
        description = "Logs message to session log file , optionally captures a screenshot",
        icon = "log_message.svg",
        name = "log_message",
        group_label = "Logs",
        documentation_url = "https://github.com/A360-Tools/Bot-Framework/blob/main/docs/logs/LogMessage.md",
        text_color = "#4285f4",
        return_label = "Screenshot file path (if captured)",
        return_type = DataType.FILE,
        return_name = "ScreenshotPath",
        return_required = false
)
public class LogMessage {
    private static final String LEVEL_INFO = "INFO";
    private static final String LEVEL_ERROR = "ERROR";
    private static final String LEVEL_WARN = "WARN";
    private static final String LOG_VARIABLE = "YES";
    private static final String DO_NOT_LOG_VARIABLE = "NO";

    @GlobalSessionContext
    private com.automationanywhere.bot.service.GlobalSessionContext globalSessionContext;

    private String testBotUri; // For testing purposes

    public void setTestBotUri(String testBotUri) {
        this.testBotUri = testBotUri;
    }

    public void setGlobalSessionContext(com.automationanywhere.bot.service.GlobalSessionContext globalSessionContext) {
        this.globalSessionContext = globalSessionContext;
    }

    @Execute
    public FileValue action(
            @Idx(index = "1", type = AttributeType.SESSION)
            @Pkg(label = "Logger session", description = "Set valid logger session", default_value_type =
                    DataType.SESSION, default_value = "Default")
            @NotEmpty
            @SessionObject
            CustomLogger session,

            @Idx(
                    index = "2", type = AttributeType.SELECT, options = {
                    @Idx.Option(index = "2.1", pkg = @Pkg(label = "INFORMATION", value = LEVEL_INFO)),
                    @Idx.Option(index = "2.2", pkg = @Pkg(label = "WARNING", value = LEVEL_WARN)),
                    @Idx.Option(index = "2.3", pkg = @Pkg(label = "ERROR", value = LEVEL_ERROR))
            }
            )
            @Pkg(label = "Select log type", default_value = LEVEL_INFO, default_value_type = DataType.STRING)
            @NotEmpty
            @SelectModes
            String logLevel,

            @Idx(index = "3", type = AttributeType.TEXTAREA)
            @Pkg(label = "Enter message to log", default_value_type = DataType.STRING, default_value = "Sample log " +
                    "message")
            @NotEmpty
            String logMessage,

            @Idx(index = "4", type = AttributeType.BOOLEAN)
            @Pkg(label = "Capture Screenshot", default_value_type = DataType.BOOLEAN, default_value = "false")
            @NotEmpty
            Boolean captureScreenshot,

            @Idx(index = "5", type = AttributeType.SELECT, options = {
                    @Idx.Option(index = "5.1", pkg = @Pkg(label = "No", value = DO_NOT_LOG_VARIABLE)),
                    @Idx.Option(index = "5.2", pkg = @Pkg(label = "Yes", value = LOG_VARIABLE))})
            @Pkg(label = "Log variable values", default_value = DO_NOT_LOG_VARIABLE, default_value_type =
                    DataType.STRING)
            @SelectModes
            String logVariable,

            @Idx(index = "5.2.2", type = AttributeType.VARIABLEMAP)
            @Pkg(label = "Variables to log", description = "Select variables with checkboxes. Use Custom selection for complex types; their By type labels can be blank.")
            @VariableMapAllowedDataTypes({DataType.STRING, DataType.NUMBER, DataType.DATETIME, DataType.BOOLEAN,
                    DataType.LIST, DataType.DICTIONARY, DataType.RECORD, DataType.TABLE, DataType.FILE})
            Map<String, Value> sourceMap
    ) {
        try {
            Map<String, Value> variableValues = LOG_VARIABLE.equalsIgnoreCase(logVariable) ? sourceMap : null;
            String screenshotPath = "";
            String videoPosterPath = "";
            String videoPath = "";

            // Convert string log level to Log4j Level
            Level log4jLevel = convertToLog4jLevel(logLevel);

            if (!LEVEL_INFO.equals(logLevel) && !LEVEL_WARN.equals(logLevel) && !LEVEL_ERROR.equals(logLevel))
                throw new BotCommandException("Invalid log level");
            boolean cloudError = LEVEL_ERROR.equals(logLevel) && session.getCloudMediaMode() != CloudMediaMode.OFF;
            boolean localVideo = session.shouldRecordVideoFor(log4jLevel);
            boolean cloudVideo = cloudError && session.getCloudMediaMode() == CloudMediaMode.VIDEO;
            String stillFailure = null;
            if (localVideo || cloudError || Boolean.TRUE.equals(captureScreenshot)) {
                String still = generateRandomScreenshotPath(session.getScreenshotFolderPath(log4jLevel), logLevel);
                try {
                    CaptureScreen.captureDesktop(still, true);
                    if (localVideo) videoPosterPath = still;
                    if (Boolean.TRUE.equals(captureScreenshot)) screenshotPath = still;
                    if (cloudError && videoPosterPath.isEmpty()) videoPosterPath = still;
                } catch (Exception captureError) {
                    if (!cloudError) throw captureError;
                    stillFailure = "captureFailed";
                }
            }

            Map<String, Object> message = new HashMap<>();
            message.put(CustomHTMLLayout.Columns.MESSAGE, logMessage);
            message.put(CustomHTMLLayout.Columns.SCREENSHOT, screenshotPath);
            message.put(CustomHTMLLayout.Columns.VIDEO, "");
            message.put(CustomHTMLLayout.Columns.VIDEO_POSTER, localVideo ? videoPosterPath : "");
            message.put(CustomHTMLLayout.Columns.VARIABLES, variableValues);
            message.put(CustomHTMLLayout.Columns.SOURCE, getFormattedBotUri());
            Path still = videoPosterPath.isEmpty() ? (screenshotPath.isEmpty() ? null : Paths.get(screenshotPath)) : Paths.get(videoPosterPath);
            String captureFailure = stillFailure;
            java.util.concurrent.atomic.AtomicBoolean registered = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.function.Consumer<CaptureResult> register = capture -> {
                if (!registered.compareAndSet(false, true)) return;
                if (localVideo && capture != null && capture.video != null)
                    message.put(CustomHTMLLayout.Columns.VIDEO, capture.video.toString());
                if (cloudError) message.put(ErrorMedia.COLUMN, new ErrorMedia(still, captureFailure, capture, cloudVideo, !localVideo && !Boolean.TRUE.equals(captureScreenshot)));
                session.getLogger().log(log4jLevel, message);
            };
            if (localVideo || cloudVideo) session.snapshotForError(UUID.randomUUID().toString(), localVideo, register);
            else register.accept(null);
            return new FileValue(screenshotPath);
        } catch (Exception e) {
            throw new BotCommandException("Error occurred: " + e.getMessage(), e);
        }
    }

    /**
     * Converts string log level to Log4j Level object
     *
     * @param logLevel The string representation of log level
     *
     * @return The corresponding Log4j Level object
     */
    private Level convertToLog4jLevel(String logLevel) {
        switch (logLevel) {
            case LEVEL_INFO:
                return Level.INFO;
            case LEVEL_WARN:
                return Level.WARN;
            case LEVEL_ERROR:
                return Level.ERROR;
            default:
                return Level.INFO;
        }
    }

    public static String generateRandomScreenshotPath(String folderPath, String logLevel) {
        // Create prefix based on log level
        String prefix = "info_";
        if (logLevel.equals(LEVEL_WARN)) {
            prefix = "warn_";
        } else if (logLevel.equals(LEVEL_ERROR)) {
            prefix = "error_";
        }

        String fileName = prefix + UUID.randomUUID() + ".png";
        Path path = Paths.get(folderPath, fileName);
        return path.toAbsolutePath().toString();
    }

  public String getFormattedBotUri() {
    if (this.testBotUri != null) {
      return this.testBotUri;
    }

    String botUri = URLDecoder.decode(this.globalSessionContext.getBotUri(), StandardCharsets.UTF_8);
    int aaIndex = botUri.indexOf("Automation Anywhere");
    int start = aaIndex >= 0 ? aaIndex + "Automation Anywhere".length() : 0;
    int end = botUri.indexOf('?');
    String path = botUri.substring(start, end > 0 ? end : botUri.length()).replace("/", "\\");

    // Extract workspace and version from query string
    String workspace = null, version = null;
    try {
      int queryStart = botUri.indexOf('?');
      if (queryStart > 0 && queryStart < botUri.length() - 1) {
        String query = botUri.substring(queryStart + 1);

        for (String param : query.split("&")) {
          String[] kv = param.split("=", 2);
          if (kv.length == 2) {
            if ("workspace".equalsIgnoreCase(kv[0])) workspace = kv[1];
            else if ("version".equalsIgnoreCase(kv[0])) version = kv[1];
          }
        }
      }
    } catch (Exception e) {
      // Continue without workspace/version if parsing fails
    }

    // Get line number if available
    Integer lineNumber = null;
    try {
      lineNumber = getCurrentLineNumber();
    } catch (Exception e) {
      // Line number not available, continue without it
    }

    // Build GitHub-style format: WORKSPACE:PATH@vVERSION#LLINE
    StringBuilder result = new StringBuilder();

    if (workspace != null) {
      result.append(workspace).append(":");
    }

    result.append(path);

    if (version != null) {
      result.append("@v").append(version);
    }

    if (lineNumber != null && lineNumber > 0) {
      result.append("#L").append(lineNumber);
    }

    return result.toString();
  }

  /**
   * Extracts the current line number from JobExecutionResponse using reflection.
   * Path: BotControl → this$0 (DispatcherImpl) → lastJobExecutionResponse → getCurrentLine()
   *
   * @return Current line number, or null if not accessible
   */
  private Integer getCurrentLineNumber() {
    try {
      // Step 1: Get BotControl from GlobalSessionContext
      com.automationanywhere.bot.service.BotControl botControl = globalSessionContext.getBotControl();
      if (botControl == null) {
        return null;
      }

      // Step 2: Get the outer DispatcherImpl instance via this$0 field (BotControl is an inner class)
      Field this0Field = botControl.getClass().getDeclaredField("this$0");
      this0Field.setAccessible(true);
      Object dispatcherImpl = this0Field.get(botControl);
      if (dispatcherImpl == null) {
        return null;
      }

      // Step 3: Get lastJobExecutionResponse field from DispatcherImpl
      Field responseField = dispatcherImpl.getClass().getDeclaredField("lastJobExecutionResponse");
      responseField.setAccessible(true);
      Object jobExecutionResponse = responseField.get(dispatcherImpl);
      if (jobExecutionResponse == null) {
        return null;
      }

      // Step 4: Call getCurrentLine() method on JobExecutionResponse
      Method getCurrentLineMethod = jobExecutionResponse.getClass().getMethod("getCurrentLine");
      Object currentLine = getCurrentLineMethod.invoke(jobExecutionResponse);

      return currentLine != null ? (Integer) currentLine : null;

    } catch (Exception e) {
      // Return null if any step fails - line number is not critical
      return null;
    }
  }
}
