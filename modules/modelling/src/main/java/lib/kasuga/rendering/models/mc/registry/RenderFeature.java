package lib.kasuga.rendering.models.mc.registry;

import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.BiFunction;

public record RenderFeature(
        String id,
        ResourceLocation modelKey,
        BiFunction<BlockState, BlockPos, Transform> rootTransform
) {
    public ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline() {
        ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> p = PipelineRegistry.resolve(modelKey);
        if (p == null) {
            throw new IllegalStateException("Model key '" + modelKey + "' is not routed to any pipeline");
        }
        return p;
    }
}
