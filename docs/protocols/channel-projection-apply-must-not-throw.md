---
id: PP-20260610-a47ef5
title: "ChannelProjection.apply() must never throw — discard malformed messages with a log"
type: rule
scope: application
applies_to: "Any ChannelProjection<S> or RenderableProjection<S> implemented in casehub applications"
severity: critical
refs:
  - server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java
  - docs/superpowers/specs/2026-06-10-n-participant-debate-sessions-design.md
violation_hint: "A private helper in apply() calls valueOf() or similar and re-throws the exception instead of returning null / returning state"
garden_ref: ""
created: 2026-06-10
---

`ProjectionService.fold()` in `casehub-qhorus` has no try-catch — verified from decompiled bytecode. Any `RuntimeException` thrown from `apply()` terminates the fold immediately, discards partial state, and propagates up through every MCP tool that calls `projectionService.project()`. The session becomes permanently broken until the server restarts. The fix is always the same: parse defensively inside `apply()`. If a message is malformed, log at ERROR (protocol violation by our own code) or WARNING (unknown but valid future value), and return `state` unchanged. The fold continues; one bad message is skipped, not the entire session.

```java
// Wrong — re-throwing crashes the fold
private AgentType agentType(Map<String, String> meta) {
    String agent = meta.get("agent");
    if (agent == null) throw new IllegalArgumentException("missing META.agent");
    return AgentType.valueOf(agent); // throws if unknown — fold crash
}

// Right — discard with log, return null for callers to check
private AgentType agentType(Map<String, String> meta) {
    String agent = meta.get("agent");
    if (agent == null) {
        LOG.log(Level.ERROR, "Debate message missing META.agent — discarded");
        return null;
    }
    try { return AgentType.valueOf(agent); }
    catch (IllegalArgumentException e) {
        LOG.log(Level.WARNING, "Unknown agent ''{0}'' — discarded", agent);
        return null;
    }
}
// Caller: AgentType at = agentType(meta); if (at == null) return state;
```
