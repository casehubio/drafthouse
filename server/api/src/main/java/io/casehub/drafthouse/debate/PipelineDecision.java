package io.casehub.drafthouse.debate;

import java.util.List;

public record PipelineDecision(
        String id, String title, String choice,
        List<String> alternatives, String rationale,
        String tradeoffs, String status,
        String explorationDepth, String dependsOn) {
}
