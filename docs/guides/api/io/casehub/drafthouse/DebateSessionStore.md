# io.casehub.drafthouse.DebateSessionStore

**Package:** `io.casehub.drafthouse`

**Kind:** `interface`

## Methods

### `public abstract java.util.Optional<io.casehub.drafthouse.DebateSessionSnapshot> load(java.util.UUID channelId)`

#### Parameters

- `channelId` (`java.util.UUID`)

### `public abstract java.util.Collection<io.casehub.drafthouse.DebateSessionSnapshot> loadAll()`

### `public abstract void remove(java.util.UUID channelId)`

#### Parameters

- `channelId` (`java.util.UUID`)

### `public abstract void save(io.casehub.drafthouse.DebateSessionSnapshot snapshot)`

#### Parameters

- `snapshot` (`io.casehub.drafthouse.DebateSessionSnapshot`)
