package lib.kasuga.create.client.rendering;

import lib.kasuga.create.proxy.ContraptionBogey;
import lib.kasuga.rendering.models.mc.proxies.ElementProxy;
import lib.kasuga.rendering.models.mc.proxies.ProxyMachineController;
import lib.kasuga.rendering.models.mc.proxies.ProxyConfig;
import lib.kasuga.rendering.models.mc.proxies.RigidPartVisualStage;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmAnimatedModel;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyAttachToken;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyCommandPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyComposition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyControllerPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyDefinition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyInputPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyKey;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyRuntime;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxySession;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyVisualPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyWritebackPort;
import lib.kasuga.rendering.models.mc.proxies.ProxyIdentity;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.VarProvider;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * External-host adapter for Create's {@code BogeyVisual} lifecycle.
 *
 * <p>The session owns the sole animation scope. Its visual port manages model resources through
 * {@link RigidPartVisualStage}, which can mount and rebind parts but cannot advance logical time.
 */
public final class CreateBogeyProxyAdapter {

    private static final LogicalStepPolicy POLICY = LogicalStepPolicy.fixed(1.0 / 20.0, 5);

    private CreateBogeyProxyAdapter() {
    }

    public static ProxySession<ContraptionBogey, ContraptionBogey.HostFrame, Void, Void,
            ContraptionBogey.HostFrame> attach(
            ContraptionBogey element,
            ElementProxy<ContraptionBogey> template,
            ProxyIdentity identity,
            ProxyAttachToken token,
            @Nullable Level level
    ) {
        return attach(element, template, identity, token, level, null);
    }

    static ProxySession<ContraptionBogey, ContraptionBogey.HostFrame, Void, Void,
            ContraptionBogey.HostFrame> attach(
            ContraptionBogey element,
            ElementProxy<ContraptionBogey> template,
            ProxyIdentity identity,
            ProxyAttachToken token,
            @Nullable Level level,
            @Nullable RigidPartVisualStage.PartHandleFactory handleFactory
    ) {
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(token, "token");

        ProxyKey<ContraptionBogey> key = ProxyKey.external(
                "kasuga_lib", "contraption_bogey/" + identity.discriminator() + "/" + token);
        DetachOnce detach = new DetachOnce(template, element);
        ProxyDefinition<ContraptionBogey, ContraptionBogey.HostFrame, Void, Void,
                ContraptionBogey.HostFrame> definition =
                new ProxyDefinition<>(key, ignored -> composition(
                        element, template, identity, key, level, detach, handleFactory));
        ProxyRuntime.GLOBAL.register(definition);
        try {
            return ProxyRuntime.GLOBAL.attach(definition, element, token)
                    .orElseThrow(() -> new IllegalStateException(
                            "contraption bogey definition did not attach"));
        } catch (RuntimeException | Error failure) {
            ProxyRuntime.GLOBAL.unregister(key);
            detach.suppress(failure);
            throw failure;
        }
    }

    private static ProxyComposition<ContraptionBogey, ContraptionBogey.HostFrame, Void, Void,
            ContraptionBogey.HostFrame> composition(
            ContraptionBogey element,
            ElementProxy<ContraptionBogey> template,
            ProxyIdentity identity,
            ProxyKey<ContraptionBogey> key,
            @Nullable Level level,
            DetachOnce detach,
            @Nullable RigidPartVisualStage.PartHandleFactory handleFactory
    ) {
        ProxyConfig config = configure(element, template, detach);
        if (config.parts().isEmpty()) {
            IllegalArgumentException failure = new IllegalArgumentException(
                    "session-native contraption bogey visuals require at least one model part");
            detach.suppress(failure);
            throw failure;
        }
        FsmAnimatedModel machineHost = null;
        RigidPartVisualStage stage = null;
        try {
            machineHost = config.machine() == null ? null : new FsmAnimatedModel(
                    element,
                    identity.discriminator(),
                    identity.key(),
                    element::normalizedRoot,
                    config.machine(),
                    null,
                    null,
                    null,
                    null);
            VarProvider varProvider = machineHost == null
                    ? null
                    : template.varProvider(element);
            if (machineHost != null) {
                machineHost.setVarProvider(varProvider);
            }
            FsmAnimatedModel activeMachineHost = machineHost;
            java.util.function.Supplier<@Nullable StateMachine<?>> machine =
                    () -> activeMachineHost == null ? null : activeMachineHost.machine(level);
            stage = handleFactory == null
                    ? new RigidPartVisualStage(
                            identity.key(), config.parts(), machine, element.normalizedRoot())
                    : new RigidPartVisualStage(
                            identity.key(), config.parts(), machine,
                            element.normalizedRoot(), handleFactory);
            ProxyInputPort<ContraptionBogey, ContraptionBogey.HostFrame> input =
                    ContraptionBogey::hostFrame;
            ProxyControllerPort<ContraptionBogey.HostFrame, Void> controller =
                    controller(element, template, stage, machineHost, machine, varProvider);
            ProxyWritebackPort<ContraptionBogey, Void> writeback = (ignored, output) -> {
            };
            ProxyVisualPort<ContraptionBogey, ContraptionBogey.HostFrame> visual =
                    new CreateVisualPort(
                            element, detach, stage, machineHost, key, level);
            return ProxyComposition.of(
                    POLICY,
                    input,
                    controller,
                    writeback,
                    ProxyCommandPort.none(),
                    visual,
                    stage.targetBindings());
        } catch (RuntimeException | Error failure) {
            cleanup(stage, failure);
            cleanup(machineHost, level, failure);
            detach.suppress(failure);
            throw failure;
        }
    }

