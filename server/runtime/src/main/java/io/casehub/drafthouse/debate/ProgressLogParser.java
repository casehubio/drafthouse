package io.casehub.drafthouse.debate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProgressLogParser {

    public sealed interface ProgressEvent {}
    public record AgentStart(String agent, boolean cached) implements ProgressEvent {}
    public record AgentStatus(String agent, int elapsedSeconds, String message) implements ProgressEvent {}
    public record AgentComplete(String agent, double cost) implements ProgressEvent {}
    public record IssuesRaised(int count) implements ProgressEvent {}
    public record RoundComplete(int round, double roundCost, double cumulativeCost) implements ProgressEvent {}
    public record ReviewTerminal(String finalState) implements ProgressEvent {}

    public record DimensionStart(String dimension, String degree, int phase) implements ProgressEvent {}

    public record RoundFindings(String dimension, int roundNumber, int issueCount,
                                java.util.Map<String, Integer> byPriority) implements ProgressEvent {}

    public record RoundEnd(String dimension, int roundNumber, double cost) implements ProgressEvent {}

    public record DimensionDone(String dimension, int totalRounds, double cost, int issues) implements ProgressEvent {}


    private static final Pattern AGENT_START = Pattern.compile(
            "\\[\\d{2}:\\d{2}:\\d{2}]\\s+(Reviewer|Implementor)\\s+\\((fresh session|continued .*cached.*)\\)");
    private static final Pattern AGENT_STATUS = Pattern.compile(
            "\\[\\d{2}:\\d{2}:\\d{2}]\\s+\\[(\\d+)s]\\s+(reviewer|implementor):\\s+(.+)");
    private static final Pattern AGENT_COMPLETE = Pattern.compile(
            "\\[\\d{2}:\\d{2}:\\d{2}]\\s+(Reviewer|Implementor)\\s+done\\s+\\(\\$(\\d+\\.\\d+)\\)");
    private static final Pattern ISSUES_RAISED = Pattern.compile(
            "\\[\\d{2}:\\d{2}:\\d{2}]\\s+(\\d+)\\s+new\\s+issue\\(s\\)\\s+raised");
    private static final Pattern ROUND_COMPLETE = Pattern.compile(
            "\\[\\d{2}:\\d{2}:\\d{2}]\\s+Round\\s+(\\d+)\\s+complete\\s+.+~\\$(\\d+\\.\\d+)/round,\\s+\\$(\\d+\\.\\d+)\\s+cumulative");
    private static final Pattern TERMINAL = Pattern.compile(
            "REVIEW\\s+(DONE|PAUSED|FAILED|ABORTED|CRASHED|INTERRUPTED)\\b");

    private static final Pattern EVENT_LINE = Pattern.compile("EVENT:\\s+(\\{.+})");


    private ProgressLogParser() {}

    public static ProgressEvent parse(String line) {
        if (line == null || line.isBlank()) {return null;}

        // JSON events first — they have a structured prefix
        Matcher em = EVENT_LINE.matcher(line);
        if (em.find()) {return parseJsonEvent(em.group(1));}

        Matcher m;

        m = TERMINAL.matcher(line);
        if (m.find()) {return new ReviewTerminal(m.group(1));}

        m = AGENT_START.matcher(line);
        if (m.find()) {return new AgentStart(m.group(1).toLowerCase(), m.group(2).contains("cached"));}

        m = AGENT_STATUS.matcher(line);
        if (m.find()) {return new AgentStatus(m.group(2), Integer.parseInt(m.group(1)), m.group(3).trim());}

        m = AGENT_COMPLETE.matcher(line);
        if (m.find()) {return new AgentComplete(m.group(1).toLowerCase(), Double.parseDouble(m.group(2)));}

        m = ISSUES_RAISED.matcher(line);
        if (m.find()) {return new IssuesRaised(Integer.parseInt(m.group(1)));}

        m = ROUND_COMPLETE.matcher(line);
        if (m.find()) {
            return new RoundComplete(
                    Integer.parseInt(m.group(1)),
                    Double.parseDouble(m.group(2)),
                    Double.parseDouble(m.group(3)));
        }

        return null;
    }

    private static ProgressEvent parseJsonEvent(String json) {
        try {
            var    node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            String type = node.path("type").asText("");
            return switch (type) {
                case "dimension_start" -> new DimensionStart(
                        node.path("dimension").asText(),
                        node.path("degree").asText(),
                        node.path("phase").asInt());
                case "round_findings" -> {
                    java.util.Map<String, Integer> byPriority = new java.util.LinkedHashMap<>();
                    node.path("issues").fields().forEachRemaining(
                            e -> byPriority.put(e.getKey(), e.getValue().asInt()));
                    yield new RoundFindings(
                            node.path("dimension").asText(),
                            node.path("round_number").asInt(),
                            byPriority.values().stream().mapToInt(Integer::intValue).sum(),
                            byPriority);
                }
                case "round_end" -> new RoundEnd(
                        node.path("dimension").asText(),
                        node.path("round_number").asInt(),
                        node.path("cost").asDouble());
                case "dimension_done" -> new DimensionDone(
                        node.path("dimension").asText(),
                        node.path("total_rounds").asInt(),
                        node.path("cost").asDouble(),
                        node.path("issues").asInt());
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }


    public static boolean isTerminal(String line) {
        return line != null && TERMINAL.matcher(line).find();
    }

    public static String terminalState(String line) {
        if (line == null) return null;
        Matcher m = TERMINAL.matcher(line);
        return m.find() ? m.group(1) : null;
    }
}
