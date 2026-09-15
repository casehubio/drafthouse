package io.casehub.drafthouse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Manages active DraftHouseSession instances. */
@ApplicationScoped
public class DraftHouseSessionRegistry {

    private final ConcurrentHashMap<String, DraftHouseSession> sessions = new ConcurrentHashMap<>();
    private final DraftHouseSessionStore store;

    @Inject
    public DraftHouseSessionRegistry(DraftHouseSessionStore store) {
        this.store = store;
    }

    public DraftHouseSession create(String sessionId) {
        DraftHouseSession session = new DraftHouseSession(sessionId);
        if (sessions.putIfAbsent(sessionId, session) != null) {
            throw new IllegalStateException("Session already exists: " + sessionId);
        }
        return session;
    }

    public Optional<DraftHouseSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void remove(String sessionId) {
        DraftHouseSession session = sessions.remove(sessionId);
        if (session != null) {
            new ArrayList<>(session.activeFacets().keySet())
                .forEach(session::deactivateFacet);
            store.remove(sessionId);
        }
    }

    public Collection<DraftHouseSession> activeSessions() {
        return sessions.values();
    }
}
