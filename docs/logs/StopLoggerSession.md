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

1. Cloud capture stops and the expected entry count is saved to the disk journal.
2. The sender flushes remaining batches and sends `POST {url}/api/ingest/sessions/{id}/end` with `expectedEntries` and the worst logged status (`ERROR`, `WARN` or `OK`). Helios verifies every ordinal before confirming delivery. Gaps trigger replay with the same ordinals and timestamps.
3. The caller waits up to 5 seconds for delivery, plus up to 1 second to stop the sender. Unverified journals remain on disk for retry at a later logger startup with the same URL and key. A pending-delivery warning is written while the HTML logger is still open.
4. The HTML logger closes, then the screen recorder stops and finalizes pending clips.

Cloud delivery never fails the bot. A disk buffer failure is reported in HTML; buffered records remain on disk. See [buffer size and recovery limits](../../README.md#failure-behaviour).

## Exceptions

Does not throw on double-close. May propagate a `BotCommandException` only if an unexpected error occurs while flushing logs or closing the underlying recorder.
