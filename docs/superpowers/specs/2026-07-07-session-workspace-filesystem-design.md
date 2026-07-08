# Assistant Session Filesystem Design

## Goal

Expose lightweight file operations to `HAssistant` while keeping server paths private and isolating files by user and chat session.

## Directory Model

The tool accepts simple session-local paths such as `/draft.txt` or `/notes/a.txt`.

Those paths resolve under:

```text
/tmp/h-agent/assistant-files/{userId}/{sessionId}/
```

The implementation resolves `{userId}` and `{sessionId}` from LangChain4j `@ToolMemoryId`. Normal chat memory ids use `userId:promptId:sessionId`; domain-agent memory ids use `userId:agent:agentId:sessionId`.

## Operations

The tool exposes `read_file`, `write_file`, `edit_file`, `list_files`, `delete_file`, and `move_file`.

Writes create new files only. Edits require exact string replacement and fail when the target string appears multiple times unless `replace_all` is true. Deletes cannot remove the session root `/` and directory deletion requires `recursive=true`. Moves default to no overwrite.

## Safety

All paths are normalized inside the current session root. `..`, `~`, blank paths, backslash paths, and attempts to escape the session root are rejected before touching the filesystem.

## Configuration

The file root is configured under `chat.filesystem`:

- `base-dir`: root for assistant session files
- `max-file-size-bytes`: maximum file size for read/search operations

Defaults are local development friendly and point under `/tmp/h-agent/assistant-files`.
