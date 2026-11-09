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

    @Nullable
    private volatile ModelInstance instance;
    @Nullable
    private volatile TargetPublication<RigidPartOutput> publication;
    @Nullable
    private volatile AnimationRenderPort renderPort;
    private volatile boolean closed;

    RigidPartBinding(int index, PartMotionSampler sampler,
                     Supplier<@Nullable StateMachine<?>> machine,
                     ModelGroupFrameState frameState, Transform local) {
        this.ref = AnimationTargetRef.part(index);
        this.index = index;
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.machine = Objects.requireNonNull(machine, "machine");
        this.frameState = Objects.requireNonNull(frameState, "frameState");
        this.local = Objects.requireNonNull(local, "local").copy();
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
        Transform placement = frame.root().at(partialTick).mul(local);
        Transform localMotion = frame.motion(index).sample(partialTick);
        if (localMotion != null) {
            placement.mul(localMotion);
        }
        model.getSkeletonInstance().transformRootWorld(placement);
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
            Transform placement = frame.root().at(1f).mul(local);
            Transform frameMotion = frame.motion(index).sample(
                    (float) context.interpolationAlpha());
            if (frameMotion != null) {
                placement.mul(frameMotion);
            }
            model.getSkeletonInstance().transformRootWorld(placement);
            return;
        }
        Transform placement = active.currentOutput().sample(context.interpolationAlpha());
        model.getSkeletonInstance().transformRootWorld(placement);
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
    }
}
