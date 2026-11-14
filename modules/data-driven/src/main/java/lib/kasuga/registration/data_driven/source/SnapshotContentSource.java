package lib.kasuga.registration.data_driven.source;

import lib.kasuga.core.resource.identity.ResourceUri;
import lib.kasuga.core.resource.source.ResourceSnapshot;
import lib.kasuga.core.resource.source.ResourceSourceDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Registration view over one captured resource-provider root.
 *
 * <p>The wrapper owns the supplied snapshot and reads files by their concrete URI-backed provider
 * descriptor, rather than resolving a global resource-stack winner.
 */
public final class SnapshotContentSource implements ContentSource {
    private final ResourceSnapshot snapshot;
    private final SourceDescriptor descriptor;
    private final List<LocatedFile> files;
    private final Map<String, ResourceSnapshot.LocatedResourceFile> filesByPath;
    private final Set<String> declaredIndexes;
    private boolean closed;

    private SnapshotContentSource(
            ResourceSnapshot snapshot,
            ResourceSourceDescriptor resource,
            Collection<String> namespaces,
            String diagnosticLabel,
            Collection<String> declaredIndexes
    ) {
        this.snapshot = snapshot;
        this.descriptor = SourceDescriptor.of(resource, namespaces, diagnosticLabel);
        this.declaredIndexes = declaredIndexes == null ? null : Set.copyOf(declaredIndexes);
        this.filesByPath = new LinkedHashMap<>();
        for (ResourceSnapshot.LocatedResourceFile file : snapshot.files()) {
            if (filesByPath.putIfAbsent(file.descriptor().logicalPath(), file) != null) {
                throw new IllegalArgumentException(
                        "Snapshot contains duplicate logical path: "
                                + file.descriptor().logicalPath());
            }
        }
        this.files = filesByPath.values().stream()
                .map(file -> {
                    var resourceDescriptor = file.descriptor();
                    if (!resourceDescriptor.source().ownerId().equals(resource.ownerId())
                            || !resourceDescriptor.source().sourceId().equals(resource.sourceId())
                            || !resourceDescriptor.source().rootId().equals(resource.rootId())) {
                        throw new IllegalArgumentException(
                                "Snapshot contains a foreign provider: " + resourceDescriptor.uri());
                    }
                    return new LocatedFile(
                            this.descriptor,
                            LogicalPath.of(resourceDescriptor.logicalPath()),
                            resourceDescriptor.size(),
                            resourceDescriptor.uri(),
                            resourceDescriptor.revision(),
                            resourceDescriptor.tags()
                    );
                })
                .sorted(java.util.Comparator.comparing(file -> file.path().value()))
                .toList();
    }

    public static SnapshotContentSource open(
            ResourceSnapshot snapshot,
            ResourceSourceDescriptor resource,
            Collection<String> namespaces,
            String diagnosticLabel,
            Collection<String> declaredIndexes
    ) {
        return new SnapshotContentSource(
                Objects.requireNonNull(snapshot, "snapshot"),
                Objects.requireNonNull(resource, "resource"),
                namespaces,
                diagnosticLabel,
                declaredIndexes
        );
    }

    public static SnapshotContentSource open(
            ResourceSnapshot snapshot,
            ResourceSourceDescriptor resource,
            Collection<String> namespaces,
            String diagnosticLabel
    ) {
        return open(snapshot, resource, namespaces, diagnosticLabel, null);
    }

    @Override
    public SourceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Optional<InputStream> open(LogicalPath path) throws IOException {
        ensureOpen();
        ResourceSnapshot.LocatedResourceFile file = filesByPath.get(path.value());
        return file == null ? Optional.empty() : Optional.of(file.open());
    }

    @Override
    public boolean exists(LogicalPath path) {
        ensureOpen();
        return filesByPath.containsKey(path.value());
    }

    @Override
    public List<LocatedFile> files() {
        ensureOpen();
        return files;
    }

    @Override
    public List<LocatedFile> indexes() {
        if (declaredIndexes == null) {
            return ContentSource.super.indexes();
        }
        return files.stream()
                .filter(file -> declaredIndexes.contains(file.path().value()))
                .sorted(java.util.Comparator.comparing(file -> file.path().value()))
                .toList();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            snapshot.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "content source is closed: " + descriptor.diagnosticLabel());
        }
    }
}
