# JSONReader

## Overview

Read data from a JSON source, which can be either a file or a text input, and convert this data into a dictionary.

![image](https://github.com/A360-Tools/Bot-Framework/assets/82057278/800d1bf7-7bda-4a1d-8f1a-6ea7821988bc)

## Features

- Supports reading JSON data from a file or direct text input.
- Capable of parsing the entire JSON structure or specific nodes based on the provided path segments.
- Offers functionality to trim whitespace from the resulting dictionary values.

## Parameters

### `inputMethod`

- **Type:** `String`
- **Description:** Specifies the source of the JSON data, either from a file or direct text input.
- **Options:**
    - `"File"`: The JSON data will be read from a file.
    - `"Text"`: The JSON data will be directly provided as text input.

### `jsonFilePath` (Conditional on `inputMethod` being `"File"`)

- **Type:** `String`
- **Description:** The file path to the JSON file.
- **Constraints:** Must be a non-empty string with a `.json` file extension.

### `charsetName` (Conditional on `inputMethod` being `"File"`)

- **Type:** `String`
- **Description:** The character set to use when reading the JSON file.
- **Default:** `"UTF-8"`

### `jsonText` (Conditional on `inputMethod` being `"Text"`)

- **Type:** `String`
- **Description:** The JSON content provided as text.
- **Constraints:** Must be a non-empty string containing valid JSON data.

### `parsingMethod`

- **Type:** `String`
- **Description:** Determines how the JSON data will be parsed.
- **Options:**
    - `"All Nodes"`: Parse the entire JSON structure.
    - `"Specific Node"`: Parse data from a specific node based on provided path segments.

### `jsonPathSegments` (Conditional on `parsingMethod` being `"Specific Node"`)

- **Type:** `List<Value>`
- **Description:** The path segments from the root to the specific node to be parsed.
- **Example:** For a path like `config.dev`, the list should be `["config", "dev"]`.

### `isTrimValues`

- **Type:** `Boolean`
- **Description:** Whether to trim whitespace from the dictionary values.
- **Default:** `false`

## Output

- **Type:** `DictionaryValue`
- **Description:** A dictionary containing key-value pairs extracted from the JSON data. Keys are constructed from the
  path to each value in the JSON structure, and values are stored as strings.
- Array keys keep their zero-based index. For example, `{"x":[1,2,3]}` produces `x[0]`, `x[1]`, and `x[2]`.
- An array inside an array adds a dot before its index. For example, `{"m":[[1,2],[3]]}` produces `m[0].[0]`,
  `m[0].[1]`, and `m[1].[0]`. The key is `m[0].[1]`, not `m[0][1]`.

More examples of keys:

| JSON | Keys |
|------|------|
| `{"a":{"b":1}}` | `a.b` |
| `{"x":[1,2]}` | `x[0]`, `x[1]` |
| `{"o":[{"n":"A"}]}` | `o[0].n` |
| `{"m":[[1,2],[3]]}` | `m[0].[0]`, `m[0].[1]`, `m[1].[0]` |
| `{"a":[[[1]]]}` | `a[0].[0].[0]` |
| `[{"id":1}]` (the root, or the selected node, is an array) | `[0].id` |
| `[[1,2]]` (the root, or the selected node, is an array) | `[0].[0]`, `[0].[1]` |
