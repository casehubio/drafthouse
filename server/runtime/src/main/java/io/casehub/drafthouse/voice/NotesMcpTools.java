package io.casehub.drafthouse.voice;

import io.casehub.drafthouse.DraftHouseSession;
import io.casehub.drafthouse.DraftHouseSessionRegistry;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

@ApplicationScoped
public class NotesMcpTools {

    @Inject DraftHouseSessionRegistry registry;

    @Tool(name = "list_notes",
          description = "List notes in the vault with filenames.")
    public String list_notes(
            @ToolArg(description = "Session ID") String sessionId) {
        try {
            var session = requireSessionWithNotes(sessionId);
            var notesDir = resolveVaultPath(session).resolve("notes");
            if (!Files.isDirectory(notesDir)) return "No notes found.";
            try (var stream = Files.list(notesDir)) {
                var notes = stream
                        .filter(p -> p.toString().endsWith(".md"))
                        .map(p -> "- " + p.getFileName())
                        .collect(Collectors.joining("\n"));
                return notes.isEmpty() ? "No notes found." : notes;
            }
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(name = "get_note",
          description = "Get a note's content and metadata.")
    public String get_note(
            @ToolArg(description = "Session ID") String sessionId,
            @ToolArg(description = "Note filename") String filename) {
        try {
            var session = requireSessionWithNotes(sessionId);
            var notePath = resolveVaultPath(session).resolve("notes").resolve(filename);
            if (!Files.exists(notePath)) return "Note not found: " + filename;
            return Files.readString(notePath);
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(name = "search_notes",
          description = "Search notes in the vault by content, tags, or title.")
    public String search_notes(
            @ToolArg(description = "Session ID") String sessionId,
            @ToolArg(description = "Search query") String query) {
        try {
            var session = requireSessionWithNotes(sessionId);
            var notesDir = resolveVaultPath(session).resolve("notes");
            if (!Files.isDirectory(notesDir)) return "No notes found.";
            try (var stream = Files.list(notesDir)) {
                var matches = stream
                        .filter(p -> p.toString().endsWith(".md"))
                        .filter(p -> {
                            try {
                                return Files.readString(p).toLowerCase().contains(query.toLowerCase());
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .map(p -> "- " + p.getFileName())
                        .collect(Collectors.joining("\n"));
                return matches.isEmpty() ? "No matching notes." : matches;
            }
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    private DraftHouseSession requireSessionWithNotes(String sessionId) {
        var session = registry.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + sessionId));
        if (session.findFacet("notes").isEmpty()) {
            throw new IllegalStateException("notes facet is not active on session " + sessionId);
        }
        return session;
    }

    private Path resolveVaultPath(DraftHouseSession session) {
        var configured = (String) session.metadata().get("vault.path");
        if (configured != null) return Path.of(configured);
        return Path.of(System.getProperty("user.home"), ".drafthouse", "vault");
    }
}
