package lib.kasuga.core.vfs.backend;

import lib.kasuga.core.vfs.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe in-memory {@link VirtualFileSystem} supporting atomic writes, staging transactions,
 * and lock-free watch notifications.
 */
public final class InMemoryVfs implements VirtualFileSystem {
    private final String sourceId;
    private final boolean readOnly;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final VfsEventDispatcher dispatcher = new VfsEventDispatcher();
    private final PathIndex<MemoryNode> nodes = new PathIndex<>();
    private volatile boolean closed = false;

    public InMemoryVfs(String sourceId) {
        this(sourceId, false);
    }

    public InMemoryVfs(String sourceId, boolean readOnly) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.readOnly = readOnly;
        this.nodes.put(VfsPath.ROOT, MemoryNode.directory(System.currentTimeMillis()));
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public boolean exists(VfsPath path) {
        Objects.requireNonNull(path, "path");
        lock.readLock().lock();
        try {
            return nodes.containsKey(path);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public VfsNodeStat stat(VfsPath path) {
        Objects.requireNonNull(path, "path");
        lock.readLock().lock();
        try {
            MemoryNode node = nodes.get(path);
            return node == null ? VfsNodeStat.missing(path) : node.toStat(path);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public InputStream openRead(VfsPath path) throws IOException {
        lock.readLock().lock();
        try {
            MemoryNode node = nodes.get(path);
            if (node == null) throw new NoSuchFileException(path.toString());
            if (node.kind != VfsNodeKind.FILE) throw new VfsException("Cannot open directory for reading: " + path);
            return node.content.open();
        } finally { lock.readLock().unlock(); }
    }

    @Override
    public byte[] readBytes(VfsPath path) throws IOException {
        Objects.requireNonNull(path, "path");
        lock.readLock().lock();
        try {
            MemoryNode node = nodes.get(path);
            if (node == null) {
                throw new NoSuchFileException(path.toString());
            }
            if (node.kind != VfsNodeKind.FILE) {
                throw new VfsException("Cannot open directory for reading: " + path);
            }
            return node.content.copy();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<String> list(VfsPath dir) throws IOException {
        Objects.requireNonNull(dir, "dir");
        lock.readLock().lock();
        try {
            MemoryNode dirNode = nodes.get(dir);
            if (dirNode == null || dirNode.kind != VfsNodeKind.DIRECTORY) {
                throw new NoSuchFileException(dir.toString());
            }
            return nodes.children(dir);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<VfsPath> walk(VfsPath prefix) throws IOException {
        Objects.requireNonNull(prefix, "prefix");
        lock.readLock().lock();
        try {
            MemoryNode start = nodes.get(prefix);
            if (start == null) {
                return List.of();
            }
            List<VfsPath> result = new ArrayList<>();
            for (var entry : nodes.subtree(prefix)) {
                if (entry.value().kind == VfsNodeKind.FILE) result.add(entry.path());
            }
            return List.copyOf(result);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void writeBytes(VfsPath path, byte[] content) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        ensureWritable();
        List<VfsChangeEvent.EntryChange> changes;
        lock.writeLock().lock();
        try {
            ensureNotClosed();
            changes = applyPutBytes(nodes, path, content);
        } finally {
            lock.writeLock().unlock();
        }
        dispatcher.dispatch(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, changes);
    }

    private void writeContent(VfsPath path, MemoryContent content) throws IOException {
        List<VfsChangeEvent.EntryChange> changes;
        lock.writeLock().lock();
        try { ensureNotClosed(); changes = applyPutContent(nodes, path, content); }
        finally { lock.writeLock().unlock(); }
        dispatcher.dispatch(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, changes);
    }

    @Override
    public OutputStream openWrite(VfsPath path) throws IOException {
        Objects.requireNonNull(path, "path");
        ensureWritable();
        ensureNotClosed();
        if (path.isRoot()) {
            throw new VfsException("Cannot open root path for writing");
        }
        return new MemoryContent.Builder() {
            private boolean closed;
            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                writeContent(path, finish());
            }
        };
    }

    @Override
    public void mkdirs(VfsPath dir) throws IOException {
        Objects.requireNonNull(dir, "dir");
        ensureWritable();
        List<VfsChangeEvent.EntryChange> changes;
        lock.writeLock().lock();
        try {
            ensureNotClosed();
            changes = applyMkdirs(nodes, dir);
        } finally {
            lock.writeLock().unlock();
        }
        dispatcher.dispatch(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, changes);
    }

    @Override
    public boolean delete(VfsPath path) throws IOException {
        Objects.requireNonNull(path, "path");
        ensureWritable();
        List<VfsChangeEvent.EntryChange> changes;
        lock.writeLock().lock();
        try {
            ensureNotClosed();
            changes = applyDelete(nodes, path);
        } finally {
            lock.writeLock().unlock();
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
        ensureWritable();
        List<VfsChangeEvent.EntryChange> changes;
        lock.writeLock().lock();
        try {
            ensureNotClosed();
            changes = applyMove(nodes, from, to);
        } finally {
            lock.writeLock().unlock();
        }
        dispatcher.dispatch(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, changes);
    }

    @Override
    public VfsWriteTransaction beginTransaction() throws IOException {
        ensureWritable();
        ensureNotClosed();
        return new InMemoryTransaction();
    }

    @Override
    public VfsSubscription watch(VfsPath prefix, VfsWatchListener listener) {
        return dispatcher.register(prefix, listener);
    }

    @Override
    public Optional<VfsChangeEvent> pollExternalChanges() {
        return Optional.empty();
    }

    @Override
    public void close() {
        closed = true;
        dispatcher.closeAll();
    }

    private void ensureWritable() throws VfsReadOnlyException {
        if (readOnly) {
            throw new VfsReadOnlyException("VFS mount '" + sourceId + "' is read-only");
        }
    }

    private void ensureNotClosed() throws VfsException {
        if (closed) {
            throw new VfsException("VFS mount '" + sourceId + "' is closed");
        }
    }

    private static List<VfsChangeEvent.EntryChange> applyPutBytes(
            PathIndex<MemoryNode> targetMap,
            VfsPath path,
            byte[] content
    ) throws IOException {
        return applyPutContent(targetMap, path, MemoryContent.copyOf(content));
    }

    private static List<VfsChangeEvent.EntryChange> applyPutContent(PathIndex<MemoryNode> targetMap, VfsPath path,
                                                                  MemoryContent content) throws IOException {
        if (path.isRoot()) {
            throw new VfsException("Cannot write file to VFS root");
        }
        MemoryNode existing = targetMap.get(path);
        if (existing != null && existing.kind == VfsNodeKind.DIRECTORY) {
            throw new FileAlreadyExistsException(path.toString(), null, "Target is a directory");
        }
        List<VfsChangeEvent.EntryChange> changes = new ArrayList<>();
        VfsPath parent = path.parent();
        if (parent != null && !parent.isRoot()) {
            changes.addAll(applyMkdirs(targetMap, parent));
        }
        MemoryNode node = MemoryNode.file(content, System.currentTimeMillis());
        targetMap.put(path, node);
        changes.add(new VfsChangeEvent.EntryChange(
                existing == null ? VfsChangeEvent.ChangeKind.CREATED : VfsChangeEvent.ChangeKind.MODIFIED,
                path,
                null,
                node.toStat(path)
        ));
        return changes;
    }

    private static List<VfsChangeEvent.EntryChange> applyMkdirs(
            PathIndex<MemoryNode> targetMap,
            VfsPath dir
    ) throws IOException {
        if (dir.isRoot()) {
            return List.of();
        }
        List<VfsChangeEvent.EntryChange> changes = new ArrayList<>();
        List<String> segments = dir.segments();
        VfsPath cursor = VfsPath.ROOT;
        long now = System.currentTimeMillis();
        for (String seg : segments) {
            cursor = cursor.resolve(seg);
            MemoryNode existing = targetMap.get(cursor);
            if (existing == null) {
                MemoryNode created = MemoryNode.directory(now);
                targetMap.put(cursor, created);
                changes.add(new VfsChangeEvent.EntryChange(
                        VfsChangeEvent.ChangeKind.CREATED,
                        cursor,
                        null,
                        created.toStat(cursor)
                ));
            } else if (existing.kind != VfsNodeKind.DIRECTORY) {
                throw new FileAlreadyExistsException(cursor.toString(), null, "Ancestor exists as a file");
            }
        }
        return changes;
    }

    private static List<VfsChangeEvent.EntryChange> applyDelete(
            PathIndex<MemoryNode> targetMap,
            VfsPath path
    ) throws IOException {
        if (path.isRoot()) {
            throw new VfsException("Cannot delete VFS root");
        }
        MemoryNode existing = targetMap.get(path);
        if (existing == null) {
            return List.of();
        }
        List<VfsPath> toRemove = targetMap.subtree(path).stream().map(PathIndex.Entry::path).toList();
        List<VfsChangeEvent.EntryChange> changes = new ArrayList<>(toRemove.size());
        for (int i = toRemove.size() - 1; i >= 0; i--) {
            VfsPath p = toRemove.get(i);
            targetMap.remove(p);
            changes.add(new VfsChangeEvent.EntryChange(
                    VfsChangeEvent.ChangeKind.DELETED,
                    p,
                    null,
                    VfsNodeStat.missing(p)
            ));
        }
        return changes;
    }

    private static List<VfsChangeEvent.EntryChange> applyMove(
            PathIndex<MemoryNode> targetMap,
            VfsPath from,
            VfsPath to
    ) throws IOException {
        if (from.isRoot() || to.isRoot()) {
            throw new VfsException("Cannot move to or from VFS root");
        }
        if (from.equals(to)) {
            return List.of();
        }
        if (from.startsWith(to)) {
            throw new VfsException("Cannot overwrite an ancestor of the source: " + from + " -> " + to);
        }
        MemoryNode source = targetMap.get(from);
        if (source == null) {
            throw new NoSuchFileException(from.toString());
        }
        if (source.kind == VfsNodeKind.DIRECTORY && to.startsWith(from)) {
            throw new VfsException("Cannot move directory into its own descendant: " + from + " -> " + to);
        }

        List<VfsPath> subtree = targetMap.subtree(from).stream().map(PathIndex.Entry::path).toList();
        List<VfsChangeEvent.EntryChange> changes = new ArrayList<>();
        VfsPath destParent = to.parent();
        if (destParent != null && !destParent.isRoot()) {
            changes.addAll(applyMkdirs(targetMap, destParent));
        }
        // Remove conflicting destination if overwriting
        if (targetMap.containsKey(to)) {
            changes.addAll(applyDelete(targetMap, to));
        }

        for (VfsPath srcPath : subtree) {
            MemoryNode node = targetMap.remove(srcPath);
            VfsPath rel = from.relativize(srcPath);
            VfsPath destPath = rel.isRoot() ? to : to.resolve(rel);
            targetMap.put(destPath, node);
            changes.add(new VfsChangeEvent.EntryChange(
                    VfsChangeEvent.ChangeKind.MOVED,
                    destPath,
                    srcPath,
                    node.toStat(destPath)
            ));
        }
        return changes;
    }

    private sealed interface StagedOp {
        record PutBytes(VfsPath path, byte[] bytes) implements StagedOp {}
        record PutStream(VfsPath path, VfsWriteTransaction.ContentSource source) implements StagedOp {}
        record Mkdirs(VfsPath dir) implements StagedOp {}
        record Delete(VfsPath path) implements StagedOp {}
        record Move(VfsPath from, VfsPath to) implements StagedOp {}
    }

    private final class InMemoryTransaction implements VfsWriteTransaction {
        private final List<StagedOp> ops = new ArrayList<>();
        private boolean committed = false;
        private boolean rolledBack = false;
        private VfsPreparedTransaction reservation;
        private final Map<StagedOp, MemoryContent> contents = new IdentityHashMap<>();

        @Override
        public VfsWriteTransaction putBytes(VfsPath path, byte[] content) {
            ensureActive();
            ops.add(new StagedOp.PutBytes(Objects.requireNonNull(path, "path"), Arrays.copyOf(content, content.length)));
            return this;
        }

        @Override
        public Object coordinationKey() { return InMemoryVfs.this; }

        @Override
        public VfsWriteTransaction putStream(VfsPath path, ContentSource source) {
            ensureActive(); ops.add(new StagedOp.PutStream(Objects.requireNonNull(path), Objects.requireNonNull(source))); return this;
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
        public void stage() throws IOException {
            ensureActive(); ensureWritable(); ensureNotClosed();
            for (StagedOp op : ops) {
                if (contents.containsKey(op)) continue;
                if (op instanceof StagedOp.PutBytes put) contents.put(op, MemoryContent.owned(put.bytes()));
                else if (op instanceof StagedOp.PutStream put) {
                    try (InputStream in = put.source().open()) { contents.put(op, MemoryContent.read(in)); }
                }
            }
        }

        @Override
        public VfsPreparedTransaction prepare() throws IOException {
            stage();
            List<VfsChangeEvent.EntryChange> allChanges = new ArrayList<>();
            lock.writeLock().lock();
            try {
                ensureNotClosed();
                TransactionTree view = new TransactionTree(InMemoryVfs.this);
                List<VfsPath> affected = new ArrayList<>();
                for (StagedOp op : ops) {
                    if (op instanceof StagedOp.PutBytes put) {
                        view.put(put.path(), put.bytes()); affected.add(put.path());
                    } else if (op instanceof StagedOp.PutStream put) {
                        view.putStream(put.path(), put.source()); affected.add(put.path());
                    } else if (op instanceof StagedOp.Mkdirs mk) { view.mkdirs(mk.dir()); affected.add(mk.dir()); }
                    else if (op instanceof StagedOp.Delete del) { view.delete(del.path()); affected.add(del.path()); }
                    else if (op instanceof StagedOp.Move mv) { view.move(mv.from(), mv.to()); affected.add(mv.from()); affected.add(mv.to()); }
                }
                List<VfsPath> roots = new ArrayList<>();
                for (VfsPath path : affected) {
                    VfsPath cursor = VfsPath.ROOT;
                    for (String part : path.segments()) { cursor = cursor.resolve(part); if (!nodes.containsKey(cursor)) break; }
                    if (!cursor.isRoot()) roots.add(cursor);
                }
                List<VfsPath> undoRoots = PathIndex.minimalRoots(roots);
                List<PathIndex.Entry<MemoryNode>> originals = new ArrayList<>();
                for (VfsPath path : undoRoots) originals.addAll(nodes.subtree(path));
                reservation = new VfsPreparedTransaction() {
                    private boolean applied;
                    private boolean finished;
                    private VfsChangeEvent event;

                    @Override
                    public void apply() throws IOException {
                        if (finished || applied) throw new IllegalStateException("Participant already applied or finished");
                        ensureNotClosed();
                        applied = true;
                        for (StagedOp op : ops) {
                            if (op instanceof StagedOp.PutBytes put) allChanges.addAll(applyPutContent(nodes, put.path(), contents.get(op)));
                            else if (op instanceof StagedOp.PutStream put) allChanges.addAll(applyPutContent(nodes, put.path(), contents.get(op)));
                            else if (op instanceof StagedOp.Mkdirs mk) allChanges.addAll(applyMkdirs(nodes, mk.dir()));
                            else if (op instanceof StagedOp.Delete del) allChanges.addAll(applyDelete(nodes, del.path()));
                            else if (op instanceof StagedOp.Move mv) allChanges.addAll(applyMove(nodes, mv.from(), mv.to()));
                        }
                    }

                    @Override
                    public void rollback() {
                        if (finished) return;
                        if (applied) {
                            for (VfsPath path : undoRoots) nodes.removeSubtree(path);
                            nodes.putAll(originals);
                        }
                        rolledBack = true;
                        finished = true;
                        contents.clear(); ops.clear(); originals.clear();
                        lock.writeLock().unlock();
                    }

                    @Override
                    public void release() {
                        if (finished || !applied) throw new IllegalStateException("Participant was not applied");
                        committed = true;
                        finished = true;
                        contents.clear(); ops.clear(); originals.clear();
                        lock.writeLock().unlock();
                    }

                    @Override
                    public VfsChangeEvent publish() {
                        if (!committed) throw new IllegalStateException("Participant was not committed");
                        if (event == null) {
                            event = dispatcher.dispatch(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, allChanges);
                            if (event == null) event = new VfsChangeEvent(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE,
                                    0L, System.currentTimeMillis(), List.of());
                        }
                        return event;
                    }
                };
                return reservation;
            } catch (IOException | RuntimeException | Error failure) {
                lock.writeLock().unlock();
                throw failure;
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
                contents.clear(); ops.clear();
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

    private static final class MemoryNode {
        final VfsNodeKind kind;
        final MemoryContent content;
        final long lastModifiedMillis;
        final Optional<String> sha256;

        private MemoryNode(VfsNodeKind kind, MemoryContent content, long lastModifiedMillis, Optional<String> sha256) {
            this.kind = kind;
            this.content = content;
            this.lastModifiedMillis = lastModifiedMillis;
            this.sha256 = sha256;
        }

        static MemoryNode directory(long now) {
            return new MemoryNode(VfsNodeKind.DIRECTORY, null, now, Optional.empty());
        }

        static MemoryNode file(MemoryContent content, long now) {
            return new MemoryNode(VfsNodeKind.FILE, content, now, Optional.of(content.hash));
        }

        VfsNodeStat toStat(VfsPath path) {
            return kind == VfsNodeKind.DIRECTORY
                    ? VfsNodeStat.directory(path, lastModifiedMillis)
                    : VfsNodeStat.file(path, content.size, lastModifiedMillis, sha256);
        }
    }
}
