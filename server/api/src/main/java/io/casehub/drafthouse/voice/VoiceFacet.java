package io.casehub.drafthouse.voice;

import io.casehub.drafthouse.ArtifactSpec;
import io.casehub.drafthouse.DraftHouseSession;
import io.casehub.drafthouse.Facet;

import java.util.List;

public class VoiceFacet implements Facet {

    @Override
    public String name() {
        return "voice";
    }

    @Override
    public void activate(final DraftHouseSession session) {
        if (session.workingDirectory() == null) {
            throw new IllegalStateException("Session requires a working directory for voice capture");
        }
    }

    @Override
    public void deactivate(final DraftHouseSession session) {
    }

    @Override
    public List<ArtifactSpec> inputs() {
        return List.of();
    }

    @Override
    public List<ArtifactSpec> outputs() {
        return List.of(new ArtifactSpec("raw-transcript-*.md", "Raw STT transcript"));
    }
}
