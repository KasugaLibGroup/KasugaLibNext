package lib.kasuga.registration.data_driven.source;

import net.neoforged.neoforgespi.locating.IModFile;
import lib.kasuga.core.resource.source.ResourceSnapshot;
import lib.kasuga.core.resource.source.ResourceSourceDescriptor;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Collection;
import java.util.Objects;

/**
 * Factories for the built-in content source adapters.
 */
public final class ContentSourceFactory {
    private ContentSourceFactory() {
    }

    public static ContentSource directory(Path root, SourceDescriptor descriptor) throws IOException {
        return DirectoryContentSource.open(root, descriptor);
    }

    public static ContentSource directory(Path root, SourceDescriptor descriptor, SourceLimits limits)
            throws IOException {
        return DirectoryContentSource.open(root, descriptor, limits);
    }

    public static ContentSource zip(Path archive, SourceDescriptor descriptor) throws IOException {
        return ZipContentSource.open(archive, descriptor);
    }

    public static ContentSource zip(Path archive, SourceDescriptor descriptor, SourceLimits limits)
            throws IOException {
        return ZipContentSource.open(archive, descriptor, limits);
    }

    public static ContentSource modJar(IModFile modFile, SourceDescriptor descriptor) throws IOException {
        return ModJarContentSource.open(modFile, descriptor);
    }

    public static ContentSource modJar(IModFile modFile, SourceDescriptor descriptor, SourceLimits limits)
            throws IOException {
        return ModJarContentSource.open(modFile, descriptor, limits);
    }

    public static ContentSource snapshot(
            ResourceSnapshot snapshot,
            ResourceSourceDescriptor resource,
            Collection<String> namespaces,
            String diagnosticLabel
    ) {
        return SnapshotContentSource.open(snapshot, resource, namespaces, diagnosticLabel);
    }

    public static ContentSource snapshot(
            ResourceSnapshot snapshot,
            ResourceSourceDescriptor resource,
            Collection<String> namespaces,
            String diagnosticLabel,
            Collection<String> declaredIndexes
    ) {
        return SnapshotContentSource.open(
                snapshot,
                resource,
                namespaces,
                diagnosticLabel,
                declaredIndexes
        );
    }

    /**
     * Opens a directory or a ZIP/JAR archive selected from the physical path.
     */
    public static ContentSource open(Path path, SourceDescriptor descriptor) throws IOException {
        return open(path, descriptor, SourceLimits.DEFAULT);
    }

    public static ContentSource open(Path path, SourceDescriptor descriptor, SourceLimits limits)
            throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(limits, "limits");

        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.NOT_FOUND,
                    descriptor,
                    null,
                    "content source does not exist: " + path,
                    exception
            );
        } catch (AccessDeniedException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.ACCESS_DENIED,
                    descriptor,
                    null,
                    "cannot access content source: " + path,
                    exception
            );
        } catch (IOException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.IO_ERROR,
                    descriptor.sourceId(),
                    null,
                    "cannot inspect content source: " + path,
                    exception
            );
        }
        if (attributes.isSymbolicLink()) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.SYMLINK_REJECTED,
                    descriptor,
                    null,
                    "content source must not be a symbolic link: " + path
            );
        }
        if (attributes.isDirectory()) {
            return directory(path, descriptor, limits);
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (attributes.isRegularFile() && (name.endsWith(".zip") || name.endsWith(".jar"))) {
            return zip(path, descriptor, limits);
        }
        throw new SourceAccessException(
                SourceAccessException.Reason.INVALID_ENTRY,
                descriptor,
                null,
                "content source must be a directory, ZIP, or JAR: " + path
        );
    }
}
