package lib.kasuga.registration.data_driven.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Content source adapter for ZIP archives and plain mod JARs.
 *
 * <p>The archive handle stays open until {@link #close()}. Entries are validated completely before
 * the source is returned, and file limits are based on bytes actually inflated.
 */
public final class ZipContentSource implements ContentSource {
    private final SourceDescriptor descriptor;
    private final SourceLimits limits;
    private final ZipFile zipFile;
    private final Map<LogicalPath, EntryInfo> entryMap;
    private final List<LocatedFile> files;
    private boolean closed;

    private ZipContentSource(
            SourceDescriptor descriptor,
            SourceLimits limits,
            ZipFile zipFile,
            Map<LogicalPath, EntryInfo> entryMap
    ) {
        this.descriptor = descriptor;
        this.limits = limits;
        this.zipFile = zipFile;
        this.entryMap = Map.copyOf(entryMap);
        this.files = entryMap.values().stream()
                .sorted(java.util.Comparator.comparing(EntryInfo::path))
                .map(info -> new LocatedFile(descriptor, info.path(), info.size()))
                .toList();
    }

    public static ZipContentSource open(Path archive, SourceDescriptor descriptor) throws IOException {
        return open(archive, descriptor, SourceLimits.DEFAULT);
    }

    public static ZipContentSource open(
            Path archive,
            SourceDescriptor descriptor,
            SourceLimits limits
    ) throws IOException {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(limits, "limits");
        Path normalizedArchive = archive.toAbsolutePath().normalize();

        BasicFileAttributes archiveAttributes;
        try {
            archiveAttributes = Files.readAttributes(
                    normalizedArchive,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
        } catch (NoSuchFileException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.NOT_FOUND,
                    descriptor,
                    null,
                    "ZIP source does not exist: " + normalizedArchive,
                    exception
            );
        } catch (AccessDeniedException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.ACCESS_DENIED,
                    descriptor,
                    null,
                    "cannot access ZIP source: " + normalizedArchive,
                    exception
            );
        } catch (IOException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.IO_ERROR,
                    descriptor.sourceId(),
                    null,
                    "cannot inspect ZIP source: " + normalizedArchive,
                    exception
            );
        }

        if (archiveAttributes.isSymbolicLink()) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.SYMLINK_REJECTED,
                    descriptor,
                    null,
                    "ZIP source must not be a symbolic link: " + normalizedArchive
            );
        }
        if (!archiveAttributes.isRegularFile()) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.INVALID_ENTRY,
                    descriptor,
                    null,
                    "ZIP source is not a regular file: " + normalizedArchive
            );
        }

        List<ZipSecurity.CentralEntry> centralEntries;
        try {
            centralEntries = ZipSecurity.readCentralEntries(normalizedArchive, limits.maxFiles());
        } catch (ZipSecurity.TooManyEntriesException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.LIMIT_EXCEEDED,
                    descriptor,
                    null,
                    "ZIP source exceeds the " + limits.maxFiles() + " entry limit",
                    exception
            );
        } catch (ZipException exception) {
            throw corrupt(descriptor, "invalid ZIP central directory in " + normalizedArchive, exception);
        } catch (AccessDeniedException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.ACCESS_DENIED,
                    descriptor,
                    null,
                    "cannot access ZIP source: " + normalizedArchive,
                    exception
            );
        } catch (IOException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.IO_ERROR,
                    descriptor.sourceId(),
                    null,
                    "cannot inspect ZIP source: " + normalizedArchive,
                    exception
            );
        }

        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(normalizedArchive.toFile(), StandardCharsets.UTF_8);
            Map<LogicalPath, EntryInfo> entries = readEntries(zipFile, centralEntries, descriptor, limits);
            validateEntryBytes(zipFile, entries.values(), descriptor, limits);
            return new ZipContentSource(descriptor, limits, zipFile, entries);
        } catch (SourceAccessException exception) {
            closeQuietly(zipFile);
            throw exception;
        } catch (ZipException exception) {
            closeQuietly(zipFile);
            throw corrupt(descriptor, "corrupt ZIP source: " + normalizedArchive, exception);
        } catch (AccessDeniedException exception) {
            closeQuietly(zipFile);
            throw new SourceAccessException(
                    SourceAccessException.Reason.ACCESS_DENIED,
                    descriptor,
                    null,
                    "cannot read ZIP source: " + normalizedArchive,
                    exception
            );
        } catch (IOException exception) {
            closeQuietly(zipFile);
            throw new SourceAccessException(
                    SourceAccessException.Reason.IO_ERROR,
                    descriptor.sourceId(),
                    null,
                    "cannot read ZIP source: " + normalizedArchive,
                    exception
            );
        }
    }

    @Override
    public SourceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public synchronized Optional<InputStream> open(LogicalPath path) throws IOException {
        ensureOpenForAccess();
        EntryInfo entry = entryMap.get(path);
        if (entry == null) {
            return Optional.empty();
        }
        try {
            InputStream input = zipFile.getInputStream(entry.entry());
            return Optional.of(new LimitedInputStream(
                    input,
                    descriptor,
                    path,
                    limits.limitFor(path)
            ));
        } catch (ZipException exception) {
            throw corrupt(descriptor, "cannot open ZIP entry " + path, exception);
        } catch (IOException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.IO_ERROR,
                    descriptor.sourceId(),
                    path,
                    "cannot open ZIP entry: " + path,
                    exception
            );
        }
    }

    @Override
    public synchronized boolean exists(LogicalPath path) {
        ensureOpenForAccessUnchecked();
        return entryMap.containsKey(path);
    }

    @Override
    public synchronized List<LocatedFile> files() {
        ensureOpenForFiles();
        return files;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        zipFile.close();
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

    private void ensureOpenForAccessUnchecked() {
        if (closed) {
            throw new IllegalStateException("content source is closed: " + descriptor.diagnosticLabel());
        }
    }

    private void ensureOpenForFiles() {
        if (closed) {
            throw new IllegalStateException("content source is closed: " + descriptor.diagnosticLabel());
        }
    }

    private static Map<LogicalPath, EntryInfo> readEntries(
            ZipFile zipFile,
            List<ZipSecurity.CentralEntry> centralEntries,
            SourceDescriptor descriptor,
            SourceLimits limits
    ) throws IOException {
        Map<LogicalPath, EntryInfo> entries = new LinkedHashMap<>();
        Set<String> rawNames = new HashSet<>();
        Set<LogicalPath> directories = new HashSet<>();
        Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
        int centralIndex = 0;

        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            if (centralIndex >= centralEntries.size()) {
                throw corrupt(descriptor, "ZIP entry count does not match its central directory", null);
            }
            ZipSecurity.CentralEntry metadata = centralEntries.get(centralIndex++);
            String rawName = entry.getName();
            if (!rawNames.add(rawName)) {
                throw new SourceAccessException(
                        SourceAccessException.Reason.DUPLICATE_ENTRY,
                        descriptor,
                        null,
                        "ZIP contains duplicate entry: " + rawName
                );
            }
            if (metadata.symbolicLink()) {
                throw new SourceAccessException(
                        SourceAccessException.Reason.SYMLINK_REJECTED,
                        descriptor,
                        null,
                        "ZIP contains a symbolic-link entry: " + rawName
                );
            }
            if (metadata.specialFile()) {
                throw invalidEntry(descriptor, "ZIP contains a special-file entry: " + rawName);
            }

            boolean trailingSlashDirectory = rawName.endsWith("/");
            if (metadata.unixDirectory() && !trailingSlashDirectory) {
                throw invalidEntry(descriptor, "ZIP directory entry is missing its trailing slash: " + rawName);
            }
            if (entry.isDirectory() || trailingSlashDirectory) {
                if (entry.getSize() > 0 || entry.getCompressedSize() > 0) {
                    throw invalidEntry(descriptor, "ZIP directory entry carries content: " + rawName);
                }
                logicalPath(rawName.substring(0, rawName.length() - 1), descriptor);
                directories.add(LogicalPath.of(rawName.substring(0, rawName.length() - 1)));
                continue;
            }

            LogicalPath path = logicalPath(rawName, descriptor);
            if (directories.contains(path)) {
                throw invalidEntry(descriptor, "ZIP file conflicts with a directory entry: " + rawName);
            }
            if (entries.size() >= limits.maxFiles()) {
                throw limit(descriptor, path, "ZIP exceeds the " + limits.maxFiles() + " file limit");
            }
            if (entries.put(path, new EntryInfo(entry, path, 0)) != null) {
                throw new SourceAccessException(
                        SourceAccessException.Reason.DUPLICATE_ENTRY,
                        descriptor,
                        path,
                        "ZIP contains duplicate logical entry: " + path
                );
            }
        }
        if (centralIndex != centralEntries.size()) {
            throw corrupt(descriptor, "ZIP central-directory count does not match its entries", null);
        }

        Set<String> fileKeys = new HashSet<>();
        for (LogicalPath path : entries.keySet()) {
            fileKeys.add(path.value());
        }
        for (LogicalPath path : entries.keySet()) {
            List<String> segments = path.segments();
            for (int index = 1; index < segments.size(); index++) {
                String prefix = String.join("/", segments.subList(0, index));
                if (fileKeys.contains(prefix)) {
                    throw invalidEntry(descriptor, "ZIP has a file used as a directory prefix: " + prefix);
                }
            }
        }
        return entries;
    }

    private static void validateEntryBytes(
            ZipFile zipFile,
            Iterable<EntryInfo> entries,
            SourceDescriptor descriptor,
            SourceLimits limits
    ) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        List<EntryInfo> ordered = new ArrayList<>();
        entries.forEach(ordered::add);
        ordered.sort(java.util.Comparator.comparing(EntryInfo::path));

        for (EntryInfo info : ordered) {
            long fileBytes = 0;
            long fileLimit = limits.limitFor(info.path());
            try (InputStream input = zipFile.getInputStream(info.entry())) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    fileBytes = Math.addExact(fileBytes, read);
                    if (fileBytes > fileLimit) {
                        throw limit(descriptor, info.path(), "ZIP entry exceeds the "
                                + fileLimit + " byte limit");
                    }
                    total = Math.addExact(total, read);
                    if (total > limits.maxTotalBytes()) {
                        throw limit(descriptor, info.path(), "ZIP source exceeds the "
                                + limits.maxTotalBytes() + " byte total limit");
                    }
                }
            } catch (ArithmeticException exception) {
                throw limit(descriptor, info.path(), "ZIP byte count overflowed");
            } catch (ZipException exception) {
                throw corrupt(descriptor, "cannot inflate ZIP entry " + info.path(), exception);
            }
            info.setSize(fileBytes);
        }
    }

    private static LogicalPath logicalPath(String rawName, SourceDescriptor descriptor)
            throws SourceAccessException {
        try {
            return LogicalPath.of(rawName);
        } catch (IllegalArgumentException exception) {
            throw new SourceAccessException(
                    SourceAccessException.Reason.INVALID_ENTRY,
                    descriptor,
                    null,
                    "ZIP contains an invalid logical path: " + rawName,
                    exception
            );
        }
    }

    private static SourceAccessException invalidEntry(SourceDescriptor descriptor, String message) {
        return new SourceAccessException(
                SourceAccessException.Reason.INVALID_ENTRY,
                descriptor,
                null,
                message
        );
    }

    private static SourceAccessException corrupt(
            SourceDescriptor descriptor,
            String message,
            Exception cause
    ) {
        return new SourceAccessException(
                SourceAccessException.Reason.CORRUPT_ARCHIVE,
                descriptor.sourceId(),
                null,
                message,
                cause
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

    private static void closeQuietly(ZipFile zipFile) {
        if (zipFile == null) {
            return;
        }
        try {
            zipFile.close();
        } catch (IOException ignored) {
        }
    }

    private static final class EntryInfo {
        private final ZipEntry entry;
        private final LogicalPath path;
        private long size;

        private EntryInfo(ZipEntry entry, LogicalPath path, long size) {
            this.entry = entry;
            this.path = path;
            this.size = size;
        }

        private ZipEntry entry() {
            return entry;
        }

        private LogicalPath path() {
            return path;
        }

        private long size() {
            return size;
        }

        private void setSize(long size) {
            this.size = size;
        }
    }
}