    private static ProxyConfig configure(
            ContraptionBogey element,
            ElementProxy<ContraptionBogey> template,
            DetachOnce detach
    ) {
        try {
            return Objects.requireNonNull(
                    template.configure(element),
                    "contraption bogey template returned null configuration");
        } catch (RuntimeException | Error failure) {
            detach.suppress(failure);
            throw failure;
        }
    }

    private static ProxyControllerPort<ContraptionBogey.HostFrame, Void> controller(
            ContraptionBogey element,
            ElementProxy<ContraptionBogey> template,
            RigidPartVisualStage stage,
            @Nullable FsmAnimatedModel machineHost,
            java.util.function.Supplier<@Nullable StateMachine<?>> machine,
            @Nullable VarProvider varProvider
    ) {
        return new ProxyControllerPort<>() {
            @Override
            public void prepare(ContraptionBogey.HostFrame input) {
                stage.prepareLogicalStep(element.normalizedRoot(input));
            }

            @Override
            public RuntimeController<Void> controller() {
                return machineHost == null
                        ? emptyController()
                        : new ProxyMachineController(
                                machine,
                                current -> template.pull(element, current),
                                current -> template.push(element, current),
                                varProvider);
            }

            @Override
            public Void output() {
                return null;
            }
        };
    }

    private static RuntimeController<Void> emptyController() {
        return RuntimeController.none();
    }

    private static void cleanup(
            @Nullable RigidPartVisualStage stage,
            Throwable primary
    ) {
        if (stage == null) {
            return;
        }
        try {
            stage.close();
        } catch (RuntimeException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private static void cleanup(
            @Nullable FsmAnimatedModel machineHost,
            @Nullable Level level,
            Throwable primary
    ) {
        if (machineHost == null) {
            return;
        }
        try {
            machineHost.onRemoved(level);
        } catch (RuntimeException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    /** Makes the legacy configure/detach contract single-shot across every attach failure path. */
    private static final class DetachOnce {

        private final ElementProxy<ContraptionBogey> template;
        private final ContraptionBogey element;
        private boolean detached;

        private DetachOnce(
                ElementProxy<ContraptionBogey> template,
                ContraptionBogey element
        ) {
            this.template = template;
            this.element = element;
        }

        private void suppress(Throwable primary) {
            if (!claim()) {
                return;
            }
            try {
                template.onDetach(element);
            } catch (RuntimeException | Error failure) {
                primary.addSuppressed(failure);
            }
        }

        private @Nullable RuntimeException failure(@Nullable RuntimeException current) {
            if (!claim()) {
                return current;
            }
            try {
                template.onDetach(element);
                return current;
            } catch (RuntimeException failure) {
                if (current == null) {
                    return failure;
                }
                current.addSuppressed(failure);
                return current;
            }
        }

        private synchronized boolean claim() {
            if (detached) {
                return false;
            }
            detached = true;
            return true;
        }
    }

    private static final class CreateVisualPort
            implements ProxyVisualPort<ContraptionBogey, ContraptionBogey.HostFrame> {

        private final ContraptionBogey element;
        private final DetachOnce detach;
        private final RigidPartVisualStage stage;
        @Nullable private final FsmAnimatedModel machineHost;
        private final ProxyKey<ContraptionBogey> key;
        @Nullable private final Level level;
        private boolean closed;

        private CreateVisualPort(ContraptionBogey element,
                                 DetachOnce detach,
                                 RigidPartVisualStage stage,
                                 @Nullable FsmAnimatedModel machineHost,
                                 ProxyKey<ContraptionBogey> key,
                                 @Nullable Level level) {
            this.element = element;
            this.detach = detach;
            this.stage = stage;
            this.machineHost = machineHost;
            this.key = key;
            this.level = level;
        }

        @Override
        public boolean isReady(ContraptionBogey element) {
            return !closed && stage.rebind();
        }

        @Override
        public void bind(AnimationRenderPort logicalRenderPort) {
            stage.bind(logicalRenderPort);
        }

        @Override
        public void tick(ContraptionBogey element) {
            if (closed) {
                return;
            }
            stage.maintainVisuals();
        }

        @Override
        public void publishFrame(ContraptionBogey.HostFrame frame) {
            if (!closed) {
                stage.publishFrame(element.normalizedRoot(frame));
            }
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
        public boolean rebind(ContraptionBogey element) {
            return isReady(element);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            try {
                stage.close();
            } catch (RuntimeException cleanupFailure) {
                failure = cleanupFailure;
            }
            try {
                if (machineHost != null) {
                    machineHost.onRemoved(level);
                }
            } catch (RuntimeException cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            try {
                failure = detach.failure(failure);
            } finally {
                ProxyRuntime.GLOBAL.unregister(key);
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
