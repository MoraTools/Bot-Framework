# Start Logger Session

## Overview

`Start Logger Session` initializes a new logging session, enabling the creation of log files. You can choose to log all levels (INFO, WARN, ERROR) to a single HTML file or configure separate HTML files for each level. The command rolls over log files based on a configurable maximum number of entries per file, and optionally enables a rolling screen-recording buffer that finalizes a video clip whenever a log entry fires at a selected level.

> **Always pair with `Stop Logger Session` in a Finally block** so log buffers flush and any pending video clips finalize, even when the bot exits via an exception. `Stop Logger Session` is idempotent, so a Finally invocation after a normal stop is safe.
![image](https://github.com/user-attachments/assets/cb497a74-d255-4e5a-9b8b-4e8e2933aa00)
![image](https://github.com/user-attachments/assets/0c8da229-737e-459d-95e5-fcd3b30a343d)

## Parameters

### Append option for different levels of log

* **Type:** `Select`
* **Options:**
  * `Same File`: Log INFO, WARN, and ERROR messages to a single file specified in "Log file path".
  * `Custom Configuration`: Log INFO, WARN, and ERROR messages to separate files specified below.
* **Default:** `Same File`
* **Description:** Determines whether all log levels are appended to the same file or if separate files are used for different log levels.

### Log file path

* **Condition:** Required if "Append option for different levels of log" is set to `Same File`.
* **Type:** `File`
* **Description:** The path to the log file where all logs (INFO, WARN, ERROR) will be stored.
* **Constraints:** Must be a local file path ending with the `.html` extension. Cannot be empty.

Each HTML file has an OS ownership lock. Sessions in the same JVM can share it. A start from another process is rejected before append or migration. The owner writes a local `WARN` on its next log call or close. This warning does not reach Helios, advance its ordinal, change its result, or capture media. The last session to close releases the lock.

### INFO logs file path

* **Condition:** Required if "Append option for different levels of log" is set to `Custom Configuration`.
* **Type:** `File`
* **Description:** The path to the file where INFO level logs will be stored.
* **Constraints:** Must be a local file path ending with the `.html` extension. Cannot be empty.

### WARN logs file path

* **Condition:** Required if "Append option for different levels of log" is set to `Custom Configuration`.
* **Type:** `File`
* **Description:** The path to the file where WARN level logs will be stored.
* **Constraints:** Must be a local file path ending with the `.html` extension. Cannot be empty.

### ERROR logs file path

* **Condition:** Required if "Append option for different levels of log" is set to `Custom Configuration`.
* **Type:** `File`
* **Description:** The path to the file where ERROR level logs will be stored.
* **Constraints:** Must be a local file path ending with the `.html` extension. Cannot be empty.

### Maximum log entries per file (default 1000)

* **Type:** `Number`
* **Default:** `1000`
* **Description:** The maximum number of log entries written to a single log file before it is rolled over (archived and a new one started).
* **Constraints:** Must be a number greater than 0.

Variable tables do not add to the entry count. Each file shows Machine and User once in a sticky header. Complete format-6 logs remain appendable across sessions with the same Machine and User. An old-format or incomplete active file, or one with a different identity, is moved intact to the next numbered archive before a fresh log is opened. All original bytes, existing archives, and old variable pages remain unchanged. If that move fails, session startup reports an error instead of altering the existing file.

### Screen recording

* **Type:** `Select`
* **Options:**
  * `No video`: Disable screen recording. The session behaves as a logger only.
  * `Capture rolling video`: Continuously capture the desktop into a rolling buffer in the background. For each log entry at one of the selected levels below, the last N seconds are saved as an MP4 placed next to the log file. On JVM crash, the next recorder startup attempts to recover complete buffered segments into a `crash-recording-<sessionId>.mp4`. Windows-only; primary monitor only. If the recorder fails to start, the session degrades silently to the existing screenshot-only path.
* **Default:** `No video`

Completed segments follow FFmpeg's segment-list order. Old recorder folders without a list use modification-time order. Interrupted recovery resumes from `.salvaging` on a later recorder startup; existing cleanup rules still apply to corrupt or failed recordings. Concurrent FFmpeg extraction uses a process lock and a unique temporary file. See [Bundled FFmpeg](../../README.md#bundled-ffmpeg).

### Record video on INFO entries

* **Condition:** Required if "Screen recording" is set to `Capture rolling video`.
* **Type:** `Boolean`
* **Default:** `false`
* **Description:** When true, every INFO log entry in this session finalizes a video clip from the rolling buffer. Use sparingly: a chatty INFO bot will produce many MP4s.

### Record video on WARN entries

* **Condition:** Required if "Screen recording" is set to `Capture rolling video`.
* **Type:** `Boolean`
* **Default:** `false`
* **Description:** When true, every WARN log entry in this session finalizes a video clip from the rolling buffer.

### Record video on ERROR entries

* **Condition:** Required if "Screen recording" is set to `Capture rolling video`.
* **Type:** `Boolean`
* **Default:** `true`
* **Description:** When true, every ERROR log entry in this session finalizes a video clip from the rolling buffer. Recommended default for production bots.

### Video buffer seconds

* **Condition:** Required if "Screen recording" is set to `Capture rolling video`.
* **Type:** `Number`
* **Default:** `30`
* **Description:** Length in seconds of the rolling video buffer. When a log entry triggers a recording, the finalized clip contains roughly the last N seconds of bot activity ending at that entry's timestamp.
* **Constraints:** Integer between 5 and 90 inclusive.

### Encoding mode

* **Condition:** Required if "Screen recording" is set to `Capture rolling video`.
* **Type:** `Select`
* **Options:**
  * `Fast: larger files, faster encoding (recommended)`: H.264 ultrafast preset. Each clip encodes in ~0.5-1 s and lands at ~5-15 MB. Choose this when the bot runner has free CPU and disk space is plentiful.
  * `Compact: smaller files, slower encoding`: AV1 (libaom, cpu-used 8). Each clip encodes in ~5-15 s and lands at ~1-3 MB. Choose this when long-term clip storage matters more than encode latency.
* **Default:** `Fast`

## Helios Cloud streaming

* **Type:** `Select`
* **Options:**
  * `Disabled`: The session writes the HTML log only. Nothing leaves the runner.
  * `Enabled`: Every entry of this session is also streamed to a Helios Cloud server while the bot runs.
* **Default:** `Disabled`
* **Description:** Every ERROR uploads media that inherits Screen recording. Capture rolling video with ERROR checked
  uploads the local ERROR clip and a separate screenshot. No video, ERROR unchecked, or disabled recording uploads
  a screenshot. INFO/WARN recording flags affect local clips only. Local HTML and returned screenshot paths keep
  their current behavior.

### Helios Cloud URL

* **Condition:** Required if "Helios Cloud streaming" is set to `Enabled`.
* **Type:** `Text`
* **Description:** Server root, for example `http://192.168.18.5:5180`. A trailing slash is ignored. The action appends `/api/ingest/...` itself.
* **Constraints:** Cannot be empty.

### Helios ingest key

* **Condition:** Required if "Helios Cloud streaming" is set to `Enabled`.
* **Type:** `Credential`
* **Description:** Ingest key issued for the Control Room this bot belongs to (`hik_<prefix>_<secret>`). Sent as the `X-Helios-Ingest-Key` header on every request. A Credential Vault attribute or a bot password are both accepted.
* **Constraints:** Cannot be empty.

## Helios Cloud streaming

When streaming is enabled the action creates a local disk journal. A background sender opens the remote session and posts batches while the bot runs.

* **Session start** `POST {url}/api/ingest/sessions` with the execution id, stable logger-session UUID, bot URI, master Task Bot URI, Control Room file id, machine, user, local start time and the UTC offset in minutes. Helios Cloud identifies the bot by its master Task Bot, so both URIs are sent: `botUri` is the bot that opened the logger session and `parentBotUri` is the master that started the run, empty when the logger runs in the master itself. The Control Room file id is read from the master URI when it carries one, otherwise from the bot URI. The execution id comes from the bot agent; a random UUID is used when it is not available.
* **Entries** `POST {url}/api/ingest/sessions/{id}/entries`, one envelope per entry, carrying the same timestamp, level, source, task, machine, user, message, variable count and screenshot/clip path that the HTML row shows. Every ERROR also declares media: video and a screenshot when ERROR recording is active, otherwise a screenshot. Local media paths remain in the row.
* **Session end** `POST {url}/api/ingest/sessions/{id}/end`, sent by `Stop Logger Session`.

An inherited Logger Session shares its identity and ordinal counter. A new `Start Logger Session` creates a separate stream under the same AA execution. Helios must return distinct `sessionId` and `executionSessionId` UUIDs and confirm the AA `executionId`. Older servers that cannot confirm separate streams leave new journals on disk without verified delivery. Old journals without `loggerSessionId` can still replay. Servers without `sessionIdentity` support receive Machine and User in each row. Helios Cloud 0.7.1 is compatible. See [the stream contract](../../README.md#what-is-sent).

**Inherited ERROR media.** There is no separate media selector. A requested ERROR video uses the existing
5-90 second buffer and FAST/COMPACT codec. No video does not start another recorder. Failed capture or encoding
preserves the ERROR row and reports unavailable media. Video keeps its independent poster, including when the
ring is empty, the queue is full, encoding fails, or the clip exceeds 8 MiB. PNG/JPEG limit: 1 MiB. Video retention:
3 days. Screenshot retention: 14 days. Storage limits can remove media sooner. The runner does not encode a second
video. A later Helios-enabled session with the same URL/key retries pending media even when No video is selected
or ERROR recording is unchecked. JSON `/end` and media delivery remain independent.

**Failure behaviour.** HTML logging continues during network or disk failures. Saved journals retry at a later logger startup. See [delivery, retries and configurable disk limits](../../README.md#failure-behaviour).

## Output

* **Type:** `Session`
* **Assignment Variable:** `Logger` (Session)
* **Description:** Returns a session object representing the initialized logger session. This session variable must be used in subsequent logging commands (e.g., `Write Log`, `End Logger Session`).

## Exceptions

Throws `BotCommandException` if:

* An invalid option is provided for "Append option for different levels of log".
* Required file paths are empty based on the selected "Append option".
* Provided file paths do not end with the `.html` extension.
* "Maximum log entries per file" is not greater than 0.
* "Video buffer seconds" is outside the 5-90 range.
* Any other error occurs during logger session initialization (e.g., file access issues). The specific error message will be included.

If the screen recorder fails to start (for example, when running headless or when the bundled ffmpeg cannot extract), the session degrades silently to the existing screenshot-only path rather than failing the bot.

Helios delivery delays and failures are reported once per logger session as a local `WARN`. A failure to save an entry adds an incomplete-log warning even after an earlier delivery warning. These diagnostics are not streamed and do not advance the ordinal or change the session result.

## Logging from other packages

Another Automation Anywhere package can write entries to this session. Each package loads in its own class loader, so the other package cannot use the classes of this package directly. Take the session object that the `Logger` variable holds, as a plain `Object`, and call `logExternal` by reflection. The method uses only JDK types.

```java
public void logExternal(String level, String message, String source, String screenshotPath, String attachmentPath)
```

```java
java.lang.reflect.Method log = session.getClass().getMethod("logExternal",
        String.class, String.class, String.class, String.class, String.class);
log.invoke(session, "WARN", "Invoice 42 skipped", "Other package",
        "C:\\Evidence\\invoice-42.png", "C:\\Reports\\invoice-42.pdf");
```

`Method.invoke` wraps an exception from `logExternal` in a `java.lang.reflect.InvocationTargetException`. Call `getCause()` on it to get the `IllegalArgumentException` or `IllegalStateException` that is described below. Do not ignore it: the entry was not written.

### Validation

* `level` must be `INFO`, `WARN`, or `ERROR`. The letter case does not matter. Any other value, including `null`, throws `IllegalArgumentException`.
* `message` must not be `null`. An empty message is allowed. The log shows the message exactly as `Log Message` shows it: HTML-escaped, with spaces, tabs, line breaks, and trailing spaces marked.
* `source` can be `null` or empty. It is shown in the Source column.
* `screenshotPath` can be `null` or empty. Otherwise it must be the absolute path of an existing image file. The call checks the file content with the `ImageIO` readers that the installed JDK provides. It accepts a file when one of these readers recognizes it, for example PNG or JPEG. The formats that the JDK supports can change, so there is no fixed list. A file that no reader recognizes is rejected. The default JDK 11 has no reader for WebP or SVG. The Screen column shows the image in the same way as a `Log Message` screenshot.
* `attachmentPath` can be `null` or empty. Otherwise it must be the absolute path of an existing regular file. The Message cell shows a link under the message text. The link text is the file name.
* A relative path, a missing path, a folder, or (for a screenshot) a file that is not an image throws `IllegalArgumentException`. When a check fails, the call writes nothing and does not change any file.

Links are relative to the folder of the HTML file when the file is on the same drive or root. For a different root (for example, another Windows drive), the link is a `file:` URI. File names are URL-quoted and HTML-escaped. A file name cannot become markup.

The HTML format does not change. The entry has the same six columns, counts as one entry toward the maximum log entries, and follows the same rollover rules. Existing format-6 logs continue to render and accept appended entries.

### No media capture or upload

`logExternal` does not capture the screen, record or save a video, take a snapshot of the rolling buffer, or add Helios ERROR media. This is also true for the `ERROR` level. Helios Cloud receives the level, the message, and the source in the same way as for other entries. The `screenPath` field is empty. Helios does not receive the screenshot path, the attachment path, or any file bytes. The local HTML log keeps the links.

### File ownership

The caller owns the files. The logger only reads them to check the path and to show the links. It never copies, moves, renames, changes, or deletes them. The links point to the original files, so keep them in place for as long as people read the log. A moved or deleted file leaves a broken link.

### Closed session

After `Stop Logger Session` closes the session, `logExternal` throws `IllegalStateException` with the message `Logger session is closed`. Nothing is written. A call from another thread waits while the session closes, and then it throws this exception. Calls from many threads are safe, and each call writes one complete entry.
