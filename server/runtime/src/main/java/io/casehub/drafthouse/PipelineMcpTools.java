package io.casehub.drafthouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.drafthouse.debate.*;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class PipelineMcpTools {

    private static final Logger LOG = Logger.getLogger(PipelineMcpTools.class.getName());
    private final ConcurrentHashMap<String, Map<String, PipelineWatcher>> activeWatchers = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject PipelineSessionRegistry registry;
    @Inject PipelineOrchestrator orchestrator;
    @Inject WebSocketEventBus eventBus;
    @Inject DebateSessionRegistry debateRegistry;

    @Tool(name = "start_pipeline",
          description = "Create a review pipeline to visualize multi-dimension reviews. "
                  + "Links to a debate session for WebSocket routing.")
    public String startPipeline(
            @ToolArg(description = "Debate session ID to link pipeline events to") String debateSessionId,
            @ToolArg(description = "Dimensions as JSON array: [{name, workspacePath, degree}]") String dimensions,
            @ToolArg(description = "Sequential (true) or parallel (false)") boolean ordered,
            @ToolArg(description = "Path to the spec being reviewed") String specPath) {
        try {
            var dimsNode = mapper.readTree(dimensions);
            var dimList = new ArrayList<DimensionDescriptor>();
            for (var node : dimsNode) {
                dimList.add(new DimensionDescriptor(
                        node.path("name").asText(),
                        node.path("workspacePath").asText(),
                        node.path("degree").asText()));
            }

            String pipelineId = UUID.randomUUID().toString();
            var session = new PipelineSession(pipelineId, debateSessionId, dimList, ordered, specPath);
            registry.create(session);

            var watchers = new LinkedHashMap<String, PipelineWatcher>();
            for (var dim : dimList) {
                Path wsPath = Path.of(dim.workspacePath());
                if (!Files.isDirectory(wsPath)) continue;
                var watcher = new PipelineWatcher(dim.name(), wsPath, (dimension, event) -> {
                    orchestrator.onEvent(session, dimension, event);
                    pushPipelineProgress(session);
                });
                try {
                    watcher.start();
                    watchers.put(dim.name(), watcher);
                } catch (Exception e) {
                    LOG.warning("Failed to start watcher for " + dim.name() + ": " + e.getMessage());
                }
            }
            activeWatchers.put(pipelineId, watchers);

            pushPipelineProgress(session);
            return mapper.writeValueAsString(session.toSnapshot());
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    @Tool(name = "update_pipeline",
          description = "Update pipeline state — HIL checkpoint decisions, dimension status changes.")
    public String updatePipeline(
            @ToolArg(description = "Pipeline ID") String pipelineId,
            @ToolArg(description = "Action: checkpoint_reached, dimension_refused, dimension_accepted, crosscutting_started, pipeline_complete") String action,
            @ToolArg(description = "Dimension name (for dimension_refused, dimension_accepted)") String dimension) {
        var session = registry.get(pipelineId);
        if (session == null) return "{\"error\": \"Pipeline not found: " + pipelineId + "\"}";

        synchronized (session) {
            switch (action) {
                case "checkpoint_reached" -> {
                    if (session.checkpointStatus() == CheckpointStatus.PENDING) break;
                    session.setCheckpoint(CheckpointStatus.PENDING);
                }
                case "dimension_refused" -> {
                    if (dimension == null) return "{\"error\": \"dimension required for dimension_refused\"}";
                    session.advanceDimension(dimension, DimensionStatus.KILLED);
                    stopWatcher(pipelineId, dimension);
                    checkCheckpointResolution(session);
                }
                case "dimension_accepted" -> {
                    checkCheckpointResolution(session);
                }
                case "crosscutting_started" -> session.setPhase(PipelinePhase.CROSS_CUTTING);
                case "pipeline_complete" -> {
                    session.setPhase(PipelinePhase.COMPLETE);
                    stopAllWatchers(pipelineId);
                    registry.remove(pipelineId);
                }
                default -> { return "{\"error\": \"Unknown action: " + action + "\"}"; }
            }
        }

        pushPipelineProgress(session);
        try { return mapper.writeValueAsString(session.toSnapshot()); }
        catch (Exception e) { return "{\"error\": \"" + e.getMessage() + "\"}"; }
    }

    @Tool(name = "load_decisions",
          description = "Load brainstorming decisions from a decisions.md file into the pipeline.")
    public String loadDecisions(
            @ToolArg(description = "Pipeline ID") String pipelineId,
            @ToolArg(description = "Path to decisions.md") String decisionsPath) {
        var session = registry.get(pipelineId);
        if (session == null) return "{\"error\": \"Pipeline not found: " + pipelineId + "\"}";

        try {
            String content = Files.readString(Path.of(decisionsPath));
            var decisions = PipelineDecisionParser.parse(content);
            session.setDecisions(decisions);

            var debateSession = debateRegistry.activeSessions().stream()
                    .filter(s -> s.debateSessionId().equals(session.debateSessionId()))
                    .findFirst().orElse(null);
            if (debateSession != null) {
                var payload = new LinkedHashMap<String, Object>();
                payload.put("pipelineId", pipelineId);
                payload.put("decisions", decisions.stream().map(d -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("id", d.id());
                    m.put("title", d.title());
                    m.put("choice", d.choice());
                    m.put("alternatives", d.alternatives());
                    m.put("rationale", d.rationale());
                    m.put("tradeoffs", d.tradeoffs());
                    m.put("status", d.status());
                    m.put("exploration", d.explorationDepth());
                    return m;
                }).toList());
                eventBus.pushMetadata(debateSession.channelId(), "pipeline-decisions", payload);
            }
            return mapper.writeValueAsString(Map.of(
                    "pipelineId", pipelineId,
                    "decisionsLoaded", decisions.size()));
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private void pushPipelineProgress(PipelineSession session) {
        debateRegistry.activeSessions().stream()
                .filter(s -> s.debateSessionId().equals(session.debateSessionId()))
                .findFirst()
                .ifPresent(ds -> eventBus.pushMetadata(
                        ds.channelId(), "pipeline-progress", session.toSnapshot()));
    }

    private void checkCheckpointResolution(PipelineSession session) {
        if (session.checkpointStatus() != CheckpointStatus.PENDING) return;
        session.setCheckpoint(CheckpointStatus.RESOLVED);
        if (session.currentPhase() == PipelinePhase.HIL_CHECKPOINT_1) {
            session.setPhase(PipelinePhase.REMAINING_ROUNDS);
        }
    }

    private void stopWatcher(String pipelineId, String dimension) {
        var watchers = activeWatchers.get(pipelineId);
        if (watchers == null) return;
        var watcher = watchers.get(dimension);
        if (watcher != null) watcher.stop();
    }

    private void stopAllWatchers(String pipelineId) {
        var watchers = activeWatchers.remove(pipelineId);
        if (watchers != null) watchers.values().forEach(PipelineWatcher::stop);
    }
}
