# A360 Bot Framework Package

**Version 4.5.2** - maintained by **jamir-boop**, forked from the original A360 Tools package.

4.5.2 shares variable-card rendering and logger setup. It keeps the existing log output, recording settings, and Helios behavior.

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
- **Rolling screen-recording buffer**: opt-in continuous capture of the last N seconds (5-90, default 30) of bot activity. When a log entry fires at a configured level (any combination of INFO/WARN/ERROR), the buffer is finalized into a browser-playable MP4 placed next to the log. Choose between **Fast** (H.264, larger files, faster encoding, recommended) and **Compact** (AV1, smaller files, slower encoding) per session. On JVM crash or drain timeout, both the rolling buffer and any pending per-error clips are salvaged on the next bot startup so the HTML log's video links self-heal at the paths they already reference.

### [Helios Cloud Streaming](https://github.com/A360-Tools/Bot-Framework/blob/main/docs/logs/StartLoggerSession.md)
- Opt-in per session: pick a Helios Cloud URL and an ingest key on `Start Logger Session`, and every entry is posted to the server while the bot runs.
- Purely additive: the HTML log, screenshots, video clips and rollover are byte-for-byte the same whether streaming is on or off.
- Never fails the bot: any ingest failure produces a single WARN row in the HTML log and nothing else.

### [Config Data Reading](https://github.com/A360-Tools/Bot-Framework/tree/main/docs/config)
- Reads configuration data from CSV, Excel, JSON, and XML formats into dictionaries.
- Excel application -independent functionality for broader applicability and convenience.
  
  ![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/08014370-a4b5-4001-9736-2e77fcc1bb90)
  
  ![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/bd3c7888-f318-49e6-bb6d-a90191249d45)
  
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
- **Config Read CSV/Excel/JSON/XML**: Streamlines the import of configuration data into dictionaries from various file formats.
- **Device Close Application**: Facilitates the closing of applications with close and terminate requests.
- **Device Create Folders**: Automates the creation of directories, ensuring the existence of necessary parent directories.
- **Device Clean Directory**: Allows for the rule-based deletion of files or folders, with options to exclude specific items by regex pattern and to skip locked files instead of failing the bot.
- **Documentation About/Caution Sequence/Comment/Sequence**: Offers a range of documentation utilities from basic commenting to detailed sequence documentation with caution highlights and screenshots.
- **Log Message**: Provides session-based logging with options for detailed messages and screenshots.
- **Logs Start/Stop Session**: Manages the lifecycle of logging sessions, from initiation to termination. Always pair `Start Logger Session` with `Stop Logger Session` in a Finally block so logs flush and any pending video clips finalize.

## Local HTML logs

Each active or rotated HTML file contains its own styles and variable snapshots. Open it directly in a browser; no server or external stylesheet is required. Each variable snapshot is compressed inside the file. The Variables dialog requires JavaScript and a browser with `DecompressionStream('gzip')` support. Messages remain ordinary HTML for browser search and copy. Only the selected snapshot is decoded, and it is cleared when the dialog closes. New entries are visible after refreshing the page. Screenshots and recordings remain in `screenshots/` and `clips/` beside the log. Copy these folders with the HTML when media is needed.

Entries are appended as complete blocks. Closing a session does not add a footer, and reopening a complete log keeps all existing bytes in place. Rollover counts log entries, including after a restart; rows inside a variable table do not count as extra entries. The existing maximum-entry setting still applies. Large variable snapshots increase the HTML file size and are not silently truncated.

When an existing file uses an earlier HTML format or ends with an incomplete write, the next session moves that file intact to the next numbered archive (for example, `2_log.html`) and starts a fresh active file. Existing archives and old `variables/` pages are preserved. Migration stops if the file cannot be moved; it does not overwrite an archive or discard the incomplete bytes.

## Helios Cloud streaming

Every logger session can optionally stream its entries live to a Helios Cloud server. The feature is off by default and existing bots keep working unchanged after upgrading the package: the new inputs were added, none were moved or renamed.

### Inputs on `Start Logger Session`

| Input | Type | Notes |
|---|---|---|
| Helios Cloud streaming | Select | `Disabled` (default) or `Enabled`. |
| Helios Cloud URL | Text | Server root, for example `http://192.168.18.5:5180`. Required when enabled. |
| Helios ingest key | Credential | Ingest key issued for the Control Room (`hik_<prefix>_<secret>`). Credential Vault attribute or bot password. Required when enabled. |

### What is sent

| Call | Payload |
|---|---|
| `POST {url}/api/ingest/sessions` | Execution id, bot URI, master Task Bot URI (empty when the logger runs in the master), Control Room file id, machine, user, local start time, UTC offset in minutes. |
| `POST {url}/api/ingest/sessions/{id}/entries` | A batch of entries: first ordinal plus timestamp, UTC offset, level, source, task, machine, user, message, variable count, the logged variables and screenshot/clip path - the same values the HTML row shows. |
| `POST {url}/api/ingest/sessions/{id}/end` | Sent by `Stop Logger Session` with `status` = the worst level logged (`ERROR`, `WARN` or `OK`) and `expectedEntries` for delivery verification. |

The ingest key travels in the `X-Helios-Ingest-Key` header. Local media paths stay in the log rows. With streaming enabled, every ERROR also uploads media according to the Screen recording settings below.

The variables logged with an entry are streamed with it, each as a name, a type label (`STRING`, `NUMBER`, `LIST`, `TABLE` ...) and a plain-text value; lists, dictionaries, records and tables render as JSON text. At most 100 variables per entry are sent and each value is cut at 8192 characters, flagged with `truncated`. `variablesCount` still reports how many were logged before the cap. Nothing is streamed when Helios Cloud streaming is disabled.

