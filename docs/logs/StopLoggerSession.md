# Stop Logger Session

## Overview

`Stop Logger Session` terminates a specified logger session, flushing any buffered log entries and finalizing any pending screen-recording clips before releasing session resources.

This action is **idempotent**: invoking it on a session that is already closed returns silently. That makes it safe to place inside a Finally block (the recommended pattern) even when the happy path also calls Stop, so a real exception is never masked by an "already closed" error.

![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/ebbb98f9-7df0-4859-a2b6-cda16880c3c5)

## Parameters

### Logger Session

- **Description:** The logger session to stop. This session should have been previously started by `Start Logger Session`.

## End of session

Stopping a session runs in this order:

1. The Log4j2 context is stopped, which flushes the HTML log and drains any queued Helios Cloud entries.
2. When Helios Cloud streaming was enabled and the remote session was opened, `POST {url}/api/ingest/sessions/{id}/end` closes it. The reported `status` is the worst level logged during the session: `ERROR` if any ERROR entry was written, `WARN` if any WARN entry was written, otherwise `OK`.
3. The screen recorder is stopped and any pending video clips are finalized.

A Helios Cloud failure at this point is swallowed like every other ingest failure; the session still closes and the bot still finishes.

## Exceptions

Does not throw on double-close. May propagate a `BotCommandException` only if an unexpected error occurs while flushing logs or closing the underlying recorder.
