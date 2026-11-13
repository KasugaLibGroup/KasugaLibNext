package lib.kasuga.core.resource.view;

import lib.kasuga.core.resource.identity.ResourcePaths;
import java.util.Objects;

public record ResourceKey(String logicalPath, String expectedType, String expectedSubtype) {
    public ResourceKey { ResourcePaths.path(logicalPath, false); Objects.requireNonNull(expectedType); Objects.requireNonNull(expectedSubtype); }
    public ResourceKey(String path, String type) { this(path, type, ""); }
    public void check(ResourceDescriptor descriptor) {
        if (!expectedType.isEmpty() && !expectedType.equals(descriptor.type())
                || !expectedSubtype.isEmpty() && !expectedSubtype.equals(descriptor.subtype()))
            throw new IllegalArgumentException("TYPE_MISMATCH: " + descriptor.uri());
    }
}
