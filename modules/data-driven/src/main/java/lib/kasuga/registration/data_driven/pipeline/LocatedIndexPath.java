package lib.kasuga.registration.data_driven.pipeline;

import lib.kasuga.registration.data_driven.source.LogicalPath;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;

import java.util.Objects;

/** One approved index manifest that may participate in the reload domain. */
public record LocatedIndexPath(
        SourceDescriptor source,
        int sourceOrder,
        LogicalPath logicalPath
) {
    public LocatedIndexPath {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(logicalPath, "logicalPath");
        if (sourceOrder < 0) throw new IllegalArgumentException("sourceOrder must not be negative");
    }
}
