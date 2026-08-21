package io.casehub.drafthouse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DraftHouseSessionRegistryTest {

    DraftHouseSessionRegistry registry;

    @BeforeEach
    void setup() {
        registry = new DraftHouseSessionRegistry(new NoOpDraftHouseSessionStore());
    }

    @Test
    void createAndFind() {
        DraftHouseSession session = registry.create("s1");
        assertEquals("s1", session.id());
        assertTrue(registry.find("s1").isPresent());
    }

    @Test
    void duplicateCreateThrows() {
        registry.create("s1");
        assertThrows(IllegalStateException.class, () -> registry.create("s1"));
    }

    @Test
    void removeSession() {
        registry.create("s1");
        registry.remove("s1");
        assertTrue(registry.find("s1").isEmpty());
    }

    @Test
    void removeDeactivatesFacets() {
        DraftHouseSession session = registry.create("s1");
        var deactivated = new java.util.concurrent.atomic.AtomicBoolean(false);
        session.activateFacet(new Facet() {
            @Override public String name() { return "test"; }
            @Override public void activate(DraftHouseSession s) {}
            @Override public void deactivate(DraftHouseSession s) { deactivated.set(true); }
            @Override public java.util.List<ArtifactSpec> inputs() { return java.util.List.of(); }
            @Override public java.util.List<ArtifactSpec> outputs() { return java.util.List.of(); }
        });
        registry.remove("s1");
        assertTrue(deactivated.get());
    }

    @Test
    void activeSessionsReturnsAll() {
        registry.create("s1");
        registry.create("s2");
        assertEquals(2, registry.activeSessions().size());
    }

    @Test
    void removeNonExistentIsNoOp() {
        registry.remove("nonexistent");
    }
}
