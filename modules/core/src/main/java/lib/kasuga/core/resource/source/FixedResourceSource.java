package lib.kasuga.core.resource.source;

import java.io.IOException;
import java.util.Objects;

/**
 * Repeats capture from one immutable startup snapshot instead of reading the original source again.
 *
 * <p>The retained snapshot is process-owned. Every capture returns an independent snapshot that
 * callers may retire without invalidating later captures from this source.
 */
public final class FixedResourceSource implements ResourceSource {
    private final ResourceSourceDescriptor descriptor;
    private final ResourceSnapshot baseline;
    private final InputPolicy policy;
    private boolean closed;

    FixedResourceSource(
            ResourceSourceDescriptor descriptor,
            ResourceSnapshot baseline,
            InputPolicy policy
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.baseline = Objects.requireNonNull(baseline, "baseline");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public ResourceSourceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public synchronized ResourceSnapshot capture() throws IOException {
        if (closed) {
            throw new IOException("SOURCE_UNAVAILABLE: fixed source closed");
        }
        try (ResourceSnapshot.Builder builder = new ResourceSnapshot.Builder(descriptor, policy)) {
            for (ResourceSnapshot.LocatedResourceFile file : baseline.files()) {
                ResourceSourceDescriptor source = file.descriptor().source();
                if (!source.ownerId().equals(descriptor.ownerId())
                        || !source.sourceId().equals(descriptor.sourceId())
                        || !source.rootId().equals(descriptor.rootId())) {
                    throw new IOException("INVALID_SOURCE: baseline contains a foreign provider");
                }
                builder.copy(file);
            }
            return builder.build();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            baseline.close();
        }
    }
}
