package lib.kasuga.core.vfs.backend;

import lib.kasuga.core.vfs.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unix-style unified mount-table {@link VirtualFileSystem} routing operations by longest-prefix
 * mount point match, synthesizing virtual ancestor directories, and remapping watch events.
 */
public final class MountTableVfs implements VirtualFileSystem {

    public record MountResolution(VfsPath mountPoint, VirtualFileSystem backend, VfsPath relativePath) {
    }

    private final String sourceId;
    private final ConcurrentHashMap<String, MountEntry> mounts = new ConcurrentHashMap<>();
    private final VfsEventDispatcher dispatcher = new VfsEventDispatcher();
    private final ThreadLocal<List<VfsChangeEvent.EntryChange>> transactionChanges = new ThreadLocal<>();
    private volatile boolean closed = false;

    public MountTableVfs(String sourceId) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
    }

    public VfsSubscription mount(VfsPath mountPoint, VirtualFileSystem backend) {
        return mount(mountPoint, backend, false);
    }

    public VfsSubscription mount(VfsPath mountPoint, VirtualFileSystem backend, boolean closeBackendOnUnmount) {
        Objects.requireNonNull(mountPoint, "mountPoint");
        Objects.requireNonNull(backend, "backend");
        if (closed) {
            throw new IllegalStateException("MountTableVfs '" + sourceId + "' is closed");
        }
        if (backend == this) {
            throw new IllegalArgumentException("Cannot mount MountTableVfs onto itself");
        }

        AtomicBoolean inserted = new AtomicBoolean(false);
        MountEntry entry = mounts.computeIfAbsent(mountPoint.asString(), ignored -> {
            VfsPath mp = mountPoint;
            inserted.set(true);
            VfsSubscription forwardSub = backend.watchAll(event -> {
                List<VfsChangeEvent.EntryChange> prefixed = prefixChanges(mp, event.changes());
                List<VfsChangeEvent.EntryChange> pending = transactionChanges.get();
                if (pending != null && event.origin() == VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE) pending.addAll(prefixed);
                else dispatcher.dispatch(sourceId, event.origin(), prefixed);
            });
            return new MountEntry(mp, backend, closeBackendOnUnmount, forwardSub);
        });

        if (!inserted.get()) {
            throw new IllegalStateException("Mount point already active: " + mountPoint);
        }

        dispatcher.dispatch(
                sourceId,
                VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE,
                List.of(new VfsChangeEvent.EntryChange(
                        VfsChangeEvent.ChangeKind.CREATED,
                        mountPoint,
                        null,
                        VfsNodeStat.directory(mountPoint, System.currentTimeMillis())
                ))
        );

        return new MountHandle(entry);
    }

    public boolean unmount(VfsPath mountPoint) {
        Objects.requireNonNull(mountPoint, "mountPoint");
        MountEntry removed = mounts.remove(mountPoint.asString());
        return finishUnmount(removed);
    }

    private boolean unmount(MountEntry expected) {
        return mounts.remove(expected.mountPoint.asString(), expected) && finishUnmount(expected);
    }

    private boolean finishUnmount(MountEntry removed) {
        if (removed == null) {
            return false;
        }
        removed.closeForwarding();
        if (removed.closeBackendOnUnmount) {
            try {
                removed.backend.close();
            } catch (IOException ignored) {
                // Best-effort close on unmount
            }
        }
        dispatcher.dispatch(
                sourceId,
                VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE,
                List.of(new VfsChangeEvent.EntryChange(
                        VfsChangeEvent.ChangeKind.DELETED,
                        removed.mountPoint,
                        null,
                        VfsNodeStat.missing(removed.mountPoint)
                ))
        );
        return true;
    }

    public Map<VfsPath, VirtualFileSystem> mounts() {
        Map<VfsPath, VirtualFileSystem> snapshot = new TreeMap<>();
        for (MountEntry entry : mounts.values()) {
            snapshot.put(entry.mountPoint, entry.backend);
        }
        return Map.copyOf(snapshot);
    }

    public Optional<MountResolution> resolveMount(VfsPath path) {
        Objects.requireNonNull(path, "path");
        String candidate = path.asString();
        while (true) {
            MountEntry entry = mounts.get(candidate);
            if (entry != null) return Optional.of(new MountResolution(entry.mountPoint, entry.backend, entry.mountPoint.relativize(path)));
            if (candidate.isEmpty()) return Optional.empty();
            int slash = candidate.lastIndexOf('/');
            candidate = slash < 0 ? "" : candidate.substring(0, slash);
        }
    }

    private boolean isVirtualAncestorDirectory(VfsPath path) {
        if (path.isRoot()) {
            return true;
        }
        for (MountEntry mounted : mounts.values()) {
            VfsPath mp = mounted.mountPoint;
            if (mp.startsWith(path) && !mp.equals(path)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMountOrAncestor(VfsPath path) {
        for (MountEntry mounted : mounts.values()) {
            VfsPath mp = mounted.mountPoint;
            if (mp.startsWith(path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public boolean isReadOnly() {
        return mounts.values().stream().allMatch(e -> e.backend.isReadOnly());
    }

    @Override
    public boolean exists(VfsPath path) {
        Objects.requireNonNull(path, "path");
        Optional<MountResolution> res = resolveMount(path);
        if (res.isPresent() && res.get().backend().exists(res.get().relativePath())) {
            return true;
        }
        return isVirtualAncestorDirectory(path);
    }

    @Override
    public VfsNodeStat stat(VfsPath path) throws IOException {
        Objects.requireNonNull(path, "path");
        Optional<MountResolution> res = resolveMount(path);
        if (res.isPresent()) {
            MountResolution r = res.get();
            VfsNodeStat backendStat = r.backend().stat(r.relativePath());
            if (backendStat.exists()) {
                return new VfsNodeStat(
                        path,
                        backendStat.kind(),
                        backendStat.size(),
                        backendStat.lastModifiedMillis(),
                        backendStat.sha256()
                );
            }
        }
        if (isVirtualAncestorDirectory(path)) {
            return VfsNodeStat.directory(path, 0L);
        }
        return VfsNodeStat.missing(path);
    }

    @Override
    public VfsNodeStat statWithHash(VfsPath path) throws IOException {
        Optional<MountResolution> resolution = resolveMount(path);
        if (resolution.isEmpty()) return stat(path);
        var r = resolution.get();
        VfsNodeStat snapshot = r.backend().statWithHash(r.relativePath());
        if (!snapshot.exists()) return stat(path);
        return new VfsNodeStat(path, snapshot.kind(), snapshot.size(), snapshot.lastModifiedMillis(), snapshot.sha256());
    }

    @Override
    public InputStream openRead(VfsPath path) throws IOException {
        Objects.requireNonNull(path, "path");
        Optional<MountResolution> res = resolveMount(path);
        if (res.isPresent() && res.get().backend().exists(res.get().relativePath())) {
            return res.get().backend().openRead(res.get().relativePath());
        }
        if (isVirtualAncestorDirectory(path)) {
            throw new VfsException("Cannot open virtual directory for reading: " + path);
        }
        throw new NoSuchFileException(path.toString());
    }

    @Override
    public List<String> list(VfsPath dir) throws IOException {
        Objects.requireNonNull(dir, "dir");
        TreeSet<String> children = new TreeSet<>();
        boolean matchedDir = dir.isRoot();

        Optional<MountResolution> res = resolveMount(dir);
        if (res.isPresent()) {
            MountResolution r = res.get();
            if (r.backend().isDirectory(r.relativePath())) {
                matchedDir = true;
                children.addAll(r.backend().list(r.relativePath()));
            }
        }

        int childDepthIndex = dir.segmentCount();
        for (MountEntry mounted : mounts.values()) {
            VfsPath mp = mounted.mountPoint;
            if (mp.startsWith(dir) && !mp.equals(dir)) {
                matchedDir = true;
                children.add(mp.segments().get(childDepthIndex));
            }
        }

        if (!matchedDir) {
            throw new NoSuchFileException(dir.toString());
        }
        return List.copyOf(children);
    }

    @Override
    public List<VfsPath> walk(VfsPath prefix) throws IOException {
        Objects.requireNonNull(prefix, "prefix");
        TreeSet<VfsPath> result = new TreeSet<>();
        for (MountEntry entry : mounts.values()) {
            VfsPath mp = entry.mountPoint;
            VfsPath backendPrefix;
            if (prefix.startsWith(mp)) {
                backendPrefix = mp.relativize(prefix);
            } else if (mp.startsWith(prefix)) {
                backendPrefix = VfsPath.ROOT;
            } else {
                continue;
            }
            for (VfsPath relFile : entry.backend.walk(backendPrefix)) {
                VfsPath fullPath = mp.resolve(relFile);
                Optional<MountResolution> owner = resolveMount(fullPath);
                if (owner.isPresent() && owner.get().mountPoint().equals(mp)) {
                    result.add(fullPath);
                }
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void writeBytes(VfsPath path, byte[] content) throws IOException {
        MountResolution res = requireWritableMount(path);
        res.backend().writeBytes(res.relativePath(), content);
    }

    @Override
    public OutputStream openWrite(VfsPath path) throws IOException {
        MountResolution res = requireWritableMount(path);
        return res.backend().openWrite(res.relativePath());
    }

    @Override
    public void mkdirs(VfsPath dir) throws IOException {
        MountResolution res = requireWritableMount(dir);
        res.backend().mkdirs(res.relativePath());
    }

    @Override
    public boolean delete(VfsPath path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (isMountOrAncestor(path)) {
            throw new VfsException("Cannot delete active mount point or its ancestor: " + path);
        }
        MountResolution res = requireWritableMount(path);
        return res.backend().delete(res.relativePath());
    }

    @Override
    public void move(VfsPath from, VfsPath to) throws IOException {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (isMountOrAncestor(from) || isMountOrAncestor(to)) {
            throw new VfsException("Cannot move an active mount point or its ancestor: " + from + " -> " + to);
        }
        MountResolution fromRes = requireWritableMount(from);
        MountResolution toRes = requireWritableMount(to);
        if (fromRes.backend() == toRes.backend()) {
            fromRes.backend().move(fromRes.relativePath(), toRes.relativePath());
            return;
        }
        try (VfsWriteTransaction tx = beginTransaction()) {
            tx.move(from, to).commit();
        }
    }

    @Override
    public VfsWriteTransaction beginTransaction() throws IOException {
        if (closed) {
            throw new VfsException("MountTableVfs '" + sourceId + "' is closed");
        }
        return new MountTableTransaction();
    }

    @Override
    public VfsSubscription watch(VfsPath prefix, VfsWatchListener listener) {
        return dispatcher.register(prefix, listener);
    }

    @Override
    public Optional<VfsChangeEvent> pollExternalChanges() throws IOException {
        if (closed) {
            return Optional.empty();
        }
        List<VfsChangeEvent.EntryChange> combined = new ArrayList<>();
        for (MountEntry entry : mounts.values()) {
            // Backend.pollExternalChanges() already fires entry.forwardSub into our dispatcher
            Optional<VfsChangeEvent> subEvent = entry.backend.pollExternalChanges();
            subEvent.ifPresent(ev -> combined.addAll(prefixChanges(entry.mountPoint, ev.changes())));
        }
        if (combined.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new VfsChangeEvent(
                sourceId,
                VfsChangeEvent.ChangeOrigin.EXTERNAL_DISK,
                0L,
                System.currentTimeMillis(),
                combined
        ));
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        List<MountEntry> entries = new ArrayList<>(mounts.values());
        mounts.clear();
        for (MountEntry entry : entries) {
            entry.closeForwarding();
            if (entry.closeBackendOnUnmount) {
                entry.backend.close();
            }
        }
        dispatcher.closeAll();
    }

    private MountResolution requireWritableMount(VfsPath path) throws VfsException {
        Objects.requireNonNull(path, "path");
        if (closed) {
            throw new VfsException("MountTableVfs '" + sourceId + "' is closed");
        }
        MountResolution res = resolveMount(path)
                .orElseThrow(() -> new VfsReadOnlyException("No mount covers VFS path: " + path));
        if (res.backend().isReadOnly()) {
            throw new VfsReadOnlyException("Mounted backend at '" + res.mountPoint() + "' is read-only for path: " + path);
        }
        return res;
    }

    private static List<VfsChangeEvent.EntryChange> prefixChanges(
            VfsPath mountPoint,
            List<VfsChangeEvent.EntryChange> backendChanges
    ) {
        List<VfsChangeEvent.EntryChange> prefixed = new ArrayList<>(backendChanges.size());
        for (VfsChangeEvent.EntryChange change : backendChanges) {
            VfsPath fullPath = mountPoint.resolve(change.path());
            VfsPath fullPrev = change.previousPath() == null ? null : mountPoint.resolve(change.previousPath());
            VfsNodeStat stat = change.stat() == null ? null : new VfsNodeStat(
                    fullPath,
                    change.stat().kind(),
                    change.stat().size(),
                    change.stat().lastModifiedMillis(),
                    change.stat().sha256()
            );
            prefixed.add(new VfsChangeEvent.EntryChange(change.kind(), fullPath, fullPrev, stat));
        }
        return prefixed;
    }

    private sealed interface StagedOp {
        record PutBytes(VfsPath path, byte[] bytes) implements StagedOp {}
        record PutStream(VfsPath path, VfsWriteTransaction.ContentSource source) implements StagedOp {}
        record Mkdirs(VfsPath dir) implements StagedOp {}
        record Delete(VfsPath path) implements StagedOp {}
        record Move(VfsPath from, VfsPath to) implements StagedOp {}
    }

    private final class MountTableTransaction implements VfsWriteTransaction {
        private final List<StagedOp> ops = new ArrayList<>();
        private boolean committed = false;
        private boolean rolledBack = false;

        @Override
        public VfsWriteTransaction putBytes(VfsPath path, byte[] content) {
            ensureActive();
            ops.add(new StagedOp.PutBytes(Objects.requireNonNull(path, "path"), Arrays.copyOf(content, content.length)));
            return this;
        }

        @Override public VfsWriteTransaction putStream(VfsPath path, ContentSource source) {
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
            ensureActive();
            // Validate every target mount before starting any backend write
            for (StagedOp op : ops) {
                if (op instanceof StagedOp.PutBytes put) {
                    requireWritableMount(put.path());
                } else if (op instanceof StagedOp.PutStream put) {
                    requireWritableMount(put.path());
                } else if (op instanceof StagedOp.Mkdirs mk) {
                    requireWritableMount(mk.dir());
                } else if (op instanceof StagedOp.Delete del) {
                    if (isMountOrAncestor(del.path())) {
                        throw new VfsException("Cannot delete active mount point or its ancestor: " + del.path());
                    }
                    requireWritableMount(del.path());
                } else if (op instanceof StagedOp.Move mv) {
                    if (isMountOrAncestor(mv.from()) || isMountOrAncestor(mv.to())) {
                        throw new VfsException("Cannot move active mount point or its ancestor: " + mv.from());
                    }
                    requireWritableMount(mv.from());
                    requireWritableMount(mv.to());
                }
            }

            boolean crossBackendMove = false;
            for (StagedOp op : ops) {
                if (op instanceof StagedOp.Move mv && requireWritableMount(mv.from()).backend()
                        != requireWritableMount(mv.to()).backend()) crossBackendMove = true;
            }
            Map<VirtualFileSystem, MountGroup> groups = new LinkedHashMap<>();
            List<VfsChangeEvent.EntryChange> previous = transactionChanges.get();
            List<VfsChangeEvent.EntryChange> changes = new ArrayList<>();
            try {
                for (StagedOp op : ops) {
                    if (op instanceof StagedOp.PutBytes put) {
                        MountResolution r = requireWritableMount(put.path());
                        MountGroup group = group(groups, r, crossBackendMove);
                        if (group.tree != null) group.tree.put(r.relativePath(), put.bytes());
                        group.tx.putBytes(r.relativePath(), put.bytes());
                    } else if (op instanceof StagedOp.PutStream put) {
                        MountResolution r = requireWritableMount(put.path());
                        MountGroup group = group(groups, r, crossBackendMove);
                        if (group.tree != null) group.tree.putStream(r.relativePath(), put.source());
                        group.tx.putStream(r.relativePath(), put.source());
                    } else if (op instanceof StagedOp.Mkdirs mk) {
                        MountResolution r = requireWritableMount(mk.dir());
                        MountGroup group = group(groups, r, crossBackendMove);
                        if (group.tree != null) group.tree.mkdirs(r.relativePath());
                        group.tx.mkdirs(r.relativePath());
                    } else if (op instanceof StagedOp.Delete del) {
                        MountResolution r = requireWritableMount(del.path());
                        MountGroup group = group(groups, r, crossBackendMove);
                        if (group.tree != null) group.tree.delete(r.relativePath());
                        group.tx.delete(r.relativePath());
                    } else if (op instanceof StagedOp.Move mv) {
                        MountResolution from = requireWritableMount(mv.from());
                        MountResolution to = requireWritableMount(mv.to());
                        MountGroup source = group(groups, from, crossBackendMove);
                        MountGroup destination = group(groups, to, crossBackendMove);
                        if (from.backend() == to.backend()) {
                            if (source.tree != null) source.tree.move(from.relativePath(), to.relativePath());
                            source.tx.move(from.relativePath(), to.relativePath());
                        } else {
                            source.tree.copyTo(from.relativePath(), destination.tree, to.relativePath(), destination.tx);
                            source.tree.delete(from.relativePath());
                            source.tx.delete(from.relativePath());
                        }
                    }
                }
                transactionChanges.set(changes);
                Map<VfsWriteTransaction, Object> reservations = new java.util.IdentityHashMap<>();
                groups.forEach((backend, group) -> reservations.put(group.tx,
                        group.tx.coordinationKey() == group.tx ? backend : group.tx.coordinationKey()));
                TransactionSupport.commit(groups.values().stream().map(MountGroup::tx).toList(), reservations::get);
                committed = true;
            } finally {
                if (previous == null) transactionChanges.remove();
                else transactionChanges.set(previous);
                for (MountGroup group : groups.values()) group.tx.close();
            }
            if (previous != null) {
                previous.addAll(changes);
                return new VfsChangeEvent(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE,
                        0L, System.currentTimeMillis(), changes);
            }
            VfsChangeEvent event = dispatcher.dispatch(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, changes);
            return event != null ? event : new VfsChangeEvent(sourceId, VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE,
                    0L, System.currentTimeMillis(), List.of());
        }

        private MountGroup group(Map<VirtualFileSystem, MountGroup> groups, MountResolution resolution,
                                 boolean withTree) throws IOException {
            MountGroup group = groups.get(resolution.backend());
            if (group == null) {
                TransactionTree tree = withTree ? new TransactionTree(resolution.backend()) : null;
                group = new MountGroup(resolution.backend().beginTransaction(), tree);
                groups.put(resolution.backend(), group);
            }
            return group;
        }

        @Override
        public void rollback() {
            if (!committed && !rolledBack) {
                rolledBack = true;
                ops.clear();
            }
        }

        @Override
        public void close() {
            rollback();
        }

        private void ensureActive() {
            if (committed || rolledBack) {
                throw new IllegalStateException("Transaction already finished");
            }
        }
    }

    private record MountGroup(VfsWriteTransaction tx, TransactionTree tree) {
    }

    private static final class MountEntry {
        final VfsPath mountPoint;
        final VirtualFileSystem backend;
        final boolean closeBackendOnUnmount;
        final VfsSubscription forwardSubscription;

        private MountEntry(
                VfsPath mountPoint,
                VirtualFileSystem backend,
                boolean closeBackendOnUnmount,
                VfsSubscription forwardSubscription
        ) {
            this.mountPoint = mountPoint;
            this.backend = backend;
            this.closeBackendOnUnmount = closeBackendOnUnmount;
            this.forwardSubscription = forwardSubscription;
        }

        VfsPath mountPoint() {
            return mountPoint;
        }

        VirtualFileSystem backend() {
            return backend;
        }

        void closeForwarding() {
            forwardSubscription.close();
        }
    }

    private final class MountHandle implements VfsSubscription {
        private final MountEntry entry;
        private final AtomicBoolean handleClosed = new AtomicBoolean(false);

        private MountHandle(MountEntry entry) {
            this.entry = entry;
        }

        @Override
        public void close() {
            if (handleClosed.compareAndSet(false, true)) {
                unmount(entry);
            }
        }

        @Override
        public boolean isClosed() {
            return handleClosed.get() || mounts.get(entry.mountPoint.asString()) != entry;
        }

        @Override
        public VfsPath watchedPrefix() {
            return entry.mountPoint;
        }
    }
}
