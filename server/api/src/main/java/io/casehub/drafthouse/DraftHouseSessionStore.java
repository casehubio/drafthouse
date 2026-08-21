package io.casehub.drafthouse;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persists session metadata, active facet set, and working directory path. */
public interface DraftHouseSessionStore {
    void save(SessionSnapshot snapshot);
    Optional<SessionSnapshot> load(String sessionId);
    void remove(String sessionId);
    Collection<SessionSnapshot> loadAll();

    record SessionSnapshot(
        String id,
        Instant created,
        Path workingDirectory,
        List<String> activeFacetNames,
        Map<String, Object> metadata
    ) {}
}
