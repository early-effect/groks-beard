# Fast session resume

## Goal

Opening a previous session should feel like Copilot and Claude: the transcript appears immediately from disk. Attaching the Grok agent happens in the background. A 20-second timer must not blank a conversation that is already on disk.

## Why it is slow (measured)

Beard treats ACP `session/load` as the way to rebuild the UI. The spec requires the agent to **replay the entire conversation** as `session/update` notifications, then answer the RPC. Grok also reconnects MCP servers during load/resume.

On this machine, real sessions under `~/.grok/sessions/` are huge:

| File | Size | Lines |
| --- | --- | --- |
| Typical `updates.jsonl` | 2–10 MB | thousands |
| Largest groks-beard session | **86 MB** | **23,315** |

Byte breakdown of that 86 MB file:

- `tool_call_update`: 12,788 lines, **76 MB** (max line 600 KB)
- `tool_call`: 6,416 lines, 7.7 MB
- thoughts: 2,731 lines, 2 MB
- user/agent/plan/turn_completed: the rest

Current Beard path (`ChatRuntime.loadResume` → `requestLoad`):

1. Clear the transcript and show **Loading session...** (`ChatModel.isLoading` = awaiting id and empty turns).
2. Send `session/load`. `session/resume` is used only when the **webview already has** that session’s turns (`ChatRuntime.canAttach`). Picker, welcome recents, share-join, and URL restore always load.
3. Every replayed notification runs `ingestLoading` **and** `ingestUpdate` (diff reconstruction + disk reads) on the exclusive runtime gate.
4. The UI stays blocked until the RPC result. Then one `HostMsg.Transcript` is posted (`ChatModel.snapshotTurns`, which already drops thoughts and tool bodies).
5. `LoadBudget` is **20 seconds**. `timeoutLoad` posts `Timed out loading session` and whatever partial `loadModel` exists.

That cannot win against 86 MB of NDJSON over stdio. Copilot and Claude look instant because they **paint a local transcript** and attach the model separately. The Grok TUI is in-process with the agent; Beard is a stdio client, so replaying the ACP stream is the wrong rebuild path.

