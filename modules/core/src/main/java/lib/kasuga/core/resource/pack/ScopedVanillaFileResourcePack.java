package lib.kasuga.core.resource.pack;

import lib.kasuga.core.vfs.VirtualFileSystem;
import lib.kasuga.core.vfs.backend.ZipVfs;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

public class ScopedVanillaFileResourcePack implements FlattenScopedPackResources, HierarchicalScopedPackResources {
    private final ZipFile zipFile;
    private final VfsScopedPackResourcesAdapter delegate;

    public ScopedVanillaFileResourcePack(ZipFile zipFile) {
        this.zipFile = zipFile;
        this.delegate = new VfsScopedPackResourcesAdapter(new ZipVfs(zipFile.getName(), zipFile, false));
    }

    public VirtualFileSystem vfs() {
        return delegate.vfs();
    }

    public VfsScopedPackResourcesAdapter asScopedAdapter() {
        return delegate;
    }

    @Override
    public InputStream open(String prefix, String path) throws IOException {
        return delegate.open(prefix, path);
    }

    @Override
    public boolean exists(String prefix, String path) {
        return delegate.exists(prefix, path);
    }

    @Override
    public Stream<String> listEntries(String prefix) {
        return delegate.listEntries(prefix);
    }

    @Override
    public boolean isRegularFile(String prefix, String path) {
        return delegate.isRegularFile(prefix, path);
    }

    @Override
    public boolean isDirectory(String prefix, String path) {
        return delegate.isDirectory(prefix, path);
    }

    @Override
    public List<String> list(String prefix, String path) throws IOException {
        return delegate.list(prefix, path);
    }

    @Override
    public String getName() {
        return zipFile.getName();
    }
}
