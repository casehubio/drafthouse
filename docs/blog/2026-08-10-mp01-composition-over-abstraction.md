---
layout: post
title: "Composition Over Abstraction — Wiring Blocks' Orchestrator into DraftHouse"
date: 2026-08-10
entry_type: note
subtype: diary
projects: [casehub-drafthouse]
tags: [orchestration, blocks, composition, autonomous-debate]
series: issue-71-conversation-protocol
---

The entire autonomous debate feature is three classes, none longer than 65 lines. That's not because the problem is simple — multi-agent conversation loops with turn policies, termination conditions, observation services, and partitioned context drains are genuinely complex. It's because the complexity already lives in blocks.

The previous session audited blocks and filed #91 — a `ConversationOrchestrator` that composes the existing summarisation, observation, and turn-taking primitives into a reactive loop. This session wired it into DraftHouse. The work was pure composition: implement three blocks SPIs, add a flag to `DebateSession`, and assemble the pieces in `start_debate`.

The interesting design decision was where to put the system prompt. The orchestrator's `AgentInvoker<String>.invoke()` receives a single string — the fully assembled prompt from `PromptAssembler`. But `PlatformDebateAgentProvider` uses `AgentTask(systemPrompt, assembledInput)` as two separate fields, mapping them to system and user messages in the LLM call. If I'd jammed the system prompt into the assembled string, it would work but degrade LLM behaviour — system-role instructions in the user message are less effective. Instead, the invoker holds a participant-to-system-prompt map and looks up the right one by `AgentRef.name()`. The assembler produces only the user-facing content: documents, selection scope, conversation history.

The other thing worth noting: the orchestrator is self-contained. I'd assumed `DebateChannelBackend.post()` would need to feed messages back into the orchestrator, creating a dispatch-receive loop. Reading `converse()` showed that's wrong — the orchestrator has its own internal queue. Responses are applied to the projection, published to the observation service, and dispatched via `responseDispatcher` all within the loop. The dispatcher pushes through the channel gateway for WebSocket updates, but the orchestrator doesn't need anything back. Human intervention works through termination policies, not message injection.

The plan written last session had a dozen API mismatches — `MaxRounds` was actually `MaxIterationsTermination`, `ConversationState.empty()` doesn't exist, `PartitionedDrain.current()` is `currentPartition()`, `LevelEvent.event()` is `payload()`. Every one was caught at compile time. The lesson isn't that plans are unreliable — they were written from an audit of blocks source before #91 was merged. The lesson is that plans against APIs you haven't compiled against are hypotheses. The real API is the one the compiler accepts.
