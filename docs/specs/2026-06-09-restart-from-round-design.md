# Restart-from-Round — Design Spec
*Issue #40 · branch: issue-40-restart-from-n · revised after code review*

## Context

The sub-agent architecture (#26) made debate channels self-bootstrapping: reasoning memos and
sub-agent findings with provenance are in the Qhorus channel, not in the LLM session. This
makes restart viable. When an LLM session expires or goes off-track, the channel history
survives — the only thing lost is the main agent's accumulated in-session judgment.

## What "restart from round N" means

The round number encodes intent — no `mode` parameter:

- **Resume** — pass the last completed round. The new session sees everything through that round
  and continues from round N+1 with fresh LLM context but full prior history.
- **Redo** — pass an earlier round. The new session sees only rounds 1–N; rounds N+1 onwards
  from the original are excluded from view (they remain in the original channel but are treated
  as not having happened in the new session).

Round numbers are caller-supplied and contiguity is not enforced. An agent may use rounds
1, 3, 7. `restart_from_round(id, 2)` on such a debate includes only round-1 messages — no
round-2 messages exist. This is correct per `round ≤ maxRound` semantics. Callers should
inspect `get_debate_summary_at_round` first to verify what a given cutoff includes before
committing to a restart.

**Minimum round:** both tools reject `round < 1` with an error. Round 0 is ambiguous — most
messages without an explicit round field parse as 0 and would be included unexpectedly.

## MCP Tool Surface

### `get_debate_summary_at_round(debateSessionId, round)` — read-only

Returns the same markdown as `get_debate_summary` but folded over messages with `round ≤ N`
only. Use to preview a prior state before committing to a restart, or to inspect any round
on a live session.

**Important:** after a session has been restarted, call `get_debate_summary_at_round` on
the **original** session's ID to inspect prior rounds. The restarted session's channel
contains only the provenance marker — calling this tool on the new session ID returns empty
until new debate content is added.

### `request_subagent` — signature change (prerequisite for round propagation)

`request_subagent` currently omits `round` from the `SUB_TASK_REQUEST` header. Without it,
`AbstractDebateSubAgentHandler.buildResponse()` cannot propagate the correct round into
`SUB_TASK_FINDING`. Fix: add `int round` parameter to `request_subagent` and encode
`|round=N` in the request header.

### `restart_from_round(debateSessionId, round)` — creates new session

1. Validate `round >= 1`.
2. Project original channel up to `round` (round-bounded fold) → bounded state.
3. Render bounded state as markdown (using `SummaryRenderer` directly, not via
   `DebateChannelProjection.render()` — see Issue 4 handling below).
4. Create a new Qhorus APPEND channel + register new `DebateSession` (same pattern as
   `start_debate`: channel + two instances + registry + `channelGateway.initChannel`).
5. Post a `RESTART_CONTEXT` provenance marker to the new channel (see below).
6. Return JSON:

```json
{
  "newDebateSessionId": "...",
  "originalDebateSessionId": "...",
  "specPath": "...",
  "summary": "## Debate State at Round 2\n...",
  "contextCarried": {
    "roundsIncluded": "1–2",
    "pointCount": 3,
    "findingsIncluded": 2,
    "findingsInOriginalOnly": 1
  },
  "message": "New session ready. Rounds 1–2 from the original are visible here. 1 sub-agent finding from later rounds remains in the original session only. Call end_debate on originalDebateSessionId when done with it."
}
```

`originalDebateSessionId` is included so the caller can end the original session when done.
`findingsIncluded` = sub-task findings in the bounded projection.
`findingsInOriginalOnly` = findings in the full projection minus `findingsIncluded`.
Computing both requires two projections of the original channel — bounded and full.
`specPath` is inherited from the original session.

**Failure cleanup:** on any exception after channel creation, deregister both instances,
remove the new session from registry, and delete the new channel. Same pattern as
`start_debate`. Original session is never touched on failure.

**Original session lifecycle:** `restart_from_round` does not touch the original session.
It remains live and usable until the caller calls `end_debate` on it. The original channel
and instances stay allocated until then.

## Round-Bounded Projection

`ProjectionService` has no `beforeId`/`upToId` in `MessageQuery` and no round awareness —
intentionally not changed. Round semantics belong in the debate layer.

### `RoundBoundedProjection`

Static inner class of `DebateChannelProjection` in `io.casehub.drafthouse.debate`. Wraps
the projection and skips messages whose `round` META field exceeds `maxRound`:

```java
static class RoundBoundedProjection implements ChannelProjection<ReviewState> {
    private final int maxRound;
    private final DebateChannelProjection delegate;

    @Override
    public ReviewState identity() { return delegate.identity(); }

    @Override
    public ReviewState apply(ReviewState state, MessageView message) {
        int round = DebateProtocol.parseRound(DebateProtocol.parseMeta(message.content()));
        if (round > maxRound) return state;
        return delegate.apply(state, message);
    }
    // no projectionName() — ChannelProjection does not declare it (only RenderableProjection does)
}
```

`projectUpToRound` is NOT on `DebateChannelProjection` — calling it from there would require
injecting `ProjectionService`, polluting a pure fold bean. Instead, `DebateMcpTools` (which
already owns `projectionService`) constructs the bounded projection directly:

```java
var bounded = new DebateChannelProjection.RoundBoundedProjection(round, debateProjection);
var result = projectionService.project(session.channelId(), bounded);
```

### isEmpty() / empty-state rendering

`ProjectionResult.isEmpty()` returns `lastMessageId == null`. When messages exist but all
have `round > maxRound`, `lastMessageId` is the last scanned message ID (non-null), so
`isEmpty() == false` — but the state is identity. Calling `SummaryRenderer.render()` on
identity state produces a header-only markdown string, not "No debate activity yet."

Fix in the tool — explicit content check before render:

```java
ReviewState bounded = result.state();
String summary = (bounded.points().isEmpty()
        && bounded.memos().isEmpty()
        && bounded.subTaskFindings().isEmpty())
    ? "No debate activity up to round " + round + "."
    : new SummaryRenderer().render(bounded);
```

This applies to both `get_debate_summary_at_round` and `restart_from_round`.

### Sub-task findings round propagation

`SUB_TASK_FINDING` messages must carry `|round=N` so the round-bounded fold correctly
excludes findings from later rounds. This requires the fix to `request_subagent` above:
once the request encodes `|round=N`, `AbstractDebateSubAgentHandler.buildResponse()` reads
it from the trigger meta and writes `|round=N` into the finding header.

## RESTART_CONTEXT Provenance Marker

`restart_from_round` posts one marker message to the new channel before any debate begins:

```
DHMETA:entryType=RESTART_CONTEXT|originChannelId=<uuid>|originRound=2

## Debate State at Round 2
...rendered summary...
```

This makes the channel self-describing: the history shows exactly where it branched and what
state it started with. The body is the round-bounded summary.

**Projection handling:** `RESTART_CONTEXT` is NOT added to `EntryType.java`. Adding it would
break `SummaryRenderer`'s exhaustive switch on `EntryType`. Instead, `DebateChannelProjection
.apply()` checks the string before calling `EntryType.valueOf()`:

```java
String entryTypeStr = meta.get("entryType");
if (entryTypeStr == null) return state;
if ("RESTART_CONTEXT".equals(entryTypeStr)) return state;  // provenance — skip silently
EntryType entryType;
try { entryType = EntryType.valueOf(entryTypeStr); } ...
```

No WARNING log. No new enum constant. No new switch case in any renderer. The DHMETA
structure is preserved — the marker is queryable via `contentPattern("originChannelId=")` if
needed in future. Post as `MessageType.STATUS`.

## File Changes

| File | Change |
|------|--------|
| `runtime/.../debate/DebateProtocol.java` | Extract `parseRound(Map<String,String>)` as a package-accessible static method |
| `runtime/.../debate/DebateChannelProjection.java` | Add string check for `"RESTART_CONTEXT"` before `EntryType.valueOf()`; add `RoundBoundedProjection` static inner class; delegate private `parseRound` to `DebateProtocol.parseRound` |
| `runtime/.../handler/AbstractDebateSubAgentHandler.java` | Propagate `round` from trigger meta into `SUB_TASK_FINDING` encoding |
| `runtime/.../DebateMcpTools.java` | Add `round` param to `request_subagent`; add `get_debate_summary_at_round`; add `restart_from_round` |

No changes to: `EntryType.java`, `DebateSession`, `DebateSessionRegistry`, `ReviewState`,
`ProjectionService`, `MessageQuery`.

## Platform Coherence

- All writes go through `MessageService.dispatch()` — no bypass.
- New channel follows existing APPEND semantic — no new channel type.
- `ChannelProjection<S>` SPI used correctly — `RoundBoundedProjection` is a pure decorator.
- `ProjectionService` untouched — round semantics stay in the debate layer.
- No new Qhorus or platform SPIs introduced.

## Out of Scope

- Browser UI controls for round navigation — DraftHouse is currently MCP-only.
- `listRounds(debateSessionId)` convenience tool — file separately if needed.
