package lib.kasuga.rendering.models.mc.content.block;

import lib.kasuga.rendering.models.mc.registry.RenderBehavior;
import lib.kasuga.rendering.models.mc.registry.RenderFeature;
import lib.kasuga.rendering.models.mc.registry.PipelineBindingRegistry;
import lib.kasuga.rendering.models.mc.registry.pipeline_binding.BlockPipelineBinding;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

public class KasugaRenderComponent {

    private final BlockEntity owner;
    private BlockPipelineBinding binding;
    private final Map<String, FeatureState> featureStates = new HashMap<>();

    private record FeatureState(ResourceLocation instanceKey, ModelInstance modelInstance) {}

    public KasugaRenderComponent(BlockEntity owner) {
        this.owner = owner;
    }

    public void onLoad() {
        if (owner.getLevel() == null || !owner.getLevel().isClientSide()) return;
        this.binding = PipelineBindingRegistry.getBlockBinding(owner.getBlockState().getBlock());
        if (binding == null) return;

        BlockState state = owner.getBlockState();
        BlockPos pos = owner.getBlockPos();
        ResourceKey<Level> dimension = owner.getLevel().dimension();

        for (RenderFeature feature : binding.features()) {
            ResourceLocation instanceKey = binding.featureInstanceKey(pos, dimension, feature.id());
            Transform transform = feature.rootTransform().apply(state, pos);

            ModelInstance instance = feature.pipeline().createInstance(
                    feature.modelKey(), instanceKey, transform, null, null);
            if (instance == null) continue;

            feature.pipeline().addToRenderer(feature.modelKey(), instanceKey, "mc_bridge", "mc_backend");
            featureStates.put(feature.id(), new FeatureState(instanceKey, instance));
        }

        initController();
    }

    public void onRemove() {
        if (binding == null) return;
        for (RenderFeature feature : binding.features()) {
            FeatureState fs = featureStates.get(feature.id());
            if (fs == null) continue;
            feature.pipeline().stopRendering(feature.modelKey(), fs.instanceKey(), "mc_backend");
        }
        featureStates.clear();
    }

    public void tick() {
    }

    public void onBlockStateChanged(BlockState newState) {
    }

    // ===== 内部 =====

    private void initController() {
        RenderBehavior behavior = binding.behavior();
        if (behavior == null) return;
        // 无动画路径暂不创建 ModelController（Issue #26 item 5），
        // behavior 存在时仅保留扩展点。
    }

    public ModelInstance getModelInstance(String featureId) {
        FeatureState fs = featureStates.get(featureId);
        return fs != null ? fs.modelInstance() : null;
    }
}
