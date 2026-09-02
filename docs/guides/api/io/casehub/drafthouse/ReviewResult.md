# io.casehub.drafthouse.ReviewResult

**Package:** `io.casehub.drafthouse`

**Kind:** `record`

Result returned by DocumentReviewer.review().

AGREE   → dispatch DONE on the review channel (point resolved, discussion concludes).
QUALIFY → dispatch RESPONSE on the review channel (reviewer qualifies position, discussion continues).
DECLINE → dispatch DECLINE on the review channel (out-of-scope or LLM error — FAILURE is reserved
           for COMMAND obligations only per Qhorus ADR-0005 speech-act taxonomy).

## Fields

### `content` (`java.lang.String`)

### `outcome` (`io.casehub.drafthouse.ReviewResult.Outcome`)

## Record Components

### `content` (`java.lang.String`)

### `outcome` (`io.casehub.drafthouse.ReviewResult.Outcome`)

## Constructors

### `public ReviewResult(io.casehub.drafthouse.ReviewResult.Outcome outcome, java.lang.String content)`

#### Parameters

- `outcome` (`io.casehub.drafthouse.ReviewResult.Outcome`)
- `content` (`java.lang.String`)

## Methods

### `public static io.casehub.drafthouse.ReviewResult agree(java.lang.String content)`

#### Parameters

- `content` (`java.lang.String`)

### `public java.lang.String content()`

### `public static io.casehub.drafthouse.ReviewResult decline(java.lang.String reason)`

#### Parameters

- `reason` (`java.lang.String`)

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public io.casehub.drafthouse.ReviewResult.Outcome outcome()`

### `public static io.casehub.drafthouse.ReviewResult qualify(java.lang.String content)`

#### Parameters

- `content` (`java.lang.String`)

### `public final java.lang.String toString()`
