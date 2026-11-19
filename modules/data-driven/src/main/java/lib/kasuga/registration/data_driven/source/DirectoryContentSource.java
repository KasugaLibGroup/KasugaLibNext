package lib.kasuga.registration.data_driven.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Content source adapter for an unpacked directory.
 */
public final class DirectoryContentSource implements ContentSource {
    private final NioRootContentSource delegate;

    private DirectoryContentSource(NioRootContentSource delegate) {
        this.delegate = delegate;
    }

    public static DirectoryContentSource open(Path root, SourceDescriptor descriptor) throws IOException {
        return open(root, descriptor, SourceLimits.DEFAULT);
    }

    public static DirectoryContentSource open(
            Path root,
            SourceDescriptor descriptor,
            SourceLimits limits
    ) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(limits, "limits");
        return new DirectoryContentSource(NioRootContentSource.open(root, descriptor, limits));
    }

    @Override
    public SourceDescriptor descriptor() {
        return delegate.descriptor();
    }

    @Override
    public Optional<InputStream> open(LogicalPath path) throws IOException {
        return delegate.open(path);
    }

    @Override
    public boolean exists(LogicalPath path) {
        return delegate.exists(path);
    }

    @Override
    public List<LocatedFile> files() {
        return delegate.files();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
