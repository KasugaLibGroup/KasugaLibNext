package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.RebindableRootTransformDriver;
import lib.kasuga.rendering.models.uml.dynamic.RootTransformDriver;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTarget;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTargetRef;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.OutputState;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublication;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Sole owner of one rigid part's model instance, placement composition, and root write.
 *
 * <p>The model group publishes a logical tick into the pure {@link PartMotionSampler}. On the render
 * frame this binding composes {@code basePlacement × localMotion} and writes the instance root. It
 * never occupies or advances the model's internal pose slot.
 */
final class RigidPartBinding
        implements AnimationTarget<RigidPartOutput>, RebindableRootTransformDriver {

    private final AnimationTargetRef ref;
    private final int index;
    private final PartMotionSampler sampler;
    private final Supplier<@Nullable StateMachine<?>> machine;
    private final ModelGroupFrameState frameState;
    private final Transform local;
    private final Vector3f worldLightOffset;

    @Nullable
    private volatile ModelInstance instance;
    @Nullable
    private volatile TargetPublication<RigidPartOutput> publication;
    @Nullable
    private volatile AnimationRenderPort renderPort;
    @Nullable
    private volatile Vector3d worldLightPosition;
    private volatile boolean closed;

    RigidPartBinding(int index, PartMotionSampler sampler,
                     Supplier<@Nullable StateMachine<?>> machine,
                     ModelGroupFrameState frameState, Transform local,
                     Vector3f worldLightOffset) {
        this.ref = AnimationTargetRef.part(index);
        this.index = index;
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.machine = Objects.requireNonNull(machine, "machine");
        this.frameState = Objects.requireNonNull(frameState, "frameState");
        this.local = Objects.requireNonNull(local, "local").copy();
        this.worldLightOffset = new Vector3f(Objects.requireNonNull(worldLightOffset, "worldLightOffset"));
        if (!this.worldLightOffset.isFinite()) {
            throw new IllegalArgumentException("worldLightOffset must be finite");
        }
    }

    RigidPartBinding(int index, PartMotionSampler sampler,
                     Supplier<@Nullable StateMachine<?>> machine,
                     ModelGroupFrameState frameState, Transform local) {
        this(index, sampler, machine, frameState, local, new Vector3f());
    }

    @Override
    public AnimationTargetRef ref() {
        return ref;
    }

    void attachRenderPort(AnimationRenderPort port) {
        renderPort = Objects.requireNonNull(port, "port");
        ModelInstance current = instance;
        if (current != null) {
            current.setAnimationRenderPort(port);
        }
    }

    /** Installs this binding as the model's only root owner. Repeated calls are no-ops. */
    void attachTo(ModelInstance target) {
        Objects.requireNonNull(target, "target");
        if (closed) {
            throw new IllegalStateException("rigid part binding is closed: " + ref);
        }
        if (instance == target && target.getRootTransformDriver() == this) {
            return;
        }
        RootTransformDriver currentOwner = target.getRootTransformDriver();
        if (currentOwner != null && currentOwner != this) {
            throw new IllegalStateException("model root is already owned by "
                    + currentOwner.getClass().getName() + ": " + ref);
        }
        detachFromCurrentInstance();
        instance = target;
        target.setRootTransformDriver(this);
        target.setAnimationRenderPort(renderPort);
    }

    @Override
    public void publish(TargetPublication<RigidPartOutput> publication) {
        this.publication = Objects.requireNonNull(publication, "publication");
    }

    /** Publishes one host-frame motion snapshot without advancing logical tick state. */
    void publishFrame() {
        if (!closed) {
            sampler.publishFrame();
        }
    }

    int index() {
        return index;
    }

    @Override
    public @Nullable ModelInstance owner() {
        return instance;
    }

    PartMotionSnapshot motionSnapshot() {
        return sampler.snapshot();
    }

    RigidPartOutput evaluate(RootSnapshot root) {
        sampler.publishTick(machine.get());
        return new RigidPartOutput(root, sampler.snapshot(), local);
    }

    @Override
    public void sample(
            TargetPublication<RigidPartOutput> publication,
            RenderSampleContext context
    ) {
        sampleOutput(publication, context);
    }

    @Override
    public void sample(RenderSampleContext context) {
        TargetPublication<RigidPartOutput> current = publication;
        if (current != null) {
            sampleOutput(current, context);
        }
    }

    @Override
    public void sampleRoot(float partialTick) {
        if (!Float.isFinite(partialTick)) {
            throw new IllegalArgumentException("partialTick must be finite");
        }
        ModelInstance model = instance;
        if (closed || model == null) {
            return;
        }
        ModelGroupFrameState.Publication publication = frameState.snapshot();
        ModelGroupFrame frame = publication.frame();
        if (frame == null) {
            return;
        }
        writeRoot(model, frame.root().at(partialTick), frame.motion(index).sample(partialTick));
    }

    @Override
    public @Nullable Vector3dc worldLightPosition() {
        Vector3d current = worldLightPosition;
        return current == null ? null : new Vector3d(current);
    }

    private void sampleOutput(
            TargetPublication<RigidPartOutput> publication,
            RenderSampleContext context
    ) {
        ModelInstance model = instance;
        if (closed || model == null) {
            return;
        }
        if (publication.outputState() instanceof OutputState.Cleared<RigidPartOutput>) {
            return;
        }
        OutputState.Active<RigidPartOutput> active =
                (OutputState.Active<RigidPartOutput>) publication.outputState();
        ModelGroupFrame frame = context.frameInputs()
                .value(ModelGroupRenderPort.FRAME_INPUT)
                .orElse(null);
        if (frame != null) {
            Transform frameMotion = frame.motion(index).sample(
                    (float) context.interpolationAlpha());
            writeRoot(model, frame.root().at(1f), frameMotion);
            return;
        }
        RigidPartOutput output = active.currentOutput();
        writeRoot(model, output.root().at((float) context.interpolationAlpha()),
                output.motion().sample((float) context.interpolationAlpha()));
    }

    private void writeRoot(ModelInstance model, Transform groupRoot, @Nullable Transform localMotion) {
        Transform placement = groupRoot.copy().mul(local);
        if (localMotion != null) {
            placement.mul(localMotion);
        }
        model.getSkeletonInstance().transformRootWorld(placement);
        worldLightPosition = new Vector3d(groupRoot.apply(new Vector3f(worldLightOffset)));
    }

    @Override
    public void rebind(ModelInstance fresh) {
        attachTo(fresh);
    }

    @Override
    public void detach(ModelInstance model) {
        if (!closed && instance == model) {
            detachFromCurrentInstance();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        detachFromCurrentInstance();
    }

    private void detachFromCurrentInstance() {
        ModelInstance current = instance;
        instance = null;
        publication = null;
        if (current != null && current.getRootTransformDriver() == this) {
            current.setRootTransformDriver(null);
            current.setAnimationRenderPort(null);
        }
        worldLightPosition = null;
    }
}
