package test.kasuga.modelling;

import lib.kasuga.rendering.models.mc.dynamic.fsm.KasugaModelPipelines;
import lib.kasuga.rendering.models.mc.backend.schedule.ModelRenderScheduler;
import lib.kasuga.rendering.models.mc.proxies.BlockEntityProxyDefinitions;
import lib.kasuga.rendering.models.mc.proxies.DeferredModelPoseTarget;
import lib.kasuga.rendering.models.mc.proxies.ProxyIdentity;
import lib.kasuga.rendering.models.mc.dynamic.fsm.sync.FsmSyncClient;
import lib.kasuga.rendering.models.mc.dynamic.fsm.sync.FsmSyncServer;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublicationFactory;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmMachineBuilder;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmPoseEvaluator;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.FsmSyncKey;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyCommandPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyComposition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyControllerPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyInputPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyVisualPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyWritebackPort;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Fan adapter for the element-agnostic kernel. All Minecraft and FSM specifics are confined here;
 * the session only sees typed input/output/command/frame ports.
 */
public final class FanProxyComposition {

    private static final LogicalStepPolicy POLICY = LogicalStepPolicy.fixed(1.0 / 20.0, 5);

    private FanProxyComposition() {
    }

    public static void register(BlockEntityType<FanBlockEntity> type, ResourceLocation id) {
        BlockEntityProxyDefinitions.register(type, id, FanProxyComposition::create);
    }

    public static ProxyComposition<FanBlockEntity, FanInput, FanOutput, FanCommand, FanFrame> create(
            FanBlockEntity element
    ) {
        Level level = element.getLevel();
        if (level == null) {
            throw new IllegalStateException("fan proxy requires an attached level");
        }
        Id machineId = element.machineId();
        if (machineId == null) {
            return noMachine(element, level);
        }

        StateMachine<?> machine = buildMachine(element, machineId, level);
        if (machine == null) {
            throw new IllegalStateException("fan machine definition is not available: " + machineId);
        }
        FsmPoseEvaluator evaluator = new FsmPoseEvaluator(machine, new FanVarProvider());
        FanController controller = new FanController(element, level, machine, evaluator);
        FanVisual visual = new FanVisual(element, level, controller::closeSync);
        DeferredModelPoseTarget target = visual.target();

        ProxyInputPort<FanBlockEntity, FanInput> input = fan -> {
            visual.ensureModel();
            return new FanInput(fan.pendingGear());
        };
        ProxyWritebackPort<FanBlockEntity, FanOutput> writeback = (fan, output) -> {
            if (!fan.getLevel().isClientSide()) {
                fan.applyProxyWriteback(output.gearIndex());
                controller.syncAfterCommit();
            }
        };
        ProxyCommandPort<FanBlockEntity, FanCommand> commands = (fan, command) -> {
            if (command == FanCommand.CYCLE) {
                machine.trigger(FanFixtures.CYCLE);
            }
        };
        List<TargetBinding<?>> targets = element.fanModel() == null
                ? List.of()
                : List.of(new TargetBinding<>(
                        target,
                        evaluator.targetEvaluator(),
                        new TargetPublicationFactory<>()));

        return ProxyComposition.of(
                POLICY, input, controller, writeback, commands, visual, targets);
    }

    private static ProxyComposition<FanBlockEntity, FanInput, FanOutput, FanCommand, FanFrame>
    noMachine(FanBlockEntity element, Level level) {
        FanVisual visual = new FanVisual(element, level, () -> {
        });
        ProxyControllerPort<FanInput, FanOutput> controller = ProxyControllerPort.none();
        return ProxyComposition.of(
                POLICY,
                ignored -> {
                    visual.ensureModel();
                    return new FanInput(-1);
                },
                controller,
                (ignored, output) -> {
                },
                ProxyCommandPort.none(),
                visual,
                List.of());
    }

    private static @Nullable StateMachine<?> buildMachine(
            FanBlockEntity element,
            Id id,
            Level level
    ) {
        var definition = FsmMachineBuilder.findDefinition(id);
        if (definition == null) {
            return null;
        }
        StateMachine<Object> machine = FsmMachineBuilder.build(element, definition, null);
        if (machine == null) {
            return null;
        }
        if (level.isClientSide()) {
            machine.setClientSide(true);
            machine.setLogicEnabled(false);
        }
        return machine;
    }

