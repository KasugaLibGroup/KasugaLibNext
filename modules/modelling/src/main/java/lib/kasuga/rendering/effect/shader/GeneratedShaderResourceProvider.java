package lib.kasuga.rendering.effect.shader;

import lib.kasuga.shader.backend.MinecraftShaderBundle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Adapts generated shader text to the resource API consumed by {@code ShaderInstance}. */
final class GeneratedShaderResourceProvider implements ResourceProvider {
    private static final PackResources SOURCE = new GeneratedPackResources();

    private final Map<ResourceLocation, byte[]> resources;

    GeneratedShaderResourceProvider(MinecraftShaderBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        Map<ResourceLocation, byte[]> converted = new LinkedHashMap<>();
        bundle.resources().forEach((path, source) -> {
            ResourceLocation location = resourceLocation(path);
            byte[] replaced = converted.putIfAbsent(location, source.getBytes(StandardCharsets.UTF_8));
            if (replaced != null) {
                throw new IllegalArgumentException("Duplicate generated shader resource: " + location);
            }
        });
        resources = Map.copyOf(converted);
    }

    @Override
    public Optional<Resource> getResource(ResourceLocation location) {
        byte[] source = resources.get(location);
        if (source == null) return Optional.empty();
        return Optional.of(new Resource(SOURCE, () -> new ByteArrayInputStream(source)));
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

    private static final class GeneratedPackResources implements PackResources {
        private static final PackLocationInfo LOCATION = new PackLocationInfo(
                "kasuga_generated_shaders",
                Component.literal("Kasuga generated shaders"),
                PackSource.BUILT_IN,
                Optional.empty()
        );

        @Override
        public IoSupplier<InputStream> getRootResource(String... elements) {
            return null;
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
            return null;
        }

        @Override
        public void listResources(PackType packType, String namespace, String path,
                                  ResourceOutput resourceOutput) {
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return Set.of();
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) throws IOException {
            return null;
        }

        @Override
        public PackLocationInfo location() {
            return LOCATION;
        }

        @Override
        public void close() {
        }
    }
}
