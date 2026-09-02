# io.casehub.drafthouse.DebateSessionRegistry

**Package:** `io.casehub.drafthouse`

**Kind:** `interface`

Registry of active debate sessions, keyed by Qhorus channel ID.
Thread-safety: implementations must be safe for concurrent access.

## Methods

### `public abstract java.util.Collection<io.casehub.drafthouse.DebateSession> activeSessions()`

Returns a snapshot of all active sessions. Safe to iterate concurrently.

### `public abstract java.util.Optional<io.casehub.drafthouse.DebateSession> find(java.util.UUID channelId)`

Returns the session for the given channel, or empty if no session is active.

#### Parameters

- `channelId` (`java.util.UUID`)

### `public abstract void persist(io.casehub.drafthouse.DebateSession session)`

Persists the current session state to the store without re-registering in the cache.

#### Parameters

- `session` (`io.casehub.drafthouse.DebateSession`)

### `public abstract void put(io.casehub.drafthouse.DebateSession session)`

Registers a new session. Replaces any existing session for the same channelId.

#### Parameters

- `session` (`io.casehub.drafthouse.DebateSession`)

### `public abstract void remove(java.util.UUID channelId)`

Removes the session for the given channel. No-op if not found.

#### Parameters

- `channelId` (`java.util.UUID`)
