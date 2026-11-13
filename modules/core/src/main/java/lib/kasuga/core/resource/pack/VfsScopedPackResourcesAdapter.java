package lib.kasuga.core.resource.pack;

import lib.kasuga.core.vfs.VfsInvalidPathException;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.VirtualFileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Adapter exposing any {@link VirtualFileSystem} through both {@link HierarchicalScopedPackResources}
 * and {@link FlattenScopedPackResources}.
 */
public final class VfsScopedPackResourcesAdapter implements HierarchicalScopedPackResources, FlattenScopedPackResources {
    private final VirtualFileSystem vfs;

    public VfsScopedPackResourcesAdapter(VirtualFileSystem vfs) {
        this.vfs = Objects.requireNonNull(vfs, "vfs");
    }

    public VirtualFileSystem vfs() {
        return vfs;
    }

    private static VfsPath toVfsPath(String prefix, String path) {
        VfsPath prefixPath = VfsPath.of(prefix == null ? "" : prefix);
        if (path == null || path.isEmpty()) {
            return prefixPath;
        }
        return prefixPath.resolve(path);
    }

    @Override
    public InputStream open(String prefix, String path) throws IOException {
        return vfs.openRead(toVfsPath(prefix, path));
    }

    @Override
    public boolean exists(String prefix, String path) {
        return vfs.exists(toVfsPath(prefix, path));
    }

    @Override
    public boolean isRegularFile(String prefix, String path) {
        return vfs.isRegularFile(toVfsPath(prefix, path));
    }

    @Override
    public boolean isDirectory(String prefix, String path) {
        return vfs.isDirectory(toVfsPath(prefix, path));
    }

    @Override
    public List<String> list(String prefix, String path) throws IOException {
        return vfs.list(toVfsPath(prefix, path));
    }

    @Override
    public Stream<String> listEntries(String prefix) {
        VfsPath prefixPath = VfsPath.of(prefix == null ? "" : prefix);
        try {
            return vfs.walk(prefixPath).stream()
                    .map(entry -> prefixPath.relativize(entry).asString())
                    .filter(s -> !s.isEmpty());
        } catch (IOException | VfsInvalidPathException e) {
            return Stream.empty();
        }
    }

    @Override
    public String getName() {
        return vfs.sourceId();
    }
}
