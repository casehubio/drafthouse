# SSE Push Delivery — Debate Channel Events to Browser

**Issue:** #50
**Sub-project:** #41 sub-project 2
**Date:** 2026-06-11

## Problem

Debate messages flow through Qhorus channels but are invisible to the browser. The DraftHouse UI has no real-time feed of debate activity. An MCP client (Claude Code) can call `start_debate`, `raise_point`, `respond_to` etc., but the browser only discovers results by polling `get_debate_summary` — there is no push mechanism.

## Approach

Cursor-based polling SSE, following Claudony's proven `MeshResource.channelEvents()` pattern. The SSE endpoint polls `MessageService.pollAfter()` every 500ms, parses each message into a `DebateStreamEntry`, and emits it as a JSON SSE data frame. No event bus, no observer backend — the polling pattern resolves the Mutiny lazy-subscription gap and the cross-thread SSE flushing issue that Claudony documented when abandoning bus-push delivery.

### Why not bus-push?

Claudony built exactly the bus-push architecture (ChannelBackend.post() → ChannelEventBus → SSE endpoint) and abandoned it. Two independent problems:

1. **Mutiny lazy subscription.** `Multi.createFrom().emitter()` registers the subscriber in its callback at subscription time. `Multi.createBy().concatenating().streams(catchUp, live)` subscribes to `live` only after `catchUp` completes — so no bus subscriber exists during catch-up. Messages dispatched during catch-up are in neither stream.

2. **Cross-thread SSE flushing.** `ChannelGateway.fanOut()` runs each `backend.post()` on `Thread.ofVirtual().start()`. The emit from the virtual thread to the SSE response (owned by a different thread) caused frames not to be flushed reliably. Claudony's `MeshResource.java` documents this explicitly:
   > *"The SSE-via-ticks approach is used here because the ChannelEventBus emitter cross-thread emit (vert.x-eventloop-thread-X → response owned by thread-Y) caused the emitted SSE frame to not be flushed to the browser reliably."*

The 500ms polling trade-off is imperceptible for debate sessions where messages arrive seconds apart.

### SSE stream vs projection independence

The SSE stream and the `DebateChannelProjection` (which folds messages into `ReviewState`) are independent and serve different purposes. The SSE stream is **raw event replay** — every debate message, individually, in arrival order. The projection is a **folded state** — an accumulated summary of points, threads, memos, and findings. Neither replaces the other. The projection is consumed by MCP tools (`get_debate_summary`); the SSE stream is consumed by the browser for real-time display.

## Data Model

### DebateStreamEntry

Record in `runtime` module (`io.casehub.drafthouse.debate`). This is a transport DTO for SSE delivery — not a domain type. All Java consumers (resource, tests) are in runtime/, so no api/ placement is needed.

Named `DebateStreamEntry` (not `DebateEvent`) because ARC42 §2/§5/§13 claim `DebateEvent` for a sealed Java 17 interface with six record variants — a domain event concept from C3, superseded in C5 by `EntryType` enum + `DebateProtocol.parseMeta()`, but still the ARC42's canonical name. **ARC42 §2 and §13 need updating** to remove the stale sealed-type references.

```java
public record DebateStreamEntry(
    EntryType entryType,    // RAISE, AGREE, DISPUTE, QUALIFY, COUNTER, FLAG_HUMAN, MEMO,
                            // SUB_TASK_REQUEST, SUB_TASK_FINDING, SUB_TASK_ERROR, DECLINED
                            // (RESTART_CONTEXT is in the enum but filtered by from() — see note below table)
    AgentType agentRole,    // REV, IMP, SUPERVISOR, MODERATOR, SELECTOR
    int round,
    String content,         // text body, stripped of meta encoding
    String pointId,         // debate point this entry belongs to (nullable — absent for MEMO, RESTART_CONTEXT)
    String subTaskId,       // sub-task identifier (nullable — only for SUB_TASK_* entries)
    Priority priority,      // P1/P2/P3 (nullable, RAISE only)
    Scope scope,            // ISOLATED/SYSTEMIC (nullable, RAISE only)
    String location,        // spec section (nullable, RAISE only)
    String sender,          // instance id
    Instant timestamp       // message creation time
) {}
```

