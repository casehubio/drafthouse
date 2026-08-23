package io.casehub.drafthouse;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Collectors;

/** Session-level MCP tools — always available regardless of active facets. */
@ApplicationScoped
public class SessionMcpTools {

    @Inject DraftHouseSessionRegistry registry;

    @Tool(description = "Create a new DraftHouse session. Returns the session ID.")
    String create_session(
            @ToolArg(description = "Optional session ID. Auto-generated if omitted.") String sessionId,
            @ToolArg(description = "Optional working directory path for artifacts.") String workingDirectory) {
        String id = (sessionId != null && !sessionId.isBlank()) ? sessionId : UUID.randomUUID().toString();
        DraftHouseSession session = registry.create(id);
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            session.setWorkingDirectory(Path.of(workingDirectory));
        }
        return "Session created: " + id;
    }

    @Tool(description = "Set or change the working directory for a session's artifact space.")
    String set_working_directory(
            @ToolArg(description = "Session ID") String sessionId,
            @ToolArg(description = "Absolute path to working directory") String path) {
        DraftHouseSession session = requireSession(sessionId);
        session.setWorkingDirectory(Path.of(path));
        return "Working directory set: " + path;
    }

    @Tool(description = "List active facets for a session.")
    String list_facets(@ToolArg(description = "Session ID") String sessionId) {
        DraftHouseSession session = requireSession(sessionId);
        var active = session.activeFacets().keySet();
        if (active.isEmpty()) return "No active facets.";
        return "Active facets: " + String.join(", ", active);
    }

    @Tool(description = "Activate a facet on a session. Registers the facet's MCP tools.")
    String activate_facet(
            @ToolArg(description = "Session ID") String sessionId,
            @ToolArg(description = "Facet name: voice, brainstorm, draft, review") String facetName) {
        DraftHouseSession session = requireSession(sessionId);
        try {
            Facet facet = switch (facetName) {
                case "voice" -> {
                    if (session.findFacet("notes").isEmpty()) {
                        session.activateFacet(new io.casehub.drafthouse.voice.NotesFacet());
                    }
                    yield new io.casehub.drafthouse.voice.VoiceFacet();
                }
                case "notes" -> new io.casehub.drafthouse.voice.NotesFacet();
                default -> throw new IllegalArgumentException("Unknown facet: " + facetName +
                                                              ". Available: voice, notes");
            };
            session.activateFacet(facet);
            return "Facet activated: " + facetName;
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }}

    @Tool(description = "Deactivate a facet on a session. Deregisters the facet's MCP tools.")
    String deactivate_facet(
            @ToolArg(description = "Session ID") String sessionId,
            @ToolArg(description = "Facet name") String facetName) {
        DraftHouseSession session = requireSession(sessionId);
        session.deactivateFacet(facetName);
        return "Facet deactivated: " + facetName;
    }

    @Tool(description = "Add a document to the session's working set.")
    String add_document_to_session(
            @ToolArg(description = "Session ID") String sessionId,
            @ToolArg(description = "File path") String path,
            @ToolArg(description = "Display label") String label) {
        DraftHouseSession session = requireSession(sessionId);
        boolean added = session.documentSet().add(path, label != null ? label : path);
        return added ? "Document added: " + path : "Document already in set: " + path;
    }

    @Tool(description = "Remove a document from the session's working set.")
    String remove_document_from_session(
            @ToolArg(description = "Session ID") String sessionId,
            @ToolArg(description = "File path") String path) {
        DraftHouseSession session = requireSession(sessionId);
        session.documentSet().remove(path);
        return "Document removed: " + path;
    }

    @Tool(description = "List documents in the session's working set.")
    String list_session_documents(@ToolArg(description = "Session ID") String sessionId) {
        DraftHouseSession session = requireSession(sessionId);
        var docs = session.documentSet().documents();
        if (docs.isEmpty()) return "No documents in session.";
        return docs.stream()
            .map(d -> "- " + d.path() + " (" + d.label() + ")")
            .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Set the comparison pair for the session.")
    String set_session_comparison(
            @ToolArg(description = "Session ID") String sessionId,
            @ToolArg(description = "Path A") String pathA,
            @ToolArg(description = "Path B") String pathB) {
        DraftHouseSession session = requireSession(sessionId);
        session.documentSet().setComparison(pathA, pathB);
        return "Comparison set: " + pathA + " vs " + pathB;
    }

    private DraftHouseSession requireSession(String sessionId) {
        return registry.find(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }
}
