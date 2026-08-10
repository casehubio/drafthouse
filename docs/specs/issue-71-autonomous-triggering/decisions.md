## D1: Trigger location for orchestrator.converse()

**Choice:** DebateChannelBackend.post() (message flow layer)
**Alternatives:**
- raisePoint() in DebateMcpTools — simpler but couples trigger to MCP tool entry point; messages from other sources (human actions, replays) wouldn't trigger
**Rationale:** The backend already sees every message on the channel and already dispatches SUB_TASK_REQUEST via CDI from post(). Adding the autonomous trigger here means any message source can kick off the orchestrator. The concern stays in the message flow layer where it belongs.
**Trade-offs:** Backend gains orchestrator lifecycle awareness — slightly more responsibility in post()
**Exploration:** quick
**Status:** captured

## D2: Session lifecycle after converse() completes

**Choice:** Leave session open after orchestrator completes
**Alternatives:**
- Auto-end — cleaner lifecycle, no zombie sessions, but forces user to re-open or lose interactive session
**Rationale:** Autonomous mode is a conversation accelerator, not fire-and-forget. The user wants to inspect results, run get_debate_summary, export, or raise manual follow-up points. A WebSocket event signals completion so the UI knows the loop finished.
**Trade-offs:** Sessions persist until explicitly ended — operator must call end_debate
**Exploration:** quick
**Status:** captured
