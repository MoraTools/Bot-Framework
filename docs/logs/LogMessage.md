# Log Message

Version 5.0.0.

## Overview
`Log Message` records a message to the session log file and optionally captures a screenshot, enriching log diagnostics. If the parent logger session has screen recording enabled for this entry's log level, the rolling video buffer is also finalized into an MP4 placed next to the log, and a still image of the desktop is captured to serve as the HTML video preview thumbnail.

![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/8110493b-9bea-478c-9d78-cd864ee581c2)

## Parameters

### Logger Session
- **Description:** Specifies the logger session to use for logging the message.

### Select Log Type
- **Options:** "INFORMATION", "WARNING", "ERROR"
- **Description:** Determines the severity level of the log message.

### Enter Message to Log
- **Type:** `String`
- **Description:** The log message content.

### Capture Screenshot
- **Type:** `Boolean`
- **Default:** `false`
- **Description:** Whether to expose a screenshot file path in this action's return value. When the parent session has screen recording enabled for this entry's log level, a still is always captured behind the scenes as the HTML video preview thumbnail; that internal still is exposed as the returned `ScreenshotPath` only when this flag is checked. With both video on and this flag on, the same still is reused for both purposes (no double capture).

### Log Variable Values
- **Options:** "Yes" or "No"
- **Description:** Specifies if variable values should be logged alongside the message.

### Variables to Log
- **Type:** `Map<String, Value>`
- **Description:** Select bot variables with the native checkboxes when "Log variable values" is set to "Yes". The selector supports String, Number, Datetime, Boolean, List, Dictionary, Record, Table, and File variables. Credential, Any, Session, Window, and Form variables are excluded.
- Use **Custom** selection for complex types. Their **By type** labels can be blank because of an A360 editor limit. Custom selection shows the variable names and icons.
- "No", a null selection, or an empty selection produces no variable output.

## Migration from 4.x

Version 5.0.0 removes the manual **Log following variables** NAME/VALUE list and its custom labels. First, record each old NAME/VALUE mapping. Switch an isolated copy of the bot or action to 5.0.0, then select the same actual bot variables in **Custom** selection. Save, reopen, and test the copy before production use. Manual selections are not moved automatically. Existing checkbox selections stay valid.

Keep the original bot pinned to the older package until the tested copy is ready. That bot keeps the old behavior, and the 4.5.4 JAR remains available.

## Local HTML output

The table has six columns: Datetime, Level, Source, Message, Variables, and Screen. Machine and User appear once in the sticky session header above it.

The viewer CSS and JavaScript are minified during the package build. Entry markup is compact. Message text, whitespace markers, variable values, and media links keep their full content.

Each message is a complete entry in the HTML log. When variable logging is enabled, the **Variables** button opens a shared dialog to show the saved values, including nested lists, dictionaries, records, and tables. Values are saved when the message is logged. Each snapshot is compressed in the HTML and decoded only when opened. This requires JavaScript and gzip `DecompressionStream` support in the browser. Messages stay readable without JavaScript. No separate variable HTML file is created, and local values are not capped by the Helios limits.

Screenshots and recordings stay in separate files. Each HTML file links to the actual media folder. Links use relative paths when possible, or file URIs for different Windows drives. A missing media file does not prevent reading the message or variables. Copy `screenshots/` and `clips/` with the HTML to retain media access. Refresh an open log to see later entries. Visible whitespace markers keep the original spaces, tabs, and line breaks when copied. Unset scalar values show `NULL`. Timestamps use English month names and keep the local numeric timezone offset.

One process can own each HTML output file. A second process that uses the same file is rejected before migration or append. The owner writes a local warning on its next log entry or when closed. This warning does not create a Helios entry or media capture. Sessions in the same process can share an output file. Ownership ends after the last session closes.

## Helios Cloud streaming

When the parent logger session has Helios Cloud streaming enabled, the logged variables are streamed with the entry: each one as its name, type label and plain-text value (lists, dictionaries, records and tables as JSON text). At most 100 variables per entry are sent and each value is cut at 8192 characters. With streaming disabled the variables stay on the runner, in the HTML log only.

## Output

* **Type:** `File`
* **Assignment Variable:** `ScreenshotPath` (File, optional)
* **Description:** The path of the screenshot PNG captured for this log entry, populated only when "Capture Screenshot" is checked. Empty when the box is unchecked, even if the parent session is recording video and a video preview thumbnail was captured internally.

## Exceptions

Throws `BotCommandException` if:
- Invalid log level is specified.
- The log message is null or empty.
- There are issues capturing the screenshot (if enabled).
- Any other unexpected error occurs during the logging process.

## Helios ERROR media

With Helios streaming enabled, every ERROR call declares media that inherits the session's Screen recording
settings. Active ERROR recording uploads the local clip and a separate screenshot using the configured buffer and
codec. No video, ERROR unchecked, or disabled recording uploads a screenshot. INFO/WARN recording stays local.
Capture and upload failure do not suppress the ERROR row. Requested video remains unavailable when the recorder
fails; its still is independent, including an empty ring, full encode queue, failed encode, or clip over 8 MiB.
The returned screenshot path still depends on Capture Screenshot. Automatic cloud capture does not add a returned
path or start a recorder when No video is selected.
