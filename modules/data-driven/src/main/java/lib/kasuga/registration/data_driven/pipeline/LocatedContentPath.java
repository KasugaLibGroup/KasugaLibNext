package lib.kasuga.registration.data_driven.pipeline;

import lib.kasuga.registration.data_driven.source.LogicalPath;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;

import java.util.Objects;

/**
 * One content path declared by an index manifest.
 */
public record LocatedContentPath(
        SourceDescriptor source,
        int sourceOrder,
        LogicalPath logicalPath,
        String jsonPointer,
        Domain domain
) {
    public LocatedContentPath {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(logicalPath, "logicalPath");
        Objects.requireNonNull(jsonPointer, "jsonPointer");
        Objects.requireNonNull(domain, "domain");
        if (sourceOrder < 0) throw new IllegalArgumentException("sourceOrder must not be negative");
    }

    public enum Domain {
        REGISTER,
        RELOAD
    }
}
