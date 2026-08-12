package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipelineSessionTest {

    @Test
    void create_with_dimensions_all_pending() {
        var dims = List.of(
                new DimensionDescriptor("coherence", "/tmp/ws/coherence", "standard"),
                new DimensionDescriptor("structure", "/tmp/ws/structure", "standard"));
        var session = new PipelineSession("p1", "d1", dims, false, "/tmp/spec.md");
        assertEquals(PipelinePhase.ROUND_1, session.currentPhase());
        assertEquals(CheckpointStatus.NONE, session.checkpointStatus());
        session.dimensions().forEach(d ->
                assertEquals(DimensionStatus.PENDING, d.status()));
    }

    @Test
    void advance_dimension_to_running() {
        var session = makeSession("coherence");
        session.advanceDimension("coherence", DimensionStatus.RUNNING);
        assertEquals(DimensionStatus.RUNNING, session.dimensions().get(0).status());
    }

    @Test
    void advance_dimension_to_killed() {
        var session = makeSession("coherence");
        session.advanceDimension("coherence", DimensionStatus.RUNNING);
        session.advanceDimension("coherence", DimensionStatus.KILLED);
        assertEquals(DimensionStatus.KILLED, session.dimensions().get(0).status());
    }

    @Test
    void advance_dimension_to_failed() {
        var session = makeSession("coherence");
        session.advanceDimension("coherence", DimensionStatus.RUNNING);
        session.advanceDimension("coherence", DimensionStatus.FAILED);
        assertEquals(DimensionStatus.FAILED, session.dimensions().get(0).status());
    }

    @Test
    void phase_and_checkpoint_transitions() {
        var session = makeSession("coherence");
        session.setPhase(PipelinePhase.HIL_CHECKPOINT_1);
        session.setCheckpoint(CheckpointStatus.PENDING);
        assertEquals(PipelinePhase.HIL_CHECKPOINT_1, session.currentPhase());
        assertEquals(CheckpointStatus.PENDING, session.checkpointStatus());
        session.setCheckpoint(CheckpointStatus.RESOLVED);
        assertEquals(CheckpointStatus.RESOLVED, session.checkpointStatus());
    }

    @Test
    void update_dimension_round_and_issues() {
        var session = makeSession("coherence");
        session.updateDimensionRound("coherence", 2);
        session.updateDimensionIssues("coherence", Map.of("HIGH", 3, "MEDIUM", 1));
        var dim = session.dimensions().get(0);
        assertEquals(2, dim.currentRound());
        assertEquals(Map.of("HIGH", 3, "MEDIUM", 1), dim.issuesByPriority());
    }

    @Test
    void update_dimension_cost() {
        var session = makeSession("coherence");
        session.updateDimensionCost("coherence", 4.50);
        assertEquals(4.50, session.dimensions().get(0).cost(), 0.001);
    }

    @Test
    void add_finding() {
        var session = makeSession("coherence");
        var finding = new PipelineFinding("coherence", "R1-01", "HIGH", "Missing section", "open", null);
        session.addFinding("coherence", finding);
        assertEquals(1, session.dimensions().get(0).findings().size());
        assertEquals("R1-01", session.dimensions().get(0).findings().get(0).issueId());
    }

    @Test
    void set_decisions() {
        var session = makeSession("coherence");
        var decisions = List.of(
                new PipelineDecision("D1", "Arch", "Option A", List.of("B"), "reason", "trade", "captured", "quick", null));
        session.setDecisions(decisions);
        assertEquals(1, session.decisions().size());
        assertEquals("D1", session.decisions().get(0).id());
    }

    @Test
    void to_snapshot_returns_map() {
        var session = makeSession("coherence");
        var snap = session.toSnapshot();
        assertEquals("p1", snap.get("pipelineId"));
        assertEquals("ROUND_1", snap.get("phase"));
        assertEquals("NONE", snap.get("checkpointStatus"));
        assertInstanceOf(List.class, snap.get("dimensions"));
    }

    @Test
    void unknown_dimension_throws() {
        var session = makeSession("coherence");
        assertThrows(IllegalArgumentException.class,
                () -> session.advanceDimension("nonexistent", DimensionStatus.RUNNING));
    }

    private PipelineSession makeSession(String... names) {
        var dims = java.util.Arrays.stream(names)
                .map(n -> new DimensionDescriptor(n, "/tmp/" + n, "standard"))
                .toList();
        return new PipelineSession("p1", "d1", dims, false, "/tmp/spec.md");
    }
}
