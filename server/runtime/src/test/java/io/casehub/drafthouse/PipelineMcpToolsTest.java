package io.casehub.drafthouse;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PipelineMcpToolsTest {

    @Inject PipelineMcpTools tools;

    @Test
    void start_pipeline_creates_session() {
        String result = tools.startPipeline("test-debate-1",
                "[{\"name\":\"coherence\",\"workspacePath\":\"/tmp/test-ws-nonexistent\",\"degree\":\"light\"}]",
                false, "/tmp/spec.md");
        assertNotNull(result);
        assertTrue(result.contains("pipelineId"));
        assertTrue(result.contains("coherence"));
        assertTrue(result.contains("ROUND_1"));

        String pipelineId = extractField(result, "pipelineId");
        tools.updatePipeline(pipelineId, "pipeline_complete", null);
    }

    @Test
    void update_pipeline_unknown_id_returns_error() {
        String result = tools.updatePipeline("nonexistent", "pipeline_complete", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Pipeline not found"));
    }

    @Test
    void update_pipeline_checkpoint_reached_idempotent() {
        String r1 = tools.startPipeline("test-debate-2",
                "[{\"name\":\"structure\",\"workspacePath\":\"/tmp/test-ws2-nonexistent\",\"degree\":\"light\"}]",
                false, "/tmp/spec.md");
        String pipelineId = extractField(r1, "pipelineId");

        String r2 = tools.updatePipeline(pipelineId, "checkpoint_reached", null);
        assertTrue(r2.contains("PENDING"));
        String r3 = tools.updatePipeline(pipelineId, "checkpoint_reached", null);
        assertEquals(r2, r3);

        tools.updatePipeline(pipelineId, "pipeline_complete", null);
    }

    @Test
    void update_pipeline_dimension_refused() {
        String r1 = tools.startPipeline("test-debate-3",
                "[{\"name\":\"coherence\",\"workspacePath\":\"/tmp/test-ws3-nonexistent\",\"degree\":\"light\"}]",
                false, "/tmp/spec.md");
        String pipelineId = extractField(r1, "pipelineId");

        String result = tools.updatePipeline(pipelineId, "dimension_refused", "coherence");
        assertTrue(result.contains("KILLED"));

        tools.updatePipeline(pipelineId, "pipeline_complete", null);
    }

    @Test
    void update_pipeline_dimension_refused_requires_dimension() {
        String r1 = tools.startPipeline("test-debate-4",
                "[{\"name\":\"coherence\",\"workspacePath\":\"/tmp/test-ws4-nonexistent\",\"degree\":\"light\"}]",
                false, "/tmp/spec.md");
        String pipelineId = extractField(r1, "pipelineId");

        String result = tools.updatePipeline(pipelineId, "dimension_refused", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("dimension required"));

        tools.updatePipeline(pipelineId, "pipeline_complete", null);
    }

    @Test
    void update_pipeline_unknown_action() {
        String r1 = tools.startPipeline("test-debate-5",
                "[{\"name\":\"coherence\",\"workspacePath\":\"/tmp/test-ws5-nonexistent\",\"degree\":\"light\"}]",
                false, "/tmp/spec.md");
        String pipelineId = extractField(r1, "pipelineId");

        String result = tools.updatePipeline(pipelineId, "invalid_action", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Unknown action"));

        tools.updatePipeline(pipelineId, "pipeline_complete", null);
    }

    @Test
    void load_decisions_unknown_pipeline() {
        String result = tools.loadDecisions("nonexistent", "/tmp/decisions.md");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Pipeline not found"));
    }

    private String extractField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":\"") + field.length() + 4;
        return json.substring(idx, json.indexOf("\"", idx));
    }
}
