package lib.kasuga.core.resource.source;

import lib.kasuga.core.resource.identity.ResourcePaths;
import java.util.Objects;

public record ResourceSourceDescriptor(String ownerId, String sourceId, String rootId, int rank, Origin origin) {
    public enum Origin { EXTERNAL, MC_MANAGED, DERIVED }
    public ResourceSourceDescriptor {
        ResourcePaths.id(ownerId);
        ResourcePaths.id(sourceId);
        ResourcePaths.id(rootId);
        Objects.requireNonNull(origin, "origin");
        if (rank < 0) {
            throw new IllegalArgumentException("source rank must not be negative");
        }
    }
}
