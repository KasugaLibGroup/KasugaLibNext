package lib.kasuga.rendering.models.mc.source.model;

import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

public interface PipeLineRouter {
    
    void registerRoute(Predicate<ResourceLocation> matcher, Supplier<ModelPipeLine> pipelineSupplier);
    
    Map<ModelPipeLine, List<ResourceLocation>> route(Collection<ResourceLocation> resources);
}