package lib.kasuga.rendering.models.mc.source.model;

import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;


public class KasugaPipeLineRouter implements PipeLineRouter {

    private final List<RouteEntry> routeTable = new ArrayList<>();

    public void register(Predicate<ResourceLocation> predicate, Supplier<ModelPipeLine<ResourceLocation, ResourceLocation>> pipeLineSupplier) {
        routeTable.add(new RouteEntry(predicate, pipeLineSupplier));
    }

    public void registerByExtension(String extension, Supplier<ModelPipeLine<ResourceLocation, ResourceLocation>> pipeLineSupplier) {
        register(
            loc -> loc.getPath().endsWith(extension),
            pipeLineSupplier
        );
    }

    public void registerByPathPattern(String pattern, Supplier<ModelPipeLine<ResourceLocation, ResourceLocation>> pipeLineSupplier) {
        register(
            loc -> loc.getPath().contains(pattern),
            pipeLineSupplier
        );
    }

    @Override
    public void registerRoute(Predicate<ResourceLocation> matcher, Supplier<ModelPipeLine<ResourceLocation, ResourceLocation>> pipelineSupplier) {
        register(matcher, pipelineSupplier);
    }

    @Nullable
    public ModelPipeLine<ResourceLocation, ResourceLocation> resolve(ResourceLocation modelKey) {
        return findMatchingPipeLine(modelKey);
    }

    @Override
    public Map<ModelPipeLine<ResourceLocation, ResourceLocation>, List<ResourceLocation>> route(Collection<ResourceLocation> resources) {
        Map<ModelPipeLine<ResourceLocation, ResourceLocation>, List<ResourceLocation>> result = new HashMap<>();

        for (ResourceLocation resource : resources) {
            ModelPipeLine<ResourceLocation, ResourceLocation> target = findMatchingPipeLine(resource);
            if (target != null) {
                result.computeIfAbsent(target, k -> new ArrayList<>()).add(resource);
            }
        }
        
        return result;
    }

    private ModelPipeLine<ResourceLocation, ResourceLocation> findMatchingPipeLine(ResourceLocation resource) {
        for (RouteEntry entry : routeTable) {
            if (entry.predicate.test(resource)) {
                return entry.pipeLineSupplier.get();
            }
        }
        return null;
    }

    private record RouteEntry(Predicate<ResourceLocation> predicate, Supplier<ModelPipeLine<ResourceLocation, ResourceLocation>> pipeLineSupplier) {
    }
}
