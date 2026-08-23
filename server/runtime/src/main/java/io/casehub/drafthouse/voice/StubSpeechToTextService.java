package io.casehub.drafthouse.voice;

import io.casehub.blocks.speech.SpeechToTextService;
import io.casehub.blocks.speech.TranscriptionOptions;
import io.casehub.blocks.speech.TranscriptionResult;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;

@ApplicationScoped
@DefaultBean
public class StubSpeechToTextService implements SpeechToTextService {
    @Override
    public TranscriptionResult transcribe(Path audioFile, TranscriptionOptions options) {
        return new TranscriptionResult(
                "[stub] Transcription of " + audioFile.getFileName(), "en", 1.0);
    }
}