**Field semantics by entry type:**

| EntryType | pointId source | subTaskId source |
|-----------|---------------|-----------------|
| RAISE | Qhorus `correlationId` | null |
| AGREE/DISPUTE/QUALIFY/COUNTER/DECLINED | Qhorus `correlationId` | null |
| FLAG_HUMAN | Qhorus `correlationId` | null |
| SUB_TASK_REQUEST/FINDING | `meta.get("pointId")` | Qhorus `correlationId` |
| SUB_TASK_ERROR | null (not in META) | Qhorus `correlationId` |
| MEMO | null | null |

`RESTART_CONTEXT` is in the `EntryType` enum but does not appear in the SSE stream. `restart_from_round` creates RESTART_CONTEXT messages with no `agent=` META field and no `round=` field (`originRound` is not the same key). The `from()` factory returns null for these (missing agent), so they are discarded. This is consistent with `DebateChannelProjection.apply()` which also discards them (`case RESTART_CONTEXT -> state`). The content of a RESTART_CONTEXT message is the full prior-session summary — infrastructure provenance not intended for the browser event stream.

`inReplyTo` (Qhorus message id of the parent message) is deliberately excluded — the browser receives no entry's own Qhorus message id, so the reference would be unresolvable. Threading is fully handled by `pointId`.

All fields except `pointId`, `subTaskId`, `priority`, `scope`, and `location` are non-null. Jackson serializes enums as strings by default.

### Static factory

A single static factory on the record itself:

```java
public static DebateStreamEntry from(Message msg) { ... }
```

Parses `DebateProtocol.parseMeta()` from `msg.content`, splits meta from body via `DebateProtocol.bodyContent()`, resolves enums via `valueOf()`, extracts `pointId` and `subTaskId` based on entry type, uses `msg.createdAt` for timestamp.

**Error handling:** returns `null` for unparseable messages — no META sentinel, null/unknown `entryType`, missing `agent` field. The caller filters nulls from the stream. This matches `DebateChannelProjection.apply()` which silently discards messages with missing `entryType` or unknown agent roles.

### EntryType enum change

`RESTART_CONTEXT` must be added to the `EntryType` enum. Currently `DebateChannelProjection.apply()` intercepts it via string comparison *before* `EntryType.valueOf()`, treating it as "infrastructure provenance." But RESTART_CONTEXT IS a debate entry type — the projection choosing to ignore it doesn't mean the enum should pretend it doesn't exist. Migration:
1. Add `RESTART_CONTEXT` to `EntryType`
2. Replace the string comparison in `DebateChannelProjection.apply()` with `case RESTART_CONTEXT -> state;` in the exhaustive switch
3. Delete the two lines of string matching

This makes the projection compiler-checked and the enum complete.

### DebateSessionRegistry interface change

The active sessions endpoint requires enumeration. Add to `DebateSessionRegistry` (in api/):

```java
Collection<DebateSession> activeSessions();
```

`DebateSessionRegistryImpl` implements via `sessions.values()`. Returns a snapshot — callers may iterate without `ConcurrentModificationException`.

## Components

### DebateEventResource

New JAX-RS resource in `runtime`.

```
GET /api/debate/{debateSessionId}/events
    Produces: text/event-stream
    Returns: Multi<String>
```

Annotated with `@io.smallrye.common.annotation.Blocking`. `MessageService.pollAfter()` is a JPA query that must not run on the Vert.x event loop. `@Blocking` moves the request to a worker thread, enables synchronous session validation (giving a real 404 before SSE headers are sent), and ensures that `Multi.createFrom().ticks()` callbacks also run on worker threads — this is Quarkus-specific behavior, verified against Claudony's `MeshResource.channelEvents()` which uses the same `@Blocking` + synchronous lookup + ticks pattern.

