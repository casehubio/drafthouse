# Autonomous Debate Triggering — Design Spec

Wire the triggering logic that starts `ConversationOrchestrator.converse()` when the
first message arrives on an autonomous debate session.

## Context

The wiring layer landed in `e493951`: `DebateAgentInvoker`, `DebatePromptAssembler`,
`DebateResponseBuilder`, the `autonomous` flag on `DebateSession`, and
`wireAutonomousOrchestrator()` in `DebateMcpTools`. The orchestrator is constructed
and stored on the session — but never started. This spec covers the trigger, lifecycle,
and completion handling.

## Scope

- Trigger `converse()` from `DebateChannelBackend.post()` on first qualifying message
- Run the orchestrator loop on a virtual thread
- Push a WebSocket completion event when the loop finishes
- Add `ContestedEscalation` to the termination composition for FLAG_HUMAN handling
- Leave the session open after completion (user calls `end_debate` explicitly)

## Trigger location: DebateChannelBackend.post()

The backend already observes every message on the channel via Qhorus gateway fan-out.
It currently pushes WebSocket events and fires CDI for SUB_TASK_REQUEST. Adding the
autonomous trigger here means any message source (MCP tools, human actions, replays)
can kick off the orchestrator.

### Trigger flow

```
raisePoint() / human action
  → messageService.dispatch()
    → ChannelGateway.fanOut()
      → DebateChannelBackend.post()
        ├── push WebSocket events (existing)
        ├── fire CDI for SUB_TASK_REQUEST (existing)
        └── if autonomous && not yet started:
              trigger converse() on virtual thread (NEW)
```

### Idempotency

`DebateSession` gains an `AtomicBoolean converseStarted` field. The guard in `post()`:

```java
if (session.isAutonomous()
    && session.orchestrator() != null
    && session.markConverseStarted()) {   // AtomicBoolean CAS
    // build triggeringMessage from OutboundMessage, subscribe on virtual thread
}
```

`markConverseStarted()` returns `true` exactly once (compareAndSet(false, true)).
Concurrent messages during the window between dispatch and trigger all lose the CAS
race — only the first wins.

### Building the triggering MessageView

`post()` receives an `OutboundMessage`. The orchestrator's `converse()` expects a
`MessageView`. Build a minimal `MessageView` from the outbound message:

```java
var triggeringMessage = new MessageView(
    null,                          // id
    channel.id(),                  // channelId
    message.sender(),              // sender
    message.type(),                // type
    message.content(),             // content
    message.correlationId(),       // correlationId
    message.inReplyTo(),           // inReplyTo
    message.target(),              // target
    message.topic(),               // topic
    message.artefactRefs(),        // artefactRefs
    message.senderActorType(),     // actorType
    Instant.now(),                 // createdAt
    null,                          // deadline
    0);                            // replyCount
```

This is the seed message that the orchestrator's internal loop processes first.
`OutboundMessage.senderActorType()` maps to `MessageView.actorType()`.

### Virtual thread execution

```java
Thread.startVirtualThread(() -> {
    try {
        var outcome = session.orchestrator()
            .converse(triggeringMessage)
            .await().indefinitely();
        handleCompletion(channel.id(), session, outcome);
    } catch (Exception e) {
        LOG.warning("Autonomous debate failed: " + e.getMessage());
        handleFailure(channel.id(), session, e);
    }
});
```

`converse()` returns `Uni<ConversationOutcome>`. The `Uni.createFrom().item()` wrapper
runs the loop synchronously on the subscribing thread — which is the virtual thread.
This is correct: virtual threads are cheap and the loop involves blocking I/O (agent
invocations) that benefits from virtual thread scheduling.

## Completion handling

When `converse()` returns, push a WebSocket metadata event so the UI knows the
autonomous loop finished:

```java
private void handleCompletion(UUID channelId, DebateSession session, ConversationOutcome outcome) {
    String reason = switch (outcome.decision()) {
        case TerminationDecision.Complete c -> c.reason();
        case TerminationDecision.Escalated e -> "escalated: " + e.reason();
        default -> "unknown";
    };
    eventBus.pushMetadata(channelId, "autonomous-completed",
        Map.of("reason", reason,
               "dispatchCount", outcome.dispatchCount(),
               "durationMs", outcome.duration().toMillis()));
    session.setOrchestrator(null);  // release references
}
```

The session stays open. The orchestrator reference is cleared to free memory.
The user inspects results via `get_debate_summary` and calls `end_debate` when done.

### Failure handling

```java
private void handleFailure(UUID channelId, DebateSession session, Exception e) {
    eventBus.pushMetadata(channelId, "autonomous-failed",
        Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
    session.setOrchestrator(null);
}
```

## FLAG_HUMAN → ContestedEscalation

Add `ContestedEscalation` to the `CompositeTermination` in `wireAutonomousOrchestrator()`:

```java
var termination = new CompositeTermination(List.of(
    new AllAgreedTermination(Set.of("AGREED", "VERIFIED")),
    new ContestedEscalation(),          // NEW — terminates on FLAG_HUMAN
    new MaxIterationsTermination<>(20)));
```

When an agent posts FLAG_HUMAN, the `DebateResponseBuilder` encodes it as a debate
entry. The orchestrator dispatches it, applies it to state, and checks termination.
`ContestedEscalation` detects FLAG_HUMAN in the `ConversationState` and returns
`TerminationDecision.Escalated`. The loop exits and `handleCompletion` pushes the
escalation reason to the UI.

## end_debate integration

`endDebate()` in `DebateMcpTools` must handle the case where the orchestrator is
still running:

```java
if (session.orchestrator() != null) {
    session.orchestrator().terminate();  // sets volatile terminated flag
    session.setOrchestrator(null);
}
```

The `terminate()` method sets a volatile boolean that the `converse()` loop checks
on each iteration. The loop exits cleanly and the virtual thread completes.

## Files to modify

| File | Change |
|------|--------|
| `DebateSession.java` | ADD `AtomicBoolean converseStarted` + `markConverseStarted()` |
| `DebateChannelBackend.java` | ADD autonomous trigger in `post()`, completion/failure handlers |
| `DebateMcpTools.java` | ADD `ContestedEscalation` to termination composition, ADD orchestrator termination in `endDebate()` |

## What this does NOT change

- `wireAutonomousOrchestrator()` — unchanged, already constructs the orchestrator correctly
- `raisePoint()`, `respondTo()`, `flagHuman()` — unchanged, they dispatch to the channel
- `DebateChannelProjection` — unchanged, used as-is by the orchestrator
- WebSocket push for individual debate entries — unchanged, `post()` still pushes events before the trigger check
- Browser UI — debate-feed panel shows entries regardless of source

## Testing

- Unit test: `DebateChannelBackend` with mock autonomous session — verify `converse()` triggered exactly once on first post, not on subsequent posts
- Unit test: `markConverseStarted()` CAS idempotency under concurrent calls
- Unit test: `endDebate()` calls `terminate()` on running orchestrator
- Integration: start autonomous debate, raise a point, verify agents respond automatically and completion event arrives via WebSocket
