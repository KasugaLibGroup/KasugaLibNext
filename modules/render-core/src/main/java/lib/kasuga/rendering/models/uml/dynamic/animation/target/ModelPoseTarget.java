package lib.kasuga.rendering.models.uml.dynamic.animation.target;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.output.PoseOutput;
import lib.kasuga.rendering.models.uml.dynamic.animation.output.PoseOutputInterpolator;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTarget;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTargetRef;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderFrameToken;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublication;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ModelInstancePoseSink;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Render endpoint for the model-pose channel.
 *
 * <p>The target only stores committed pose results and resolves them while sampling one render
 * frame. It never advances animation sources or logical time. Rebinding changes the destination
 * model while preserving the latest publication.
 */
public final class ModelPoseTarget implements AnimationTarget<PoseOutput> {

    private static final AnimationTargetRef REF = AnimationTargetRef.modelPose();

    private final PoseOutputInterpolator interpolator = new PoseOutputInterpolator();

    @Nullable
    private ModelInstance model;
    @Nullable
    private ModelInstancePoseSink sink;
    @Nullable
    private TargetPublication<PoseOutput> publication;
    @Nullable
    private RenderFrameToken lastSampleToken;
    private boolean closed;

    public ModelPoseTarget(ModelInstance model) {
        this.model = Objects.requireNonNull(model, "model");
        this.sink = new ModelInstancePoseSink(model);
    }

    @Override
    public AnimationTargetRef ref() {
        return REF;
    }

    @Override
    public synchronized void publish(TargetPublication<PoseOutput> publication) {
        Objects.requireNonNull(publication, "publication");
        if (closed) {
            throw new IllegalStateException("model pose target is closed");
        }
        if (!REF.equals(publication.ref())) {
            throw new IllegalArgumentException(
                    "publication target must be " + REF + ": " + publication.ref());
        }
        this.publication = publication;
    }

    @Override
    public synchronized void sample(RenderSampleContext context) {
        TargetPublication<PoseOutput> currentPublication = publication;
        if (currentPublication == null) {
            return;
        }
        sample(currentPublication, context);
    }

    @Override
    public synchronized void sample(
            TargetPublication<PoseOutput> publication,
            RenderSampleContext context
    ) {
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(context, "context");
        if (context.frameToken().equals(lastSampleToken)) {
            return;
        }
        lastSampleToken = context.frameToken();
        ModelInstancePoseSink currentSink = sink;
        if (currentSink == null) {
            return;
        }
        Pose pose = interpolator.interpolate(
                publication.outputState(), context.interpolationAlpha());
        currentSink.applyPose(pose);
    }

    /**
     * Moves subsequent render writes to another model. The publication, including its revision and
     * previous/current outputs, remains unchanged.
     */
    public synchronized void rebind(ModelInstance model) {
        Objects.requireNonNull(model, "model");
        if (closed) {
            throw new IllegalStateException("model pose target is closed");
        }
        if (this.model == model) {
            return;
        }
        this.model = model;
        this.sink = new ModelInstancePoseSink(model);
    }

    public synchronized @Nullable ModelInstance model() {
        return model;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (sink != null) {
            sink.applyPose(Pose.empty());
        }
        sink = null;
        model = null;
        publication = null;
        lastSampleToken = null;
    }
}
