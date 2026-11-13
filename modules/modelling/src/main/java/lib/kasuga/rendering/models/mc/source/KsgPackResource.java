package lib.kasuga.rendering.models.mc.source;

import lib.kasuga.core.resource.pack.ScopedPackResources;
import lib.kasuga.core.resource.pack.VfsBackedPackResources;
import lib.kasuga.core.resource.pack.VfsPackResourcesBridge;
import lib.kasuga.core.resource.pack.VfsScopedPackResourcesAdapter;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.VfsWriteTransaction;
import lib.kasuga.core.vfs.VirtualFileSystem;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public abstract class KsgPackResource<UrlType> extends PathPackResources implements VfsBackedPackResources {

    private final HashMap<UrlType, IoSupplier<InputStream>> resources;
    private final Map<UrlType, VfsPath> mappedPaths;
    private final InMemoryVfs vfs;
    private final VfsPackResourcesBridge bridge;

    public KsgPackResource(PackLocationInfo location) {
        super(location, Path.of("dummy"));
        this.resources = new HashMap<>();
        this.mappedPaths = new HashMap<>();
        this.vfs = new InMemoryVfs("ksg-pack:" + location.id());
        this.bridge = new VfsPackResourcesBridge(this.vfs, location, true);
    }

    public abstract UrlType getUrl(String str);

    public abstract UrlType getUrl(ResourceLocation location);

    protected @Nullable VfsPath toVfsPath(UrlType url) {
        if (url == null) {
            return null;
        }
        if (url instanceof ResourceLocation rl) {
            return VfsPath.of(PackType.CLIENT_RESOURCES.getDirectory() + "/" + rl.getNamespace() + "/" + rl.getPath());
        }
        String raw = url.toString();
        try {
            if (raw.startsWith(PackType.CLIENT_RESOURCES.getDirectory() + "/")
                    || raw.startsWith(PackType.SERVER_DATA.getDirectory() + "/")) {
                return ScopedPackResources.toLogicalPath(raw);
            }
            ResourceLocation parsed = ResourceLocation.tryParse(raw);
            if (parsed != null) {
                return VfsPath.of(PackType.CLIENT_RESOURCES.getDirectory()
                        + "/" + parsed.getNamespace()
                        + "/" + parsed.getPath());
            }
            return ScopedPackResources.toLogicalPath(PackType.CLIENT_RESOURCES.getDirectory() + "/minecraft", raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public void addResource(String identifier, IoSupplier<InputStream> supplier) {
        UrlType url = getUrl(identifier);
        addResource(url, supplier);
    }

    public void addResource(UrlType url, IoSupplier<InputStream> supplier) {
        resources.put(url, supplier);
        VfsPath vfsPath = toVfsPath(url);
        if (vfsPath != null) {
            try (VfsWriteTransaction tx = vfs.beginTransaction()) {
                tx.putStream(vfsPath, supplier::get);
                tx.commit();
                mappedPaths.put(url, vfsPath);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write resource to KsgPackResource VFS: " + vfsPath, e);
            }
        }
    }

    public @Nullable IoSupplier<InputStream> getResource(UrlType url) {
        return resources.get(url);
    }

    public boolean hasResource(UrlType url) {
        return resources.containsKey(url);
    }

    public void removeResource(UrlType url) {
        resources.remove(url);
        VfsPath vfsPath = mappedPaths.remove(url);
        if (vfsPath == null) {
            vfsPath = toVfsPath(url);
        }
        if (vfsPath != null) {
            try {
                if (vfs.exists(vfsPath)) {
                    try (VfsWriteTransaction tx = vfs.beginTransaction()) {
                        tx.delete(vfsPath);
                        tx.commit();
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to remove resource from KsgPackResource VFS: " + vfsPath, e);
            }
        }
    }

    public void clearResources() {
        resources.clear();
        if (!mappedPaths.isEmpty()) {
            try (VfsWriteTransaction tx = vfs.beginTransaction()) {
                for (VfsPath vfsPath : mappedPaths.values()) {
                    if (vfs.exists(vfsPath)) {
                        tx.delete(vfsPath);
                    }
                }
                tx.commit();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to clear resources from KsgPackResource VFS", e);
            } finally {
                mappedPaths.clear();
            }
        }
    }

    public int getResourceCount() {
        return resources.size();
    }

    @Override
    public VirtualFileSystem getVfs() {
        return bridge.getVfs();
    }

    @Override
    public VfsScopedPackResourcesAdapter asScopedPackResources() {
        return bridge.asScopedPackResources();
    }

    @Override
    public @Nullable IoSupplier<InputStream> getRootResource(String... elements) {
        return bridge.getRootResource(elements);
    }

    @Override
    public @Nullable IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
        return bridge.getResource(packType, location);
    }

    @Override
    public void listResources(PackType packType, String namespace, String p_path, ResourceOutput resourceOutput) {
        bridge.listResources(packType, namespace, p_path, resourceOutput);
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return bridge.getNamespaces(type);
    }

    @Override
    public <T> @Nullable T getMetadataSection(MetadataSectionSerializer<T> deserializer) throws IOException {
        return bridge.getMetadataSection(deserializer);
    }

    @Override
    public void close() {
        bridge.close();
        super.close();
    }
}
