# Restart-from-Round — Design Spec
*Issue #40 · branch: issue-40-restart-from-n*

## Context

The sub-agent architecture (#26) made debate channels self-bootstrapping: reasoning memos and
sub-agent findings with provenance are in the Qhorus channel, not in the LLM session. This
makes restart viable. When an LLM session expires or goes off-track, the channel history
survives — the only thing lost is the main agent's accumulated in-session judgment.

## What "restart from round N" means

The round number encodes intent:

- **Resume** — pass the last completed round. The new session sees everything through that round
  and continues from round N+1 with fresh LLM context but full prior history.
- **Redo** — pass an earlier round. The new session sees only rounds 1–N; rounds N+1 onwards are
  excluded from view (they remain in the original channel but are treated as not having happened).

No `mode` parameter. The round number is the complete specification.

## MCP Tool Surface

### `get_debate_summary_at_round(debateSessionId, round)` — read-only

Returns the same markdown as `get_debate_summary` but folded over messages with `round ≤ N` only.
Use to preview a prior state before committing to a restart, or to inspect any round on a
live session.

### `restart_from_round(debateSessionId, round)` — creates new session

1. Project original channel up to `round` (round-bounded fold).
2. Render that state as markdown.
3. Create a new Qhorus APPEND channel + register new `DebateSession` (same pattern as
   `start_debate`: channel + two instances + registry + `channelGateway.initChannel`).
4. Post a `RESTART_CONTEXT` provenance marker to the new channel.
5. Return JSON:

```json
{
  "newDebateSessionId": "...",
  "specPath": "...",
  "summary": "## Debate State at Round 2\n...",
  "contextCarried": {
    "roundsIncluded": "1–2",
    "pointCount": 3,
    "findingsIncluded": 2,
    "findingsInOriginalOnly": 1
  },
  "message": "New session ready. Rounds 1–2 from the original are visible here. 1 sub-agent finding from later rounds remains in the original session only."
}
```

`findingsIncluded` = findings in the round-bounded projection.
`findingsInOriginalOnly` = findings in the full projection minus `findingsIncluded`.
`specPath` inherited from the original session.

## Round-Bounded Projection

`ProjectionService` has no `beforeId`/`upToId` in `MessageQuery` and no round awareness —
intentionally not changed. Round semantics belong in the debate layer.

### `RoundBoundedProjection`

Static inner class of `DebateChannelProjection` in `io.casehub.drafthouse.debate`. Wraps
the projection and skips messages whose `round` META field exceeds `maxRound`. Uses
`DebateProtocol.parseRound()` (extracted in this change from the private helper):

```java
class RoundBoundedProjection implements ChannelProjection<ReviewState> {
    private final int maxRound;
    private final DebateChannelProjection delegate;

    @Override
    public ReviewState apply(ReviewState state, MessageView message) {
        int round = parseRound(DebateProtocol.parseMeta(message.content()));
        if (round > maxRound) return state;
        return delegate.apply(state, message);
    }

    @Override public ReviewState identity() { return delegate.identity(); }
    @Override public String projectionName() { return delegate.projectionName() + "@r" + maxRound; }
}
```

`DebateChannelProjection` gains a helper:

```java
public ProjectionResult<ReviewState> projectUpToRound(UUID channelId, int maxRound) {
    return projectionService.project(channelId, new RoundBoundedProjection(maxRound, this));
}
```

### Sub-task findings fix

`SUB_TASK_FINDING` messages currently omit `round`. `parseRound` returns 0 for them, so
they pass every `round > N` gate and appear in all bounded views — even findings about
discarded debate content. This is wrong for REDO.

Fix in `AbstractDebateSubAgentHandler.buildResponse()`: propagate `round` from the trigger
meta into the finding encoding:

```java
String round = meta.getOrDefault("round", "0");
// add to encoded string:
"|round=" + round
```

The round is already in the `SUB_TASK_REQUEST` trigger meta. One-line addition.
Findings from later rounds are then correctly excluded from bounded views.

## RESTART_CONTEXT Provenance Marker

On new channel creation, `restart_from_round` posts one message before any debate begins:

```
DHMETA:entryType=RESTART_CONTEXT|originChannelId=<uuid>|originRound=2

## Debate State at Round 2
...rendered summary...
```

- Makes the channel self-describing and self-bootstrapping without querying the original.
- `get_debate_summary` on the new channel returns "No debate activity yet" — the
  `RESTART_CONTEXT` case in `DebateChannelProjection` returns `state` unchanged (provenance,
  not a debate entry).
- `RESTART_CONTEXT` is posted as `MessageType.STATUS`, sender = revInstanceId.

## File Changes

| File | Change |
|------|--------|
| `api/.../debate/EntryType.java` | Add `RESTART_CONTEXT` |
| `runtime/.../debate/DebateProtocol.java` | Add `parseRound(Map<String,String> meta)` static method (extracted from `DebateChannelProjection`) |
| `runtime/.../debate/DebateChannelProjection.java` | Add `RESTART_CONTEXT` noop switch case; add `RoundBoundedProjection` static inner class; add `projectUpToRound(channelId, maxRound)` method; delegate private `parseRound` to `DebateProtocol.parseRound` |
| `runtime/.../handler/AbstractDebateSubAgentHandler.java` | Add `\|round=N` to `SUB_TASK_FINDING` encoding |
| `runtime/.../DebateMcpTools.java` | Add `get_debate_summary_at_round` and `restart_from_round` tools |

No changes to: `DebateSession`, `DebateSessionRegistry`, `ReviewState`, `ProjectionService`,
`MessageQuery`, `DebateProtocol`.

## Platform Coherence

- All writes go through `MessageService.dispatch()` — no bypass.
- New channel follows existing APPEND semantic — no new channel type.
- `ChannelProjection<S>` SPI used correctly — `RoundBoundedProjection` is a decorator, not a
  parallel projection implementation.
- No new Qhorus or platform SPIs introduced.

## Out of Scope (captured as issues)

- UI controls in `index.html` for navigating to a prior round — deferred; DraftHouse is
  currently MCP-only with no debate UI.
- `listRounds(debateSessionId)` convenience tool — useful but not required for restart.
  File separately if needed.
