package io.casehub.drafthouse.debate;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PipelineOrchestrator {

    public void onEvent(PipelineSession session, String dimension,
                        ProgressLogParser.ProgressEvent event) {
        synchronized (session) {
            switch (event) {
                case ProgressLogParser.DimensionStart ds ->
                        session.advanceDimension(ds.dimension(), DimensionStatus.RUNNING);
                case ProgressLogParser.RoundFindings rf -> {
                    session.updateDimensionRound(rf.dimension(), rf.roundNumber());
                    session.updateDimensionIssues(rf.dimension(), rf.byPriority());
                }
                case ProgressLogParser.RoundEnd re -> {
                    session.updateDimensionRound(re.dimension(), re.roundNumber());
                    session.updateDimensionCost(re.dimension(), re.cost());
                    checkRound1Complete(session);
                }
                case ProgressLogParser.DimensionDone dd -> {
                    session.advanceDimension(dd.dimension(), DimensionStatus.DONE);
                    session.updateDimensionCost(dd.dimension(), dd.cost());
                    checkAllDimensionsDone(session);
                }
                case ProgressLogParser.ReviewTerminal rt -> {
                    if ("DONE".equals(rt.finalState())) {
                        session.advanceDimension(dimension, DimensionStatus.DONE);
                    } else {
                        session.advanceDimension(dimension, DimensionStatus.FAILED);
                    }
                    checkAllDimensionsDone(session);
                }
                case ProgressLogParser.RoundComplete rc ->
                        session.updateDimensionRound(dimension, rc.round());
                default -> {}
            }
        }
    }

    private void checkRound1Complete(PipelineSession session) {
        if (session.currentPhase() != PipelinePhase.ROUND_1) return;
        boolean allPastRound1 = session.dimensions().stream()
                .filter(d -> !d.name().equals("crosscutting"))
                .allMatch(d -> d.currentRound() >= 1);
        if (allPastRound1) session.setPhase(PipelinePhase.HIL_CHECKPOINT_1);
    }

    private void checkAllDimensionsDone(PipelineSession session) {
        if (session.currentPhase() == PipelinePhase.COMPLETE) return;
        boolean allTerminal = session.dimensions().stream()
                .filter(d -> !d.name().equals("crosscutting"))
                .allMatch(d -> d.status() == DimensionStatus.DONE
                        || d.status() == DimensionStatus.KILLED
                        || d.status() == DimensionStatus.FAILED);
        if (allTerminal && session.currentPhase().ordinal() < PipelinePhase.HIL_CHECKPOINT_2.ordinal()) {
            session.setPhase(PipelinePhase.HIL_CHECKPOINT_2);
        }
    }
}
