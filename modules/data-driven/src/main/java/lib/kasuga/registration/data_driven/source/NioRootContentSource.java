package lib.kasuga.registration.data_driven.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared implementation for sources backed by a read-only NIO root.
 */
final class NioRootContentSource implements ContentSource {
    private final SourceDescriptor descriptor;
    private final SourceLimits limits;
    private final Path root;
    private final Map<LogicalPath, LocatedFile> fileMap;
    private final List<LocatedFile> files;
    private boolean closed;

    private NioRootContentSource(
            SourceDescriptor descriptor,
            SourceLimits limits,
            Path root,
            Map<LogicalPath, LocatedFile> fileMap
    ) {
        this.descriptor = descriptor;
        this.limits = limits;
        this.root = root;
        this.fileMap = Map.copyOf(fileMap);
        this.files = fileMap.keySet().stream()
                .sorted()
                .map(fileMap::get)
                .toList();
    }

    static NioRootContentSource open(
            Path root,
            SourceDescriptor descriptor,
            SourceLimits limits
    ) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        BasicFileAttributes rootAttributes;
        try {
            rootAttributes = Files.readAttributes(
                    normalizedRoot,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
        } catch (NoSuchFileException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.NOT_FOUND,
                    descriptor,
                    null,
                    "content root does not exist: " + normalizedRoot,
                    exception
            );
        } catch (AccessDeniedException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.ACCESS_DENIED,
                    descriptor,
                    null,
                    "cannot access content root: " + normalizedRoot,
                    exception
            );
        } catch (IOException exception) {
            throw ioFailure(descriptor, null, "cannot inspect content root: " + normalizedRoot, exception);
        }
        if (rootAttributes.isSymbolicLink()) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.SYMLINK_REJECTED,
                    descriptor,
                    null,
                    "content root must not be a symbolic link: " + normalizedRoot
            );
        }
        if (!rootAttributes.isDirectory()) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.INVALID_ENTRY,
                    descriptor,
                    null,
                    "content root is not a directory: " + normalizedRoot
            );
        }

        Map<LogicalPath, LocatedFile> fileMap = new LinkedHashMap<>();
        long[] totalBytes = {0};
        try {
            Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    if (attributes.isSymbolicLink() || Files.isSymbolicLink(directory)) {
                        throw symlink(descriptor, directory);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                        throw symlink(descriptor, file);
                    }
                    if (!attributes.isRegularFile()) {
                        throw new SourceAccessException(
                                SourceAccessException.Reason.INVALID_ENTRY,
                                descriptor,
                                null,
                                "source contains a non-regular file: " + file
                        );
                    }

                    LogicalPath path = logicalPath(normalizedRoot, file, descriptor);
                    if (fileMap.size() >= limits.maxFiles()) {
                        throw limit(descriptor, path, "source exceeds the " + limits.maxFiles()
                                + " file limit");
                    }
                    long size = attributes.size();
                    if (size > limits.limitFor(path)) {
                        throw limit(descriptor, path, "file exceeds the "
                                + limits.limitFor(path) + " byte limit");
                    }
                    try {
                        totalBytes[0] = Math.addExact(totalBytes[0], size);
                    } catch (ArithmeticException exception) {
                        throw limit(descriptor, path, "source byte count overflowed");
                    }
                    if (totalBytes[0] > limits.maxTotalBytes()) {
                        throw limit(descriptor, path, "source exceeds the "
                                + limits.maxTotalBytes() + " byte total limit");
                    }
                    fileMap.put(path, new LocatedFile(descriptor, path, size));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (AccessDeniedException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.ACCESS_DENIED,
                    descriptor,
                    null,
                    "cannot enumerate content root: " + normalizedRoot,
                    exception
            );
        } catch (SourceAccessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw ioFailure(descriptor, null, "cannot enumerate content root: " + normalizedRoot, exception);
        }
        return new NioRootContentSource(descriptor, limits, normalizedRoot, fileMap);
    }

    @Override
    public SourceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Optional<InputStream> open(LogicalPath path) throws IOException {
        ensureOpenForAccess();
        LocatedFile located = fileMap.get(path);
        if (located == null) {
            return Optional.empty();
        }

        ensureNoSymlink(path);
        Path physicalPath = path.resolve(root);
        try {
            InputStream input;
            try {
                input = Files.newInputStream(physicalPath, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException unsupported) {
                // The FML union filesystem exposes read-only streams but rejects the extra
                // NOFOLLOW option. Symlinks were checked immediately above and again during the
                // source snapshot, so the fallback remains within the already validated file set.
                input = Files.newInputStream(physicalPath);
            }
            return Optional.of(new LimitedInputStream(
                    input,
                    descriptor,
                    path,
                    limits.limitFor(path)
            ));
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (AccessDeniedException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.ACCESS_DENIED,
                    descriptor,
                    path,
                    "cannot read logical file: " + path,
                    exception
            );
        } catch (IOException exception) {
            throw ioFailure(descriptor, path, "cannot read logical file: " + path, exception);
        }
    }

    @Override
    public boolean exists(LogicalPath path) {
        ensureOpen();
        return fileMap.containsKey(path);
    }

    @Override
    public List<LocatedFile> files() {
        ensureOpen();
        return files;
    }

    @Override
    public void close() {
        closed = true;
    }

    private void ensureNoSymlink(LogicalPath path) throws SourceAccessException {
        Path current = root;
        for (String segment : path.segments()) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw symlink(descriptor, current);
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("content source is closed: " + descriptor.diagnosticLabel());
        }
    }

    private void ensureOpenForAccess() throws SourceAccessException {
        if (closed) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.SOURCE_CLOSED,
                    descriptor,
                    null,
                    "content source is closed"
            );
        }
    }

    private static LogicalPath logicalPath(Path root, Path file, SourceDescriptor descriptor)
            throws SourceAccessException {
        Path relative = root.relativize(file);
        List<String> segments = new ArrayList<>();
        for (Path segment : relative) {
            segments.add(segment.toString());
        }
        try {
            return LogicalPath.of(String.join("/", segments));
        } catch (IllegalArgumentException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.INVALID_ENTRY,
                    descriptor,
                    null,
                    "source contains an invalid logical path: " + relative,
                    exception
            );
        }
    }

    private static SourceAccessException symlink(SourceDescriptor descriptor, Path path) {
        return new SourceAccessException(
                SourceAccessException.Reason.SYMLINK_REJECTED,
                descriptor,
                null,
                "source contains a symbolic link: " + path
        );
    }

    private static SourceAccessException limit(
            SourceDescriptor descriptor,
            LogicalPath path,
            String message
    ) {
        return new SourceAccessException(
                SourceAccessException.Reason.LIMIT_EXCEEDED,
                descriptor,
                path,
                message + ": " + path
        );
    }

    static SourceAccessException ioFailure(
            SourceDescriptor descriptor,
            LogicalPath path,
            String message,
            IOException cause
    ) {
        return new SourceAccessException(
                SourceAccessException.Reason.IO_ERROR,
                descriptor.sourceId(),
                path,
                message,
                cause
        );
    }
}
