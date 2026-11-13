package lib.kasuga.core.vfs.backend;

import lib.kasuga.core.vfs.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.zip.ZipFile;

/**
 * Read-only {@link VirtualFileSystem} backed by a ZIP or JAR archive.
 * Builds a complete hierarchical index (including implicit parent directories) on open
 * and ignores unsafe Zip-Slip entries.
 */
public final class ZipVfs implements VirtualFileSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZipVfs.class);

    private final String sourceId;
    private final ZipFile zipFile;
    private final boolean closeZipOnClose;
    private final VfsEventDispatcher dispatcher = new VfsEventDispatcher();
    private static final Map<ZipFile, Index> OPEN_ARCHIVE_INDEXES = new WeakHashMap<>();
    private record Index(Map<VfsPath, ZipNodeMeta> nodes, Map<VfsPath, List<String>> children,
                         NavigableMap<String, VfsPath> files) {}
    private final Map<VfsPath, ZipNodeMeta> nodes;
    private final Map<VfsPath, List<String>> dirChildren;
    private final NavigableMap<String, VfsPath> sortedFiles;
    private volatile boolean closed = false;

    public ZipVfs(String sourceId, Path archivePath) throws IOException {
        this(sourceId, new ZipFile(Objects.requireNonNull(archivePath, "archivePath").toFile()), true);
    }

    public ZipVfs(String sourceId, ZipFile zipFile, boolean closeZipOnClose) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.zipFile = Objects.requireNonNull(zipFile, "zipFile");
        this.closeZipOnClose = closeZipOnClose;
        zipFile.size(); // A cached index must not make an already closed handle appear usable.
        Index index;
        synchronized (zipFile) {
            synchronized (OPEN_ARCHIVE_INDEXES) { index = OPEN_ARCHIVE_INDEXES.get(zipFile); }
            if (index == null) {
                index = indexArchive(zipFile, sourceId);
                synchronized (OPEN_ARCHIVE_INDEXES) { OPEN_ARCHIVE_INDEXES.put(zipFile, index); }
            }
        }
        this.nodes = index.nodes(); this.dirChildren = index.children(); this.sortedFiles = index.files();
    }

    private static Index indexArchive(ZipFile zipFile, String sourceId) {
        Map<VfsPath, ZipNodeMeta> nodes = new HashMap<>();
        Map<VfsPath, TreeSet<String>> children = new HashMap<>();
        NavigableMap<String, VfsPath> files = new TreeMap<>();
        Map<String, VfsPath> parents = new HashMap<>();
        parents.put("", VfsPath.ROOT);
        nodes.put(VfsPath.ROOT, ZipNodeMeta.directory(0L));
        children.put(VfsPath.ROOT, new TreeSet<>());
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String raw = entry.getName();
            if (raw == null || raw.isEmpty() || raw.equals("/")) continue;
            VfsPath path;
            try { path = VfsPath.of(raw); }
            catch (VfsInvalidPathException invalid) { LOGGER.warn("Skipping unsafe ZIP entry '{}' in '{}': {}", raw, sourceId, invalid.getMessage()); continue; }
            if (path.isRoot()) continue;
            long modified = Math.max(0L, entry.getTime());
            VfsPath parent = VfsPath.ROOT;
            String normalized = path.asString(); int end = 0;
            for (int i = 0; i < path.segmentCount(); i++) {
                String segment = path.segments().get(i);
                children.computeIfAbsent(parent, ignored -> new TreeSet<>()).add(segment);
                if (i == path.segmentCount() - 1) break;
                end += segment.length();
                String key = normalized.substring(0, end);
                parent = parents.computeIfAbsent(key, VfsPath::of); end++;
                nodes.putIfAbsent(parent, ZipNodeMeta.directory(modified));
            }
            if (entry.isDirectory() || raw.endsWith("/")) {
                nodes.putIfAbsent(path, ZipNodeMeta.directory(modified)); children.computeIfAbsent(path, ignored -> new TreeSet<>());
            } else { nodes.put(path, ZipNodeMeta.file(entry, Math.max(0L, entry.getSize()), modified)); files.put(normalized, path); }
        }
        Map<VfsPath, List<String>> immutableChildren = new HashMap<>();
        children.forEach((path, names) -> immutableChildren.put(path, List.copyOf(names)));
        // No reference to the ZipFile is retained in this cache value; weak keys can be collected.
        return new Index(nodes, immutableChildren, files);
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public boolean exists(VfsPath path) {
        Objects.requireNonNull(path, "path");
        return nodes.containsKey(path);
    }

    @Override
    public VfsNodeStat stat(VfsPath path) {
        Objects.requireNonNull(path, "path");
        ZipNodeMeta meta = nodes.get(path);
        if (meta == null) {
            return VfsNodeStat.missing(path);
        }
        return meta.kind == VfsNodeKind.DIRECTORY
                ? VfsNodeStat.directory(path, meta.lastModifiedMillis)
                : VfsNodeStat.file(path, meta.size, meta.lastModifiedMillis, Optional.empty());
    }

    @Override
    public InputStream openRead(VfsPath path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (closed) {
            throw new VfsException("ZipVfs '" + sourceId + "' is closed");
        }
        ZipNodeMeta meta = nodes.get(path);
        if (meta == null || meta.kind != VfsNodeKind.FILE || meta.entry == null) {
            throw new NoSuchFileException(path.toString());
        }
        return zipFile.getInputStream(meta.entry);
    }

    @Override
    public List<String> list(VfsPath dir) throws IOException {
        Objects.requireNonNull(dir, "dir");
        ZipNodeMeta meta = nodes.get(dir);
        if (meta == null || meta.kind != VfsNodeKind.DIRECTORY) {
            throw new NoSuchFileException(dir.toString());
        }
        return dirChildren.getOrDefault(dir, List.of());
    }

    @Override
    public List<VfsPath> walk(VfsPath prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (!nodes.containsKey(prefix)) {
            return List.of();
        }
        if (prefix.isRoot()) return List.copyOf(sortedFiles.values());
        if (sortedFiles.containsKey(prefix.asString())) return List.of(sortedFiles.get(prefix.asString()));
        return List.copyOf(sortedFiles.subMap(prefix.asString() + "/", true, prefix.asString() + "0", false).values());
    }

    @Override
    public void writeBytes(VfsPath path, byte[] content) throws IOException {
        throw readOnlyError();
    }

    @Override
    public OutputStream openWrite(VfsPath path) throws IOException {
        throw readOnlyError();
    }

    @Override
    public void mkdirs(VfsPath dir) throws IOException {
        throw readOnlyError();
    }

    @Override
    public boolean delete(VfsPath path) throws IOException {
        throw readOnlyError();
    }

    @Override
    public void move(VfsPath from, VfsPath to) throws IOException {
        throw readOnlyError();
    }

    @Override
    public VfsWriteTransaction beginTransaction() throws IOException {
        throw readOnlyError();
    }

    private VfsReadOnlyException readOnlyError() {
        return new VfsReadOnlyException("ZipVfs '" + sourceId + "' is read-only");
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
    public void close() throws IOException {
        closed = true;
        dispatcher.closeAll();
        if (closeZipOnClose) {
            zipFile.close();
        }
    }

    private record ZipNodeMeta(VfsNodeKind kind, ZipEntry entry, long size, long lastModifiedMillis) {
        static ZipNodeMeta directory(long lastModifiedMillis) {
            return new ZipNodeMeta(VfsNodeKind.DIRECTORY, null, 0L, lastModifiedMillis);
        }

        static ZipNodeMeta file(ZipEntry entry, long size, long lastModifiedMillis) {
            return new ZipNodeMeta(VfsNodeKind.FILE, entry, size, lastModifiedMillis);
        }
    }
}
