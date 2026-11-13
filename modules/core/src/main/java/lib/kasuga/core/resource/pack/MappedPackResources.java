package lib.kasuga.core.resource.pack;

import lib.kasuga.core.resource.identity.ResourcePaths;
import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.source.ResourceSnapshot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One concrete source/root layer, never a flattened overlay. Every supplier opens captured bytes. */
public final class MappedPackResources implements PackResources {
    private record Entry(ResourceSnapshot.LocatedResourceFile file, ResourceSnapshot.ResourceLease lease) {}
    private final PackLocationInfo location;
    private final PackType type;
    private final Map<String, Entry> entries = new TreeMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    public MappedPackResources(PackLocationInfo location, List<ResourceRouting.Route> routes) {
        this(location, routes, PackType.CLIENT_RESOURCES);
    }
    public MappedPackResources(PackLocationInfo location, List<ResourceRouting.Route> routes, PackType type) {
        this.location = location; this.type = type;
        try {
            for (ResourceRouting.Route route : routes) {
                String path = route.targetPath();
                if (!path.equals("pack.mcmeta") && !path.equals("pack.png") && !path.startsWith("assets/") && !path.startsWith("data/")) continue;
                validateMinecraftPath(path);
                if ((path.startsWith("assets/kasuga_generated/") || path.startsWith("data/kasuga_generated/")) && route.file().descriptor().source().origin()
                        != lib.kasuga.core.resource.source.ResourceSourceDescriptor.Origin.DERIVED)
                    throw new IllegalArgumentException("Reserved generated resource namespace");
                if (entries.containsKey(path)) throw new IllegalArgumentException("ROUTING_CONFLICT: duplicate projected path: " + path);
                entries.put(path, new Entry(route.file(), route.file().lease()));
            }
        } catch (RuntimeException failure) { close(); throw failure; }
    }
    public static void validateMinecraftPath(String path) {
        ResourcePaths.path(path, false);
        if (path.equals("pack.mcmeta") || path.equals("pack.png")) return;
        String[] parts = path.split("/", 3);
        if (parts.length != 3 || !Set.of("assets", "data").contains(parts[0]) || ResourceLocation.tryBuild(parts[1], parts[2]) == null)
            throw new IllegalArgumentException("Invalid projected Minecraft path: " + path);
    }
    private IoSupplier<InputStream> supplier(String path) {
        Entry entry = entries.get(path);
        return entry == null ? null : () -> {
            if (closed.get()) throw new IOException("STALE_REFERENCE: resource pack closed");
            return entry.file().open(entry.lease());
        };
    }
    @Override public @Nullable IoSupplier<InputStream> getRootResource(String... elements) {
        return supplier(ResourcePaths.path(String.join("/", elements), false));
    }
    @Override public @Nullable IoSupplier<InputStream> getResource(PackType type, ResourceLocation id) {
        return supplier(type.getDirectory() + "/" + id.getNamespace() + "/" + id.getPath());
    }
    @Override public void listResources(PackType type, String namespace, String prefix, ResourceOutput output) {
        String root = type.getDirectory() + "/" + namespace + "/";
        for (String path : entries.keySet()) {
            if (!path.startsWith(root)) continue;
            String relative = path.substring(root.length());
            if (ResourcePaths.contains(prefix, relative)) output.accept(ResourceLocation.fromNamespaceAndPath(namespace, relative), supplier(path));
        }
    }
    @Override public Set<String> getNamespaces(PackType type) {
        Set<String> result = new TreeSet<>(); String prefix = type.getDirectory() + "/";
        for (String path : entries.keySet()) if (path.startsWith(prefix)) result.add(path.substring(prefix.length(), path.indexOf('/', prefix.length())));
        return Set.copyOf(result);
    }
    @Override public <T> @Nullable T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
        var source = supplier("pack.mcmeta");
        if (source == null) {
            // Host-generated metadata, not a rewritten author file. MC 1.21.1 formats.
            var root = new com.google.gson.JsonObject();
            root.addProperty("pack_format", net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(type));
            root.addProperty("description", "Kasuga approved resources");
            if (serializer.getMetadataSectionName().equals("pack")) return serializer.fromJson(root);
            return null;
        }
        try (var input = source.get(); var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var json = GsonHelper.parse(reader);
            return json.has(serializer.getMetadataSectionName()) ? serializer.fromJson(json.getAsJsonObject(serializer.getMetadataSectionName())) : null;
        }
    }
    @Override public PackLocationInfo location() { return location; }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        UncheckedIOException failure = null;
        for (Entry entry : entries.values()) {
            try { entry.lease().close(); } catch (IOException e) { if (failure == null) failure = new UncheckedIOException(e); else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
    }
}
