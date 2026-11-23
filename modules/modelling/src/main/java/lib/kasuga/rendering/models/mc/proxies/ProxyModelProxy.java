package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.output.PoseOutput;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.PlaybackCommandApplierFactory;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetOutputEvaluator;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublicationFactory;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyCommandPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyComposition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyControllerPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyPlaybackRoot;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyVisualPort;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shared model host for the generic {@code proxy_model} block factory.
 *
 * <p>The default composition renders the selected native model and owns only its root transform.
 * A consuming mod may override {@link ProxyModelBlock#createProxyComposition(ProxyModelBlockEntity)}
 * to attach a typed controller, playback roots, command application and a model-pose evaluator.
 */
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

    private static ProxyComposition<ProxyModelBlockEntity, ProxyModelInput, Void, Void, Void>
    create(
            ProxyModelBlockEntity element
    ) {
        if (element.getBlockState().getBlock() instanceof ProxyModelBlock block) {
            return block.createProxyComposition(element);
        }
        return defaultComposition(element);
    }

    public static ProxyComposition<ProxyModelBlockEntity, ProxyModelInput, Void, Void, Void>
    defaultComposition(ProxyModelBlockEntity element) {
        return builder(element).build();
    }

    public static CompositionBuilder builder(ProxyModelBlockEntity element) {
        return new CompositionBuilder(element);
    }

    private static Transform rootTransform(ProxyModelBlockEntity element) {
        return rootTransform(
                element, element.resolveGroup(element.getBlockState()).yOffset());
    }

    private static Transform rootTransform(
            ProxyModelBlockEntity element,
            float groupYOffset
    ) {
        BlockPos pos = element.getBlockPos();
        float yaw = ProxyModelBlock.facingYaw(element.getBlockState());
        if (element.getBlockState().hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            String facing = element.getBlockState().getValue(
                    BlockStateProperties.HORIZONTAL_FACING
            ).getSerializedName();
            yaw = element.facingYaw().getOrDefault(facing, yaw + element.yawOffset());
        } else {
            yaw += element.yawOffset();
        }
        return applyBlockstateYaw(
                new Transform().translate(
                        pos.getX() + 0.5f,
                        pos.getY() + groupYOffset,
                        pos.getZ() + 0.5f
                ),
                yaw
        );
    }

    /**
     * Applies a horizontal {@code facing} y value to the whole-model root.
     *
     * <p>Minecraft's positive blockstate Y turns a north-facing model toward east;
     * {@link Transform}'s positive Y turns it toward west. Keep that sign conversion at
     * this proxy boundary and do not delegate to element-local rotation helpers.
     */
    static Transform applyBlockstateYaw(Transform transform, float yaw) {
        return transform.rotate(0f, -yaw, 0f, true);
    }

    public static final class CompositionBuilder {
        private final ProxyModelBlockEntity element;
        private final DeferredModelPoseTarget poseTarget = new DeferredModelPoseTarget();
        private final Visual visual;
        private final List<ProxyPlaybackRoot> playbackRoots = new ArrayList<>();
        private ProxyControllerPort<ProxyModelInput, Void> controllerPort =
                ProxyControllerPort.none();
        private TargetOutputEvaluator<PoseOutput> poseEvaluator = ignored -> null;
        @Nullable
        private PlaybackCommandApplierFactory commandApplierFactory;

        private CompositionBuilder(ProxyModelBlockEntity element) {
            this.element = Objects.requireNonNull(element, "element");
            this.visual = new Visual(element, poseTarget);
        }

        public CompositionBuilder controller(
                ProxyControllerPort<ProxyModelInput, Void> controllerPort
        ) {
            this.controllerPort = Objects.requireNonNull(controllerPort, "controllerPort");
            return this;
        }

        /** Samples native model animation into the deferred pose target on each committed step. */
        public CompositionBuilder poseEvaluator(TargetOutputEvaluator<PoseOutput> evaluator) {
            this.poseEvaluator = Objects.requireNonNull(evaluator, "evaluator");
            return this;
        }

        public CompositionBuilder playbackRoot(ProxyPlaybackRoot root) {
            playbackRoots.add(Objects.requireNonNull(root, "root"));
            return this;
        }

        public CompositionBuilder commandApplier(PlaybackCommandApplierFactory factory) {
            this.commandApplierFactory = Objects.requireNonNull(factory, "factory");
            return this;
        }

        /** Current model instance; null until the native model has mounted. */
        public Supplier<@Nullable ModelInstance> modelInstance() {
            return visual::instance;
        }

        public ProxyComposition<ProxyModelBlockEntity, ProxyModelInput, Void, Void, Void>
        build() {
            List<TargetBinding<?>> targets = new ArrayList<>(visual.targetBindings());
            targets.add(new TargetBinding<>(
                    poseTarget, poseEvaluator, new TargetPublicationFactory<>()));
            return ProxyComposition.of(
                    POLICY,
                    element -> new ProxyModelInput(element.getBlockState()),
                    new ProxyControllerPort<>() {
                        @Override
                        public void prepare(ProxyModelInput input) {
                            visual.prepareLogicalStep(input.state());
                            controllerPort.prepare(input);
                        }

                        @Override
                        public lib.kasuga.rendering.models.uml.dynamic.animation.scope
                                .RuntimeController<Void> controller() {
                            return controllerPort.controller();
                        }

                        @Override
                        public Void output() {
                            return controllerPort.output();
                        }
                    },
                    (ignored, output) -> {
                    },
                    ProxyCommandPort.none(),
                    visual,
                    targets,
                    playbackRoots,
                    commandApplierFactory
            );
        }
    }

    private static final class Visual
            implements ProxyVisualPort<ProxyModelBlockEntity, Void> {
        private final ProxyModelBlockEntity element;
        private final DeferredModelPoseTarget poseTarget;
        private final ProxyModelSelection selection;
        private final RigidPartVisualStage stage;
        private CompiledModelGroup activeGroup;
        @Nullable
        private AnimationRenderPort renderPort;

        private Visual(
                ProxyModelBlockEntity element,
                DeferredModelPoseTarget poseTarget
        ) {
            this.element = element;
            this.poseTarget = poseTarget;
            selection = element.selection();
            activeGroup = selection.resolve(element.getBlockState());
            stage = createStage(element, activeGroup);
        }

        private static RigidPartVisualStage createStage(
                ProxyModelBlockEntity element,
                CompiledModelGroup group
        ) {
            Level level = element.getLevel();
            if (level == null) {
                throw new IllegalStateException("proxy model block entity requires a level");
            }
            return new RigidPartVisualStage(
                    ProxyIdentity.ofBlock(level, element.getBlockPos()).key(),
                    group,
                    () -> null,
                    rootTransform(element, group.yOffset())
            );
        }

        private List<TargetBinding<?>> targetBindings() {
            return stage.targetBindings();
        }

        private void prepareLogicalStep(BlockState state) {
            CompiledModelGroup group = selection.resolve(state);
            stage.prepareLogicalStep(rootTransform(element, group.yOffset()));
        }

        @Override
        public boolean isReady(ProxyModelBlockEntity ignored) {
            return true;
        }

        @Override
        public void bind(AnimationRenderPort logicalRenderPort) {
            renderPort = Objects.requireNonNull(logicalRenderPort, "logicalRenderPort");
            stage.bind(renderPort);
            bindPoseTarget();
        }

        @Override
        public void publishFrame(Void frame) {
            stage.publishFrame(rootTransform(element, activeGroup.yOffset()));
        }

        @Override
        public void tick(ProxyModelBlockEntity current) {
            CompiledModelGroup selected = selection.resolve(current.getBlockState());
            if (!selected.id().equals(activeGroup.id())) {
                if (!stage.replaceGroup(
                        selected, rootTransform(current, selected.yOffset()))) {
                    return;
                }
                activeGroup = selected;
            }
            stage.maintainVisuals();
            bindPoseTarget();
        }

        @Override
        public void suspend() {
            stage.suspend();
        }

        @Override
        public void resume() {
            stage.resume();
        }

        @Override
        public boolean rebind(ProxyModelBlockEntity current) {
            CompiledModelGroup selected = selection.resolve(current.getBlockState());
            if (!selected.id().equals(activeGroup.id())) {
                if (!stage.replaceGroup(
                        selected, rootTransform(current, selected.yOffset()))) {
                    return false;
                }
                activeGroup = selected;
            }
            boolean ready = stage.rebind();
            bindPoseTarget();
            return ready;
        }

        @Override
        public void close() {
            stage.close();
        }

        private void bindPoseTarget() {
            ModelInstance instance = instance();
            if (instance != null) {
                poseTarget.rebind(instance);
            }
        }

        private @Nullable ModelInstance instance() {
            return stage.primaryInstance();
        }
    }
}
