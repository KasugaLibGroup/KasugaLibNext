package lib.kasuga.core.vfs.backend;

import lib.kasuga.core.vfs.*;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Aggregated overlay {@link VirtualFileSystem} combining multiple ordered mounts with explicit
 * conflict detection, configurable override policy, and unified watch forwarding.
 */
public final class CompositeVfs implements VirtualFileSystem {
    private final String sourceId;
    private final List<VirtualFileSystem> delegates;
    private final VfsConflictPolicy conflictPolicy;
    private final @Nullable VirtualFileSystem writablePrimary;
    private final VfsEventDispatcher dispatcher = new VfsEventDispatcher();
    private final List<VfsSubscription> delegateSubscriptions = new ArrayList<>();

    public CompositeVfs(String sourceId, List<? extends VirtualFileSystem> delegates, VfsConflictPolicy conflictPolicy) {
        this(sourceId, delegates, conflictPolicy, null);
    }

    public CompositeVfs(
            String sourceId,
            List<? extends VirtualFileSystem> delegates,
            VfsConflictPolicy conflictPolicy,
            @Nullable VirtualFileSystem writablePrimary
    ) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.delegates = List.copyOf(Objects.requireNonNull(delegates, "delegates"));
        this.conflictPolicy = Objects.requireNonNull(conflictPolicy, "conflictPolicy");
        this.writablePrimary = writablePrimary;
        for (VirtualFileSystem delegate : this.delegates) {
            delegateSubscriptions.add(delegate.watchAll(event ->
                    dispatcher.dispatch(this.sourceId, event.origin(), event.changes())));
        }
    }

    /**
     * Scans all delegate mounts under {@code prefix} and returns every regular file path provided
     * by two or more delegates, mapped to the ordered list of conflicting {@code sourceId}s.
     */
    public Map<VfsPath, List<String>> detectConflicts(VfsPath prefix) throws IOException {
        Objects.requireNonNull(prefix, "prefix");
        Map<VfsPath, List<String>> seenBy = new TreeMap<>();
        for (VirtualFileSystem delegate : delegates) {
            for (VfsPath file : delegate.walk(prefix)) {
                seenBy.computeIfAbsent(file, ignored -> new ArrayList<>()).add(delegate.sourceId());
            }
        }
        Map<VfsPath, List<String>> conflicts = new TreeMap<>();
        for (Map.Entry<VfsPath, List<String>> entry : seenBy.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
        return conflicts;
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public boolean isReadOnly() {
        return writablePrimary == null || writablePrimary.isReadOnly();
    }

    @Override
    public boolean exists(VfsPath path) {
        Objects.requireNonNull(path, "path");
        for (VirtualFileSystem delegate : delegates) {
            if (delegate.exists(path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public VfsNodeStat stat(VfsPath path) throws IOException {
        VirtualFileSystem winner = resolveDelegateForPath(path, false);
        return winner == null ? VfsNodeStat.missing(path) : winner.stat(path);
    }

    @Override
    public InputStream openRead(VfsPath path) throws IOException {
        VirtualFileSystem winner = resolveDelegateForPath(path, true);
        if (winner == null) {
            throw new NoSuchFileException(path.toString());
        }
        return winner.openRead(path);
    }

    @Override
    public List<String> list(VfsPath dir) throws IOException {
        Objects.requireNonNull(dir, "dir");
        TreeSet<String> merged = new TreeSet<>();
        boolean foundAnyDir = false;
        for (VirtualFileSystem delegate : delegates) {
            if (delegate.isDirectory(dir)) {
                foundAnyDir = true;
                merged.addAll(delegate.list(dir));
            }
        }
        if (!foundAnyDir) {
            throw new NoSuchFileException(dir.toString());
        }
        return List.copyOf(merged);
    }

    @Override
    public List<VfsPath> walk(VfsPath prefix) throws IOException {
        Objects.requireNonNull(prefix, "prefix");
        TreeSet<VfsPath> merged = new TreeSet<>();
        for (VirtualFileSystem delegate : delegates) {
            merged.addAll(delegate.walk(prefix));
        }
        return List.copyOf(merged);
    }

    @Override
    public void writeBytes(VfsPath path, byte[] content) throws IOException {
        requireWritablePrimary().writeBytes(path, content);
    }

    @Override
    public OutputStream openWrite(VfsPath path) throws IOException {
        return requireWritablePrimary().openWrite(path);
    }

    @Override
    public void mkdirs(VfsPath dir) throws IOException {
        requireWritablePrimary().mkdirs(dir);
    }

    @Override
    public boolean delete(VfsPath path) throws IOException {
        return requireWritablePrimary().delete(path);
    }

    @Override
    public void move(VfsPath from, VfsPath to) throws IOException {
        requireWritablePrimary().move(from, to);
    }

    @Override
    public VfsWriteTransaction beginTransaction() throws IOException {
        return requireWritablePrimary().beginTransaction();
    }

    @Override
    public VfsSubscription watch(VfsPath prefix, VfsWatchListener listener) {
        return dispatcher.register(prefix, listener);
    }

    @Override
    public Optional<VfsChangeEvent> pollExternalChanges() throws IOException {
        List<VfsChangeEvent.EntryChange> combined = new ArrayList<>();
        for (VirtualFileSystem delegate : delegates) {
            Optional<VfsChangeEvent> subEvent = delegate.pollExternalChanges();
            subEvent.ifPresent(ev -> combined.addAll(ev.changes()));
        }
        if (combined.isEmpty()) {
            return Optional.empty();
        }
        // Delegate polling already forwards each notification through our subscriptions.
        return Optional.of(new VfsChangeEvent(sourceId, VfsChangeEvent.ChangeOrigin.EXTERNAL_DISK,
                0L, System.currentTimeMillis(), combined));
    }

    @Override
    public void close() {
        for (VfsSubscription sub : delegateSubscriptions) {
            sub.close();
        }
        delegateSubscriptions.clear();
        dispatcher.closeAll();
    }

    private VirtualFileSystem requireWritablePrimary() throws VfsReadOnlyException {
        if (writablePrimary == null || writablePrimary.isReadOnly()) {
            throw new VfsReadOnlyException("CompositeVfs '" + sourceId + "' has no writable primary mount");
        }
        return writablePrimary;
    }

    private @Nullable VirtualFileSystem resolveDelegateForPath(VfsPath path, boolean checkFileConflict) throws VfsConflictException {
        Objects.requireNonNull(path, "path");
        List<VirtualFileSystem> matching = new ArrayList<>();
        for (VirtualFileSystem delegate : delegates) {
            if (delegate.exists(path)) {
                matching.add(delegate);
            }
        }
        if (matching.isEmpty()) {
            return null;
        }
        if (checkFileConflict && conflictPolicy == VfsConflictPolicy.THROW_ON_CONFLICT && matching.size() > 1) {
            List<VirtualFileSystem> fileMatches = matching.stream()
                    .filter(d -> d.isRegularFile(path))
                    .toList();
            if (fileMatches.size() > 1) {
                List<String> sources = fileMatches.stream().map(VirtualFileSystem::sourceId).toList();
                throw new VfsConflictException(path, sources);
            }
        }
        return conflictPolicy == VfsConflictPolicy.LAST_WINS
                ? matching.get(matching.size() - 1)
                : matching.get(0);
    }
}
