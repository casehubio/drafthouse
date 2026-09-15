package io.casehub.drafthouse.voice;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class TranscriptReadyTest {

    @Test
    void rejectsNullSessionId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TranscriptReady(null, Path.of("/tmp/t.md"), null));
    }

    @Test
    void rejectsNullTranscriptPath() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TranscriptReady("s1", null, null));
    }

    @Test
    void storesAllFields() {
        final var event = new TranscriptReady("s1", Path.of("/tmp/t.md"), "prompt");
        assertThat(event.sessionId()).isEqualTo("s1");
        assertThat(event.transcriptPath()).isEqualTo(Path.of("/tmp/t.md"));
        assertThat(event.goalTag()).isEqualTo("prompt");
    }

    @Test
    void goalTagCanBeNull() {
        final var event = new TranscriptReady("s1", Path.of("/tmp/t.md"), null);
        assertThat(event.goalTag()).isNull();
    }
}
