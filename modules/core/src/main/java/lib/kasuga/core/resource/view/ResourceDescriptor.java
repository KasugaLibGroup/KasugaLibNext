package lib.kasuga.core.resource.view;

import lib.kasuga.core.resource.identity.ResourcePaths;
import lib.kasuga.core.resource.identity.ResourceUri;
import lib.kasuga.core.resource.source.ResourceSourceDescriptor;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record ResourceDescriptor(ResourceUri uri, ResourceSourceDescriptor source, String logicalPath,
                                 String type, String subtype, ResourceTypeDetector.Evidence typeEvidence,
                                 Set<String> tags, String revision, long size) {
    public ResourceDescriptor {
        Objects.requireNonNull(uri); Objects.requireNonNull(source);
        ResourcePaths.path(logicalPath, false);
        Objects.requireNonNull(type); Objects.requireNonNull(subtype); Objects.requireNonNull(typeEvidence); Objects.requireNonNull(revision);
        tags = Set.copyOf(tags);
        if (!uri.sourceId().equals(source.sourceId()) || !uri.rootId().equals(source.rootId()) || size < 0)
            throw new IllegalArgumentException("Inconsistent provider identity");
    }

    public static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < path.lastIndexOf('/') || dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public java.util.Optional<ResourceUri> parentUri() {
        return uri.childKey().isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(uri.parent());
    }
}
