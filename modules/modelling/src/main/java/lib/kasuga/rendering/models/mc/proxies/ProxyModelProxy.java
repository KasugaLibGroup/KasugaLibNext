package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.mc.api.McModelHandle;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyCommandPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyComposition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyControllerPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyVisualPort;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.List;

/** Static model-only proxy used by the generic {@code proxy_model} block factory. */
public final class ProxyModelProxy {
    private static final LogicalStepPolicy POLICY = LogicalStepPolicy.fixed(1.0 / 20.0, 1);

    private ProxyModelProxy() {
    }

    public static void register(
            BlockEntityType<ProxyModelBlockEntity> type,
            ResourceLocation id
    ) {
        BlockEntityProxyDefinitions.register(type, id, ProxyModelProxy::create);
    }

    private static ProxyComposition<ProxyModelBlockEntity, Void, Void, Void, Void> create(
            ProxyModelBlockEntity element
    ) {
        return ProxyComposition.of(
                POLICY,
                ignored -> null,
                ProxyControllerPort.none(),
                (ignored, output) -> {
                },
                ProxyCommandPort.none(),
                new Visual(element),
                List.of()
        );
    }

    private static Transform rootTransform(ProxyModelBlockEntity element) {
        BlockPos pos = element.getBlockPos();
        return new Transform()
                .translate(pos.getX() + 0.5f, pos.getY(), pos.getZ() + 0.5f)
                .rotate(0f, ProxyModelBlock.facingYaw(element.getBlockState()), 0f, true);
    }

    private static final class Visual
            implements ProxyVisualPort<ProxyModelBlockEntity, Void> {
        private final McModelHandle handle;
        private boolean hidden;

        private Visual(ProxyModelBlockEntity element) {
            Level level = element.getLevel();
            if (level == null) {
                throw new IllegalStateException("proxy model block entity requires a level");
            }
            handle = McModelHandle.of(
                    element.model(),
                    element.modelName(),
                    ProxyIdentity.ofBlock(level, element.getBlockPos()).key(),
                    null
            );
            handle.setPose(rootTransform(element)).scheduleAlways();
        }

        @Override
        public boolean isReady(ProxyModelBlockEntity element) {
            return true;
        }

        @Override
        public void bind(AnimationRenderPort logicalRenderPort) {
        }

        @Override
        public void publishFrame(Void frame) {
        }

        @Override
        public void tick(ProxyModelBlockEntity element) {
            handle.setPose(rootTransform(element));
            handle.mount();
        }

        @Override
        public void suspend() {
            hidden = true;
            handle.hide();
        }

        @Override
        public void resume() {
            hidden = false;
            handle.show();
        }

        @Override
        public boolean rebind(ProxyModelBlockEntity element) {
            handle.setPose(rootTransform(element));
            return handle.mount();
        }

        @Override
        public void close() {
            if (!hidden) {
                handle.hide();
            }
            handle.destroy();
        }
    }
}