    private static final class FanController
            implements ProxyControllerPort<FanInput, FanOutput> {

        private final FanBlockEntity element;
        private final Level level;
        private final StateMachine<?> machine;
        private final FsmPoseEvaluator evaluator;
        private final RuntimeController<Void> controller;

        private FanController(FanBlockEntity element, Level level,
                              StateMachine<?> machine, FsmPoseEvaluator evaluator) {
            this.element = element;
            this.level = level;
            this.machine = machine;
            this.evaluator = evaluator;
            this.controller = evaluator.controller();
            if (level.isClientSide()) {
                FsmSyncClient.INSTANCE.bind(syncKey(level), machine);
            }
        }

        @Override
        public void prepare(FanInput input) {
            int pending = element.pendingGear();
            if (pending < 0 || level.isClientSide()) {
                return;
            }
            int target = Math.min(pending, FanFixtures.GEAR_STATE_IDS.length - 1);
            if (gearIndex(machine) == target) {
                element.clearPendingGear();
                return;
            }
            machine.goTo("gear", FanFixtures.GEAR_STATE_IDS[target]);
        }

        @Override
        public RuntimeController<Void> controller() {
            return controller;
        }

        @Override
        public FanOutput output() {
            return new FanOutput(gearIndex(machine));
        }

        private void syncAfterCommit() {
            if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
                FsmSyncServer.GLOBAL.pushToChunkTrackers(
                        syncKey(serverLevel), machine, serverLevel, element.getBlockPos());
            }
        }

        private void closeSync() {
            if (level.isClientSide()) {
                FsmSyncClient.INSTANCE.unbind(syncKey(level));
            } else {
                FsmSyncServer.GLOBAL.unbind(syncKey(level));
            }
        }

        private FsmSyncKey syncKey(Level currentLevel) {
            return new FsmSyncKey(
                    FanFixtures.MACHINE_ID,
                    currentLevel.dimension().location().toString(),
                    element.getBlockPos().asLong());
        }
    }

    private static int gearIndex(StateMachine<?> machine) {
        var gear = machine.layerOrNull("gear");
        int index = gear == null ? -1 : gear.activeStateIndex();
        return Math.max(index, 0);
    }

    private static final class FanVisual implements ProxyVisualPort<FanBlockEntity, FanFrame> {

        private final FanBlockEntity element;
        private final Level level;
        private final DeferredModelPoseTarget target = new DeferredModelPoseTarget();
        private final Runnable closeSync;
        @Nullable private ModelInstance model;
        @Nullable private lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort logicalPort;
        private boolean suspended;
        private boolean closed;

        private FanVisual(FanBlockEntity element, Level level, Runnable closeSync) {
            this.element = element;
            this.level = level;
            this.closeSync = closeSync;
        }

        private DeferredModelPoseTarget target() {
            return target;
        }

        @Override
        public boolean isReady(FanBlockEntity element) {
            return true;
        }

        @Override
        public void bind(lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort port) {
            this.logicalPort = port;
            ensureModel();
        }

        private void ensureModel() {
            if (closed || !level.isClientSide() || element.fanModel() == null) {
                return;
            }
            ResourceLocation instance = ProxyIdentity.ofBlock(level, element.getBlockPos()).key();
            BlockPos pos = element.getBlockPos();
            Transform root = new Transform().translate(pos.getX() + 0.5f, pos.getY(), pos.getZ() + 0.5f);
            ModelInstance fresh = KasugaModelPipelines.createAndBind(
                    element.fanModel(), instance, null, root);
            if (fresh == null) {
                return;
            }
            this.model = fresh;
            target.rebind(fresh);
            if (logicalPort != null) {
                fresh.setAnimationRenderPort(logicalPort);
            }
            ModelRenderScheduler.setVisible(fresh, !suspended);
        }

        @Override
        public void publishFrame(FanFrame frame) {
            // Fan has no frame-domain host input. Its model follows the logical publication.
        }

        @Override
        public void suspend() {
            suspended = true;
            if (model != null) {
                ModelRenderScheduler.setVisible(model, false);
            }
        }

        @Override
        public void resume() {
            suspended = false;
            if (model != null) {
                ModelRenderScheduler.setVisible(model, true);
            }
        }

        @Override
        public boolean rebind(FanBlockEntity element) {
            ensureModel();
            return true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            for (Runnable cleanup : List.of(target::close, this::removeModel, closeSync)) {
                try {
                    cleanup.run();
                } catch (RuntimeException cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            model = null;
            logicalPort = null;
            if (failure != null) {
                throw failure;
            }
        }

        private void removeModel() {
            if (level.isClientSide() && element.fanModel() != null) {
                KasugaModelPipelines.remove(
                        element.fanModel(), null,
                        ProxyIdentity.ofBlock(level, element.getBlockPos()).key());
            }
        }
    }

    public record FanInput(int pendingGear) {
    }

    public record FanOutput(int gearIndex) {
    }

    public record FanFrame() {
    }

    public enum FanCommand {
        CYCLE
    }
}
