package io.casehub.drafthouse.debate;

public record PipelineFinding(
        String dimension, String issueId, String priority,
        String summary, String status, String location) {
}
