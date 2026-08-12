package io.casehub.drafthouse.debate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DimensionDescriptor {

    private final String name;
    private final String workspacePath;
    private final String degree;
    private volatile DimensionStatus status = DimensionStatus.PENDING;
    private volatile int currentRound;
    private volatile int totalRounds;
    private volatile double cost;
    private volatile int elapsedSeconds;
    private final Map<String, Integer> issuesByPriority = new LinkedHashMap<>();
    private final List<PipelineFinding> findings = new ArrayList<>();

    public DimensionDescriptor(String name, String workspacePath, String degree) {
        this.name = name;
        this.workspacePath = workspacePath;
        this.degree = degree;
    }

    public String name() { return name; }
    public String workspacePath() { return workspacePath; }
    public String degree() { return degree; }
    public DimensionStatus status() { return status; }
    public int currentRound() { return currentRound; }
    public int totalRounds() { return totalRounds; }
    public double cost() { return cost; }
    public int elapsedSeconds() { return elapsedSeconds; }
    public Map<String, Integer> issuesByPriority() { return Map.copyOf(issuesByPriority); }
    public List<PipelineFinding> findings() { return List.copyOf(findings); }

    void setStatus(DimensionStatus status) { this.status = status; }
    void setCurrentRound(int round) { this.currentRound = round; }
    void setTotalRounds(int total) { this.totalRounds = total; }
    void setCost(double cost) { this.cost = cost; }
    void setElapsedSeconds(int seconds) { this.elapsedSeconds = seconds; }

    void setIssuesByPriority(Map<String, Integer> byPriority) {
        this.issuesByPriority.clear();
        this.issuesByPriority.putAll(byPriority);
    }

    void addFinding(PipelineFinding finding) { this.findings.add(finding); }

    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", name);
        map.put("status", status.name());
        map.put("currentRound", currentRound);
        map.put("totalRounds", totalRounds);
        map.put("degree", degree);
        map.put("issuesByPriority", Map.copyOf(issuesByPriority));
        map.put("cost", cost);
        map.put("elapsed", elapsedSeconds);
        map.put("findingsCount", findings.size());
        return map;
    }
}
