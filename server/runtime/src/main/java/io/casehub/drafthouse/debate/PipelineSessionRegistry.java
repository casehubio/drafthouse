package io.casehub.drafthouse.debate;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PipelineSessionRegistry {

    private final ConcurrentHashMap<String, PipelineSession> sessions = new ConcurrentHashMap<>();

    public void create(PipelineSession session) {
        sessions.put(session.pipelineId(), session);
    }

    public PipelineSession get(String pipelineId) {
        return sessions.get(pipelineId);
    }

    public void remove(String pipelineId) {
        sessions.remove(pipelineId);
    }
}
