# io.casehub.drafthouse.debate.PipelineDecisionParser

**Package:** `io.casehub.drafthouse.debate`

**Kind:** `class`

## Fields

### `DECISION_HEADER` (`java.util.regex.Pattern`)

## Constructors

### `private PipelineDecisionParser()`

## Methods

### `private static java.util.List<java.lang.String> extractAlternatives(java.lang.String section)`

#### Parameters

- `section` (`java.lang.String`)

### `private static java.lang.String extractField(java.lang.String section, java.lang.String fieldName)`

#### Parameters

- `section` (`java.lang.String`)
- `fieldName` (`java.lang.String`)

### `public static java.util.List<io.casehub.drafthouse.debate.PipelineDecision> parse(java.lang.String markdown)`

#### Parameters

- `markdown` (`java.lang.String`)
