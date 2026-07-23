package io.casehub.drafthouse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class BrainstormMcpTools {

    private static final Logger LOG = Logger.getLogger(BrainstormMcpTools.class.getName());

    @Inject
    BrainstormService service;
    @Inject
    ObjectMapper      mapper;

    @Tool(name = "start_brainstorm",
          description = "Start a brainstorming session. Returns JSON with sessionId.")
    public String startBrainstorm() {
        String sessionId = service.startSession();
        return "{\"sessionId\":\"" + sessionId + "\"}";
    }

    @Tool(name = "present_options",
          description = "Present 2-4 brainstorming options. Options is a JSON array with objects containing id, title, description, tradeoffs.")
    public String presentOptions(
            @ToolArg(description = "Brainstorm session ID from start_brainstorm") String sessionId,
            @ToolArg(description = "JSON array of options: [{id, title, description, tradeoffs}, ...]") String optionsJson) {

        List<Map<String, String>> optionMaps;
        try {
            optionMaps = mapper.readValue(optionsJson, new TypeReference<>() {});
        } catch (Exception e) {
            return "error: invalid options JSON — " + e.getMessage();
        }

        List<BrainstormService.OptionInput> inputs = optionMaps.stream().map(om -> {
            String id    = om.get("id");
            String title = om.get("title");
            if (id == null || title == null) {
                throw new IllegalArgumentException("each option must have at least 'id' and 'title'");
            }
            return new BrainstormService.OptionInput(id, title,
                                                     om.getOrDefault("description", ""),
                                                     om.getOrDefault("tradeoffs", ""));
        }).toList();

        try {
            service.presentOptions(sessionId, inputs);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        }
        return "presented " + inputs.size() + " option(s)";
    }

    @Tool(name = "update_option",
          description = "Enrich an option with exploration results. Sets status to EXPLORED.")
    public String updateOption(
            @ToolArg(description = "Brainstorm session ID") String sessionId,
            @ToolArg(description = "Option ID to update") String optionId,
            @ToolArg(description = "Updated description with exploration findings") String description,
            @ToolArg(description = "Updated tradeoffs") String tradeoffs) {
        try {
            service.updateOption(sessionId, optionId, description, tradeoffs);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        }
        return "updated option '" + optionId + "'";
    }

    @Tool(name = "set_recommendation",
          description = "Mark one option as the recommended choice.")
    public String setRecommendation(
            @ToolArg(description = "Brainstorm session ID") String sessionId,
            @ToolArg(description = "Option ID to recommend") String optionId) {
        try {
            service.setRecommendation(sessionId, optionId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        }
        return "recommended option '" + optionId + "'";
    }

    @Tool(name = "mark_eliminated",
          description = "Mark an option as eliminated (e.g. after a challenge reveals a fatal flaw).")
    public String markEliminated(
            @ToolArg(description = "Brainstorm session ID") String sessionId,
            @ToolArg(description = "Option ID to eliminate") String optionId) {
        try {
            service.markEliminated(sessionId, optionId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        }
        return "eliminated option '" + optionId + "'";
    }

    @Tool(name = "mark_selected",
          description = "Mark the final option selection. Converges the session.")
    public String markSelected(
            @ToolArg(description = "Brainstorm session ID") String sessionId,
            @ToolArg(description = "Option ID selected") String optionId) {
        try {
            service.markSelected(sessionId, optionId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        }
        return "selected option '" + optionId + "'";
    }

    @Tool(name = "end_brainstorm",
          description = "End a brainstorming session.")
    public String endBrainstorm(
            @ToolArg(description = "Brainstorm session ID") String sessionId) {
        try {
            service.endSession(sessionId);
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        }
        return "ended brainstorm session '" + sessionId + "'";
    }
}
