# Runtime mode: get

## Overview

`Runtime mode: get` (`mode_get`) returns the mode of a session created by [Runtime mode: start](StartRuntimeMode.md).

## Parameters

### Runtime mode session

- **Default:** `runtimeMode`.
- The session does not have to exist. This lets a child taskbot run alone.

### Default mode

- **Default:** `DEV`.
- Used when the session does not exist. Validated like the mode in `Runtime mode: start`.

## Output

String: `DEV`, `QA` or `PRD`. The session mode, or the default mode when the session does not exist.

## Exceptions

An invalid default mode fails with `Runtime mode must be DEV, QA or PRD. Received: '<value>'.`
