package io.casehub.drafthouse;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@DefaultBean
@ApplicationScoped
public class NoOpDraftHouseSessionStore implements DraftHouseSessionStore {
    @Override public void save(SessionSnapshot snapshot) {}
    @Override public Optional<SessionSnapshot> load(String sessionId) { return Optional.empty(); }
    @Override public void remove(String sessionId) {}
    @Override public Collection<SessionSnapshot> loadAll() { return List.of(); }
}
