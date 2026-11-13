package lib.kasuga.core.vfs.backend;

import lib.kasuga.core.vfs.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cross-platform (Windows / macOS / Linux) read-write {@link VirtualFileSystem} backed by
 * a physical directory on disk.
 *
 * <ul>
 *   <li>Blocks symlink and relative-path escapes outside {@code rootDir.toRealPath()}.</li>
 *   <li>Uses same-directory temporary files and atomic replace with Windows lock fallback.</li>
 *   <li>Tracks an internal snapshot baseline so {@link #pollExternalChanges()} reliably reports
 *       external disk modifications without misreporting internal VFS writes.</li>
 * </ul>
 */
public final class DirectoryVfs implements VirtualFileSystem {
    private static final int WINDOWS_MOVE_MAX_RETRIES = 3;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(DirectoryVfs.class);

    private final String sourceId;
    private final Path realRoot;
    private final ReentrantLock stateLock = new ReentrantLock();
    private final VfsEventDispatcher dispatcher = new VfsEventDispatcher();
    private final Map<VfsPath, FileFingerprint> baseline = new TreeMap<>();
    private volatile boolean closed = false;

    public DirectoryVfs(String sourceId, Path rootDir) throws IOException {
        this(sourceId, rootDir, true);
    }

    public DirectoryVfs(String sourceId, Path rootDir, boolean createIfMissing) throws IOException {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(rootDir, "rootDir");
        if (createIfMissing) {
            Files.createDirectories(rootDir);
        } else if (!Files.isDirectory(rootDir)) {
            throw new NoSuchFileException(rootDir.toString(), null, "VFS root directory does not exist");
        }
        this.realRoot = rootDir.toRealPath();
        stateLock.lock();
        try {
            baseline.putAll(scanDiskFingerprints());
        } finally {
            stateLock.unlock();
        }
    }

    public Path rootDir() {
        return realRoot;
    }

    /**
     * Resolves {@code path} under {@code realRoot}, verifying at every existing ancestor (and leaf
     * target) that no symbolic link or junction escapes {@code realRoot}.
     */
    public Path resolveSafePhysicalPath(VfsPath path) throws IOException {
        Objects.requireNonNull(path, "path");
        path.requirePortable();
        Path cursor = realRoot;
        verifyInsideRootIfExists(cursor, path);
        for (String segment : path.segments()) {
            cursor = cursor.resolve(segment);
            verifyInsideRootIfExists(cursor, path);
        }
        Path normalized = cursor.normalize();
        if (!normalized.startsWith(realRoot)) {
            throw new VfsInvalidPathException("Resolved physical path escapes VFS root: " + path);
        }
        return normalized;
    }

    private void verifyInsideRootIfExists(Path candidate, VfsPath logicalPath) throws IOException {
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            Path resolvedReal;
            try {
                resolvedReal = candidate.toRealPath();
            } catch (NoSuchFileException brokenSymlink) {
                // Broken symlink cannot safely be resolved; inspect raw symlink target if readable
                if (Files.isSymbolicLink(candidate)) {
                    Path linkTarget = Files.readSymbolicLink(candidate);
                    Path absTarget = linkTarget.isAbsolute()
                            ? linkTarget.normalize()
                            : candidate.getParent().resolve(linkTarget).normalize();
                    if (!absTarget.startsWith(realRoot)) {
                        throw new VfsInvalidPathException("Symlink escapes VFS root: " + logicalPath);
                    }
                    return;
                }
                throw brokenSymlink;
            }
            if (!resolvedReal.startsWith(realRoot)) {
                throw new VfsInvalidPathException("Path escapes VFS root via symlink: " + logicalPath);
            }
        }
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean exists(VfsPath path) {
        stateLock.lock();
        try {
            try {
                Path target = resolveSafePhysicalPath(path);
                return Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            } catch (VfsInvalidPathException invalid) {
                throw invalid;
            } catch (IOException e) {
                return false;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public boolean isRegularFile(VfsPath path) {
        stateLock.lock();
        try {
            try {
                Path target = resolveSafePhysicalPath(path);
                return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
            } catch (VfsInvalidPathException invalid) {
                throw invalid;
            } catch (IOException e) {
                return false;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public boolean isDirectory(VfsPath path) {
        stateLock.lock();
        try {
            try {
                Path target = resolveSafePhysicalPath(path);
                return Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
            } catch (VfsInvalidPathException invalid) {
                throw invalid;
            } catch (IOException e) {
                return false;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public VfsNodeStat stat(VfsPath path) throws IOException {
        stateLock.lock();
        try {
            Path target = resolveSafePhysicalPath(path);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return VfsNodeStat.missing(path);
            }
            BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            long lastMod = attrs.lastModifiedTime().toMillis();
            if (attrs.isDirectory()) {
                return VfsNodeStat.directory(path, lastMod);
            }
            byte[] content = Files.readAllBytes(target);
            return VfsNodeStat.file(path, attrs.size(), lastMod, Optional.of(VfsSupport.sha256Hex(content)));
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public InputStream openRead(VfsPath path) throws IOException {
        stateLock.lock();
        try {
            ensureNotClosed();
            Path target = resolveSafePhysicalPath(path);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new NoSuchFileException(path.toString());
            }
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new VfsException("Cannot open directory for reading: " + path);
            }
            return Files.newInputStream(target);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public List<String> list(VfsPath dir) throws IOException {
        stateLock.lock();
        try {
            ensureNotClosed();
            Path target = resolveSafePhysicalPath(dir);
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new NoSuchFileException(dir.toString());
            }
            TreeSet<String> names = new TreeSet<>();
            try (var stream = Files.list(target)) {
                for (Path child : (Iterable<Path>) stream::iterator) {
                    String name = child.getFileName().toString();
                    if (name.startsWith(VfsSupport.TEMP_FILE_PREFIX)) {
                        continue;
                    }
                    names.add(name);
                }
            }
            return List.copyOf(names);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public List<VfsPath> walk(VfsPath prefix) throws IOException {
        stateLock.lock();
        try {
            ensureNotClosed();
            Path start = resolveSafePhysicalPath(prefix);
            if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            TreeSet<VfsPath> files = new TreeSet<>();
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (isTemporaryDirectory(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String fileName = file.getFileName().toString();
                    if (!fileName.startsWith(VfsSupport.TEMP_FILE_PREFIX) && attrs.isRegularFile()) {
                        files.add(VfsPath.fromOsRelativePath(realRoot.relativize(file)));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return List.copyOf(files);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void writeBytes(VfsPath path, byte[] content) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        if (path.isRoot()) {
            throw new VfsException("Cannot write file to VFS root");
        }
        List<VfsChangeEvent.EntryChange> changes;
        stateLock.lock();
        try {
            ensureNotClosed();
            changes = applyWriteBytesLocked(path, content);
        } finally {
            stateLock.unlock();
        }
        dispatcher.dispatch(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, changes);
    }

    @Override
    public OutputStream openWrite(VfsPath path) throws IOException {
        Objects.requireNonNull(path, "path");
        ensureNotClosed();
        if (path.isRoot()) {
            throw new VfsException("Cannot open root path for writing");
        }
        // Validate path safety eagerly before buffering payload
        resolveSafePhysicalPath(path);
        return VfsSupport.bufferedAtomicOutputStream(bytes -> writeBytes(path, bytes));
    }

    @Override
    public void mkdirs(VfsPath dir) throws IOException {
        Objects.requireNonNull(dir, "dir");
        List<VfsChangeEvent.EntryChange> changes;
        stateLock.lock();
        try {
            ensureNotClosed();
            changes = applyMkdirsLocked(dir);
        } finally {
            stateLock.unlock();
        }
        dispatcher.dispatch(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, changes);
    }

    @Override
    public boolean delete(VfsPath path) throws IOException {
        Objects.requireNonNull(path, "path");
        List<VfsChangeEvent.EntryChange> changes;
        stateLock.lock();
        try {
            ensureNotClosed();
            changes = applyDeleteLocked(path);
        } finally {
            stateLock.unlock();
        }
        if (changes.isEmpty()) {
            return false;
        }
        dispatcher.dispatch(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, changes);
        return true;
    }

    @Override
    public void move(VfsPath from, VfsPath to) throws IOException {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        List<VfsChangeEvent.EntryChange> changes;
        stateLock.lock();
        try {
            ensureNotClosed();
            changes = applyMoveLocked(from, to);
        } finally {
            stateLock.unlock();
        }
        dispatcher.dispatch(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, changes);
    }

    @Override
    public VfsWriteTransaction beginTransaction() throws IOException {
        ensureNotClosed();
        return new DirectoryTransaction();
    }

    @Override
    public VfsSubscription watch(VfsPath prefix, VfsWatchListener listener) {
        return dispatcher.register(prefix, listener);
    }

    @Override
    public Optional<VfsChangeEvent> pollExternalChanges() throws IOException {
        List<VfsChangeEvent.EntryChange> externalChanges = new ArrayList<>();
        stateLock.lock();
        try {
            if (closed) {
                return Optional.empty();
            }
            Map<VfsPath, FileFingerprint> current = scanDiskFingerprints();

            for (Map.Entry<VfsPath, FileFingerprint> prev : baseline.entrySet()) {
                if (!current.containsKey(prev.getKey())) {
                    externalChanges.add(new VfsChangeEvent.EntryChange(
                            VfsChangeEvent.ChangeKind.DELETED,
                            prev.getKey(),
                            null,
                            VfsNodeStat.missing(prev.getKey())
                    ));
                }
            }

            for (Map.Entry<VfsPath, FileFingerprint> now : current.entrySet()) {
                FileFingerprint prev = baseline.get(now.getKey());
                FileFingerprint fp = now.getValue();
                if (prev == null) {
                    externalChanges.add(new VfsChangeEvent.EntryChange(
                            VfsChangeEvent.ChangeKind.CREATED,
                            now.getKey(),
                            null,
                            fp.toStat(now.getKey())
                    ));
                } else if (!prev.matches(fp)) {
                    externalChanges.add(new VfsChangeEvent.EntryChange(
                            VfsChangeEvent.ChangeKind.MODIFIED,
                            now.getKey(),
                            null,
                            fp.toStat(now.getKey())
                    ));
                }
            }

            if (!externalChanges.isEmpty()) {
                baseline.clear();
                baseline.putAll(current);
            }
        } finally {
            stateLock.unlock();
        }

        if (externalChanges.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(dispatcher.dispatch(sourceId, VfsChangeEvent.ChangeOrigin.EXTERNAL_DISK, externalChanges));
    }

    @Override
    public void close() {
        closed = true;
        dispatcher.closeAll();
    }

    private void ensureNotClosed() throws VfsException {
        if (closed) {
            throw new VfsException("DirectoryVfs '" + sourceId + "' is closed");
        }
    }

    private List<VfsChangeEvent.EntryChange> applyWriteBytesLocked(VfsPath path, byte[] content) throws IOException {
        Path target = resolveSafePhysicalPath(path);
        boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (existed && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(path.toString(), null, "Target is a directory");
        }
        List<VfsChangeEvent.EntryChange> changes = new ArrayList<>();
        if (path.parent() != null && !path.parent().isRoot()) {
            changes.addAll(applyMkdirsLocked(path.parent()));
        }
        writeAtomically(target, content);
        FileFingerprint fp = recordFileInBaseline(path, target, content);
        changes.add(new VfsChangeEvent.EntryChange(
                existed ? VfsChangeEvent.ChangeKind.MODIFIED : VfsChangeEvent.ChangeKind.CREATED,
                path,
                null,
                fp.toStat(path)
        ));
        return changes;
    }

    private List<VfsChangeEvent.EntryChange> applyMkdirsLocked(VfsPath dir) throws IOException {
        if (dir.isRoot()) {
            return List.of();
        }
        List<VfsChangeEvent.EntryChange> changes = new ArrayList<>();
        VfsPath cursor = VfsPath.ROOT;
        for (String seg : dir.segments()) {
            cursor = cursor.resolve(seg);
            Path phys = resolveSafePhysicalPath(cursor);
            if (!Files.exists(phys, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(phys);
                long mtime = Files.getLastModifiedTime(phys, LinkOption.NOFOLLOW_LINKS).toMillis();
                baseline.put(cursor, FileFingerprint.directory(mtime));
                changes.add(new VfsChangeEvent.EntryChange(
                        VfsChangeEvent.ChangeKind.CREATED,
                        cursor,
                        null,
                        VfsNodeStat.directory(cursor, mtime)
                ));
            } else if (!Files.isDirectory(phys, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(cursor.toString(), null, "Ancestor exists as a file");
            }
        }
        return changes;
    }

    private List<VfsChangeEvent.EntryChange> applyDeleteLocked(VfsPath path) throws IOException {
        if (path.isRoot()) {
            throw new VfsException("Cannot delete VFS root");
        }
        Path target = resolveSafePhysicalPath(path);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<VfsPath> removedPaths = new ArrayList<>();
        List<Path> toDelete = new ArrayList<>();
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                toDelete.add(file);
                if (!file.getFileName().toString().startsWith(VfsSupport.TEMP_FILE_PREFIX)) {
                    removedPaths.add(VfsPath.fromOsRelativePath(realRoot.relativize(file)));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                toDelete.add(dir);
                removedPaths.add(VfsPath.fromOsRelativePath(realRoot.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
        });
        for (Path p : toDelete) {
            Files.deleteIfExists(p);
        }
        baseline.keySet().removeIf(k -> k.startsWith(path));
        List<VfsChangeEvent.EntryChange> changes = new ArrayList<>(removedPaths.size());
        for (VfsPath removed : removedPaths) {
            changes.add(new VfsChangeEvent.EntryChange(
                    VfsChangeEvent.ChangeKind.DELETED,
                    removed,
                    null,
                    VfsNodeStat.missing(removed)
            ));
        }
        return changes;
    }

    private List<VfsChangeEvent.EntryChange> applyMoveLocked(VfsPath from, VfsPath to) throws IOException {
        if (from.isRoot() || to.isRoot()) {
            throw new VfsException("Cannot move to or from VFS root");
        }
        if (from.equals(to)) {
            return List.of();
        }
        if (from.startsWith(to)) {
            throw new VfsException("Cannot overwrite an ancestor of the source: " + from + " -> " + to);
        }
        Path src = resolveSafePhysicalPath(from);
        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(from.toString());
        }
        boolean isDir = Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS);
        if (isDir && to.startsWith(from)) {
            throw new VfsException("Cannot move directory into its own descendant: " + from + " -> " + to);
        }
        Path dest = resolveSafePhysicalPath(to);

        List<VfsChangeEvent.EntryChange> changes = new ArrayList<>();
        if (to.parent() != null && !to.parent().isRoot()) {
            changes.addAll(applyMkdirsLocked(to.parent()));
        }
        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
            changes.addAll(applyDeleteLocked(to));
        }

        List<VfsPath> affectedNodes;
        try (var paths = Files.walk(src)) {
            affectedNodes = paths.filter(p -> !Files.isSymbolicLink(p))
                    .map(p -> VfsPath.fromOsRelativePath(realRoot.relativize(p))).toList();
        }

        moveWithCrossPlatformFallback(src, dest);
        baseline.keySet().removeIf(k -> k.startsWith(from));

        for (VfsPath oldFile : affectedNodes) {
            VfsPath rel = from.relativize(oldFile);
            VfsPath newFile = rel.isRoot() ? to : to.resolve(rel);
            Path physDest = resolveSafePhysicalPath(newFile);
            if (Files.isDirectory(physDest, LinkOption.NOFOLLOW_LINKS)) {
                FileFingerprint fp = FileFingerprint.directory(Files.getLastModifiedTime(physDest).toMillis());
                baseline.put(newFile, fp);
                changes.add(new VfsChangeEvent.EntryChange(VfsChangeEvent.ChangeKind.MOVED, newFile, oldFile, fp.toStat(newFile)));
            } else if (Files.isRegularFile(physDest, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = Files.readAllBytes(physDest);
                FileFingerprint fp = recordFileInBaseline(newFile, physDest, bytes);
                changes.add(new VfsChangeEvent.EntryChange(
                        VfsChangeEvent.ChangeKind.MOVED,
                        newFile,
                        oldFile,
                        fp.toStat(newFile)
                ));
            }
        }
        return changes;
    }

    private FileFingerprint recordFileInBaseline(VfsPath path, Path target, byte[] bytes) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        FileFingerprint fp = new FileFingerprint(
                VfsNodeKind.FILE,
                attrs.size(),
                attrs.lastModifiedTime().toMillis(),
                VfsSupport.sha256Hex(bytes)
        );
        baseline.put(path, fp);
        return fp;
    }

    private Map<VfsPath, FileFingerprint> scanDiskFingerprints() throws IOException {
        Map<VfsPath, FileFingerprint> snapshot = new TreeMap<>();
        Files.walkFileTree(realRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (isTemporaryDirectory(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!dir.equals(realRoot)) {
                    snapshot.put(VfsPath.fromOsRelativePath(realRoot.relativize(dir)),
                            FileFingerprint.directory(attrs.lastModifiedTime().toMillis()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) {
                    return FileVisitResult.CONTINUE;
                }
                String name = file.getFileName().toString();
                if (!name.startsWith(VfsSupport.TEMP_FILE_PREFIX) && attrs.isRegularFile()) {
                    VfsPath vfsPath = VfsPath.fromOsRelativePath(realRoot.relativize(file));
                    byte[] bytes = Files.readAllBytes(file);
                    snapshot.put(vfsPath, new FileFingerprint(
                            VfsNodeKind.FILE,
                            attrs.size(),
                            attrs.lastModifiedTime().toMillis(),
                            VfsSupport.sha256Hex(bytes)
                    ));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return snapshot;
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        Path tmp = Files.createTempFile(parent, VfsSupport.TEMP_FILE_PREFIX, ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                out.write(bytes);
                out.flush();
            }
            moveWithCrossPlatformFallback(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void moveWithCrossPlatformFallback(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (AtomicMoveNotSupportedException ignored) {
            // Fall through to standard replace move
        } catch (AccessDeniedException transientWinLock) {
            // Fall through to retry loop for transient Windows file handle locks
        }

        IOException lastError = null;
        for (int attempt = 0; attempt <= WINDOWS_MOVE_MAX_RETRIES; attempt++) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException accessDenied) {
                lastError = accessDenied;
                if (attempt < WINDOWS_MOVE_MAX_RETRIES) {
                    try {
                        Thread.sleep(5L * (1L << attempt));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw accessDenied;
                    }
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
    }

    private boolean isTemporaryDirectory(Path dir) {
        return !dir.equals(realRoot) && dir.getFileName().toString().startsWith(VfsSupport.TEMP_FILE_PREFIX);
    }

    private record FileFingerprint(VfsNodeKind kind, long size, long lastModifiedMillis, String sha256) {
        static FileFingerprint directory(long modified) {
            return new FileFingerprint(VfsNodeKind.DIRECTORY, 0L, modified, "");
        }

        boolean matches(FileFingerprint other) {
            // Parent-directory mtimes change on internal writes; content/type determine changes.
            return kind == other.kind && size == other.size && sha256.equals(other.sha256);
        }

        VfsNodeStat toStat(VfsPath path) {
            return kind == VfsNodeKind.DIRECTORY ? VfsNodeStat.directory(path, lastModifiedMillis)
                    : VfsNodeStat.file(path, size, lastModifiedMillis, Optional.of(sha256));
        }
    }

    private sealed interface StagedOp {
        record PutBytes(VfsPath path, byte[] bytes) implements StagedOp {}
        record Mkdirs(VfsPath dir) implements StagedOp {}
        record Delete(VfsPath path) implements StagedOp {}
        record Move(VfsPath from, VfsPath to) implements StagedOp {}
    }

    private final class DirectoryTransaction implements VfsWriteTransaction {
        private final List<StagedOp> ops = new ArrayList<>();
        private boolean committed = false;
        private boolean rolledBack = false;
        private VfsPreparedTransaction reservation;

        @Override
        public VfsWriteTransaction putBytes(VfsPath path, byte[] content) {
            ensureActive();
            ops.add(new StagedOp.PutBytes(Objects.requireNonNull(path, "path"), Arrays.copyOf(content, content.length)));
            return this;
        }

        @Override
        public VfsWriteTransaction mkdirs(VfsPath dir) {
            ensureActive();
            ops.add(new StagedOp.Mkdirs(Objects.requireNonNull(dir, "dir")));
            return this;
        }

        @Override
        public VfsWriteTransaction delete(VfsPath path) {
            ensureActive();
            ops.add(new StagedOp.Delete(Objects.requireNonNull(path, "path")));
            return this;
        }

        @Override
        public VfsWriteTransaction move(VfsPath from, VfsPath to) {
            ensureActive();
            ops.add(new StagedOp.Move(Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to")));
            return this;
        }

        @Override
        public VfsChangeEvent commit() throws IOException {
            return TransactionSupport.commit(List.of(this)).getFirst();
        }

        @Override
        public VfsPreparedTransaction prepare() throws IOException {
            ensureActive();
            stateLock.lock();
            try {
                ensureNotClosed();
                validateSequenceLocked();
                List<VfsPath> affected = new ArrayList<>();
                for (StagedOp op : ops) {
                    if (op instanceof StagedOp.PutBytes put) affected.add(put.path());
                    else if (op instanceof StagedOp.Mkdirs mk) affected.add(mk.dir());
                    else if (op instanceof StagedOp.Delete del) affected.add(del.path());
                    else if (op instanceof StagedOp.Move mv) { affected.add(mv.from()); affected.add(mv.to()); }
                }
                DirectoryTransactionJournal journal = new DirectoryTransactionJournal(realRoot, affected);
                Map<VfsPath, FileFingerprint> previousBaseline = new TreeMap<>(baseline);
                reservation = new VfsPreparedTransaction() {
                    private final List<VfsChangeEvent.EntryChange> changes = new ArrayList<>();
                    private boolean started;
                    private boolean applied;
                    private boolean finished;
                    private VfsChangeEvent event;

                    @Override
                    public void apply() throws IOException {
                        if (finished || started) throw new IllegalStateException("Participant already applied or finished");
                        ensureNotClosed();
                        started = true;
                        for (StagedOp op : ops) {
                            if (op instanceof StagedOp.PutBytes put) changes.addAll(applyWriteBytesLocked(put.path(), put.bytes()));
                            else if (op instanceof StagedOp.Mkdirs mk) changes.addAll(applyMkdirsLocked(mk.dir()));
                            else if (op instanceof StagedOp.Delete del) changes.addAll(applyDeleteLocked(del.path()));
                            else if (op instanceof StagedOp.Move mv) changes.addAll(applyMoveLocked(mv.from(), mv.to()));
                        }
                        applied = true;
                    }

                    @Override
                    public void rollback() throws IOException {
                        if (finished) return;
                        try {
                            if (started) journal.restore();
                            else journal.cleanup();
                            baseline.clear();
                            baseline.putAll(previousBaseline);
                        } finally {
                            rolledBack = true;
                            finished = true;
                            stateLock.unlock();
                        }
                    }

                    @Override
                    public void release() {
                        if (finished || !applied) throw new IllegalStateException("Participant was not applied");
                        committed = true;
                        finished = true;
                        try { journal.cleanup(); }
                        catch (IOException cleanupFailure) { LOGGER.warn("Unable to remove committed VFS journal", cleanupFailure); }
                        finally { stateLock.unlock(); }
                    }

                    @Override
                    public VfsChangeEvent publish() {
                        if (!committed) throw new IllegalStateException("Participant was not committed");
                        if (event == null) {
                            event = dispatcher.dispatch(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, changes);
                            if (event == null) event = new VfsChangeEvent(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE,
                                    0L, System.currentTimeMillis(), List.of());
                        }
                        return event;
                    }
                };
                return reservation;
            } catch (IOException | RuntimeException | Error failure) {
                stateLock.unlock();
                throw failure;
            }
        }

        private void validateSequenceLocked() throws IOException {
            Map<VfsPath, VfsNodeKind> simulated = new HashMap<>();
            simulated.put(VfsPath.ROOT, VfsNodeKind.DIRECTORY);
            Files.walkFileTree(realRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (isTemporaryDirectory(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(realRoot)) {
                        simulated.put(VfsPath.fromOsRelativePath(realRoot.relativize(dir)), VfsNodeKind.DIRECTORY);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!file.getFileName().toString().startsWith(VfsSupport.TEMP_FILE_PREFIX) && attrs.isRegularFile()) {
                        simulated.put(VfsPath.fromOsRelativePath(realRoot.relativize(file)), VfsNodeKind.FILE);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            for (StagedOp op : ops) {
                if (op instanceof StagedOp.PutBytes put) {
                    if (put.path().isRoot()) {
                        throw new VfsException("Cannot write file to VFS root");
                    }
                    resolveSafePhysicalPath(put.path());
                    if (simulated.get(put.path()) == VfsNodeKind.DIRECTORY) {
                        throw new FileAlreadyExistsException(put.path().toString());
                    }
                    simulateMkdirs(simulated, put.path().parent());
                    simulated.put(put.path(), VfsNodeKind.FILE);
                } else if (op instanceof StagedOp.Mkdirs mk) {
                    resolveSafePhysicalPath(mk.dir());
                    simulateMkdirs(simulated, mk.dir());
                } else if (op instanceof StagedOp.Delete del) {
                    if (del.path().isRoot()) {
                        throw new VfsException("Cannot delete VFS root");
                    }
                    resolveSafePhysicalPath(del.path());
                    simulated.keySet().removeIf(k -> k.startsWith(del.path()));
                } else if (op instanceof StagedOp.Move mv) {
                    if (mv.from().isRoot() || mv.to().isRoot()) {
                        throw new VfsException("Cannot move to or from VFS root");
                    }
                    resolveSafePhysicalPath(mv.from());
                    resolveSafePhysicalPath(mv.to());
                    if (mv.from().equals(mv.to())) continue;
                    if (mv.from().startsWith(mv.to())) {
                        throw new VfsException("Cannot overwrite an ancestor of the source: " + mv.from());
                    }
                    VfsNodeKind srcKind = simulated.get(mv.from());
                    if (srcKind == null) {
                        throw new NoSuchFileException(mv.from().toString());
                    }
                    if (srcKind == VfsNodeKind.DIRECTORY && mv.to().startsWith(mv.from())) {
                        throw new VfsException("Cannot move directory into its own descendant: " + mv.from());
                    }
                    simulateMkdirs(simulated, mv.to().parent());
                    simulated.keySet().removeIf(k -> k.startsWith(mv.to()));
                    List<Map.Entry<VfsPath, VfsNodeKind>> movedEntries = simulated.entrySet().stream()
                            .filter(e -> e.getKey().startsWith(mv.from()))
                            .sorted(Comparator.comparing(Map.Entry::getKey))
                            .toList();
                    for (Map.Entry<VfsPath, VfsNodeKind> entry : movedEntries) {
                        simulated.remove(entry.getKey());
                        VfsPath rel = mv.from().relativize(entry.getKey());
                        VfsPath dest = rel.isRoot() ? mv.to() : mv.to().resolve(rel);
                        simulated.put(dest, entry.getValue());
                    }
                }
            }
        }

        private void simulateMkdirs(Map<VfsPath, VfsNodeKind> simulated, VfsPath dir) throws FileAlreadyExistsException {
            if (dir == null || dir.isRoot()) {
                return;
            }
            VfsPath cursor = VfsPath.ROOT;
            for (String seg : dir.segments()) {
                cursor = cursor.resolve(seg);
                VfsNodeKind existing = simulated.get(cursor);
                if (existing == VfsNodeKind.FILE) {
                    throw new FileAlreadyExistsException(cursor.toString());
                }
                simulated.put(cursor, VfsNodeKind.DIRECTORY);
            }
        }

        @Override
        public void rollback() {
            if (!committed && !rolledBack) {
                if (reservation != null) {
                    try { reservation.rollback(); }
                    catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                }
                rolledBack = true;
                ops.clear();
            }
        }

        @Override
        public void close() {
            rollback();
        }

        private void ensureActive() {
            if (committed || rolledBack || reservation != null) {
                throw new IllegalStateException("Transaction already finished");
            }
        }
    }
}
