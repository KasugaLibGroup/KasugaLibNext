package test.kasuga.create.bogey;

import lib.kasuga.create.proxy.BogeyModels;
import lib.kasuga.create.proxy.BogeyProxy;
import lib.kasuga.create.proxy.BogeyRoots;
import lib.kasuga.rendering.models.mc.proxies.ModelPart;
import lib.kasuga.rendering.models.mc.proxies.ProxyIdentity;
import lib.kasuga.rendering.models.mc.proxies.RigidPartVisualStage;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyCommandPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyComposition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyControllerPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyInputPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyVisualPort;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Kernel composition for a bogey placed in the world.
 *
 * <p>The element supplies only its tick-domain wheel angle. The rigid-part visual stage has no
 * clock: its targets are registered in this session's scope, and its port only owns resources,
 * root preparation and render-frame writes.
 */
public final class ExampleBogeyProxyComposition {

    private static final LogicalStepPolicy POLICY = LogicalStepPolicy.fixed(1.0 / 20.0, 5);

    private ExampleBogeyProxyComposition() {
    }

    public static ProxyComposition<ExampleBogeyBlockEntity, PlacedBogeyInput, Void, Void,
            PlacedBogeyFrame> create(ExampleBogeyBlockEntity element) {
        FrozenTickInput tickInput = new FrozenTickInput(element.wheelAngle());
        Placement placement = placement(element, tickInput::wheelAngle);
        RigidPartVisualStage visual = new RigidPartVisualStage(
                placement.instance(), placement.parts(), () -> null, new Transform());
        return ProxyComposition.of(
                POLICY,
                inputPort(placement.yOffset()),
                controller(visual, tickInput),
                (host, output) -> {
                },
                ProxyCommandPort.none(),
                new PlacedBogeyVisualPort(visual),
                visual.targetBindings());
    }

    private static ProxyInputPort<ExampleBogeyBlockEntity, PlacedBogeyInput> inputPort(
            float yOffset
    ) {
        return element -> new PlacedBogeyInput(
                BogeyRoots.of(element, yOffset), element.wheelAngle());
    }

    private static ProxyControllerPort<PlacedBogeyInput, Void> controller(
            RigidPartVisualStage visual,
            FrozenTickInput tickInput
    ) {
        return new ProxyControllerPort<>() {
            @Override
            public void prepare(PlacedBogeyInput input) {
                tickInput.accept(input);
                visual.prepareLogicalStep(input.root());
            }

            @Override
            public RuntimeController<Void> controller() {
                return RuntimeController.none();
            }

            @Override
            public Void output() {
                return null;
            }
        };
    }

    private static Placement placement(
            ExampleBogeyBlockEntity element,
            ModelPart.Spin.AngleSource angleSource
    ) {
        Level level = element.getLevel();
        if (level == null) {
            throw new IllegalStateException("placed bogey proxy requires an attached level");
        }
        BogeyModels.Placement resolved = BogeyModels.resolve(
                element.getStyle(), BogeyProxy.sizeOf(element));
        if (resolved == null) {
            throw new IllegalStateException(
                    "placed bogey has no registered model: " + element.getStyle().id);
        }
        return new Placement(
                ProxyIdentity.ofBlock(level, element.getBlockPos()).key(),
                AllExampleBogey.spinningWheelsForTick(
                        resolved.parts(), angleSource),
                resolved.yOffset());
    }

    public record PlacedBogeyInput(Transform root, float wheelAngle) {
    }

    public record PlacedBogeyFrame() {
    }

    private record Placement(
            ResourceLocation instance,
            List<ModelPart> parts,
            float yOffset
    ) {
        private Placement {
            parts = List.copyOf(parts);
        }
    }

    private static final class FrozenTickInput {

        private float wheelAngle;

        private FrozenTickInput(float wheelAngle) {
            this.wheelAngle = wheelAngle;
        }

        private void accept(PlacedBogeyInput input) {
            wheelAngle = input.wheelAngle();
        }

        private float wheelAngle() {
            return wheelAngle;
        }
    }

    private static final class PlacedBogeyVisualPort
            implements ProxyVisualPort<ExampleBogeyBlockEntity, PlacedBogeyFrame> {

        private final RigidPartVisualStage visual;
        private boolean closed;

        private PlacedBogeyVisualPort(RigidPartVisualStage visual) {
            this.visual = visual;
        }

        @Override
        public boolean isReady(ExampleBogeyBlockEntity element) {
            return !closed;
        }

        @Override
        public void bind(AnimationRenderPort logicalRenderPort) {
            visual.bind(logicalRenderPort);
        }

        @Override
        public void tick(ExampleBogeyBlockEntity element) {
            Level level = element.getLevel();
            if (!closed && level != null && level.isClientSide()) {
                visual.maintainVisuals();
            }
        }

        @Override
        public void publishFrame(PlacedBogeyFrame frame) {
        }

        @Override
        public boolean rebind(ExampleBogeyBlockEntity element) {
            Level level = element.getLevel();
            return !closed && (level == null || !level.isClientSide() || visual.rebind());
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            visual.close();
        }
    }
}
