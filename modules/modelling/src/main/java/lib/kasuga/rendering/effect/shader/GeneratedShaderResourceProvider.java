package lib.kasuga.rendering.effect.shader;

import lib.kasuga.core.resource.pack.VfsPackResourcesBridge;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.VfsWriteTransaction;
import lib.kasuga.core.vfs.VirtualFileSystem;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import lib.kasuga.shader.backend.MinecraftShaderBundle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Adapts generated shader text to the resource API consumed by {@code ShaderInstance}. */
final class GeneratedShaderResourceProvider implements ResourceProvider {
    private static final PackLocationInfo LOCATION = new PackLocationInfo(
            "kasuga_generated_shaders",
            Component.literal("Kasuga generated shaders"),
            PackSource.BUILT_IN,
            Optional.empty()
    );

    private final InMemoryVfs vfs;
    private final VfsPackResourcesBridge packResources;
    private final Map<ResourceLocation, VfsPath> resources;

    GeneratedShaderResourceProvider(MinecraftShaderBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        this.vfs = new InMemoryVfs("kasuga_generated_shaders");
        this.packResources = new VfsPackResourcesBridge(this.vfs, LOCATION, true);
        Map<ResourceLocation, VfsPath> converted = new LinkedHashMap<>();
        try (VfsWriteTransaction tx = this.vfs.beginTransaction()) {
            bundle.resources().forEach((path, source) -> {
                ResourceLocation location = resourceLocation(path);
                VfsPath vfsPath = VfsPath.of(path);
                VfsPath existing = converted.putIfAbsent(location, vfsPath);
                if (existing != null) {
                    throw new IllegalArgumentException("Duplicate generated shader resource: " + location);
                }
                tx.putBytes(vfsPath, source.getBytes(StandardCharsets.UTF_8));
            });
            tx.commit();
        } catch (IOException e) {
            this.vfs.close();
            throw new UncheckedIOException("Failed to populate generated shader VFS", e);
        } catch (RuntimeException e) {
            this.vfs.close();
            throw e;
        }
        this.resources = Map.copyOf(converted);
    }

    @Override
    public Optional<Resource> getResource(ResourceLocation location) {
        VfsPath vfsPath = resources.get(location);
        if (vfsPath == null) return Optional.empty();
        return Optional.of(new Resource(packResources, () -> vfs.openRead(vfsPath)));
    }

    ResourceProvider overlay(ResourceProvider fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return location -> {
            Optional<Resource> generated = getResource(location);
            return generated.isPresent() ? generated : fallback.getResource(location);
        };
    }

    Set<ResourceLocation> locations() {
        return resources.keySet();
    }

    PackResources packResources() {
        return packResources;
    }

    VirtualFileSystem vfs() {
        return vfs;
    }

    private static ResourceLocation resourceLocation(String path) {
        if (!path.startsWith("assets/")) {
            throw new IllegalArgumentException("Generated client resource must start with assets/: " + path);
        }
        String relative = path.substring("assets/".length());
        int separator = relative.indexOf('/');
        if (separator <= 0 || separator == relative.length() - 1) {
            throw new IllegalArgumentException("Invalid generated client resource path: " + path);
        }
        return ResourceLocation.fromNamespaceAndPath(
                relative.substring(0, separator), relative.substring(separator + 1)
        );
    }
}
