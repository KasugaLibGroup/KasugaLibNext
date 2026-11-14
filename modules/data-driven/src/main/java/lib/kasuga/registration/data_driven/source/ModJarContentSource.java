package lib.kasuga.registration.data_driven.source;

import net.neoforged.neoforgespi.locating.IModFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Content source adapter backed by the root of a NeoForge secure mod JAR.
 *
 * <p>The adapter snapshots the secure JAR's logical root but does not own or close the mod file's
 * filesystem. That filesystem is runtime-owned and may be shared by other mod-file consumers.
 */
public final class ModJarContentSource implements ContentSource {
    private final NioRootContentSource delegate;

    private ModJarContentSource(NioRootContentSource delegate) {
        this.delegate = delegate;
    }

    public static ModJarContentSource open(IModFile modFile, SourceDescriptor descriptor) throws IOException {
        return open(modFile, descriptor, SourceLimits.DEFAULT);
    }

    public static ModJarContentSource open(
            IModFile modFile,
            SourceDescriptor descriptor,
            SourceLimits limits
    ) throws IOException {
        Objects.requireNonNull(modFile, "modFile");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(limits, "limits");

        final Path root;
        try {
            root = modFile.getSecureJar().getRootPath();
        } catch (RuntimeException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.IO_ERROR,
                    descriptor.sourceId(),
                    null,
                    "cannot access secure mod JAR root",
                    exception
            );
        }
        return new ModJarContentSource(NioRootContentSource.open(root, descriptor, limits));
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
