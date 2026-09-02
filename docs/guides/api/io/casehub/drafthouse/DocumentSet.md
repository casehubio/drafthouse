# io.casehub.drafthouse.DocumentSet

**Package:** `io.casehub.drafthouse`

**Kind:** `class`

## Fields

### `currentComparison` (`io.casehub.drafthouse.ComparisonPair`)

### `documents` (`java.util.ArrayList<io.casehub.drafthouse.DocumentEntry>`)

## Constructors

### `DocumentSet()`

## Methods

### `public synchronized boolean add(java.lang.String path, java.lang.String label)`

#### Parameters

- `path` (`java.lang.String`)
- `label` (`java.lang.String`)

### `public synchronized void clearComparison()`

### `public static io.casehub.drafthouse.DocumentSet copyOf(io.casehub.drafthouse.DocumentSet source)`

#### Parameters

- `source` (`io.casehub.drafthouse.DocumentSet`)

### `public synchronized io.casehub.drafthouse.ComparisonPair currentComparison()`

### `public synchronized java.util.List<io.casehub.drafthouse.DocumentEntry> documents()`

### `public synchronized java.util.Optional<io.casehub.drafthouse.DocumentEntry> primary()`

### `public synchronized boolean remove(java.lang.String path)`

#### Parameters

- `path` (`java.lang.String`)

### `public synchronized void setComparison(java.lang.String pathA, java.lang.String pathB)`

#### Parameters

- `pathA` (`java.lang.String`)
- `pathB` (`java.lang.String`)
