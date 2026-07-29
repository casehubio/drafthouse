package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;
import io.casehub.qhorus.runtime.instance.InstanceService;

import java.util.List;

final class DebateParticipants {

    private DebateParticipants() {}

    static String ensureSender(DebateSession session, AgentType role,
                               InstanceService instanceService,
                               DebateSessionRegistry registry) {
        String existing = session.instanceIdFor(role);
        String instanceId = session.registerIfAbsent(role, () -> {
            String id = DebateSession.instanceId(role, session.debateSessionId());
            instanceService.register(id,
                    "DraftHouse " + role.name().toLowerCase() + " " + session.debateSessionId(),
                    List.of("document-debate-" + role.name().toLowerCase()));
            return id;
        });
        if (existing == null) {
            registry.persist(session);
        }
        return instanceId;
    }
}
