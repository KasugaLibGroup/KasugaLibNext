package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTarget;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTargetRef;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.FrameInputKey;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.OutputState;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublication;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TimelineValueHistory;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ModelInstancePoseSink;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.dynamic.fsm.PoseBlend;

import java.util.List;
import java.util.Objects;

/**
 * Render endpoint for one standalone player. It keeps the committed substep poses for the current
 * host interval and resolves the interpolated logical time without calling the animation source.
 */
final class ScopedPlayerPoseTarget implements AnimationTarget<ScopedPlayerPoseTarget.Output> {

    static final FrameInputKey<Double> RENDER_TIME =
            new FrameInputKey<>("animation-player/render-time", Double.class);

    private static final AnimationTargetRef REF = AnimationTargetRef.modelPose();

    private final TimelineValueHistory<Pose> history = new TimelineValueHistory<>();
    private ModelInstance model;
    private ModelInstancePoseSink sink;
    private boolean closed;

    ScopedPlayerPoseTarget(ModelInstance model) {
        this.model = Objects.requireNonNull(model, "model");
        this.sink = new ModelInstancePoseSink(model);
    }

    @Override
    public AnimationTargetRef ref() {
        return REF;
    }

    @Override
    public synchronized void publish(TargetPublication<Output> publication) {
        Objects.requireNonNull(publication, "publication");
        if (closed) {
            throw new IllegalStateException("scoped player pose target is closed");
        }
        if (!REF.equals(publication.ref())) {
            throw new IllegalArgumentException(
                    "publication target must be " + REF + ": " + publication.ref());
        }
        if (!(publication.outputState() instanceof OutputState.Active<Output> active)) {
            return;
        }
        history.append(active.currentOutput().samples().stream()
                .map(sample -> new TimelineValueHistory.TimedValue<>(sample.time(), sample.pose()))
                .toList());
    }

    /** Drops outputs older than the previous host boundary before the next Scope advance. */
    synchronized void beginHostAdvance(double previousTime) {
        history.retainFrom(previousTime);
    }

    synchronized void resetHistory() {
        history.clear();
    }

    @Override
    public synchronized void sample(RenderSampleContext context) {
        Objects.requireNonNull(context, "context");
        history.resolveFrame(
                context,
                RENDER_TIME,
                "scoped player render time is missing",
                PoseBlend::blend).ifPresent(sink::applyPose);
    }

    @Override
    public synchronized void sample(
            TargetPublication<Output> publication,
            RenderSampleContext context
    ) {
        sample(context);
    }

    synchronized void rebind(ModelInstance fresh) {
        Objects.requireNonNull(fresh, "fresh");
        if (closed) {
            throw new IllegalStateException("scoped player pose target is closed");
        }
        if (model == fresh) {
            return;
        }
        model = fresh;
        sink = new ModelInstancePoseSink(fresh);
    }

    synchronized ModelInstance model() {
        return model;
    }

    synchronized void clearPose() {
        if (!closed && sink != null) {
            sink.applyPose(Pose.empty());
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        history.clear();
        sink = null;
        model = null;
    }

    record Output(List<TimedPose> samples) {
        Output {
            samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
            if (samples.isEmpty()) {
                throw new IllegalArgumentException("samples must not be empty");
            }
        }
    }

    record TimedPose(double time, Pose pose) {

        TimedPose {
            if (!Double.isFinite(time) || time < 0.0) {
                throw new IllegalArgumentException("time must be finite and non-negative");
            }
            Objects.requireNonNull(pose, "pose");
        }
    }
}
