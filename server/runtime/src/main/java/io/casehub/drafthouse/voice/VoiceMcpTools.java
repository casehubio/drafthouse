package io.casehub.drafthouse.voice;

import io.casehub.drafthouse.DraftHouseSession;
import io.casehub.drafthouse.DraftHouseSessionRegistry;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@ApplicationScoped
public class VoiceMcpTools {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    @Inject DraftHouseSessionRegistry registry;

    @Tool(name = "start_voice_capture",
          description = "Prepare a session for voice capture. Auto-creates a session with voice+notes facets if none provided. Returns upload instructions.")
    public String start_voice_capture(
            @ToolArg(description = "Session ID. Auto-created if omitted.") String sessionId,
            @ToolArg(description = "Working directory path (required for auto-session).") String workingDirectory) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                var id = "voice-" + TS_FMT.format(Instant.now());
                var session = registry.create(id);
                var dir = workingDirectory != null
                        ? Path.of(workingDirectory)
                        : Path.of(System.getProperty("user.home"), ".drafthouse", "sessions", id);
                session.setWorkingDirectory(dir);
                session.activateFacet(new VoiceFacet());
                session.activateFacet(new NotesFacet());
                return "Voice capture started (auto-session " + id + "). " +
                        "Upload audio to POST /api/voice/upload with sessionId=" + id;
            }

            var session = registry.find(sessionId).orElse(null);
            if (session == null) {
                return "Failed: session not found: " + sessionId;
            }
            if (session.findFacet("voice").isEmpty()) {
                return "Failed: voice facet is not active on session " + sessionId +
                        ". Activate it first with activate_facet.";
            }

            return "Voice capture started on session " + sessionId + ". " +
                    "Upload audio to POST /api/voice/upload with sessionId=" + sessionId;
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(name = "stop_voice_capture",
          description = "Stop the current voice capture. The browser sends the recorded audio to POST /api/voice/upload.")
    public String stop_voice_capture(
            @ToolArg(description = "Session ID") String sessionId) {
        return "Voice capture stopped. Upload the recorded audio to POST /api/voice/upload with sessionId=" + sessionId;
    }

    @Tool(name = "list_recordings",
          description = "List audio recordings in the session working directory.")
    public String list_recordings(
            @ToolArg(description = "Session ID") String sessionId) {
        try {
            var session = registry.find(sessionId).orElse(null);
            if (session == null) {
                return "Failed: session not found: " + sessionId;
            }
            if (session.findFacet("voice").isEmpty()) {
                return "Failed: voice facet is not active on session " + sessionId;
            }
            var workDir = session.workingDirectory();
            if (workDir == null) {
                return "No working directory set.";
            }
            try (var stream = java.nio.file.Files.list(workDir)) {
                var files = stream
                        .filter(p -> p.getFileName().toString().startsWith("audio-"))
                        .map(p -> "- " + p.getFileName())
                        .collect(java.util.stream.Collectors.joining("\n"));
                return files.isEmpty() ? "No recordings found." : files;
            }
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }
}
