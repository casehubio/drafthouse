# io.casehub.drafthouse.ReviewSession

**Package:** `io.casehub.drafthouse`

**Kind:** `record`

## Fields

### `channelId` (`java.util.UUID`)

### `channelName` (`java.lang.String`)

### `docAContent` (`java.lang.String`)

### `docBContent` (`java.lang.String`)

### `instanceId` (`java.lang.String`)

### `reviewer` (`io.casehub.drafthouse.ResolvedReviewer`)

### `selection` (`io.casehub.drafthouse.SelectionScope`)

### `sessionId` (`java.lang.String`)

## Record Components

### `channelId` (`java.util.UUID`)

### `channelName` (`java.lang.String`)

### `docAContent` (`java.lang.String`)

### `docBContent` (`java.lang.String`)

### `instanceId` (`java.lang.String`)

### `reviewer` (`io.casehub.drafthouse.ResolvedReviewer`)

### `selection` (`io.casehub.drafthouse.SelectionScope`)

### `sessionId` (`java.lang.String`)

## Constructors

### `public ReviewSession(java.util.UUID channelId, java.lang.String sessionId, java.lang.String channelName, java.lang.String instanceId, java.lang.String docAContent, java.lang.String docBContent, io.casehub.drafthouse.SelectionScope selection, io.casehub.drafthouse.ResolvedReviewer reviewer)`

#### Parameters

- `channelId` (`java.util.UUID`)
- `sessionId` (`java.lang.String`)
- `channelName` (`java.lang.String`)
- `instanceId` (`java.lang.String`)
- `docAContent` (`java.lang.String`)
- `docBContent` (`java.lang.String`)
- `selection` (`io.casehub.drafthouse.SelectionScope`)
- `reviewer` (`io.casehub.drafthouse.ResolvedReviewer`)

## Methods

### `public java.util.UUID channelId()`

### `public java.lang.String channelName()`

### `public java.lang.String docAContent()`

### `public java.lang.String docBContent()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.lang.String instanceId()`

### `public io.casehub.drafthouse.ResolvedReviewer reviewer()`

### `public io.casehub.drafthouse.SelectionScope selection()`

### `public java.lang.String sessionId()`

### `public final java.lang.String toString()`

### `public io.casehub.drafthouse.ReviewSession withSelection(io.casehub.drafthouse.SelectionScope selection)`

#### Parameters

- `selection` (`io.casehub.drafthouse.SelectionScope`)