Connection lifecycle:

1. **Validate** — resolve `debateSessionId` via `DebateSessionRegistry`. `@Blocking` + synchronous check gives a real 404.
2. **Catch-up** — `MessageService.pollAfter(channelId, 0L, 500)`. Parse each `Message` into `DebateStreamEntry` via the static factory, filter nulls, serialize as JSON, emit as SSE `data:` frames. Update `AtomicLong lastSentId` with the highest message id seen.
3. **Live polling** — `Multi.createFrom().ticks().every(Duration.ofMillis(500))` → on each tick, `pollAfter(channelId, lastSentId.get(), 50)`, parse + serialize new entries, update `lastSentId`. Filter nulls (no new messages = no emission). Failure on a tick is logged and recovered.
4. **Heartbeat** — when a tick produces no debate messages, emit `{"type":"heartbeat"}` as a JSON data frame. The browser filters heartbeats: `if (parsed.type === 'heartbeat') return;`. This uses the JSON `type` field (not `entryType`) — a deliberate naming distinction. The standard SSE approach would use the `event:` field (`event: heartbeat\ndata: {}\n\n`) with separate `addEventListener('heartbeat', ...)`, but `Multi<String>` in Quarkus maps each emission to a `data:` frame with no control over the `event:` field. The JSON approach is a known trade-off, not an oversight.

Catch-up and live streams concatenated via `Multi.createBy().concatenating().streams(catchUp, live)`. The `lastSentId` cursor carries forward from catch-up to live — no gap, no duplicates.

`pollAfter()` uses the default overload (without `includeEvents` parameter), which excludes Qhorus `EVENT` messages. This is intentional — debate channels don't produce useful EVENTs, and including them would leak infrastructure telemetry into the browser stream.

SSE frame format:
```
data: {"entryType":"RAISE","agentRole":"REV","round":1,"content":"...","pointId":"...","priority":"P1","scope":"ISOLATED","sender":"drafthouse-rev-...","timestamp":"2026-06-11T..."}
```

### Active Sessions Endpoint

```
GET /api/debate/sessions
    Produces: application/json
    Returns: JSON array of active session summaries
```

Response shape per session:
```json
{"debateSessionId": "...", "channelName": "drafthouse/debate/d-...", "specPath": "/path/to/spec.md"}
```

Calls `DebateSessionRegistry.activeSessions()`, maps each `DebateSession` to the response DTO. Allows the browser to discover active debates and connect SSE.

## Reconnection

Debate sessions are short-lived (minutes) with small message counts (tens, not thousands). On reconnect (including `EventSource` auto-reconnect), the endpoint creates a new `AtomicLong lastSentId` at 0 and replays the full catch-up from Qhorus.

`OutboundMessage` does not carry the Qhorus `Message.id` (Long), so cursor-based `Last-Event-ID` reconnection is not feasible without a platform API change. Replay-from-start is correct for the session lifetimes involved.

### sp3 contract

On SSE reconnect, the browser must clear its accumulated event list before processing the replayed stream. Failure to do so produces duplicates in the UI. This contract is documented here (sp2) and must be followed by sp3's implementation.

## Browser Integration

Minimal `EventSource` wiring in `index.html`. No debate rendering — that's sp3's scope.

```javascript
function connectDebateSSE(debateSessionId) {
    const source = new EventSource(`/api/debate/${debateSessionId}/events`);
    source.onmessage = (e) => {
        const event = JSON.parse(e.data);
        if (event.type === 'heartbeat') return;
        document.dispatchEvent(new CustomEvent('debate-event', { detail: event }));
    };
    return source;
}
```

Events emitted as `CustomEvent`s for sp3's UI components to subscribe to. Auto-reconnection is handled by `EventSource` natively.

## Protocol Compliance

