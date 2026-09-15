package io.casehub.drafthouse;

import java.util.Objects;

/** Declares an artifact a facet reads or writes, relative to the session working directory. */
public record ArtifactSpec(String pathPattern, String description, boolean required) {
    public ArtifactSpec {
        Objects.requireNonNull(pathPattern);
    }

    public ArtifactSpec(String pathPattern, String description) {
        this(pathPattern, description, false);
    }
}
