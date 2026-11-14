package lib.kasuga.registration.data_driven.source;

import lib.kasuga.core.resource.identity.ResourceUri;

import java.util.Set;
import java.util.Objects;

/**
 * One immutable entry in a source's logical file view.
 */
public record LocatedFile(
        SourceDescriptor source,
        LogicalPath path,
        long size,
        ResourceUri uri,
        String revision,
        Set<String> tags
) {
    public LocatedFile {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(revision, "revision");
        tags = Set.copyOf(tags);
        if (size < 0) {
            throw new IllegalArgumentException("file size must not be negative");
        }
        if (uri != null && (!uri.sourceId().equals(source.sourceId())
                || !uri.rootId().equals(source.rootId()))) {
            throw new IllegalArgumentException("Located file URI does not match its source");
        }
    }

    public LocatedFile(
            SourceDescriptor source,
            LogicalPath path,
            long size
    ) {
        this(
                source,
                path,
                size,
                null,
                "",
                Set.of()
        );
    }
}
