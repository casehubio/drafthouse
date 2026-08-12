package io.casehub.drafthouse.debate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PipelineSession {

    private final String pipelineId;
    private final String debateSessionId;
    private final List<DimensionDescriptor> dimensions;
    private final boolean ordered;
    private final String specPath;
    private volatile PipelinePhase currentPhase = PipelinePhase.ROUND_1;
    private volatile CheckpointStatus checkpointStatus = CheckpointStatus.NONE;
    private volatile List<PipelineDecision> decisions = List.of();

    public PipelineSession(String pipelineId, String debateSessionId,
                           List<DimensionDescriptor> dimensions,
                           boolean ordered, String specPath) {
        this.pipelineId = pipelineId;
        this.debateSessionId = debateSessionId;
        this.dimensions = new ArrayList<>(dimensions);
        this.ordered = ordered;
        this.specPath = specPath;
    }

    public String pipelineId() { return pipelineId; }
    public String debateSessionId() { return debateSessionId; }
    public boolean ordered() { return ordered; }
    public String specPath() { return specPath; }
    public PipelinePhase currentPhase() { return currentPhase; }
    public CheckpointStatus checkpointStatus() { return checkpointStatus; }
    public List<PipelineDecision> decisions() { return decisions; }

    public synchronized List<DimensionDescriptor> dimensions() {
        return List.copyOf(dimensions);
    }

    public synchronized void advanceDimension(String name, DimensionStatus status) {
        findDimension(name).setStatus(status);
    }

    public synchronized void setPhase(PipelinePhase phase) {
        this.currentPhase = phase;
    }

    public synchronized void setCheckpoint(CheckpointStatus status) {
        this.checkpointStatus = status;
    }

    public synchronized void updateDimensionRound(String name, int round) {
        findDimension(name).setCurrentRound(round);
    }

    public synchronized void updateDimensionIssues(String name, Map<String, Integer> byPriority) {
        findDimension(name).setIssuesByPriority(byPriority);
    }

    public synchronized void updateDimensionCost(String name, double cost) {
        findDimension(name).setCost(cost);
    }

    public synchronized void addFinding(String dimension, PipelineFinding finding) {
        findDimension(dimension).addFinding(finding);
    }

    public synchronized void setDecisions(List<PipelineDecision> decisions) {
        this.decisions = List.copyOf(decisions);
    }

    public synchronized Map<String, Object> toSnapshot() {
        var map = new LinkedHashMap<String, Object>();
        map.put("pipelineId", pipelineId);
        map.put("phase", currentPhase.name());
        map.put("checkpointStatus", checkpointStatus.name());
        map.put("ordered", ordered);
        map.put("dimensions", dimensions.stream().map(DimensionDescriptor::toMap).toList());
        return map;
    }

    private DimensionDescriptor findDimension(String name) {
        return dimensions.stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown dimension: " + name));
    }
}
