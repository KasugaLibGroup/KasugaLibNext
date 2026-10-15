package lib.kasuga.rendering.models.mc.registry;

import lib.kasuga.rendering.models.mc.registry.pipeline_binding.BlockPipelineBinding;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PipelineBindingRegistry {
    private static final Map<ResourceLocation, BlockPipelineBinding> BLOCK_BINDINGS = new ConcurrentHashMap<>();

    public static void registerBlock(Block block, BlockPipelineBinding binding) {
        BLOCK_BINDINGS.put(BuiltInRegistries.BLOCK.getKey(block), binding);
    }

    public static void registerBlock(ResourceLocation blockId, BlockPipelineBinding binding) {
        BLOCK_BINDINGS.put(blockId, binding);
    }

    public static BlockPipelineBinding getBlockBinding(Block block) {
        return BLOCK_BINDINGS.get(BuiltInRegistries.BLOCK.getKey(block));
    }

    public static BlockPipelineBinding getBlockBinding(ResourceLocation blockId) {
        return BLOCK_BINDINGS.get(blockId);
    }
}
