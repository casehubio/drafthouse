# io.casehub.drafthouse.debate.DebateAgentProvider

**Package:** `io.casehub.drafthouse.debate`

**Kind:** `interface`

## Methods

### `public abstract java.lang.String analyse(AgentTask task)`

Invoke an LLM and return the complete text response.
Blocking — callers must be on a non-event-loop thread.

#### Parameters

- `task` (`AgentTask`)
