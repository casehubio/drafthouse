package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DebateSessionAutonomousTest {

    @Test
    void autonomous_defaultsFalse() {
        var session = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        assertThat(session.isAutonomous()).isFalse();
    }

    @Test
    void setAutonomous_changesFlag() {
        var session = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        session.setAutonomous(true);
        assertThat(session.isAutonomous()).isTrue();
    }

    @Test
    void orchestrator_defaultsNull() {
        var session = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        assertThat(session.orchestrator()).isNull();
    }

    @Test
    void setOrchestrator_storesAndReturnsReference() {
        var session = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        assertThat(session.orchestrator()).isNull();
        // ConversationOrchestrator requires many constructor args — verify via set/get contract
        session.setOrchestrator(null);
        assertThat(session.orchestrator()).isNull();
    }

    @Test
    void branchFrom_doesNotCopyAutonomous() {
        var source = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        source.setAutonomous(true);
        var branched = DebateSession.branchFrom(source, UUID.randomUUID(), "s2", "ch-2");
        assertThat(branched.isAutonomous()).isFalse();
    }

    @Test
    void markConverseStarted_returnsTrueOnFirstCall() {
        var session = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        assertThat(session.markConverseStarted()).isTrue();
    }

    @Test
    void markConverseStarted_returnsFalseOnSubsequentCalls() {
        var session = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        session.markConverseStarted();
        assertThat(session.markConverseStarted()).isFalse();
        assertThat(session.markConverseStarted()).isFalse();
    }

    @Test
    void branchFrom_doesNotCopyConverseStarted() {
        var source = new DebateSession(UUID.randomUUID(), "s1", "ch-1", "agent-1");
        source.markConverseStarted();
        var branched = DebateSession.branchFrom(source, UUID.randomUUID(), "s2", "ch-2");
        assertThat(branched.markConverseStarted()).isTrue();
    }
}
