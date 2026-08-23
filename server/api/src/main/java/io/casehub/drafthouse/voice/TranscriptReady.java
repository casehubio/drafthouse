package io.casehub.drafthouse.voice;

import java.nio.file.Path;
import java.util.Objects;

public record TranscriptReady(String sessionId, Path transcriptPath, String goalTag) {
    public TranscriptReady {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(transcriptPath, "transcriptPath");
    }
}
