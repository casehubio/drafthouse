package io.casehub.drafthouse;

import java.util.List;

/** A composable session facet — independently activatable with its own tools, state, and artifacts. */
public interface Facet {
    String name();
    void activate(DraftHouseSession session);
    void deactivate(DraftHouseSession session);
    List<ArtifactSpec> inputs();
    List<ArtifactSpec> outputs();
}
