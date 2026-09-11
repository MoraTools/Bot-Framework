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

### Screen recording

* **Type:** `Select`
* **Options:**
  * `No video`: Disable screen recording. The session behaves as a logger only.
  * `Capture rolling video`: Continuously capture the desktop into a rolling buffer in the background. For each log entry at one of the selected levels below, the last N seconds are saved as an MP4 placed next to the log file. On JVM crash, the buffer at the moment of death is salvaged into a `crash-recording-<sessionId>.mp4` on the next bot startup. Windows-only; primary monitor only. If the recorder fails to start, the session degrades silently to the existing screenshot-only path.
* **Default:** `No video`

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

### Helios Cloud streaming

* **Type:** `Select`
* **Options:**
  * `Disabled`: The session writes the HTML log only. Nothing leaves the runner.
  * `Enabled`: Every entry of this session is also streamed to a Helios Cloud server while the bot runs.
* **Default:** `Disabled`
* **Description:** Streaming is additive. The HTML log, screenshots, video clips and rollover behave exactly the same whether streaming is on or off.

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

* **Session start** `POST {url}/api/ingest/sessions` with the execution id, bot URI, master Task Bot URI, Control Room file id, machine, user, local start time and the UTC offset in minutes. Helios Cloud identifies the bot by its master Task Bot, so both URIs are sent: `botUri` is the bot that opened the logger session and `parentBotUri` is the master that started the run, empty when the logger runs in the master itself. The Control Room file id is read from the master URI when it carries one, otherwise from the bot URI. The execution id comes from the bot agent; a random UUID is used when it is not available.
* **Entries** `POST {url}/api/ingest/sessions/{id}/entries`, one envelope per entry, carrying the same timestamp, level, source, task, machine, user, message, variable count and screenshot/clip path that the HTML row shows. Screenshots and clips stay on the runner; only their path is sent.
* **Session end** `POST {url}/api/ingest/sessions/{id}/end`, sent by `Stop Logger Session`.

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

Helios Cloud streaming never raises `BotCommandException`. Any ingest failure is reported as a single WARN row in the HTML log.
