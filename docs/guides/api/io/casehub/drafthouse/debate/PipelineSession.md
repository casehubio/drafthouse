# io.casehub.drafthouse.debate.PipelineSession

**Package:** `io.casehub.drafthouse.debate`

**Kind:** `class`

## Fields

### `checkpointStatus` (`io.casehub.drafthouse.debate.CheckpointStatus`)

### `currentPhase` (`io.casehub.drafthouse.debate.PipelinePhase`)

### `debateSessionId` (`java.lang.String`)

### `decisions` (`java.util.List<io.casehub.drafthouse.debate.PipelineDecision>`)

### `dimensions` (`java.util.List<io.casehub.drafthouse.debate.DimensionDescriptor>`)

### `ordered` (`boolean`)

### `pipelineId` (`java.lang.String`)

### `specPath` (`java.lang.String`)

## Constructors

### `public PipelineSession(java.lang.String pipelineId, java.lang.String debateSessionId, java.util.List<io.casehub.drafthouse.debate.DimensionDescriptor> dimensions, boolean ordered, java.lang.String specPath)`

#### Parameters

- `pipelineId` (`java.lang.String`)
- `debateSessionId` (`java.lang.String`)
- `dimensions` (`java.util.List<io.casehub.drafthouse.debate.DimensionDescriptor>`)
- `ordered` (`boolean`)
- `specPath` (`java.lang.String`)

## Methods

### `public synchronized void addFinding(java.lang.String dimension, io.casehub.drafthouse.debate.PipelineFinding finding)`

#### Parameters

- `dimension` (`java.lang.String`)
- `finding` (`io.casehub.drafthouse.debate.PipelineFinding`)

### `public synchronized void advanceDimension(java.lang.String name, io.casehub.drafthouse.debate.DimensionStatus status)`

#### Parameters

- `name` (`java.lang.String`)
- `status` (`io.casehub.drafthouse.debate.DimensionStatus`)

### `public io.casehub.drafthouse.debate.CheckpointStatus checkpointStatus()`

### `public io.casehub.drafthouse.debate.PipelinePhase currentPhase()`

### `public java.lang.String debateSessionId()`

### `public java.util.List<io.casehub.drafthouse.debate.PipelineDecision> decisions()`

### `public synchronized java.util.List<io.casehub.drafthouse.debate.DimensionDescriptor> dimensions()`

### `private io.casehub.drafthouse.debate.DimensionDescriptor findDimension(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public boolean ordered()`

### `public java.lang.String pipelineId()`

### `public synchronized void setCheckpoint(io.casehub.drafthouse.debate.CheckpointStatus status)`

#### Parameters

- `status` (`io.casehub.drafthouse.debate.CheckpointStatus`)

### `public synchronized void setDecisions(java.util.List<io.casehub.drafthouse.debate.PipelineDecision> decisions)`

#### Parameters

- `decisions` (`java.util.List<io.casehub.drafthouse.debate.PipelineDecision>`)

### `public synchronized void setPhase(io.casehub.drafthouse.debate.PipelinePhase phase)`

#### Parameters

- `phase` (`io.casehub.drafthouse.debate.PipelinePhase`)

### `public java.lang.String specPath()`

### `public synchronized java.util.Map<java.lang.String,java.lang.Object> toSnapshot()`

### `public synchronized void updateDimensionCost(java.lang.String name, double cost)`

#### Parameters

- `name` (`java.lang.String`)
- `cost` (`double`)

### `public synchronized void updateDimensionIssues(java.lang.String name, java.util.Map<java.lang.String,java.lang.Integer> byPriority)`

#### Parameters

- `name` (`java.lang.String`)
- `byPriority` (`java.util.Map<java.lang.String,java.lang.Integer>`)

### `public synchronized void updateDimensionRound(java.lang.String name, int round)`

#### Parameters

- `name` (`java.lang.String`)
- `round` (`int`)
