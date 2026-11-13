package lib.kasuga.core.resource.view;

import lib.kasuga.core.resource.identity.*;
import lib.kasuga.core.resource.source.ResourceSnapshot;
import lib.kasuga.core.vfs.*;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Revocable capability view. No global VFS, physical path, mount, manager or engine is exposed. */
public final class ResourceAccess implements AutoCloseable {
    public enum Operation { READ, QUERY, WRITE, CREATE, DELETE }
    public record Grant(String owner, String source, String root, String prefix, Set<Operation> operations) {
        public Grant {
            ResourcePaths.id(owner); ResourcePaths.id(source); ResourcePaths.id(root); ResourcePaths.path(prefix, true);
            operations = Set.copyOf(operations);
        }
        private boolean permits(ResourceDescriptor d, Operation operation) {
            return owner.equals(d.source().ownerId()) && source.equals(d.uri().sourceId()) && root.equals(d.uri().rootId())
                    && ResourcePaths.contains(prefix, d.uri().path()) && operations.contains(operation);
        }
        private boolean workspace(Workspace workspace, String path, Operation operation) {
            return owner.equals(workspace.owner) && source.equals(workspace.source) && root.equals(workspace.root)
                    && ResourcePaths.contains(prefix, path) && operations.contains(operation);
        }
    }
    /** Host-owned, bounded memory workspace; it cannot route writes to packs, worlds or other mounts. */
    public static final class Workspace implements AutoCloseable {
        private final String owner, source, root;
        private final long maxBytes;
        private final InMemoryVfs files;
        private final Map<String, Long> sizes = new TreeMap<>();
        private long usedBytes;
        private boolean closed;
        public Workspace(String owner, String source, String root, long maxBytes) {
            this.owner = ResourcePaths.id(owner); this.source = ResourcePaths.id(source); this.root = ResourcePaths.id(root);
            if (maxBytes < 1) throw new IllegalArgumentException("Invalid workspace budget");
            this.maxBytes = maxBytes; files = new InMemoryVfs("workspace-" + source);
        }
        @Override public synchronized void close() throws IOException { if (!closed) { closed = true; files.close(); } }
    }
    private final ResourceCatalog catalog;
    private final List<Grant> grants;
    private final Map<String, Workspace> workspaces;
    private final long maxReadBytes, maxTotalReadBytes;
    private final AtomicLong bytesRead = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    public ResourceAccess(ResourceCatalog catalog, List<Grant> grants, Map<String, Workspace> workspaces,
                          long maxReadBytes, long maxTotalReadBytes) {
        this.catalog = catalog; this.grants = List.copyOf(grants); this.workspaces = Map.copyOf(workspaces);
        if (maxReadBytes < 1 || maxReadBytes >= Integer.MAX_VALUE || maxTotalReadBytes < maxReadBytes) throw new IllegalArgumentException("Invalid access budget");
        this.maxReadBytes = maxReadBytes; this.maxTotalReadBytes = maxTotalReadBytes;
    }
    private void live() { if (closed.get()) throw new IllegalStateException("STALE_REFERENCE: resource access revoked"); }
    private boolean permitted(ResourceDescriptor descriptor, Operation operation) {
        return !closed.get() && grants.stream().anyMatch(grant -> grant.permits(descriptor, operation));
    }
    public List<ResourceDescriptor> query(ResourceQuery query) {
        live(); return catalog.query(query, descriptor -> permitted(descriptor, Operation.QUERY));
    }
    public byte[] read(ResourceUri uri, int requestedLimit) throws IOException {
        live();
        if (requestedLimit < 1 || requestedLimit > maxReadBytes) throw new IllegalArgumentException("LIMIT_EXCEEDED: resource read");
        var file = catalog.concrete(uri).filter(f -> permitted(f.descriptor(), Operation.READ))
                .orElseThrow(() -> new SecurityException("PERMISSION_DENIED: resource unavailable"));
        if (file.descriptor().size() > requestedLimit) throw new IOException("LIMIT_EXCEEDED: resource read");
        long count = file.descriptor().size();
        while (true) {
            long previous = bytesRead.get();
            if (count > maxTotalReadBytes - previous) throw new IOException("LIMIT_EXCEEDED: total resource reads");
            if (bytesRead.compareAndSet(previous, previous + count)) break;
        }
        try (InputStream input = file.open()) {
            live(); byte[] bytes = input.readNBytes(requestedLimit + 1); live();
            if (bytes.length > requestedLimit) throw new IOException("LIMIT_EXCEEDED: resource read");
            return bytes;
        }
    }
    public String readText(ResourceUri uri, int limit) throws IOException { return new String(read(uri, limit), StandardCharsets.UTF_8); }
    public void writeText(String workspaceId, String path, String text) throws IOException {
        live(); ResourcePaths.path(path, false); Workspace workspace = workspace(workspaceId);
        if (text.length() > workspace.maxBytes) throw new IOException("LIMIT_EXCEEDED: workspace bytes");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        synchronized (workspace) {
            live(); if (workspace.closed) throw new IllegalStateException("Workspace closed");
            boolean exists = workspace.files.exists(VfsPath.of(path));
            check(workspace, path, exists ? Operation.WRITE : Operation.CREATE);
            long size = workspace.usedBytes - workspace.sizes.getOrDefault(path, 0L);
            if (bytes.length > workspace.maxBytes - size || !exists && workspace.sizes.size() >= 50_000) throw new IOException("LIMIT_EXCEEDED: workspace bytes/files");
            try (VfsWriteTransaction tx = workspace.files.beginTransaction()) { tx.putBytes(VfsPath.of(path), bytes); tx.commit(); }
            workspace.sizes.put(path, (long) bytes.length); workspace.usedBytes = size + bytes.length;
        }
    }
    public boolean delete(String workspaceId, String path) throws IOException {
        live(); ResourcePaths.path(path, false); Workspace workspace = workspace(workspaceId);
        synchronized (workspace) {
            live(); check(workspace, path, Operation.DELETE);
            // Check every descendant too; a directory permission cannot erase a separately denied subtree.
            for (VfsPath child : workspace.files.walk(VfsPath.of(path))) check(workspace, child.asString(), Operation.DELETE);
            boolean deleted = workspace.files.delete(VfsPath.of(path));
            if (deleted) workspace.sizes.entrySet().removeIf(entry -> {
                if (!ResourcePaths.contains(path, entry.getKey())) return false;
                workspace.usedBytes -= entry.getValue(); return true;
            });
            return deleted;
        }
    }
    private Workspace workspace(String id) {
        Workspace workspace = workspaces.get(id); if (workspace == null) throw new SecurityException("PERMISSION_DENIED: workspace unavailable"); return workspace;
    }
    private void check(Workspace workspace, String path, Operation operation) {
        if (grants.stream().noneMatch(grant -> grant.workspace(workspace, path, operation))) throw new SecurityException("PERMISSION_DENIED: workspace operation");
    }
    @Override public void close() { closed.set(true); }
}
