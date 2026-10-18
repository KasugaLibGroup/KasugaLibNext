package lib.kasuga.rendering.models.mc.source.model;

import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;


public class KasugaPipeLineRouter implements PipeLineRouter {

    private final List<RouteEntry> routeTable = new ArrayList<>();

    public void register(Predicate<ResourceLocation> predicate, Supplier<ModelPipeLine> pipeLineSupplier) {
        routeTable.add(new RouteEntry(predicate, pipeLineSupplier));
    }

    public void registerByExtension(String extension, Supplier<ModelPipeLine> pipeLineSupplier) {
        register(
            loc -> loc.getPath().endsWith(extension),
            pipeLineSupplier
        );
    }

    public void registerByPathPattern(String pattern, Supplier<ModelPipeLine> pipeLineSupplier) {
        register(
            loc -> loc.getPath().contains(pattern),
            pipeLineSupplier
        );
    }

    @Override
    public void registerRoute(Predicate<ResourceLocation> matcher, Supplier<ModelPipeLine> pipelineSupplier) {
        register(matcher, pipelineSupplier);
    }

    @Nullable
    public ModelPipeLine resolve(ResourceLocation modelKey) {
        return findMatchingPipeLine(modelKey);
    }

    @Override
    public Map<ModelPipeLine, List<ResourceLocation>> route(Collection<ResourceLocation> resources) {
        Map<ModelPipeLine, List<ResourceLocation>> result = new HashMap<>();

        for (ResourceLocation resource : resources) {
            ModelPipeLine target = findMatchingPipeLine(resource);
            if (target != null) {
                result.computeIfAbsent(target, k -> new ArrayList<>()).add(resource);
            }
        }
        
        return result;
    }

    private ModelPipeLine findMatchingPipeLine(ResourceLocation resource) {
        for (RouteEntry entry : routeTable) {
            if (entry.predicate.test(resource)) {
                return entry.pipeLineSupplier.get();
            }
        }
        return null;
    }

    private record RouteEntry(Predicate<ResourceLocation> predicate, Supplier<ModelPipeLine> pipeLineSupplier) {
    }
}
