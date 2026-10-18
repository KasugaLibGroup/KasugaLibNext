package lib.kasuga.rendering.models.mc.dynamic.fsm;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * In-memory pack stack for the reload-domain tests: a map of virtual files keyed by resource location,
 * with no mod jar and no running game behind it. Shared by the reload orchestrator's test classes.
 */
final class StubResourceManager implements ResourceManager {

    private final Map<ResourceLocation, String> files = new LinkedHashMap<>();

    /** Adds one virtual file; returns this stub for chaining. */
    StubResourceManager add(String namespace, String path, String content) {
        files.put(ResourceLocation.fromNamespaceAndPath(namespace, path), content);
        return this;
    }

    @Override
    public Set<String> getNamespaces() {
        return files.keySet().stream().map(ResourceLocation::getNamespace)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public Optional<Resource> getResource(ResourceLocation location) {
        String content = files.get(location);
        return content == null ? Optional.empty() : Optional.of(resource(content));
    }

    @Override
    public List<Resource> getResourceStack(ResourceLocation location) {
        return getResource(location).map(List::of).orElseGet(List::of);
    }

    @Override
    public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
        Map<ResourceLocation, Resource> result = new TreeMap<>();
        files.forEach((loc, content) -> {
            if (loc.getPath().startsWith(path + "/") && filter.test(loc)) {
                result.put(loc, resource(content));
            }
        });
        return result;
    }

    @Override
    public Map<ResourceLocation, List<Resource>> listResourceStacks(String path,
                                                                   Predicate<ResourceLocation> filter) {
        Map<ResourceLocation, List<Resource>> result = new TreeMap<>();
        listResources(path, filter).forEach((loc, resource) -> result.put(loc, List.of(resource)));
        return result;
    }

    @Override
    public Stream<PackResources> listPacks() {
        return Stream.of();
    }

    private static Resource resource(String content) {
        return new Resource(null, () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
}
