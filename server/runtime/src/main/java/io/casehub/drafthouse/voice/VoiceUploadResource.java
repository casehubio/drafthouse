package io.casehub.drafthouse.voice;

import io.casehub.blocks.speech.SpeechToTextService;
import io.casehub.blocks.speech.TranscriptionOptions;
import io.casehub.drafthouse.DraftHouseSessionRegistry;
import io.casehub.drafthouse.WebSocketEventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestForm;

import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.logging.Logger;

@ApplicationScoped
@Path("/api/voice")
public class VoiceUploadResource {

    private static final Logger LOG = Logger.getLogger(VoiceUploadResource.class.getName());

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss").withZone(ZoneOffset.UTC);

    @Inject DraftHouseSessionRegistry registry;
    @Inject SpeechToTextService sttService;
    @Inject Event<TranscriptReady> transcriptReadyEvent;
    @Inject WebSocketEventBus eventBus;

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_PLAIN)
    public String upload(
            @RestForm String sessionId,
            @RestForm String goalTag,
            @RestForm("audio") InputStream audioStream) {
        try {
            var session = registry.find(sessionId).orElse(null);
            if (session == null) {
                return "Failed: session not found: " + sessionId;
            }
            if (session.findFacet("voice").isEmpty()) {
                return "Failed: voice facet is not active on session " + sessionId;
            }

            var workDir = session.workingDirectory();
            var timestamp = TS_FMT.format(Instant.now());

            var audioPath = workDir.resolve("audio-" + timestamp + ".webm");
            Files.copy(audioStream, audioPath);

            var result = sttService.transcribe(audioPath, TranscriptionOptions.defaults());

            var transcriptPath = workDir.resolve("raw-transcript-" + timestamp + ".md");
            Files.writeString(transcriptPath, result.text());

            transcriptReadyEvent.fireAsync(new TranscriptReady(sessionId, transcriptPath, goalTag));

            eventBus.pushVoiceEvent(sessionId, "voice-transcript-ready",
                    Map.of("text", result.text(),
                            "language", result.language(),
                            "confidence", result.confidence(),
                            "path", transcriptPath.toString()));

            return "Transcribed (" + result.language() + ", " +
                    String.format("%.0f%%", result.confidence() * 100) + "): " + result.text();
        } catch (Exception e) {
            LOG.warning("Voice upload failed for session " + sessionId + ": " + e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }
}
