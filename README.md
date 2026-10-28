# A360 Bot Framework Package

**Version 5.3.4** - maintained by **jamir-boop**, forked from the original A360 Tools package.

## 5.x changes

- **Log Message** uses native **Variables to log** checkboxes for String, Number, Datetime, Boolean, List, Dictionary, Record, Table, and File; use **Custom** selection for complex types whose **By type** labels can be blank; Credential, Any, Session, Window, and Form are excluded.
- A Credential value inside a List, Dictionary, Record or Table is shown as `********` in the HTML log and in Helios (before: the HTML log showed the plain text).
- HTML logs use format 7 and `.a360.lock` / `.a360-contention` sidecar files; see [Local HTML logs](#local-html-logs).
- The HTML log shows the first 5 lines of a long message. The last visible line fades. **Show full message** opens the whole text in a dialog. The file keeps the full text of every message.
- Other packages can call `logExternal` to write to an open logger session; see [Logging from other packages](#logging-from-other-packages). Helios gets only their WARN and ERROR entries. Their ERROR entries capture media like a Log Message ERROR entry.
- **Create Folders** checks all paths before changes, requires full paths in Absolute mode, confines Relative paths inside the base, and rejects blank paths, roots, invalid parents, missing roots, and overlapping or linked folders with Overwrite on; Overwrite deletes folders recursively (see [rejected paths](docs/device/CreateFolders.md#rejected-paths)).
- **Read JSON** keeps nested array keys such as `m[0].[1]`; see [JSON Reader](docs/config/JSONReader.md#output).
- **Read Excel** and **Read CSV** are removed; **Read JSON** and **Read XML** stay.
- The log viewer is no longer minified.
- **Close Application** terminates the listed applications at once, with one hard kill per process. Unsaved work is lost.
- **Clean Directory** does not enter or delete Windows junctions inside the target folder.
- **Create Folders** treats an unset append option as off instead of failing.
- **Read JSON** no longer checks the `.json` extension in code; the input's file-extension rule stays.
- An invalid Helios URL gives one warning, saves nothing for Helios, and `Stop Logger Session` does not wait. Helios Cloud 0.5.0 or later is required; code for older servers and journals is removed.
- Crash recovery does not write a `salvaged/` recording when the log folder of the dead session is gone.
- HTML logs use one escaper. Variable names, column names, Machine, User, Source and attachment link text keep non-ASCII characters as UTF-8 (before: named entities such as `&eacute;`), and `'` is written as `&#39;` (before: not escaped). Message text is unchanged.

## Overview
Designed to streamline bot development, enhance logging, and facilitate comprehensive documentation within tasks for A360. Tailored for efficiency, consistency, and ease of use, this package addresses common challenges in bot development and maintenance, making it an indispensable tool for modern automation projects.

![main](https://github.com/A360-Tools/Bot-Framework/assets/82057278/8d29c730-b988-4721-b375-f937f623f158)

## Use Cases
- **Framework Templating**: Ideal for organizations looking to standardize bot development practices across multiple teams.
- **Detailed Logging**: Suited for complex workflows where detailed traceability and error tracking are crucial.
- **Live Run Monitoring**: Optionally stream every log entry of a session to a Helios Cloud server as the bot runs, so operators watch executions live instead of collecting HTML files afterwards. See [Helios Cloud streaming](#helios-cloud-streaming) below.
- **Visual Failure Replay**: Optionally attach a short MP4 of the seconds leading up to each WARN/ERROR entry, so post-mortems show exactly what the screen looked like at the moment of failure. See [Robust Logging](#robust-logging) and [Bundled FFmpeg](#bundled-ffmpeg) below.
- **Process Documentation**: Perfect for documenting automated processes within the bot, ensuring clarity and ease of maintenance.

## Key Features

### [Robust Logging](https://github.com/A360-Tools/Bot-Framework/blob/main/docs/logs/LogMessage.md)
- Global session logger for comprehensive logging across subtasks.
- Automatic capture of source task details, environment details for enhanced traceability.
- Visualization of complex/nested variables for efficient debugging and tracking.
- HTML format for logs to enable monitoring from various devices while minimizing the risk of unintended file locking.
- Log file rollover based on a configurable maximum number of entries per file (default 1000).
- Log file separation per level: send all log levels to a single file, or split INFO / WARN / ERROR into separate files.
- Screenshot capture alongside log messages for a complete logging experience.
- Variable values are stored inside each HTML log entry. Select **Variables** to inspect names, types, nested containers, and tables in a shared dialog. The local snapshot keeps all logged values; Helios streaming limits do not apply to it.
- **Rolling screen-recording buffer**: opt-in continuous capture of the last N seconds (5-90, default 30) of bot activity. When a log entry fires at a configured level (any combination of INFO/WARN/ERROR), the buffer is finalized into a browser-playable MP4 placed next to the log. Choose between **Fast** (H.264, larger files, faster encoding, recommended) and **Compact** (AV1, smaller files, slower encoding) per session. On JVM crash or drain timeout, the next recorder startup attempts to recover the rolling buffer and pending per-error clips at their original paths.

### [Helios Cloud Streaming](https://github.com/A360-Tools/Bot-Framework/blob/main/docs/logs/StartLoggerSession.md)
- Opt-in per session: pick a Helios Cloud URL and an ingest key on `Start Logger Session`, and every entry is posted to the server while the bot runs.
- HTML logging, screenshots, video clips, and rollover continue when streaming is enabled.
- Delivery delays and failures produce a local WARN. Saved journals remain available for later delivery.

### [Config Data Reading](https://github.com/A360-Tools/Bot-Framework/tree/main/docs/config)
- Reads configuration data from JSON and XML into dictionaries.
  
  ![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/800d1bf7-7bda-4a1d-8f1a-6ea7821988bc)
  
  ![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/68a7dcdd-40d2-4f5f-a78a-21f4da117039)

### [Auto Delete Logs](https://github.com/A360-Tools/Bot-Framework/blob/main/docs/device/DeleteFilesFolders.md)
- Automated log deletion based on log age to optimize log management.
- Customizable log retention through pattern matching for directories and files, aligning with organizational policies.

  ![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/c7646e32-4c73-4d54-a6e1-18ec92d5c37d)

### [Close Process/Application](https://github.com/A360-Tools/Bot-Framework/blob/main/docs/device/CloseApplications.md)
- Simplifies the termination of processes or applications by name, enhancing system resource management.

  ![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/144ef622-7068-4a86-8574-4585749628dc)

### [Generate Framework Template](https://github.com/A360-Tools/Bot-Framework/tree/main/docs)
- Standardizes bot structures across teams.
- Ensures consistent logging practices for uniformity in deployment and maintenance.

### [Enhanced Documentation](https://github.com/A360-Tools/Bot-Framework/tree/main/docs/documentation)
- In-task documentation capabilities with screenshot incorporation.
- Error-prone activity highlights with detailed explanations and visual aids.
- Simplified process comprehension through design screenshots, aiding replication and understanding.

  ![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/f4fc8d69-fddf-4d49-94d0-e68823dfee6e)

  ![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/235b9976-93c2-4001-9740-08c386ad6597)

  ![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/9d55028e-740a-433b-a1cb-af7e2d346402)

  ![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/133d4bf1-1113-42b4-8d7f-184e2e137b5a)


## Activity Summary
- **Config Read JSON/XML**: Imports configuration data from JSON and XML into dictionaries.
- **Device Close Application**: Terminates the listed applications at once. Unsaved work is lost.
- **Device Create Folders**: Automates the creation of directories, ensuring the existence of necessary parent directories.
- **Device Clean Directory**: Allows for the rule-based deletion of files or folders, with options to exclude specific items by regex pattern and to skip locked files instead of failing the bot. Windows junctions inside the target folder are not entered or deleted.
- **Documentation About/Caution Sequence/Comment/Sequence**: Offers a range of documentation utilities from basic commenting to detailed sequence documentation with caution highlights and screenshots.
- **Log Message**: Provides session-based logging with options for detailed messages and screenshots.
- **Logs Start/Stop Session**: Manages the lifecycle of logging sessions, from initiation to termination. Always pair `Start Logger Session` with `Stop Logger Session` in a Finally block so logs flush and any pending video clips finalize.

## Local HTML logs

Machine and User stay visible in the sticky session header above the scrolling table. The six columns are Datetime, Level, Source, Message, Variables, and Screen. The title and identity share one line on desktop and stack on small screens. The log follows the browser's light or dark theme.

Each active or rotated HTML file contains its own styles and variable snapshots. Open it directly in a browser; no server or external stylesheet is required. Each variable snapshot is compressed inside the file. The Variables dialog requires JavaScript and a browser with `DecompressionStream('gzip')` support. Messages remain ordinary HTML for browser search and copy. A message longer than 5 lines shows its first 5 lines, and the last visible line fades. **Show full message** under it opens the whole text in the same dialog; text that ends in JSON opens with the JSON indented. Without JavaScript, and in print, every line shows. Only the selected snapshot is decoded, and it is cleared when the dialog closes. New entries are visible after refreshing the page. Media links are resolved from each HTML file's directory to the actual screenshot or clip folder, including logs in separate folders. Copy the media folders with the HTML when media is needed.

Message copy preserves spaces, tabs, line breaks, and trailing whitespace. Unset scalar values show `NULL`. Variable type labels stay uppercase in all locales. Timestamps use English month names and include the local numeric time-zone offset.

The readable viewer is `src/main/resources/templates/log.html`, and the package ships it unchanged. Building needs no Node.js; the viewer script test runs only when `node` is on the PATH.

Entries are appended as complete blocks. Closing a session does not add a footer, and reopening a complete log keeps all existing bytes in place. Rollover counts log entries, including after a restart; rows inside a variable table do not count as extra entries. The existing maximum-entry setting still applies. Large variable snapshots increase the HTML file size and are not silently truncated.

Each HTML output file has two sidecar files in the same folder. Their names are the full HTML file name plus a suffix, for example `log.html.a360.lock` and `log.html.a360-contention`.

- `<log>.a360.lock` holds an OS file lock. It stops two processes from writing one HTML log. The file stays on disk after the session ends; this is normal.
- `<log>.a360-contention` is a notice that a rejected process leaves for the owner. The owner deletes it when it reports it. A new owner deletes an old notice when it starts, because that notice is not about the new owner.

Do not delete either file while a bot that uses the log runs. The folder is resolved through links, so `.`, `..` and links to one folder count as one file. Sessions in the same JVM share the lock until the last session closes; the lock is released before the recorder drains.

A start from another process is rejected before append or migration, and leaves the notice. `Start Logger Session` then fails with `BotCommandException`: `Error occurred while creating new session: Another process owns HTML log: <path>. Close its logger session before starting this session.` The same real file already open in this process under another path spelling, or by another copy of the package classes, fails with `HTML log is already open in this process: <path>. Use one path for it, or close the other logger session first.` That case writes no notice. `<path>` is the HTML path with its folder resolved through links.

The owner looks for the notice at most once per second when it logs, and always at close. When it finds the notice, it writes one local `WARN` row: `Another process was rejected because this session owns HTML log: <path>`. This row does not reach Helios, advance the stream ordinal, change the session result, or trigger media capture.

When the file system gives no lock (for example a read-only folder, or a share without lock support), the session still starts. Only sessions in this process are protected. The Log4j status log gets an `ERROR`: `Cannot lock HTML log <path>. Another process can write it at the same time.` The HTML log gets no row for it.

Current logs use HTML format 7. When an existing file uses an earlier format, has a different Machine or User, or ends with an incomplete write, the next session moves that file intact to the next numbered archive (for example, `2_log.html`) and starts a fresh active file. All original bytes, existing archives, and old `variables/` pages are preserved. Migration stops if the file cannot be moved; it does not overwrite an archive or discard the incomplete bytes.

## Logging from other packages

Another package can write to an open logger session with one reflective call. It needs no dependency on this package, because the signature uses only JDK types.

```java
java.lang.reflect.Method log = session.getClass().getMethod("logExternal",
        String.class, String.class, String.class, String.class, String.class);
log.invoke(session, "WARN", "Invoice 42 skipped", "Other package", screenshotPathOrNull, attachmentPathOrNull);
```

The level is `INFO`, `WARN`, or `ERROR`, in any letter case. A screenshot is the absolute path of an existing image that an installed JDK `ImageIO` reader recognizes, for example PNG or JPEG. An attachment is the absolute path of an existing file; the log shows a link with the file name under the message. The call never copies, moves, or deletes these files. Helios gets only the WARN and ERROR entries, and never a path or the bytes of these files. INFO entries stay in the HTML log. An ERROR entry captures media like a Log Message ERROR entry with **Capture Screenshot** off. INFO and WARN entries never capture. See [Logging from other packages](https://github.com/A360-Tools/Bot-Framework/blob/main/docs/logs/StartLoggerSession.md#logging-from-other-packages) for validation and errors.

## Helios Cloud streaming

Every logger session can optionally stream its entries live to a Helios Cloud server. The feature is off by default.

### Inputs on `Start Logger Session`

| Input | Type | Notes |
|---|---|---|
| Helios Cloud streaming | Select | `Disabled` (default) or `Enabled`. |
| Helios Cloud URL | Text | Server root, for example `http://192.168.18.5:5180`. Required when enabled. |
| Helios ingest key | Credential | Ingest key issued for the Control Room (`hik_<prefix>_<secret>`). Credential Vault attribute or bot password. Required when enabled. |

### What is sent

| Call | Payload |
|---|---|
| `POST {url}/api/ingest/sessions` | AA execution id, stable `loggerSessionId`, `sessionIdentity`, bot URI, master Task Bot URI (empty when the logger runs in the master), Control Room file id, machine, user, local start time, UTC offset in minutes. |
| `POST {url}/api/ingest/sessions/{id}/entries` | A batch of entries: first ordinal plus timestamp, UTC offset, level, source, task, message, variable count, logged variables and screenshot/clip path. Entries do not carry Machine and User; Helios takes both from the session start (`sessionIdentity`). |
| `POST {url}/api/ingest/sessions/{id}/end` | Sent by `Stop Logger Session` with `status` = the worst level logged (`ERROR`, `WARN` or `OK`) and `expectedEntries` for delivery verification. |

The ingest key travels in the `X-Helios-Ingest-Key` header. Local media paths stay in the log rows. With streaming enabled, every ERROR also uploads media according to the Screen recording settings below.

An inherited Logger Session shares its stream identity and ordinal counter. Each new `Start Logger Session` creates a separate stream under the same AA execution. Helios must confirm distinct UUIDs in `sessionId` and `executionSessionId`, and an `executionId` that matches the AA execution id sent by the runner (up to Helios' 256-character limit). A start reply that has a valid `sessionId` but does not confirm this refuses the session with one warning: `Helios did not confirm separate logger streams. Update Helios; saved entries remain on disk for later delivery.` Any other invalid start reply is retried.

A pending journal keeps the Machine and User of its own session start, not those of the current runner. Helios Cloud 0.5.0 or later is required; 0.7.1 was used for the delivery checks.

The variables logged with an entry are streamed with it, each as a name, a type label (`STRING`, `NUMBER`, `LIST`, `TABLE` ...) and a plain-text value; lists, dictionaries, records and tables render as JSON text. At most 100 variables per entry are sent and each value is cut at 8192 characters, flagged with `truncated`. `variablesCount` still reports how many were logged before the cap. Nothing is streamed when Helios Cloud streaming is disabled.

### Failure behaviour

Streaming never fails the bot. Each entry is numbered and copied to a UTF-8 disk journal before network delivery. The background sender batches up to 50 entries or 256 KiB, or flushes after 2 seconds. A larger single entry travels alone, subject to the server's 5 MiB request limit. HTTP 413 splits a rejected batch; an oversized single entry remains on disk while later valid entries continue to send. Timeouts, HTTP 408/429 and server errors use retries with backoff and `Retry-After`; retries preserve the original ordinals and payloads.

A delivery delay emits one local `WARN` per logger session. Each distinct terminal message, including HTTP rejection, incomplete replay, pending delivery, a retained journal or a renamed older journal, is written once and is never hidden by an earlier delay warning. If an entry cannot be saved, an additional incomplete-log warning is written even after an earlier delivery warning. An invalid Helios URL (not http or https, or no host) gives one warning: `Invalid Helios URL. Use an http or https URL with a host. HTML logging continues without cloud logs.` Nothing is saved for Helios, and `Stop Logger Session` does not wait.

`Stop Logger Session` saves the expected entry count and waits up to 5 seconds for delivery, plus up to 1 second to stop the sender. A refused session ends the delivery wait early when no older journals remain to replay. HTTP rejections such as 400/401/403/404 refuse delivery, and so does a start reply that does not confirm separate logger streams. A start reply without a valid `sessionId`, and an end reply that is not a valid Helios receipt (including an empty reply), are retried, not treated as a refusal.

Helios verifies the complete sequence before confirming delivery. A missing entry triggers one full repair pass; duplicate ordinals are ignored. If the sequence is still incomplete after that pass, the journal of the running session stays on disk under its name and one local warning is written. At a later logger startup it is an older journal. The journal is deleted only after an explicit verified receipt. If deletion fails, the verified end still counts as delivered, the journal stays on disk, and one local warning is written. Pending journals retry at a later logger startup under the same operating-system user, Helios URL and ingest key. A crash without a closing record permits replay of saved entries, but cannot prove that the complete session was captured. Complete records before a damaged or truncated tail are sent. An older journal is renamed to `<name>.helios-journal.unverified`, kept for inspection and not sent again, in two cases: it has no closing record or a damaged tail and Helios has stored all its complete records one time, or it is still incomplete after its repair pass (for example, because Helios rejected an oversized entry). One local warning per renamed journal gives the new file name and the reason: `an oversized entry was retained` or `delivery is incomplete after replay`. A journal with no closing record or a damaged tail and no oversized entry is renamed without a warning. Recovery then moves on to other sessions.

Journals live in `<user.home>/.helios/outbox/`, separate from HTML retention. They contain log data, including logged variable values, but no ingest keys. A journal retains all entries until final verification, including entries already acknowledged during the run. Key changes use a separate directory; pending journals under an old key need operator attention.

The default limit is **256 MiB per session**. To raise it to 1 GiB, set the runner environment variable `HELIOS_BUFFER_MIB=1024` and restart the Bot Agent so new sessions inherit it. Values from 1 to 65536 MiB are supported. The Java system property `helios.bufferMiB` takes precedence; `helios.outbox.directory` can override the root directory. Each concurrent or pending session has its own limit, so total disk use can exceed one session's limit. There is no automatic deletion of unverified journals. Recovery skips files that another process has locked or already deleted without reporting a delivery failure.

If the journal reaches its limit or a disk write fails, buffered entries are preserved and HTML logging continues. A local warning marks cloud logs as incomplete; the expected count still includes entries that could not be saved. Network requests never block individual log calls, but each call does perform a local disk write. Delivery warnings are local HTML diagnostics. They are not streamed and do not advance the stream ordinal or change its result.

## Bundled FFmpeg

Screen-recording uses an FFmpeg 6.0 binary (~12-15 MB) bundled at `src/main/resources/ffmpeg/ffmpeg.exe` and extracted on first use to `%LOCALAPPDATA%\A360-BotFramework\ffmpeg\<hash>\ffmpeg.exe` on each Bot Runner. The build enables only the libx264 (Fast mode), libaom-av1 (Compact mode), and ffvhuff (rolling buffer) encoders, plus the segment + concat + mp4 muxers, so the binary stays small. Because libx264 is GPL, the bundled ffmpeg is distributed under GPL-2.0 or later; full notice and build configuration are at `src/main/resources/ffmpeg/LICENSE-FFMPEG.txt` and `tools/ffmpeg-build/Dockerfile`. Source code for FFmpeg is at https://ffmpeg.org/download.html.

Concurrent processes share an extraction lock. Under that lock, each extraction replaces the fixed `ffmpeg.exe.tmp` file and checks the SHA-256 before placing the binary in the cache.

Completed rolling segments follow FFmpeg's segment-list order; the active segment is excluded. Recovery uses modification-time order for rings without a segment list and for scratch copies with a missing, empty or invalid list. Scratch lists are published with an atomic move.

Interrupted recovery keeps its `.salvaging` folder for a later startup. After salvage completes without interruption, recovery removes `session.active` so a retained folder's ring is not salvaged twice; pending managed clips can still retry. Cleanup keeps `session.meta` until the other contents are deleted. A folder without that metadata can be claimed if its name ends in `.salvaging` or its modification time is more than one day old. Existing cleanup rules still apply to corrupt or failed recordings.

Every bot that runs on one machine must use Bot-Framework **5.0.1 or later**. Older packages delete `.salvaging` folders even when recovery is still running.

### Disk usage with screen recording on

| Setting | Cost |
|---|---|
| Stage-1 rolling buffer (per session, %LOCALAPPDATA%) | ~30-100 MB at default 30 s buffer / 5 fps / 1080p |
| Per-finalized clip (Fast / H.264) | ~5-15 MB MP4, encodes in ~0.5-1 s |
| Per-finalized clip (Compact / AV1) | ~1-3 MB MP4, encodes in ~5-15 s |
| Per-error poster PNG (in `<logDir>/screenshots/`) | ~50-200 KB |
| Stage-2 encode CPU | ~80-100% one core for the encode duration above |

Document this when planning bot deployments: enabling video on every INFO entry of a chatty bot will produce hundreds of MP4s. Stick to ERROR-only unless you have a specific need. Pick **Fast** when the bot runner has free CPU but disk is plentiful; pick **Compact** when long-term clip storage matters more than encode latency.

## Building the Project
You can build this project using Gradle with the following command:

```bash
gradle clean build :shadowJar
```

## Helios ERROR media

Helios ERROR media inherits the existing **Screen recording** settings. There is no separate media control.

| Helios Cloud streaming | Screen recording | ERROR recording | ERROR media |
|---|---|---|---|
| Disabled | Any setting | Any setting | No cloud media |
| Enabled | No video, or no active recording levels/buffer | Any setting | Screenshot |
| Enabled | Capture rolling video | Unchecked | Screenshot |
| Enabled | Capture rolling video | Checked | Video and a separate screenshot |

Every `Log Message(ERROR)` qualifies, including an ERROR that the bot later recovers from. INFO and WARN recording
flags control local clips only. Video uploads use the same recorder, 5-90 second buffer, and FAST or COMPACT codec
as the local ERROR clip. No extra recorder starts when No video is selected. A failed recorder still reports the
requested video unavailable and retains its independent screenshot when capture works.

Screenshots expire after 14 days and videos after 3 days. Storage limits can remove files sooner, with videos
removed first. Screenshots (PNG) over 1 MiB and MP4 files over 8 MiB report `tooLarge`. The runner does not encode a
second video. Local HTML media and the action's returned screenshot path keep their current behavior.

A child that uses the inherited logger keeps its logger identity and ordinal counter. An explicit Start Logger
Session creates a new UUID `loggerSessionId`. Helios groups those streams under the unchanged AA `executionId`.

Completed upload copies and per-row state are saved under `~/.helios/outbox/media` (or the configured outbox root).
The 256 MiB media byte budget applies across sessions and URL/key partitions for the same OS user. It is separate
from the 256 MiB JSON limit per session. Admission uses a root file lock and counts partial copies and reservations.
Active uploads are pinned. The runner removes old cloud videos before screenshots and preserves the original local
files and status records. No ingest key is stored in a journal or media manifest.

Media upload starts after a verified ERROR row receipt. It uses the AA proxy selector and raw file PUT requests.
A verified media receipt is required before an upload copy is deleted. Media state survives JSON `/end` and verified
JSON journal deletion. Any later Helios logger startup with the same URL/key can retry saved media, including a new
session with No video selected or ERROR recording unchecked. Media retry does not recreate a deleted parent row. Close leaves the file sender
active while the recorder drains. It then waits up to 30 seconds for the ERROR media of this session whose rows Helios
stored, and stops the sender with an additional wait of at most one second. Delivery after JVM
exit is not guaranteed.

New sessions reuse the requested local recorder and clips. Local recording with Helios disabled needs no media
ownership sidecars or media completion publisher. A media ownership write failure reports cloud video unavailable
and keeps a requested local encode running. Local rings, native temporary output, and original MP4 files remain
outside the 256 MiB upload-copy budget.

**Acceptance still required on Windows:** gdigrab capture and failure, FAST/COMPACT at 5 and 90 seconds, native
write overshoot, disk and CPU use, close time, authenticated corporate proxy, and TLS inspection. Linux synthetic
muxer and HTTP tests do not prove these results. Deploy the server capability before bots use the inherited ERROR upload behavior.
