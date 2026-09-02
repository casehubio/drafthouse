# io.casehub.drafthouse.BrainstormSession

**Package:** `io.casehub.drafthouse`

**Kind:** `class`

## Fields

### `lastActivity` (`java.time.Instant`)

### `options` (`java.util.List<io.casehub.drafthouse.BrainstormOption>`)

### `sessionId` (`java.lang.String`)

### `state` (`io.casehub.drafthouse.BrainstormSession.State`)

## Constructors

### `public BrainstormSession(java.lang.String sessionId)`

#### Parameters

- `sessionId` (`java.lang.String`)

## Methods

### `public void abandon()`

### `public void addOption(io.casehub.drafthouse.BrainstormOption option)`

#### Parameters

- `option` (`io.casehub.drafthouse.BrainstormOption`)

### `public java.util.Optional<io.casehub.drafthouse.BrainstormOption> findOption(java.lang.String optionId)`

#### Parameters

- `optionId` (`java.lang.String`)

### `public java.time.Instant lastActivity()`

### `public void markSelected(java.lang.String optionId)`

#### Parameters

- `optionId` (`java.lang.String`)

### `public java.util.List<io.casehub.drafthouse.BrainstormOption> options()`

### `public java.lang.String sessionId()`

### `public void setRecommendation(java.lang.String optionId)`

#### Parameters

- `optionId` (`java.lang.String`)

### `public io.casehub.drafthouse.BrainstormSession.State state()`

### `public void touch()`
