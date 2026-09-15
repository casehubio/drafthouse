package io.casehub.drafthouse;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Unified session container — owns shared state and manages independently activatable facets. */
public class DraftHouseSession {

    private final String id;
    private final Instant created;
    private final DocumentSet documentSet;
    private final Map<String, Facet> activeFacets = new ConcurrentHashMap<>();
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();
    private volatile Path workingDirectory;

    public DraftHouseSession(String id) {
        this.id = id;
        this.created = Instant.now();
        this.documentSet = new DocumentSet();
    }

    public String id() { return id; }
    public Instant created() { return created; }
    public DocumentSet documentSet() { return documentSet; }
    public Map<String, Object> metadata() { return metadata; }

    public Path workingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(Path dir) { this.workingDirectory = dir; }

    public void activateFacet(Facet facet) {
        if (activeFacets.putIfAbsent(facet.name(), facet) != null) {
            throw new IllegalStateException("Facet already active: " + facet.name());
        }
        facet.activate(this);
    }

    public void deactivateFacet(String name) {
        Facet facet = activeFacets.remove(name);
        if (facet == null) {
            throw new IllegalArgumentException("Facet not active: " + name);
        }
        facet.deactivate(this);
    }

    public Optional<Facet> findFacet(String name) {
        return Optional.ofNullable(activeFacets.get(name));
    }

    public Map<String, Facet> activeFacets() {
        return Collections.unmodifiableMap(activeFacets);
    }
}
