# CloseApplications

## Overview

`CloseApplications` terminates the listed applications at once. It does not send a close request and does not wait
for a graceful shutdown, so unsaved work is lost.

![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/144ef622-7068-4a86-8574-4585749628dc)

## Parameters

### List of Process Names to Close

- **Type:** `List<Value>`
- **Description:** Names of processes to be closed. Can include both the application's executable name (e.g., "
  excel.exe") and the process name (e.g., "excel").
- **Example Values:** "excel.exe", "chrome", "notepad"
