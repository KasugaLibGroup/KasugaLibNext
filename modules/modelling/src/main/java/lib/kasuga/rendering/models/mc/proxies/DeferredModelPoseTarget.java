package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.output.PoseOutput;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTarget;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTargetRef;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublication;
import lib.kasuga.rendering.models.uml.dynamic.animation.target.ModelPoseTarget;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Pose target that can exist before the model resource is published. It preserves the latest
 * logical publication and hands it to the first successfully bound model.
 */
public final class DeferredModelPoseTarget implements AnimationTarget<PoseOutput> {

    @Nullable
    private ModelPoseTarget delegate;
    @Nullable
    private TargetPublication<PoseOutput> latest;
    private boolean closed;

    @Override
    public AnimationTargetRef ref() {
        return AnimationTargetRef.modelPose();
    }

    public synchronized void rebind(ModelInstance model) {
        Objects.requireNonNull(model, "model");
        if (closed) {
            throw new IllegalStateException("deferred model pose target is closed");
        }
        if (delegate == null) {
            delegate = new ModelPoseTarget(model);
        } else {
            delegate.rebind(model);
        }
        if (latest != null) {
            delegate.publish(latest);
        }
    }

    @Override
    public synchronized void publish(TargetPublication<PoseOutput> publication) {
        Objects.requireNonNull(publication, "publication");
        if (closed) {
            throw new IllegalStateException("deferred model pose target is closed");
        }
        latest = publication;
        if (delegate != null) {
            delegate.publish(publication);
        }
    }

    @Override
    public synchronized void sample(RenderSampleContext context) {
        if (!closed && delegate != null) {
            delegate.sample(context);
        }
    }

    @Override
    public synchronized void sample(TargetPublication<PoseOutput> publication,
                                    RenderSampleContext context) {
        if (!closed && delegate != null) {
            delegate.sample(publication, context);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        latest = null;
        if (delegate != null) {
            delegate.close();
            delegate = null;
        }
    }
}
