# io.casehub.drafthouse.debate.DimensionDescriptor

**Package:** `io.casehub.drafthouse.debate`

**Kind:** `class`

## Fields

### `cost` (`double`)

### `currentRound` (`int`)

### `degree` (`java.lang.String`)

### `elapsedSeconds` (`int`)

### `findings` (`java.util.List<io.casehub.drafthouse.debate.PipelineFinding>`)

### `issuesByPriority` (`java.util.Map<java.lang.String,java.lang.Integer>`)

### `name` (`java.lang.String`)

### `status` (`io.casehub.drafthouse.debate.DimensionStatus`)

### `totalRounds` (`int`)

### `workspacePath` (`java.lang.String`)

## Constructors

### `public DimensionDescriptor(java.lang.String name, java.lang.String workspacePath, java.lang.String degree)`

#### Parameters

- `name` (`java.lang.String`)
- `workspacePath` (`java.lang.String`)
- `degree` (`java.lang.String`)

## Methods

### `void addFinding(io.casehub.drafthouse.debate.PipelineFinding finding)`

#### Parameters

- `finding` (`io.casehub.drafthouse.debate.PipelineFinding`)

### `public double cost()`

### `public int currentRound()`

### `public java.lang.String degree()`

### `public int elapsedSeconds()`

### `public java.util.List<io.casehub.drafthouse.debate.PipelineFinding> findings()`

### `public java.util.Map<java.lang.String,java.lang.Integer> issuesByPriority()`

### `public java.lang.String name()`

### `void setCost(double cost)`

#### Parameters

- `cost` (`double`)

### `void setCurrentRound(int round)`

#### Parameters

- `round` (`int`)

### `void setElapsedSeconds(int seconds)`

#### Parameters

- `seconds` (`int`)

### `void setIssuesByPriority(java.util.Map<java.lang.String,java.lang.Integer> byPriority)`

#### Parameters

- `byPriority` (`java.util.Map<java.lang.String,java.lang.Integer>`)

### `void setStatus(io.casehub.drafthouse.debate.DimensionStatus status)`

#### Parameters

- `status` (`io.casehub.drafthouse.debate.DimensionStatus`)

### `void setTotalRounds(int total)`

#### Parameters

- `total` (`int`)

### `public io.casehub.drafthouse.debate.DimensionStatus status()`

### `public java.util.Map<java.lang.String,java.lang.Object> toMap()`

### `public int totalRounds()`

### `public java.lang.String workspacePath()`
