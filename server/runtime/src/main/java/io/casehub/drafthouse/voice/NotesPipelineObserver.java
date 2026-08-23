package io.casehub.drafthouse.voice;

import io.casehub.drafthouse.DraftHouseSession;
import io.casehub.drafthouse.DraftHouseSessionRegistry;
import io.casehub.drafthouse.WebSocketEventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class NotesPipelineObserver {

    private static final Logger LOG = Logger.getLogger(NotesPipelineObserver.class.getName());

    @Inject NotesPipeline pipeline;
    @Inject VaultWriter vaultWriter;
    @Inject DraftHouseSessionRegistry registry;
    @Inject WebSocketEventBus eventBus;

    public void onTranscript(@ObservesAsync TranscriptReady event) {
        try {
            var session = registry.find(event.sessionId()).orElse(null);
            if (session == null) {
                LOG.warning("Session not found for transcript event: " + event.sessionId());
                return;
            }

            var rawText = Files.readString(event.transcriptPath());
            var result = pipeline.process(rawText, event.goalTag());

            var vaultPath = resolveVaultPath(session);
            var notePath = vaultWriter.writeNote(
                    result, event.transcriptPath(), vaultPath,
                    "voice", 0, event.sessionId(), event.goalTag());

            eventBus.pushVoiceEvent(event.sessionId(), "note-created",
                    Map.of("path", notePath.toString(),
                            "title", result.title(),
                            "summary", result.summary() != null ? result.summary() : ""));

            LOG.info("Note created: " + notePath);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Pipeline failed for session " + event.sessionId(), e);
            eventBus.pushVoiceEvent(event.sessionId(), "pipeline-error",
                    Map.of("error", e.getMessage(),
                            "stage", "cleanup"));
        }
    }

    private Path resolveVaultPath(DraftHouseSession session) {
        var configured = (String) session.metadata().get("vault.path");
        if (configured != null) return Path.of(configured);
        return Path.of(System.getProperty("user.home"), ".drafthouse", "vault");
    }
}
