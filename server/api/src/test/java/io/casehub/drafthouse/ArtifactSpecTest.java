package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactSpecTest {

    @Test
    void twoArgConstructorDefaultsRequiredToFalse() {
        var spec = new ArtifactSpec("notes/accumulated.md", "Accumulated voice notes");
        assertEquals("notes/accumulated.md", spec.pathPattern());
        assertEquals("Accumulated voice notes", spec.description());
        assertFalse(spec.required());
    }

    @Test
    void threeArgConstructorSetsRequired() {
        var spec = new ArtifactSpec("stages/*.md", "Draft stages", true);
        assertTrue(spec.required());
    }

    @Test
    void pathPatternIsRequired() {
        assertThrows(NullPointerException.class,
            () -> new ArtifactSpec(null, "desc"));
    }
}