### Failure behaviour

Streaming never fails the bot. Each entry is numbered and copied to a UTF-8 disk journal before network delivery. The background sender batches up to 50 entries or 256 KiB, or flushes after 2 seconds. A larger single entry travels alone, subject to the server's 5 MiB request limit. HTTP 413 splits a rejected batch; an oversized single entry remains on disk while later valid entries continue to send. Timeouts, HTTP 408/429 and server errors use retries with backoff and `Retry-After`; retries preserve the original ordinals and payloads.

`Stop Logger Session` saves the expected entry count and waits up to 5 seconds for delivery, plus up to 1 second to stop the sender. Helios verifies the complete sequence before confirming delivery. A missing entry triggers one full repair pass; duplicate ordinals are ignored. If the sequence is still incomplete, the journal is retained and recovery moves on to other sessions. The journal is deleted only after an explicit verified receipt. Pending journals retry at a later logger startup under the same operating-system user, Helios URL and ingest key. A crash without a closing record permits replay of saved entries, but cannot prove that the complete session was captured. Complete records before a damaged or truncated tail are sent; the journal stays unverified and is retained for inspection.

Journals live in `<user.home>/.helios/outbox/`, separate from HTML retention. They contain log data, including logged variable values, but no ingest keys. A journal retains all entries until final verification, including entries already acknowledged during the run. Key changes use a separate directory; pending journals under an old key need operator attention.

The default limit is **256 MiB per session**. To raise it to 1 GiB, set the runner environment variable `HELIOS_BUFFER_MIB=1024` and restart the Bot Agent so new sessions inherit it. Values from 1 to 65536 MiB are supported. The Java system property `helios.bufferMiB` takes precedence; `helios.outbox.directory` can override the root directory. Each concurrent or pending session has its own limit, so total disk use can exceed one session's limit. There is no automatic deletion of unverified journals. Recovery skips files that another process has locked or already deleted without reporting a delivery failure.

If the journal reaches its limit or a disk write fails, buffered entries are preserved and HTML logging continues. A local warning marks cloud logs as incomplete; the expected count still includes entries that could not be saved. Network requests never block individual log calls, but each call does perform a local disk write. Delivery warnings are local HTML diagnostics and are not themselves streamed.

## Bundled FFmpeg

Screen-recording uses an FFmpeg 6.0 binary (~12-15 MB) bundled at `src/main/resources/ffmpeg/ffmpeg.exe` and extracted on first use to `%LOCALAPPDATA%\A360-BotFramework\ffmpeg\<hash>\ffmpeg.exe` on each Bot Runner. The build enables only the libx264 (Fast mode), libaom-av1 (Compact mode), and ffvhuff (rolling buffer) encoders, plus the segment + concat + mp4 muxers, so the binary stays small. Because libx264 is GPL, the bundled ffmpeg is distributed under GPL-2.0 or later; full notice and build configuration are at `src/main/resources/ffmpeg/LICENSE-FFMPEG.txt` and `tools/ffmpeg-build/Dockerfile`. Source code for FFmpeg is at https://ffmpeg.org/download.html.

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
removed first. PNG/JPEG files over 1 MiB and MP4 files over 8 MiB report `tooLarge`. The runner does not encode a
second video. Local HTML media and the action's returned screenshot path keep their current behavior.

A child that uses the inherited logger keeps its logger identity and ordinal counter. An explicit Start Logger
Session creates a new UUID `loggerSessionId`. Helios groups those streams under the unchanged AA `executionId`.
A server that has no media capability still accepts JSON logging. The runner warns once and keeps bounded media
copies for a later compatible startup.

Completed upload copies and per-row state are saved under `~/.helios/outbox/media` (or the configured outbox root).
The 256 MiB media byte budget applies across sessions and URL/key partitions for the same OS user. It is separate
from the 256 MiB JSON limit per session. Admission uses a root file lock and counts partial copies and reservations.
Active uploads are pinned. The runner removes old cloud videos before screenshots and preserves the original local
files and status records. No ingest key is stored in a journal or media manifest.

Media upload starts after a verified ERROR row receipt. It uses the AA proxy selector and raw file PUT requests.
A verified media receipt is required before an upload copy is deleted. Media state survives JSON `/end` and verified
JSON journal deletion. Any later Helios logger startup with the same URL/key can retry saved media, including a new
session with No video selected or ERROR recording unchecked. Media retry does not recreate a deleted parent row. Close leaves the file sender
active while the recorder drains, then stops it with an additional wait of at most one second. Delivery after JVM
exit is not guaranteed.

New sessions reuse the requested local recorder and clips. They do not add a cloud-only ring or lossless scratch
copy. Local recording with Helios disabled needs no media ownership sidecars or media completion publisher.
A media ownership write failure reports cloud video unavailable and keeps a requested local encode running.
Shared originals can remain after a crash when ownership persistence failed; cleanup preserves them to avoid
removing a requested local clip. Local rings, native temporary output, and original MP4 files remain outside the
256 MiB upload-copy budget.

Recovery support for older pending capture work remains. Its 512 MiB capture admission, 128 MiB cloud-only ring
reservation, and software stop monitor apply to that older work, not to automatic startup in this configuration.
Those limits are not an OS disk quota or a bound on all runner disk use. Native overshoot and orphan behavior still
need Windows measurements if such older work is present.

**Acceptance still required on Windows:** gdigrab capture and failure, FAST/COMPACT at 5 and 90 seconds, native
write overshoot, disk and CPU use, close time, authenticated corporate proxy, and TLS inspection. Linux synthetic
muxer and HTTP tests do not prove these results. Deploy the server capability before bots use the inherited ERROR upload behavior.
