# Autonomous Debate Wiring — Design Spec

Wire blocks' `ConversationOrchestrator` into DraftHouse's `DebateChannelBackend`
so debate sessions can run autonomously when configured.

## Scope

Composition only — all orchestration logic lives in blocks. DraftHouse provides:
- `PromptAssembler` that injects document context
- `ResponseMessageBuilder` that encodes responses as debate entries
- `Consumer<MessageView>` that dispatches via Qhorus `MessageService`
- Opt-in flag on `DebateSession`
- MCP tool surface for starting autonomous debates

## Blocks API surface (shipped in blocks#91)

```
ConversationOrchestrator(
    projection,              // DebateChannelProjection (existing)
    observationService,      // PartitionedObservationService<MessageView, String>
    turnPolicy,              // PointAddressedTurnPolicy (from blocks)
    terminationCondition,    // AllAgreedTermination or CompositeTermination
    agentInvoker,            // wraps DebateAgentProvider.analyse()
    promptAssembler,         // NEW — DebatePromptAssembler
    responseBuilder,         // NEW — DebateResponseBuilder
    responseDispatcher,      // Consumer<MessageView> → MessageService.dispatch()
    participants             // List<AgentParticipant> from session config
)
```

`converse(triggeringMessage)` runs the full loop and returns `ConversationOutcome`.

## Implementation mapping

### 1. DebatePromptAssembler (implements PromptAssembler)

```java
String assemble(AgentParticipant agent, PartitionedDrain<String> drain, ConversationState state)
```

Assembles:
- Agent's system prompt (from `AgentParticipant.systemPrompt()`)
- Document content (A-side + B-side from `DebateSession.documentSet`)
- Selection scope if active (`DebateSession.currentSelection()`)
- Conversation history from `drain` (what happened since this agent's last turn)
- Current debate state summary (open points, round number)

### 2. DebateResponseBuilder (implements ResponseMessageBuilder)

```java
MessageView build(AgentParticipant agent, AgentResult result, ConversationState state)
```

Encodes the LLM response as a debate entry:
- Parse result text for entry type (RAISE, COUNTER, AGREE, etc.)
- Encode with `DebateProtocol.META_SENTINEL` prefix
- Set sender, actorType, round number from state

### 3. DebateAgentInvoker (implements AgentInvoker<String>)

Wraps existing `DebateAgentProvider.analyse(AgentTask)`:
- Constructs `AgentTask` from the prompt
- Returns `AgentResult` with the response text

### 4. Response dispatcher (Consumer<MessageView>)

Lambda that calls `MessageService.dispatch()`:
- Builds `MessageDispatch` from the `MessageView`
- Sets channelId, sender, actorType, content
- The dispatch triggers `DebateChannelBackend.post()` for WebSocket push (existing behaviour)

### 5. DebateSession changes

Add `autonomous: boolean` field (default false). When true:
- `DebateChannelBackend.post()` feeds events to the orchestrator
- The orchestrator loop runs on a virtual thread

When false: behaviour unchanged — agents call MCP tools manually.

### 6. MCP tool: start_autonomous_debate

New tool (or flag on `start_debate`):
- Creates debate session with `autonomous=true`
- Registers `AgentParticipant` for each resolved reviewer
- Constructs and stores `ConversationOrchestrator`
- On first `raise_point`, the orchestrator begins `converse()` on a virtual thread
- Returns sessionId; caller observes via WebSocket

### 7. Orchestrator lifecycle

- **Start**: first event triggers `converse()` on a virtual thread
- **Observe**: `responseDispatcher` pushes through `DebateChannelBackend.post()` → WebSocket → browser UI
- **Intervene**: `FLAG_HUMAN` entries pause the loop (terminate with `ContestedEscalation`)
- **End**: `AllAgreedTermination` or `MaxRounds` → `end_debate` called automatically

## Turn and termination policies (from blocks)

- **TurnPolicy**: `PointAddressedTurnPolicy` — agents respond to open points they haven't addressed yet
- **TerminationCondition**: `CompositeTermination` of `AllAgreedTermination(Set.of("AGREED", "VERIFIED"))` + `MaxRounds(configurable)`
- **ContestedEscalation** available for FLAG_HUMAN escalation

## What this does NOT change

- Existing MCP-driven debate flow (autonomous=false, the default)
- DebateChannelProjection (used as-is by the orchestrator)
- WebSocket push (existing DebateChannelBackend.post() still fires)
- Browser UI (debate-feed panel shows entries regardless of source)

## Files to create/modify

| File | Change |
|------|--------|
| `DebatePromptAssembler.java` | NEW — implements PromptAssembler |
| `DebateResponseBuilder.java` | NEW — implements ResponseMessageBuilder |
| `DebateAgentInvoker.java` | NEW — wraps DebateAgentProvider as AgentInvoker |
| `DebateSession.java` | ADD autonomous field |
| `DebateChannelBackend.java` | ADD orchestrator feed path when autonomous |
| `DebateMcpTools.java` | ADD start_autonomous_debate tool (or flag) |
| `DebateSessionRegistryImpl.java` | STORE orchestrator reference per session |
