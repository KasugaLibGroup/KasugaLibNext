package lib.kasuga.core.resource.pack;

import lib.kasuga.core.vfs.VfsInvalidPathException;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.VirtualFileSystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Bridges any {@link VirtualFileSystem} into Minecraft's {@link PackResources} pipeline
 * without referencing client-only classes (safe on Dedicated Server and Client).
 */
public final class VfsPackResourcesBridge implements PackResources {
    private static final Logger LOGGER = LoggerFactory.getLogger(VfsPackResourcesBridge.class);

    private final VirtualFileSystem vfs;
    private final PackLocationInfo locationInfo;
    private final boolean closeVfsOnClose;
    private final VfsScopedPackResourcesAdapter scopedAdapter;

    public VfsPackResourcesBridge(VirtualFileSystem vfs, PackLocationInfo locationInfo) {
        this(vfs, locationInfo, false);
    }

    public VfsPackResourcesBridge(VirtualFileSystem vfs, PackLocationInfo locationInfo, boolean closeVfsOnClose) {
        this.vfs = Objects.requireNonNull(vfs, "vfs");
        this.locationInfo = Objects.requireNonNull(locationInfo, "locationInfo");
        this.closeVfsOnClose = closeVfsOnClose;
        this.scopedAdapter = new VfsScopedPackResourcesAdapter(vfs);
    }

    public VirtualFileSystem getVfs() {
        return vfs;
    }

    public VfsScopedPackResourcesAdapter asScopedPackResources() {
        return scopedAdapter;
    }

    @Override
    public @Nullable IoSupplier<InputStream> getRootResource(String... elements) {
        if (elements == null || elements.length == 0) {
            return null;
        }
        try {
            VfsPath path = VfsPath.ofSegments(List.of(elements));
            if (vfs.isRegularFile(path)) {
                return () -> vfs.openRead(path);
            }
        } catch (VfsInvalidPathException invalid) {
            return null;
        }
        return null;
    }

    @Override
    public @Nullable IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
        if (packType == null || location == null) {
            return null;
        }
        try {
            VfsPath path = VfsPath.of(packType.getDirectory())
                    .resolve(location.getNamespace())
                    .resolve(location.getPath());
            if (vfs.isRegularFile(path)) {
                return () -> vfs.openRead(path);
            }
        } catch (VfsInvalidPathException invalid) {
            return null;
        }
        return null;
    }

    @Override
    public void listResources(PackType packType, String namespace, String path, ResourceOutput resourceOutput) {
        if (packType == null || namespace == null || path == null || resourceOutput == null) {
            return;
        }
        try {
            VfsPath namespaceRoot = VfsPath.of(packType.getDirectory()).resolve(namespace);
            VfsPath searchRoot = path.isEmpty() ? namespaceRoot : namespaceRoot.resolve(path);
            for (VfsPath file : vfs.walk(searchRoot)) {
                String relative = namespaceRoot.relativize(file).asString();
                ResourceLocation rl = ResourceLocation.tryBuild(namespace, relative);
                if (rl != null) {
                    resourceOutput.accept(rl, () -> vfs.openRead(file));
                }
            }
        } catch (VfsInvalidPathException | IOException e) {
            LOGGER.warn("Failed to list VFS resources for {}:{}/{}", namespace, packType.getDirectory(), path, e);
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        if (type == null) {
            return Set.of();
        }
        VfsPath typeDir = VfsPath.of(type.getDirectory());
        if (!vfs.isDirectory(typeDir)) {
            return Set.of();
        }
        try {
            TreeSet<String> namespaces = new TreeSet<>();
            for (String child : vfs.list(typeDir)) {
                if (ResourceLocation.isValidNamespace(child) && vfs.isDirectory(typeDir.resolve(child))) {
                    namespaces.add(child);
                }
            }
            return Set.copyOf(namespaces);
        } catch (IOException e) {
            return Set.of();
        }
    }

    @Override
    public <T> @Nullable T getMetadataSection(MetadataSectionSerializer<T> deserializer) throws IOException {
        IoSupplier<InputStream> mcmeta = getRootResource(PACK_META);
        if (mcmeta == null) {
            return null;
        }
        try (InputStream stream = mcmeta.get();
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var json = GsonHelper.parse(reader);
            String sectionName = deserializer.getMetadataSectionName();
            if (!json.has(sectionName)) {
                return null;
            }
            return deserializer.fromJson(GsonHelper.getAsJsonObject(json, sectionName));
        } catch (RuntimeException invalidMetadata) {
            LOGGER.error("Couldn't load {} metadata from VFS '{}'", deserializer.getMetadataSectionName(), vfs.sourceId(), invalidMetadata);
            return null;
        }
    }

    @Override
    public PackLocationInfo location() {
        return locationInfo;
    }

    @Override
    public void close() {
        if (closeVfsOnClose) {
            try {
                vfs.close();
            } catch (IOException e) {
                LOGGER.warn("Error closing bridged VFS '{}'", vfs.sourceId(), e);
            }
        }
    }
}
