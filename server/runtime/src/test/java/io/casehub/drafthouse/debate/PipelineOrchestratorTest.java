package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipelineOrchestratorTest {

    private final PipelineOrchestrator orchestrator = new PipelineOrchestrator();

    @Test
    void dimension_start_sets_running() {
        var session = makeSession("coherence");
        orchestrator.onEvent(session, "coherence",
                new ProgressLogParser.DimensionStart("coherence", "standard", 1));
        assertEquals(DimensionStatus.RUNNING, session.dimensions().get(0).status());
    }

    @Test
    void dimension_done_sets_done_and_cost() {
        var session = makeSession("coherence");
        orchestrator.onEvent(session, "coherence",
                new ProgressLogParser.DimensionStart("coherence", "standard", 1));
        orchestrator.onEvent(session, "coherence",
                new ProgressLogParser.DimensionDone("coherence", 3, 4.20, 8));
        assertEquals(DimensionStatus.DONE, session.dimensions().get(0).status());
        assertEquals(4.20, session.dimensions().get(0).cost(), 0.001);
    }

    @Test
    void review_terminal_done_sets_done_using_dimension_param() {
        var session = makeSession("coherence", "structure");
        session.advanceDimension("coherence", DimensionStatus.RUNNING);
        session.advanceDimension("structure", DimensionStatus.RUNNING);
        orchestrator.onEvent(session, "coherence",
                new ProgressLogParser.ReviewTerminal("DONE"));
        assertEquals(DimensionStatus.DONE, session.dimensions().get(0).status());
        assertEquals(DimensionStatus.RUNNING, session.dimensions().get(1).status());
    }

    @Test
    void review_terminal_failed_sets_failed_using_dimension_param() {
        var session = makeSession("coherence");
        session.advanceDimension("coherence", DimensionStatus.RUNNING);
        orchestrator.onEvent(session, "coherence",
                new ProgressLogParser.ReviewTerminal("FAILED"));
        assertEquals(DimensionStatus.FAILED, session.dimensions().get(0).status());
    }

    @Test
    void all_round_1_complete_advances_to_checkpoint() {
        var session = makeSession("coherence", "structure");
        orchestrator.onEvent(session, "coherence",
                new ProgressLogParser.DimensionStart("coherence", "standard", 1));
        orchestrator.onEvent(session, "structure",
                new ProgressLogParser.DimensionStart("structure", "standard", 1));
        orchestrator.onEvent(session, "coherence",
                new ProgressLogParser.RoundEnd("coherence", 1, 1.0));
        assertEquals(PipelinePhase.ROUND_1, session.currentPhase());
        orchestrator.onEvent(session, "structure",
                new ProgressLogParser.RoundEnd("structure", 1, 1.0));
        assertEquals(PipelinePhase.HIL_CHECKPOINT_1, session.currentPhase());
    }

    @Test
    void all_done_advances_to_checkpoint_2() {
        var session = makeSession("coherence", "structure");
        session.advanceDimension("coherence", DimensionStatus.RUNNING);
        session.advanceDimension("structure", DimensionStatus.RUNNING);
        orchestrator.onEvent(session, "coherence",
                new ProgressLogParser.DimensionDone("coherence", 3, 4.0, 5));
        assertEquals(PipelinePhase.ROUND_1, session.currentPhase());
        orchestrator.onEvent(session, "structure",
                new ProgressLogParser.DimensionDone("structure", 3, 3.0, 4));
        assertEquals(PipelinePhase.HIL_CHECKPOINT_2, session.currentPhase());
    }

    @Test
    void round_findings_updates_issues() {
        var session = makeSession("coherence");
        orchestrator.onEvent(session, "coherence",
                new ProgressLogParser.RoundFindings("coherence", 1, 6,
                        Map.of("HIGH", 3, "MEDIUM", 2, "LOW", 1)));
        var dim = session.dimensions().get(0);
        assertEquals(1, dim.currentRound());
        assertEquals(Map.of("HIGH", 3, "MEDIUM", 2, "LOW", 1), dim.issuesByPriority());
    }

    @Test
    void killed_dimension_counts_as_terminal_for_checkpoint() {
        var session = makeSession("coherence", "structure");
        session.advanceDimension("coherence", DimensionStatus.RUNNING);
        session.advanceDimension("structure", DimensionStatus.RUNNING);
        session.advanceDimension("coherence", DimensionStatus.KILLED);
        orchestrator.onEvent(session, "structure",
                new ProgressLogParser.DimensionDone("structure", 3, 3.0, 4));
        assertEquals(PipelinePhase.HIL_CHECKPOINT_2, session.currentPhase());
    }

    private PipelineSession makeSession(String... names) {
        var dims = java.util.Arrays.stream(names)
                .map(n -> new DimensionDescriptor(n, "/tmp/" + n, "standard"))
                .toList();
        return new PipelineSession("p1", "d1", dims, false, "/tmp/spec.md");
    }
}
