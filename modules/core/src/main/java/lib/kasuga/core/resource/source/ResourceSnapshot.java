package lib.kasuga.core.resource.source;

import lib.kasuga.core.resource.identity.ResourcePaths;
import lib.kasuga.core.resource.identity.ResourceUri;
import lib.kasuga.core.resource.view.ResourceDescriptor;
import lib.kasuga.core.resource.view.ResourceTypeDetector;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Immutable captured bytes. Large files are spooled; leases outlive retirement of their owner. */
public final class ResourceSnapshot implements AutoCloseable {
    private final Map<ResourceUri, LocatedResourceFile> files = new TreeMap<>();
    private final List<Path> spoolFiles = new ArrayList<>();
    private int references = 1;
    private boolean retired;

    private ResourceSnapshot() {}

    public List<LocatedResourceFile> files() { return List.copyOf(files.values()); }

    public synchronized ResourceLease lease() {
        if (retired) throw new IllegalStateException("STALE_REFERENCE: snapshot retired");
        references++;
        return new ResourceLease(this);
    }

    private synchronized void retain(ResourceLease existing) {
        existing.requireOpen(this);
        references++;
    }

    private synchronized void release() throws IOException {
        if (--references == 0) {
            for (LocatedResourceFile file : files.values()) file.memory = null;
            IOException failure = null;
            for (Path file : spoolFiles) {
                try { Files.deleteIfExists(file); }
                catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            if (failure != null) throw failure;
        }
    }

    @Override public synchronized void close() throws IOException {
        if (!retired) { retired = true; release(); }
    }

    public static final class ResourceLease implements AutoCloseable {
        private final ResourceSnapshot owner;
        private final AtomicBoolean closed = new AtomicBoolean();
        private ResourceLease(ResourceSnapshot owner) { this.owner = owner; }
        private void requireOpen(ResourceSnapshot expected) {
            if (owner != expected || closed.get()) throw new IllegalStateException("STALE_REFERENCE: closed or foreign lease");
        }
        @Override public void close() throws IOException { if (closed.compareAndSet(false, true)) owner.release(); }
    }

    public static final class LocatedResourceFile {
        private final ResourceSnapshot owner;
        private final ResourceDescriptor descriptor;
        private byte[] memory;
        private final Path spool;
        private LocatedResourceFile(ResourceSnapshot owner, ResourceDescriptor descriptor, byte[] memory, Path spool) {
            this.owner = owner; this.descriptor = descriptor; this.memory = memory; this.spool = spool;
        }
        public ResourceDescriptor descriptor() { return descriptor; }
        public ResourceLease lease() { return owner.lease(); }
        public InputStream open() throws IOException {
            try (ResourceLease lease = lease()) { return open(lease); }
        }
        public InputStream open(ResourceLease lease) throws IOException {
            owner.retain(lease);
            try {
                InputStream stream = memory == null ? Files.newInputStream(spool) : new ByteArrayInputStream(memory);
                return new FilterInputStream(stream) {
                    private boolean closed;
                    @Override public void close() throws IOException {
                        if (!closed) {
                            closed = true;
                            try { super.close(); } finally { owner.release(); }
                        }
                    }
                };
            } catch (IOException | RuntimeException e) { owner.release(); throw e; }
        }
        public byte[] readBytes() throws IOException { try (InputStream stream = open()) { return stream.readAllBytes(); } }
    }

    @FunctionalInterface public interface Opener { InputStream open() throws IOException; }

    public static final class Builder implements AutoCloseable {
        private final ResourceSnapshot snapshot = new ResourceSnapshot();
        private final ResourceSourceDescriptor source;
        private final InputPolicy policy;
        private final NavigableSet<String> logicalPaths = new TreeSet<>();
        private long total;
        private boolean built;
        public Builder(ResourceSourceDescriptor source, InputPolicy policy) { this.source = source; this.policy = policy; }

        public void add(String path, String logicalPath, Opener opener) throws IOException {
            add(ResourceUri.file(source.sourceId(), source.rootId(), path), logicalPath,
                    "", "", Set.of(), opener);
        }

        /** Preserve source metadata and concrete child identity when an adapter adds derived outputs. */
        public void copy(LocatedResourceFile original) throws IOException {
            ResourceDescriptor before = original.descriptor();
            if (!source.ownerId().equals(before.source().ownerId()) || !source.sourceId().equals(before.uri().sourceId())
                    || !source.rootId().equals(before.uri().rootId())) throw new IllegalArgumentException("Cannot copy a foreign provider into this snapshot");
            add(before.uri(), before.logicalPath(), before.type(), before.subtype(), before.tags(), original::open);
            LocatedResourceFile captured = snapshot.files.get(before.uri());
            ResourceDescriptor after = captured.descriptor();
            var retained = new ResourceDescriptor(after.uri(), after.source(), after.logicalPath(), before.type(), before.subtype(),
                    before.typeEvidence(), before.tags(), after.revision(), after.size());
            snapshot.files.put(after.uri(), new LocatedResourceFile(snapshot, retained, captured.memory, captured.spool));
        }

        public void add(ResourceUri uri, String logicalPath, String type, String subtype, Set<String> tags, Opener opener) throws IOException {
            if (built) throw new IllegalStateException("Snapshot already built");
            ResourcePaths.path(logicalPath, false);
            if (snapshot.files.containsKey(uri) || !logicalPaths.add(logicalPath)) throw new IOException("DUPLICATE_ENTRY: " + logicalPath);
            for (int slash = logicalPath.indexOf('/'); slash >= 0; slash = logicalPath.indexOf('/', slash + 1)) {
                if (logicalPaths.contains(logicalPath.substring(0, slash)))
                    throw new IOException("DUPLICATE_ENTRY: file/directory conflict: " + logicalPath);
            }
            String child = logicalPaths.ceiling(logicalPath + "/");
            if (child != null && child.startsWith(logicalPath + "/")) throw new IOException("DUPLICATE_ENTRY: file/directory conflict");
            if (snapshot.files.size() >= policy.maxFiles()) throw new IOException("LIMIT_EXCEEDED: file count");
            long maximum = ResourceDescriptor.extension(logicalPath).equals("json") ? policy.maxJsonBytes() : policy.maxFileBytes();
            ByteArrayOutputStream memory = new ByteArrayOutputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream(ResourceTypeDetector.HEADER_BYTES);
            Path spool = null;
            OutputStream output = memory;
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            long size = 0;
            try (InputStream input = opener.open()) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (count == 0) continue;
                    header.write(buffer, 0, Math.min(count, ResourceTypeDetector.HEADER_BYTES - header.size()));
                    size += count; total += count;
                    if (size > maximum || total > policy.maxTotalBytes()) throw new IOException("LIMIT_EXCEEDED: captured bytes");
                    if (spool == null && size > policy.memoryThresholdBytes()) {
                        spool = Files.createTempFile("kasuga-resource-", ".snapshot");
                        snapshot.spoolFiles.add(spool);
                        output = Files.newOutputStream(spool);
                        memory.writeTo(output);
                    }
                    output.write(buffer, 0, count); digest.update(buffer, 0, count);
                }
            } finally { output.close(); }
            var detected = ResourceTypeDetector.detect(uri.path(), header.toByteArray());
            if ((detected.subtype().equals("json") || type.equals("config") && subtype.equals("json")) && size > policy.maxJsonBytes())
                throw new IOException("LIMIT_EXCEEDED: JSON bytes");
            var provider = uri.childKey().isEmpty() ? source : new ResourceSourceDescriptor(source.ownerId(), source.sourceId(), source.rootId(),
                    source.rank(), ResourceSourceDescriptor.Origin.DERIVED);
            var descriptor = new ResourceDescriptor(uri, provider, logicalPath,
                    type.isEmpty() ? detected.type() : type, type.isEmpty() ? detected.subtype() : subtype,
                    type.isEmpty() ? detected.evidence() : ResourceTypeDetector.Evidence.DECLARED, tags,
                    HexFormat.of().formatHex(digest.digest()), size);
            snapshot.files.put(uri, new LocatedResourceFile(snapshot, descriptor, spool == null ? memory.toByteArray() : null, spool));
        }

        public ResourceSnapshot build() { if (built) throw new IllegalStateException("Already built"); built = true; return snapshot; }
        @Override public void close() throws IOException { if (!built) snapshot.close(); }
    }
}
