# Bot-Framework — Agent Context

Open-source Automation Anywhere A360 in-bot package (github.com/A360-Tools/Bot-Framework):
robust HTML session logging, config reading (CSV/Excel/JSON/XML), auto log deletion,
close-application, framework templating, and a rolling screen-recording buffer (bundled FFmpeg).
Since 4.0.0 (author jamir-boop) it also streams logger sessions to Helios Cloud
(`utilities/helios/`); the binding API contract lives in `../helios-cloud/plan.md`.

## Build

JDK 11 only (`gradle.properties` → `org.gradle.java.home`). `./gradlew clean test shadowJar --console=plain`.
On Linux 19 tests fail for Windows-only reasons (bundled ffmpeg.exe, file-age semantics); the Windows build
host is the acceptance environment. Output: `build/libs/A360BotFramework-<version>.jar`.

## Central Knowledge Base

Shared AA knowledge: `<AA_KB_ROOT>`. Resolve environment variable; fallback: sibling `../aa-kb`.

- Start: `<AA_KB_ROOT>/README.md` → `<AA_KB_ROOT>/projects/bot-framework.md`.
- Never bulk-load `<AA_KB_ROOT>/artifacts/` (~50 GB).
- Updates: `<AA_KB_ROOT>/UPDATE.md`.
- Diffs: compare by default. Ingest requires explicit approval.
