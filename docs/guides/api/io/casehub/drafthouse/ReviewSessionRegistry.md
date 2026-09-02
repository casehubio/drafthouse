# io.casehub.drafthouse.ReviewSessionRegistry

**Package:** `io.casehub.drafthouse`

**Kind:** `interface`

Registry of active document review sessions, keyed by Qhorus channel ID.

Implemented by ReviewSessionRegistryImpl in the runtime module.
ReviewerChannelBackendFactory injects this interface to look up sessions
when wiring backends on channel init. DraftHouseMcpTools also injects it
directly for session lifecycle management.

Thread-safety: implementations must be safe for concurrent calls from
the Qhorus InProcessMessageBus (async CDI event delivery).

## Methods

### `public abstract java.util.Optional<io.casehub.drafthouse.ReviewSession> find(java.util.UUID channelId)`

Returns the session for the given channel, or empty if no session is active.

#### Parameters

- `channelId` (`java.util.UUID`)

### `public abstract void put(io.casehub.drafthouse.ReviewSession session)`

Registers a new session. Replaces any existing session for the same channelId.

#### Parameters

- `session` (`io.casehub.drafthouse.ReviewSession`)

### `public abstract void remove(java.util.UUID channelId)`

Removes the session for the given channel. No-op if not found.

#### Parameters

- `channelId` (`java.util.UUID`)

### `public abstract void updateSelection(java.util.UUID channelId, io.casehub.drafthouse.SelectionScope selection)`

Atomically replaces the ReviewSession with an updated selection state.
No-op if no session exists for the given channelId.

#### Parameters

- `channelId` (`java.util.UUID`)
- `selection` (`io.casehub.drafthouse.SelectionScope`) — the new selection, or null to clear