| Protocol | Status |
|----------|--------|
| PP-20260520-mesh-dashboard (consumer integration) | ✅ MessageService.pollAfter() for data access, not MCP tools |
| Application tier boundary | ✅ All new code in DraftHouse, no foundation changes |

## Testing

### Unit tests

- **DebateStreamEntryTest** — verify `from(Message)` parses all entry types correctly (RAISE through RESTART_CONTEXT). Verify `pointId`/`subTaskId` extraction: for debate entries, pointId from correlationId; for sub-task entries, pointId from META and subTaskId from correlationId. Edge cases: null content, no META sentinel (returns null), unknown entryType (returns null), missing agent field (returns null), missing optional fields (priority, scope, location — null in record), malformed round (defaults to 0).

### Integration tests

- **DebateEventResourceTest** — `@QuarkusTest` with `@Blocking` endpoint:
  - Start debate via `DebateMcpTools.startDebate()`, post messages via `raise_point`/`respond_to`, connect SSE client, assert events arrive with correct parsed fields, enum types, and pointId/subTaskId mapping.
  - Test catch-up: post messages, then connect — verify all historical events delivered in order.
  - Test live polling: connect first, then post messages — verify events arrive within ~500ms.
  - Test cursor continuity: post 5 messages, connect, verify catch-up delivers 5, post 3 more, verify live delivers 3 (no duplicates, no gaps).
  - Test heartbeat: connect to idle session, verify `{"type":"heartbeat"}` frames arrive.
  - Test 404 for invalid session ID (synchronous validation via @Blocking).
  - Test pollAfter excludes EVENT messages.
  - Test active sessions endpoint returns correct session data.

### Out of scope

Playwright E2E for browser-side EventSource — that's sp3.

## Prerequisites

### EntryType enum update

Add `RESTART_CONTEXT` to `EntryType` and update both exhaustive switches before implementing the SSE components:
- `DebateChannelProjection.apply()` — replace the string comparison with `case RESTART_CONTEXT -> state;`
- `SummaryRenderer.render()` — add `RESTART_CONTEXT` to the multi-label throw case (`MEMO, SUB_TASK_REQUEST, ... RESTART_CONTEXT -> throw ...`) since it must not appear in `ThreadEntry`

No other exhaustive switches on `EntryType` exist (`DebateMcpTools` uses string comparison, not the enum). `DebateStreamEntry.from(Message)` is a new consumer of the enum introduced by this spec — it handles `RESTART_CONTEXT` correctly via `valueOf()` without special-casing.

This is a separate commit — it changes existing code and should be reviewed independently.

### DebateSessionRegistry.activeSessions()

Add `Collection<DebateSession> activeSessions()` to the interface and implement in `DebateSessionRegistryImpl`. This is a separate commit.

## Components NOT in this design

The following were in earlier spec revisions and have been removed:

| Component | Why removed |
|-----------|-------------|
| `DebateEventBus` | Bus-push SSE failed in Claudony (cross-thread flushing) and has a Mutiny lazy-subscription gap. Polling eliminates both issues. No other consumer exists. |
| `DebateObserverBackend` | Bridged ChannelBackend.post() to the bus. No bus → no bridge needed. SSE endpoint polls Qhorus directly. |
| `DebateStreamEntryMapper` | With only one factory method (from Message), a separate mapper class is unnecessary abstraction. Static factory on the record. |
| `inReplyTo` field | Qhorus message id of parent message. Browser receives no entry's own Qhorus id, so the reference is unresolvable. Threading handled by pointId. |
| `correlationId` field | Multipurpose Qhorus internal — carries pointId for debate entries, subTaskId for sub-task entries. Replaced by explicit `pointId` + `subTaskId` fields with clear semantics. |

## Out of Scope

- UI rendering of debate events (sp3)
- Context meter and auto-reset (sp4)
- Fleet/cluster delivery (DraftHouse is single-node)
- Authentication on SSE endpoint (DraftHouse has no auth layer)
- ARC42 §2/§13 update for stale DebateEvent sealed-type references (flagged for follow-up)
