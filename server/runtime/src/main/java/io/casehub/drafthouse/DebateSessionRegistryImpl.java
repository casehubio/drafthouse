package io.casehub.drafthouse;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory registry of active debate sessions, keyed by Qhorus channel ID.
 * Thread-safe via ConcurrentHashMap.
 */
@ApplicationScoped
public class DebateSessionRegistryImpl implements DebateSessionRegistry {

    private final ConcurrentHashMap<UUID, DebateSession> sessions = new ConcurrentHashMap<>();

    @Override
    public Optional<DebateSession> find(final UUID channelId) {
        return Optional.ofNullable(sessions.get(channelId));
    }

    @Override
    public void put(final DebateSession session) {
        sessions.put(session.channelId(), session);
    }

    @Override
    public void remove(final UUID channelId) {
        sessions.remove(channelId);
    }

    @Override
    public Collection<DebateSession> activeSessions() {
        return List.copyOf(sessions.values());
    }
}
