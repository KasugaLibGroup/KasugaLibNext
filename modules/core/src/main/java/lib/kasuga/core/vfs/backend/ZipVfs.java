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
    private final Map<VfsPath, ZipNodeMeta> nodes = new HashMap<>();
    private final Map<VfsPath, TreeSet<String>> dirChildren = new HashMap<>();
    private final TreeSet<VfsPath> sortedFiles = new TreeSet<>();
    private volatile boolean closed = false;

    public ZipVfs(String sourceId, Path archivePath) throws IOException {
        this(sourceId, new ZipFile(Objects.requireNonNull(archivePath, "archivePath").toFile()), true);
    }

    public ZipVfs(String sourceId, ZipFile zipFile, boolean closeZipOnClose) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.zipFile = Objects.requireNonNull(zipFile, "zipFile");
        this.closeZipOnClose = closeZipOnClose;
        indexArchive();
    }

    private void indexArchive() {
        nodes.put(VfsPath.ROOT, ZipNodeMeta.directory(0L));
        dirChildren.put(VfsPath.ROOT, new TreeSet<>());

        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String rawName = entry.getName();
            if (rawName == null || rawName.isEmpty() || "/".equals(rawName)) {
                continue;
            }
            boolean isDir = entry.isDirectory() || rawName.endsWith("/");
            VfsPath path;
            try {
                path = VfsPath.of(rawName);
            } catch (VfsInvalidPathException invalid) {
                LOGGER.warn("Skipping unsafe ZIP entry '{}' in '{}': {}", rawName, sourceId, invalid.getMessage());
                continue;
            }
            if (path.isRoot()) {
                continue;
            }

            long lastMod = Math.max(0L, entry.getTime());
            ensureParentDirectories(path, lastMod);

            if (isDir) {
                nodes.putIfAbsent(path, ZipNodeMeta.directory(lastMod));
                dirChildren.computeIfAbsent(path, ignored -> new TreeSet<>());
            } else {
                long size = Math.max(0L, entry.getSize());
                nodes.put(path, ZipNodeMeta.file(entry, size, lastMod));
                sortedFiles.add(path);
            }
        }
    }

    private void ensureParentDirectories(VfsPath path, long lastModifiedMillis) {
        VfsPath current = VfsPath.ROOT;
        List<String> segments = path.segments();
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            dirChildren.computeIfAbsent(current, ignored -> new TreeSet<>()).add(segment);
            current = current.resolve(segment);
            if (i < segments.size() - 1) {
                nodes.putIfAbsent(current, ZipNodeMeta.directory(lastModifiedMillis));
                dirChildren.computeIfAbsent(current, ignored -> new TreeSet<>());
            }
        }
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
        TreeSet<String> children = dirChildren.get(dir);
        return children == null ? List.of() : List.copyOf(children);
    }

    @Override
    public List<VfsPath> walk(VfsPath prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (!nodes.containsKey(prefix)) {
            return List.of();
        }
        List<VfsPath> result = new ArrayList<>();
        for (VfsPath file : sortedFiles) {
            if (file.startsWith(prefix)) {
                result.add(file);
            }
        }
        return List.copyOf(result);
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
