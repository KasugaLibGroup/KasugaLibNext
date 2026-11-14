package lib.kasuga.core.resource.source;

import lib.kasuga.core.resource.identity.ResourcePaths;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.VirtualFileSystem;
import lib.kasuga.core.vfs.backend.DirectoryVfs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Host-approved adapters. No URI causes mounting, downloading or filesystem discovery. */
public final class ResourceSources {
    private ResourceSources() {}

    public static ResourceSource vfs(ResourceSourceDescriptor descriptor, VirtualFileSystem files,
                                     boolean owned, String sourcePrefix, String logicalPrefix, InputPolicy policy) {
        return new VfsSource(descriptor, files, owned, sourcePrefix, logicalPrefix, policy, () -> {});
    }

    public static ResourceSource directory(ResourceSourceDescriptor descriptor, Path root, InputPolicy policy) throws IOException {
        Path directory = root.toAbsolutePath().normalize();
        checkDirectory(directory, policy);
        return new VfsSource(descriptor, new DirectoryVfs(descriptor.sourceId(), directory), true, "", "", policy,
                () -> checkDirectory(directory, policy));
    }

    /** Accepts a mod's findResource("") path, whether an expanded directory or an ordinary JAR. */
    public static ResourceSource mod(ResourceSourceDescriptor descriptor, Path root, InputPolicy policy) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return archive(descriptor, root, policy);
        }
        FileSystem fileSystem = root.getFileSystem();
        if (fileSystem.provider() == FileSystems.getDefault().provider()) {
            return directory(descriptor, root, policy);
        }
        return readOnlyDirectory(descriptor, root, policy);
    }

    /**
     * Captures a read-only directory tree whose provider does not support creating directories,
     * such as NeoForge's union filesystem for an expanded mod artifact.
     */
    public static ResourceSource readOnlyDirectory(
            ResourceSourceDescriptor descriptor,
            Path root,
            InputPolicy policy
    ) throws IOException {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(policy, "policy");
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw new IOException("SAFE_PATH: expected a non-link directory");
        }
        return new ReadOnlyNioSource(descriptor, normalized, policy);
    }

    public static ResourceSource archive(ResourceSourceDescriptor descriptor, Path archive, InputPolicy policy) throws IOException {
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) throw new IOException("SOURCE_UNAVAILABLE: archive is not a regular file");
        return new ResourceSource() {
            private boolean closed;
            @Override public ResourceSourceDescriptor descriptor() { return descriptor; }
            @Override public synchronized ResourceSnapshot capture() throws IOException {
                if (closed) throw new IOException("SOURCE_UNAVAILABLE: archive source closed");
                for (int attempt = 0; attempt < 3; attempt++) {
                    try (ResourceSnapshot first = copiedArchive(descriptor, archive, policy)) {
                        ResourceSnapshot second = copiedArchive(descriptor, archive, policy);
                        if (first.files().stream().map(ResourceSnapshot.LocatedResourceFile::descriptor).toList()
                                .equals(second.files().stream().map(ResourceSnapshot.LocatedResourceFile::descriptor).toList())) return second;
                        second.close();
                    }
                }
                throw new IOException("SOURCE_UNAVAILABLE: archive changed during capture");
            }
            @Override public synchronized void close() { closed = true; }
        };
    }

    /**
     * Captures a live source once and returns a source whose later captures are reprojected from
     * the original bytes. The supplied source is closed after capture.
     */
    public static ResourceSource fixed(
            ResourceSourceDescriptor descriptor,
            ResourceSource source,
            InputPolicy policy
    ) throws IOException {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(policy, "policy");
        ResourceSnapshot baseline;
        try {
            baseline = source.capture();
        } finally {
            source.close();
        }
        return fixed(descriptor, baseline, policy);
    }

    /** Wraps an existing immutable snapshot and takes ownership of it. */
    public static ResourceSource fixed(
            ResourceSourceDescriptor descriptor,
            ResourceSnapshot baseline,
            InputPolicy policy
    ) {
        return new FixedResourceSource(descriptor, baseline, policy);
    }

    private static ResourceSnapshot copiedArchive(ResourceSourceDescriptor descriptor, Path archive, InputPolicy policy) throws IOException {
        Path copy = Files.createTempFile("kasuga-archive-", ".zip");
        try {
            try (var input = Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS); var output = Files.newOutputStream(copy)) {
                byte[] buffer = new byte[64 * 1024]; long total = 0;
                for (int count; (count = input.read(buffer)) != -1;) {
                    total += count;
                    if (total > policy.maxTotalBytes()) throw new IOException("LIMIT_EXCEEDED: archive capture");
                    output.write(buffer, 0, count);
                }
            }
            checkCentralDirectory(copy, policy);
            try (ZipFile zip = new ZipFile(copy.toFile()); var builder = new ResourceSnapshot.Builder(descriptor, policy)) {
            Set<String> names = new HashSet<>();
            Map<String, Boolean> kinds = new HashMap<>();
            List<ZipEntry> regularFiles = new ArrayList<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String raw = entry.getName();
                String name = entry.isDirectory() && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
                ResourcePaths.path(name, false);
                if (!names.add(name)) throw new IOException("DUPLICATE_ENTRY: " + name);
                kinds.put(name, entry.isDirectory());
                if (!entry.isDirectory()) regularFiles.add(entry);
            }
            for (String name : names) {
                for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
                    if (Boolean.FALSE.equals(kinds.get(name.substring(0, slash))))
                        throw new IOException("DUPLICATE_ENTRY: file/directory conflict");
                }
            }
            for (ZipEntry entry : regularFiles) builder.add(entry.getName(), entry.getName(), () -> new java.io.FilterInputStream(zip.getInputStream(entry)) {
                private final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                private long bytes;
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = in.read(buffer, offset, length);
                    if (count >= 0) { crc.update(buffer, offset, count); bytes += count; }
                    else if (bytes != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("INVALID_ARCHIVE: entry size/CRC mismatch");
                    return count;
                }
                @Override public int read() throws IOException { byte[] single = new byte[1]; return read(single, 0, 1) < 0 ? -1 : single[0] & 255; }
            });
            return builder.build();
            }
        } finally { Files.deleteIfExists(copy); }
    }

    private static void checkDirectory(Path root, InputPolicy policy) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
            throw new IOException("UNSAFE_PATH: expected a non-link directory");
        try (var paths = Files.walk(root)) {
            int count = 0;
            for (var iterator = paths.iterator(); iterator.hasNext();) {
                Path path = iterator.next();
                if (++count > policy.maxFiles() * 2L + 1) throw new IOException("LIMIT_EXCEEDED: directory entries");
                if (Files.isSymbolicLink(path)) throw new IOException("UNSAFE_PATH: symbolic link in source");
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("UNSAFE_PATH: special file in source");
            }
        }
    }

    /** ZipEntry hides Unix link mode. Validate central attributes before opening entry streams. */
    private static void checkCentralDirectory(Path archive, InputPolicy policy) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "r")) {
            long end = file.length(), eocd = -1;
            for (long p = end - 22, lower = Math.max(0, end - 65557); p >= lower; p--) {
                file.seek(p);
                if (Integer.reverseBytes(file.readInt()) == 0x06054b50) {
                    file.seek(p + 20);
                    if (p + 22 + readShort(file) == end) { eocd = p; break; }
                }
            }
            if (eocd < 0) throw new IOException("INVALID_ARCHIVE: missing ZIP directory");
            file.seek(eocd + 4);
            if (readShort(file) != 0 || readShort(file) != 0) throw new IOException("INVALID_ARCHIVE: split ZIP unsupported");
            int diskCount = readShort(file), count = readShort(file);
            long length = readInt(file), start = readInt(file);
            if (count == 65535 || start == 0xffffffffL || diskCount != count)
                throw new IOException("INVALID_ARCHIVE: ZIP64/split archives unsupported");
            if (count > policy.maxFiles() * 2L || start + length != eocd)
                throw new IOException("INVALID_ARCHIVE: directory size/count");
            file.seek(start);
            for (int i = 0; i < count; i++) {
                long position = file.getFilePointer();
                if (readInt(file) != 0x02014b50L) throw new IOException("INVALID_ARCHIVE: central entry");
                int madeBy = readShort(file);
                file.seek(position + 8);
                if ((readShort(file) & 1) != 0) throw new IOException("INVALID_ARCHIVE: encrypted entry");
                file.seek(position + 28);
                int name = readShort(file), extra = readShort(file), comment = readShort(file);
                file.seek(position + 38);
                long external = readInt(file);
                int mode = (int) (external >>> 16) & 0170000;
                if ((madeBy >>> 8) == 3 && mode != 0 && mode != 0100000 && mode != 0040000)
                    throw new IOException("INVALID_ARCHIVE: link or special entry");
                long next = position + 46L + name + extra + comment;
                if (next > eocd) throw new IOException("INVALID_ARCHIVE: truncated central entry");
                file.seek(next);
            }
            if (file.getFilePointer() != eocd) throw new IOException("INVALID_ARCHIVE: inconsistent directory");
        }
    }

    private static int readShort(RandomAccessFile file) throws IOException { return Short.toUnsignedInt(Short.reverseBytes(file.readShort())); }
    private static long readInt(RandomAccessFile file) throws IOException { return Integer.toUnsignedLong(Integer.reverseBytes(file.readInt())); }
    @FunctionalInterface private interface Validator { void validate() throws IOException; }

    private static final class ReadOnlyNioSource implements ResourceSource {
        private final ResourceSourceDescriptor descriptor;
        private final Path root;
        private final InputPolicy policy;
        private boolean closed;

        private ReadOnlyNioSource(
                ResourceSourceDescriptor descriptor,
                Path root,
                InputPolicy policy
        ) {
            this.descriptor = descriptor;
            this.root = root;
            this.policy = policy;
        }

        @Override
        public ResourceSourceDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public synchronized ResourceSnapshot capture() throws IOException {
            if (closed) {
                throw new IOException("SOURCE_UNAVAILABLE: mod root source closed");
            }
            for (int attempt = 0; attempt < 3; attempt++) {
                try (ResourceSnapshot first = captureOnce()) {
                    ResourceSnapshot second = captureOnce();
                    if (first.files().stream()
                            .map(ResourceSnapshot.LocatedResourceFile::descriptor)
                            .toList()
                            .equals(second.files().stream()
                                    .map(ResourceSnapshot.LocatedResourceFile::descriptor)
                                    .toList())) {
                        return second;
                    }
                    second.close();
                }
            }
            throw new IOException("SOURCE_UNAVAILABLE: mod root changed during capture");
        }

        private ResourceSnapshot captureOnce() throws IOException {
            List<Path> files = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                        Path directory,
                        BasicFileAttributes attributes
                ) throws IOException {
                    if ((!directory.equals(root) && attributes.isSymbolicLink())
                            || Files.isSymbolicLink(directory)) {
                        throw new IOException("UNSAFE_PATH: symbolic link in mod root");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                        throw new IOException("UNSAFE_PATH: symbolic link in mod root");
                    }
                    if (!attributes.isRegularFile()) {
                        throw new IOException("UNSAFE_PATH: special file in mod root");
                    }
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
            Map<Path, String> logicalPaths = new LinkedHashMap<>();
            for (Path file : files) {
                logicalPaths.put(file, relative(file));
            }
            files.sort(Comparator.comparing(logicalPaths::get));
            try (ResourceSnapshot.Builder builder = new ResourceSnapshot.Builder(descriptor, policy)) {
                for (Path file : files) {
                    String path = logicalPaths.get(file);
                    builder.add(path, path, () -> Files.newInputStream(file));
                }
                return builder.build();
            }
        }

        private String relative(Path file) throws IOException {
            String value = root.relativize(file).toString().replace('\\', '/');
            try {
                return ResourcePaths.path(value, false);
            } catch (IllegalArgumentException exception) {
                throw new IOException("UNSAFE_PATH: invalid mod root path: " + value, exception);
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
        }
    }

    private static final class VfsSource implements ResourceSource {
        private final ResourceSourceDescriptor descriptor;
        private final VirtualFileSystem files;
        private final boolean owned;
        private final String prefix, logicalPrefix;
        private final InputPolicy policy;
        private final Validator validator;
        private boolean closed;
        private VfsSource(ResourceSourceDescriptor descriptor, VirtualFileSystem files, boolean owned, String prefix,
                          String logicalPrefix, InputPolicy policy, Validator validator) {
            this.descriptor = Objects.requireNonNull(descriptor); this.files = Objects.requireNonNull(files); this.owned = owned;
            this.prefix = ResourcePaths.path(prefix, true); this.logicalPrefix = ResourcePaths.path(logicalPrefix, true);
            this.policy = policy; this.validator = validator;
        }
        @Override public ResourceSourceDescriptor descriptor() { return descriptor; }
        @Override public synchronized ResourceSnapshot capture() throws IOException {
            if (closed) throw new IOException("SOURCE_UNAVAILABLE: source closed");
            // A bounded full-content verification detects same-size, restored-mtime rewrites as well.
            for (int attempt = 0; attempt < 3; attempt++) {
                validator.validate();
                try (ResourceSnapshot first = captureOnce()) {
                    validator.validate();
                    ResourceSnapshot second = captureOnce();
                    if (first.files().stream().map(ResourceSnapshot.LocatedResourceFile::descriptor).toList()
                            .equals(second.files().stream().map(ResourceSnapshot.LocatedResourceFile::descriptor).toList())) return second;
                    second.close();
                }
            }
            throw new IOException("SOURCE_UNAVAILABLE: source changed during capture");
        }
        private ResourceSnapshot captureOnce() throws IOException {
            try (var builder = new ResourceSnapshot.Builder(descriptor, policy)) {
                for (VfsPath path : files.walk(VfsPath.of(prefix))) {
                    String raw = ResourcePaths.path(path.asString(), false);
                    String relative = prefix.isEmpty() ? raw : raw.substring(prefix.length() + 1);
                    builder.add(raw, ResourcePaths.join(logicalPrefix, relative), () -> files.openRead(path));
                }
                return builder.build();
            }
        }
        @Override public synchronized void close() throws IOException { if (!closed) { closed = true; if (owned) files.close(); } }
    }
}
