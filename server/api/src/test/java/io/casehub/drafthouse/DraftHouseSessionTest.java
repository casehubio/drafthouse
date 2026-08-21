package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DraftHouseSessionTest {

    @TempDir Path workDir;

    private final Facet stubFacet = new Facet() {
        boolean activated = false;
        @Override public String name() { return "test"; }
        @Override public void activate(DraftHouseSession s) { activated = true; }
        @Override public void deactivate(DraftHouseSession s) { activated = false; }
        @Override public List<ArtifactSpec> inputs() { return List.of(); }
        @Override public List<ArtifactSpec> outputs() { return List.of(); }
    };

    @Test
    void newSessionHasIdAndTimestamp() {
        var session = new DraftHouseSession("s1");
        assertEquals("s1", session.id());
        assertNotNull(session.created());
        assertTrue(session.activeFacets().isEmpty());
    }

    @Test
    void activateFacetCallsActivateAndRegisters() {
        var session = new DraftHouseSession("s1");
        session.activateFacet(stubFacet);
        assertTrue(session.findFacet("test").isPresent());
        assertEquals(1, session.activeFacets().size());
    }

    @Test
    void deactivateFacetCallsDeactivateAndRemoves() {
        var session = new DraftHouseSession("s1");
        session.activateFacet(stubFacet);
        session.deactivateFacet("test");
        assertTrue(session.findFacet("test").isEmpty());
    }

    @Test
    void duplicateActivationThrows() {
        var session = new DraftHouseSession("s1");
        session.activateFacet(stubFacet);
        assertThrows(IllegalStateException.class,
            () -> session.activateFacet(stubFacet));
    }

    @Test
    void deactivateUnknownFacetThrows() {
        var session = new DraftHouseSession("s1");
        assertThrows(IllegalArgumentException.class,
            () -> session.deactivateFacet("unknown"));
    }

    @Test
    void workingDirectoryIsSettable() {
        var session = new DraftHouseSession("s1");
        assertNull(session.workingDirectory());
        session.setWorkingDirectory(workDir);
        assertEquals(workDir, session.workingDirectory());
    }

    @Test
    void documentSetIsShared() {
        var session = new DraftHouseSession("s1");
        session.documentSet().add("/doc.md", "Doc");
        assertEquals(1, session.documentSet().documents().size());
    }

    @Test
    void activeFacetsReturnsUnmodifiableView() {
        var session = new DraftHouseSession("s1");
        session.activateFacet(stubFacet);
        assertThrows(UnsupportedOperationException.class,
            () -> session.activeFacets().put("x", stubFacet));
    }
}
