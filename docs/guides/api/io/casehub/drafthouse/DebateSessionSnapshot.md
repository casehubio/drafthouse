# io.casehub.drafthouse.DebateSessionSnapshot

**Package:** `io.casehub.drafthouse`

**Kind:** `record`

## Fields

### `agentId` (`java.lang.String`)

### `channelId` (`java.util.UUID`)

### `channelName` (`java.lang.String`)

### `comparison` (`io.casehub.drafthouse.ComparisonPair`)

### `debateSessionId` (`java.lang.String`)

### `documents` (`java.util.List<io.casehub.drafthouse.DocumentEntry>`)

### `participants` (`java.util.Map<io.casehub.drafthouse.debate.AgentType,java.lang.String>`)

### `threads` (`java.util.Map<java.lang.String,io.casehub.drafthouse.SelectionThread>`)

### `workspacePath` (`java.lang.String`)

## Record Components

### `agentId` (`java.lang.String`)

### `channelId` (`java.util.UUID`)

### `channelName` (`java.lang.String`)

### `comparison` (`io.casehub.drafthouse.ComparisonPair`)

### `debateSessionId` (`java.lang.String`)

### `documents` (`java.util.List<io.casehub.drafthouse.DocumentEntry>`)

### `participants` (`java.util.Map<io.casehub.drafthouse.debate.AgentType,java.lang.String>`)

### `threads` (`java.util.Map<java.lang.String,io.casehub.drafthouse.SelectionThread>`)

### `workspacePath` (`java.lang.String`)

## Constructors

### `public DebateSessionSnapshot(java.util.UUID channelId, java.lang.String debateSessionId, java.lang.String channelName, java.util.List<io.casehub.drafthouse.DocumentEntry> documents, io.casehub.drafthouse.ComparisonPair comparison, java.util.Map<io.casehub.drafthouse.debate.AgentType,java.lang.String> participants, java.lang.String agentId, java.lang.String workspacePath, java.util.Map<java.lang.String,io.casehub.drafthouse.SelectionThread> threads)`

#### Parameters

- `channelId` (`java.util.UUID`)
- `debateSessionId` (`java.lang.String`)
- `channelName` (`java.lang.String`)
- `documents` (`java.util.List<io.casehub.drafthouse.DocumentEntry>`)
- `comparison` (`io.casehub.drafthouse.ComparisonPair`)
- `participants` (`java.util.Map<io.casehub.drafthouse.debate.AgentType,java.lang.String>`)
- `agentId` (`java.lang.String`)
- `workspacePath` (`java.lang.String`)
- `threads` (`java.util.Map<java.lang.String,io.casehub.drafthouse.SelectionThread>`)

## Methods

### `public java.lang.String agentId()`

### `public java.util.UUID channelId()`

### `public java.lang.String channelName()`

### `public io.casehub.drafthouse.ComparisonPair comparison()`

### `public java.lang.String debateSessionId()`

### `public java.util.List<io.casehub.drafthouse.DocumentEntry> documents()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.util.Map<io.casehub.drafthouse.debate.AgentType,java.lang.String> participants()`

### `public java.util.Map<java.lang.String,io.casehub.drafthouse.SelectionThread> threads()`

### `public final java.lang.String toString()`

### `public java.lang.String workspacePath()`
