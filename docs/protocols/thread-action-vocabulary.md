---
id: PP-20260804-4a1c9e
title: "Thread messages use threadAction vocabulary, not debate entryType"
type: rule
scope: repo
applies_to: "ThreadMcpTools, ThreadProjection, ThreadStreamEntry, HumanActionResource thread endpoints"
severity: important
refs:
  - specs/issue-60-selection-scoped-channels/2026-08-04-selection-scoped-channels-design.md
garden_ref: "GE-20260804-0e809e"
violation_hint: "A thread message using entryType=RAISE instead of threadAction=START, or DebateChannelProjection processing a message with threadId present"
created: 2026-08-04
---

Thread messages use `threadAction` (`START`, `REPLY`, `RESOLVE`) as their lifecycle
vocabulary. Debate messages use `entryType` (`RAISE`, `AGREE`, `COUNTER`, `DISPUTE`,
etc.). These are separate vocabularies for separate concerns — never mix them.

The partition key `threadId` in message metadata is the boundary:
- Present → thread message. Use `threadAction`. `ThreadProjection` processes it.
  `DebateChannelProjection` skips it. `DebateChannelBackend` routes to `thread-entries`.
- Absent → debate message. Use `entryType`. `DebateChannelProjection` processes it.
  `ThreadProjection` skips it. `DebateChannelBackend` routes to `debate-entries`.

Using debate vocabulary (`entryType=RAISE`) in a thread message will cause
`ThreadProjection` to discard it (unknown `threadAction`) and `DebateChannelProjection`
to process it as a debate point (wrong channel partition).
