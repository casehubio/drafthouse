package io.casehub.drafthouse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SessionMcpToolsActivateFacetTest {

    @TempDir Path workDir;
    DraftHouseSessionRegistry registry;
    SessionMcpTools tools;

    @BeforeEach
    void setUp() {
        registry = new DraftHouseSessionRegistry(new NoOpDraftHouseSessionStore());
        tools = new SessionMcpTools();
        tools.registry = registry;

        var session = registry.create("s1");
        session.setWorkingDirectory(workDir);
    }

    @Test
    void activateVoiceAlsoActivatesNotes() {
        var result = tools.activate_facet("s1", "voice");
        assertEquals("Facet activated: voice", result);
        var session = registry.find("s1").orElseThrow();
        assertTrue(session.findFacet("voice").isPresent());
        assertTrue(session.findFacet("notes").isPresent());
    }

    @Test
    void activateNotesAlone() {
        var result = tools.activate_facet("s1", "notes");
        assertEquals("Facet activated: notes", result);
        var session = registry.find("s1").orElseThrow();
        assertTrue(session.findFacet("notes").isPresent());
        assertTrue(session.findFacet("voice").isEmpty());
    }

    @Test
    void activateUnknownFacetReturnsError() {
        var result = tools.activate_facet("s1", "unknown");
        assertTrue(result.startsWith("Failed:"));
        assertTrue(result.contains("Unknown facet"));
    }

    @Test
    void activateOnUnknownSessionReturnsError() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.activate_facet("nonexistent", "voice"));
    }
}
