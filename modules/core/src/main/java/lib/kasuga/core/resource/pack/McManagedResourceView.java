package lib.kasuga.core.resource.pack;

import lib.kasuga.core.resource.source.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.*;
import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** Borrowed manager, refreshed by its owner. Never closes a pack or manager. */
public final class McManagedResourceView {
    private final Supplier<ResourceManager> manager;
    private final PackType type;
    public McManagedResourceView(Supplier<ResourceManager> manager, PackType type) { this.manager = manager; this.type = type; }
    public Optional<Resource> find(ResourceLocation id) { return manager.get().getResource(id); }
    public List<Resource> stack(ResourceLocation id) { return List.copyOf(manager.get().getResourceStack(id)); }
    /** Host assigns stable source/root IDs and ranks to MC's actual pack provenance. */
    public List<ResourceSnapshot> capture(String prefix, Predicate<ResourceLocation> filter,
                                           Function<String, ResourceSourceDescriptor> provenance, InputPolicy policy) throws IOException {
        Map<ResourceSourceDescriptor, ResourceSnapshot.Builder> builders = new LinkedHashMap<>();
        List<ResourceSnapshot> snapshots = new ArrayList<>(); boolean success = false;
        try {
            var resources = new TreeMap<>(manager.get().listResourceStacks(prefix, filter));
            for (var entry : resources.entrySet()) for (Resource resource : entry.getValue()) {
                ResourceSourceDescriptor source = provenance.apply(resource.sourcePackId());
                if (source.origin() != ResourceSourceDescriptor.Origin.MC_MANAGED) throw new IllegalArgumentException("MC provenance must be MC_MANAGED");
                var builder = builders.computeIfAbsent(source, key -> new ResourceSnapshot.Builder(key, policy));
                String path = type.getDirectory() + "/" + entry.getKey().getNamespace() + "/" + entry.getKey().getPath();
                builder.add(path, path, resource::open);
            }
            for (var builder : builders.values()) snapshots.add(builder.build());
            success = true; return List.copyOf(snapshots);
        } finally {
            for (var builder : builders.values()) builder.close();
            if (!success) for (var snapshot : snapshots) snapshot.close();
        }
    }
}
