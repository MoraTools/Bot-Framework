# Runtime mode is

## Overview

`Runtime mode is` (`mode_is`) is a condition for `If` and `Loop while`. It is true when the effective runtime mode equals the selected mode.

## Parameters

### Runtime mode session

- **Default:** `runtimeMode`.
- The session does not have to exist. This lets a child taskbot run alone.

### Mode

- **Options:** `DEV`, `QA`, `PRD`.

### Default mode

- **Default:** `DEV`.
- Used when the session does not exist. Validated like the mode in [Runtime mode: start](StartRuntimeMode.md).

## Exceptions

An invalid default mode fails with `Runtime mode must be DEV, QA or PRD. Received: '<value>'.`
