package io.casehub.drafthouse.voice;

import io.casehub.drafthouse.DraftHouseSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class VoiceFacetTest {

    @TempDir Path workDir;

    @Test
    void nameReturnsVoice() {
        assertThat(new VoiceFacet().name()).isEqualTo("voice");
    }

    @Test
    void outputsDeclareRawTranscriptPattern() {
        final var facet = new VoiceFacet();
        assertThat(facet.outputs()).hasSize(1);
        assertThat(facet.outputs().get(0).pathPattern()).contains("raw-transcript");
    }

    @Test
    void inputsAreEmpty() {
        assertThat(new VoiceFacet().inputs()).isEmpty();
    }

    @Test
    void activateSucceedsWithWorkingDirectory() {
        final var facet = new VoiceFacet();
        final var session = new DraftHouseSession("s1");
        session.setWorkingDirectory(workDir);
        session.activateFacet(facet);
        assertThat(session.findFacet("voice")).isPresent();
    }

    @Test
    void activateFailsWithoutWorkingDirectory() {
        final var facet = new VoiceFacet();
        final var session = new DraftHouseSession("s1");
        assertThatIllegalStateException()
                .isThrownBy(() -> session.activateFacet(facet))
                .withMessageContaining("working directory");
    }

    @Test
    void deactivateRemovesFacet() {
        final var facet = new VoiceFacet();
        final var session = new DraftHouseSession("s1");
        session.setWorkingDirectory(workDir);
        session.activateFacet(facet);
        session.deactivateFacet("voice");
        assertThat(session.findFacet("voice")).isEmpty();
    }
}
