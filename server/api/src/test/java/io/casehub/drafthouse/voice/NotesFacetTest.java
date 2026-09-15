package io.casehub.drafthouse.voice;

import io.casehub.drafthouse.DraftHouseSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class NotesFacetTest {

    @TempDir Path workDir;

    @Test
    void nameReturnsNotes() {
        assertThat(new NotesFacet().name()).isEqualTo("notes");
    }

    @Test
    void inputsDeclareRawTranscriptPattern() {
        final var facet = new NotesFacet();
        assertThat(facet.inputs()).hasSize(1);
        assertThat(facet.inputs().get(0).pathPattern()).contains("raw-transcript");
    }

    @Test
    void outputsDeclareNotesPattern() {
        final var facet = new NotesFacet();
        assertThat(facet.outputs()).hasSize(1);
        assertThat(facet.outputs().get(0).pathPattern()).contains("notes/");
    }

    @Test
    void activateSucceedsWithWorkingDirectory() {
        final var facet = new NotesFacet();
        final var session = new DraftHouseSession("s1");
        session.setWorkingDirectory(workDir);
        session.activateFacet(facet);
        assertThat(session.findFacet("notes")).isPresent();
    }

    @Test
    void activateFailsWithoutWorkingDirectory() {
        final var facet = new NotesFacet();
        final var session = new DraftHouseSession("s1");
        assertThatIllegalStateException()
                .isThrownBy(() -> session.activateFacet(facet))
                .withMessageContaining("working directory");
    }
}
