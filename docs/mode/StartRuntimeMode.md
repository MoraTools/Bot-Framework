# Runtime mode: start

## Overview

`Runtime mode: start` (`mode_start`) creates a runtime mode session that holds `DEV`, `QA` or `PRD`.

In the master taskbot, save the session as `runtimeMode` with **GLOBAL** scope. Child taskbots can then read it by name with [Runtime mode: get](GetRuntimeMode.md) or [Runtime mode is](IsRuntimeMode.md), without inputs.

## Parameters

### Mode

- **Type:** Text. A variable or an expression is allowed.
- **Allowed values:** `DEV`, `QA`, `PRD`. Spaces before and after the value are removed and case is ignored. The session stores the value in uppercase.

## Output

A session that holds the mode.

## Exceptions

Any other value fails with a `BotCommandException` that names the allowed values: `Runtime mode must be DEV, QA or PRD. Received: '<value>'.`