Grok 1.0.24 already advertises `sessionCapabilities.resume`: restore context **without** replaying history ([ACP session setup](https://agentclientprotocol.com/protocol/v1/session-setup.md)). `updates.jsonl` is documented as the authoritative conversation log ([`17-sessions.md`](file:///Users/russ/.grok/docs/user-guide/17-sessions.md)).

## Target UX

1. Click a session (picker, welcome, dashboard, share-join, `?session=`).
2. Title and chrome update immediately (already true).
3. Transcript appears from disk as soon as the compact fold finishes. No full-screen loader once any turns exist.
4. Composer stays usable: Send **queues** until `session/resume` (or fallback `session/load`) returns, same as send-before-ready.
5. Optional quiet status: `Connecting…` until attach completes. Not a blocking empty stage.
6. Attach timeout is a toast (`Timed out attaching to session`). It must **not** clear or replace the disk transcript.
7. `Restore code` still uses `session/load` + `_meta.restoreCode`. Transcript still paints from disk first.

Same-session re-click with turns already in the view keeps today’s `session/resume` (no clear, no disk re-read).

## Design

Decouple **show history** from **attach agent**.

```
picker / share-join / URL
        │
        ├─► SessionRepo.transcript(id)  ──► HostMsg.Transcript   (fast, local)
        │
        └─► session/resume  (preferred)  or session/load (restoreCode / no resume cap)
              ignore session/update replay while attaching if disk already painted
```

### 1. Compact fold: `SessionLog`

New pure module. Input: one JSONL line at a time (disk envelope `{ timestamp, method, params }` or live ACP notify). Output: `ChatModel`-shaped snapshot (turns, todos, tasks).

Reuse `SessionState.decodeUpdate` + `SessionUpdate.hostMsgs` + `ChatModel.applyMsg` for the kinds we keep. Do **not** keep:

- thought text (today’s `snapshotTurns` already strips it)
- tool `content` / `rawInput` / `rawOutput`

For `tool_call` / `tool_call_update`: identity, title, kind, status, locations, +N/−M if cheap. Last update per `toolCallId` wins.

**Large-line rule:** if a `tool_call_update` line is huge (tens of KB+), do not run `zio.json` on the whole AST. Extract `toolCallId` / `status` / `title` with a small scan. That is how 76 MB of tool bodies stay off the heap.

Skip `_x.ai/session/update` kinds that are not transcript (retry, recap, image_compressed). Keep `turn_completed` so historical turns get `StopReason`.

`SessionLog.snapshot(turns)` matches `ChatModel.snapshotTurns` so disk and today’s ACP snapshot look the same.

### 2. Stream the file: `SessionFs.foldLines`

Do not `readFileSync` 86 MB into one `String`.

```scala
trait SessionFs:
  def foldLines[S](path: String, z: S)(f: (S, String) => S): BeardError.Result[S]
```

Default (tests / `MemorySessionFs`): split `readText`. Production:

- `NodeSessionFs`: `fs.createReadStream` + readline
- `NioSessionFs`: `Files.lines` (close the stream)

`SessionRepo.transcript(id)` reads `updates.jsonl` under the session dir, falls back to `events.jsonl` if that is all that exists, folds with `SessionLog`, returns `SessionSnapshot(turns, todos, tasks)`. Missing file → empty snapshot, not an error.

### 3. Runtime: paint, then attach

`resumeSession` / share-join / URL restore:

1. Take the exclusive lock: `beginResume`, `ClearTranscript`, `postMeta`, close picker. **Release the lock.**
2. `sessions.transcript(id)` **outside** the lock so a large fold cannot stall ACP ingest.
3. Re-take the lock: if `pendingResume` is still this id, `post(HostMsg.Transcript(snap))`, copy todos/tasks, set `diskPainted = true`, `loading = false`.
4. Attach:
   - `restoreCode` → `session/load` with `_meta` (unchanged)
   - else if `sessionCapabilities.resume` → `session/resume`
   - else → `session/load`
5. While `pendingResume` and `diskPainted`, **drop** inbound `session/update` (the load replay). After the RPC result, live updates flow as today.
6. `timeoutLoad`: if disk already painted, toast only. Do not post a second Transcript and do not clear turns. Copy: `Timed out attaching to session`.
7. `doSend` while `pendingResume`: **queue**, even if `initialized` (today it can start a turn mid-load because `running` is false).

`canAttach` stays for same-session, in-memory reattach (no disk read).

Share-join after `initialize` uses this same paint-then-attach path instead of a blocking `session/load`.

### 4. UI

`ChatModel.isLoading` already means “awaiting this id and no turns.” Disk `Transcript` clears that, so the full-screen loader only covers the short fold.

Add a thin attaching signal if needed (`HostMsg` or reuse error/status): composer/activity `Connecting…` while `pendingResume` after turns exist. Do not keep the empty-stage loader.

Chrome tests that gate on `session-loading` until `completeResume` must expect the snapshot as soon as the host posts `Transcript` (PreviewBridge already synthesizes one). Live preview: click a real recent session and see turns before grok finishes attach.

### 5. What we will not do in this PR

- Write a Beard cache file into `~/.grok/sessions/` (CLI-owned).
- Virtualize a 200-turn markdown transcript (follow-up if paint is instant but scroll janks).
- Reconstruct Keep/Undo diffs from historical tool bodies on resume (live tools after attach still reconstruct).
- Change MCP reconnect inside grok (`session/resume` still reconnects servers; that only delays Send, not the transcript).

## Key decisions

1. **Disk is the transcript; ACP is the live attach.** Matches Copilot/Claude and the Grok session layout. `session/load` replay is not a UI protocol.
2. **Prefer `session/resume` whenever advertised**, except `Restore code`. Grok already advertises it; Beard only used it for same-view reattach.
3. **Ignore replay if disk painted.** A fallback `session/load` must not dump 23k `HostMsg`s into the webview.
4. **Parse outside the exclusive gate.** A 1–2 s fold must not block `ingestData`.
5. **Timeout cannot destroy a painted transcript.** 20 s remains an attach budget, not a load budget.
6. **Compact history matches today’s snapshot.** No thoughts, no tool stdout. Fast and consistent with `HostMsg.Transcript` now.

## Files

| Area | Touch |
| --- | --- |
| `core/.../SessionLog.scala` | new fold |
| `core/.../SessionIndex.scala` / `ChatServices.scala` | `foldLines`, `transcript` |
| `host/.../NodeSessionFs.scala`, `core/.../SessionWalk.scala` | streaming |
| `core/.../ChatRuntime.scala`, `ChatState.scala` | paint-then-attach, ignore replay, queue send, timeout |
| `core/.../protocol.scala` | only if attaching chrome needs a HostMsg |
| `ui/.../ChatApp.scala` | connecting caption if we add one |
| tests | `SessionLogSpec`, `ChatRuntimeSpec`, Memory Fs, chrome if overlay copy changes |

## Tests (must prove the bug is gone)

- `SessionLog` fixture jsonl: user + agent + tool identity; thoughts and 600 KB `content` do not appear in turns; last tool status is `completed`.
- `SessionLog` skips huge `tool_call_update` without allocating the body string into the model.
- `SessionRepo.transcript` on Memory Fs and (JVM) a temp `updates.jsonl`.
- `resumeSession("sess_disk")` posts `Transcript` from disk **before** the ACP result; writes `session/resume` not `session/load` when advertised.
- `FakeAgent(hangLoad = true)` + disk history: transcript still posted; 20 s adjust toasts attach timeout; turns remain.
- `restoreCode = true` still writes `session/load` with `_meta.restoreCode`.
- Same-id `hasHistory = true` still `session/resume` and does not `ClearTranscript`.
- Send during attach enqueues (`HostMsg.Queued`), does not `session/prompt`.
- Share-join after initialize paints disk then resume.
- Existing: lock, agent-gone, live-load cancel, stale load drop.

Proof: `sbt --no-server "core/testFull; uiJS/testFull"` (Metals `compile-file` / `test` while iterating).

Visual: live preview, click a large recent session. Turns on screen before Output would have logged `session/load ok` / timeout. Stretch and scroll. Send queues then flushes after attach.

## Implementation order

One PR, one concern (fast resume). Not a stack: a `SessionRepo.transcript` landing without the runtime change is dead API.

1. `SessionLog` + specs (fixture jsonl, huge-line skip).
2. `SessionFs.foldLines` + `SessionRepo.transcript` + Node/Nio streaming + Memory defaults.
3. `ChatRuntime` paint-then-attach, ignore replay, queue send, timeout toast.
4. Chrome/connecting caption if the attach gap is visible.
5. Headed Firefox on live preview with a real large session.

## Out of scope

Voice, Keep/Undo reconstruction on resume, transcript virtualization, writing sidecar caches, Windows named pipes.
