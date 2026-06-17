package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DebateSessionRegistryTest {

    private DebateSessionRegistryImpl registry;

    @BeforeEach
    void setUp() {
        registry = new DebateSessionRegistryImpl();
    }

    @Test
    void activeSessions_empty_returnsEmptyCollection() {
        Collection<DebateSession> sessions = registry.activeSessions();
        assertThat(sessions).isEmpty();
    }

    @Test
    void activeSessions_afterPut_containsSession() {
        UUID channelId = UUID.randomUUID();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                "drafthouse/debate/d-test");
        session.documentSet().add("test-spec.md", "spec");
        registry.put(session);

        Collection<DebateSession> sessions = registry.activeSessions();
        assertThat(sessions).hasSize(1);
        assertThat(sessions.iterator().next().channelId()).isEqualTo(channelId);
    }

    @Test
    void activeSessions_afterRemove_doesNotContainSession() {
        UUID channelId = UUID.randomUUID();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                "drafthouse/debate/d-test");
        session.documentSet().add("test-spec.md", "spec");
        registry.put(session);
        registry.remove(channelId);

        Collection<DebateSession> sessions = registry.activeSessions();
        assertThat(sessions).isEmpty();
    }
}
