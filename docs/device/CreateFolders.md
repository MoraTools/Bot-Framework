# CreateFolders

## Overview

`CreateFolders` facilitates the creation of directories based on specified paths, handling both relative and absolute
paths. It ensures the creation of any necessary parent directories and allows for optional overwriting of existing
folders. Overwrite deletes an existing folder and all its content (recursively) before it creates the folder again.

![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/a8645e97-62fc-4ccb-8b1d-ea55577f1997)

## Parameters

### Creation Mode

- **Options:** "Relative Path" or "Absolute Path"
- **Description:** Specifies whether folder paths are relative to a given base path or are absolute.

### Base Folder Path

- **Condition:** Required for "Relative Path" mode. The path must not be blank.
- **Description:** The root folder path where directories will be created.

### Folder Names or Paths

- **Type:** `List<Value>`
- **Description:** List of folder names or paths to create. For relative paths, they are relative to the base folder
  path. In "Absolute Path" mode, each entry must be a full path, for example `D:\Reports\2024`.
- The action checks all paths before it creates, overwrites or deletes any folder. If it rejects one path, it changes no
  folder. See [Rejected paths](#rejected-paths).

### Overwrite Existing Folders

- **Type:** `Boolean`
- **Description:** Determines whether to overwrite folders if they already exist.
- When on, the action **deletes the existing folder with all its files and subfolders (recursively)** and then creates
  the folder again. You cannot undo this. Use it only for folders that hold no data you need.
- When off, the action keeps an existing folder and its content, and still returns its path.

### Append to Existing Dictionary

- **Type:** `Boolean`
- **Description:** If enabled, details of created folders are appended to an existing dictionary.

### Existing Config Dictionary

- **Condition:** Required if appending to an existing dictionary.
- **Description:** The dictionary to which the created folder details will be appended.

### Key Suffix

- **Description:** Suffix to add to the dictionary keys representing folder names.

### Key Prefix

- **Description:** Prefix to add to the dictionary keys representing folder names.

## Output

- **Type:** `DictionaryValue`
- **Description:** A dictionary containing keys as folder names (with optional prefix/suffix) and values as the
  corresponding folder paths.

## Rejected paths

The action stops with an error, and changes no folder, when it finds one of these.

All modes:

- A blank folder path.
- A filesystem root, for example `C:\` or `/`.
- A path whose root does not exist, for example an unmapped drive or an offline network share. This also applies to the
  base folder path.
- A path that has a file, or a symbolic link that points nowhere, in place of a folder. This applies to a parent folder
  and to the path itself.

"Absolute Path" mode:

- A path that is not a full path, for example `.`, `..`, `Output` or `C:`.

"Relative Path" mode:

- A blank base folder path.
- A folder path that is absolute, for example `C:\Reports`.
- A folder path that is the base folder itself, or that leaves the base folder, for example `.`, `child/..` or
  `../outside`. The check also follows existing symbolic links.

When "Overwrite existing folders" is on:

- Two paths that are the same, or where one path is inside the other, for example `Reports` and `Reports\2024`. The
  action compares the real folders, so a path through a symbolic link counts too.
- An existing folder that is, or contains, a symbolic link, a junction (a Windows folder link) or another reparse point.
  Such a link can lead outside the folder, and the delete could remove files there.

## Exceptions

Throws `BotCommandException` if:

- The action rejects a path (see [Rejected paths](#rejected-paths)).
- Invalid paths are provided.
- There are permissions issues.
- Overwriting existing folders fails.
- Any other unexpected error occurs during folder creation.
