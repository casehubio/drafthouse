package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;
import io.casehub.qhorus.runtime.instance.InstanceService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DebateParticipantsTest {

    @Test
    void ensureSender_registersOnFirstCall() {
        InstanceService instanceService = mock(InstanceService.class);
        DebateSessionRegistry registry = mock(DebateSessionRegistry.class);
        DebateSession session = new DebateSession(
                UUID.randomUUID(), "test-id", "test-channel", "agent-1");

        String result = DebateParticipants.ensureSender(
                session, AgentType.HUMAN, instanceService, registry);

        assertThat(result).startsWith("drafthouse-human-");
        verify(instanceService).register(anyString(), anyString(), anyList());
        verify(registry).persist(session);
    }

    @Test
    void ensureSender_returnsExistingOnSecondCall() {
        InstanceService instanceService = mock(InstanceService.class);
        DebateSessionRegistry registry = mock(DebateSessionRegistry.class);
        DebateSession session = new DebateSession(
                UUID.randomUUID(), "test-id", "test-channel", "agent-1");

        String first = DebateParticipants.ensureSender(
                session, AgentType.HUMAN, instanceService, registry);
        String second = DebateParticipants.ensureSender(
                session, AgentType.HUMAN, instanceService, registry);

        assertThat(second).isEqualTo(first);
        verify(instanceService, times(1)).register(anyString(), anyString(), anyList());
        verify(registry, times(1)).persist(session);
    }
}
