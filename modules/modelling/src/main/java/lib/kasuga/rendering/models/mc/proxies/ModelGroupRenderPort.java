package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.FrameInputKey;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;

import java.util.Objects;

/**
 * Joins a frame-domain model-group update to the committed logical target batch sampled for the
 * same render call.
 */
final class ModelGroupRenderPort implements AnimationRenderPort {

    static final FrameInputKey<ModelGroupFrame> FRAME_INPUT =
            new FrameInputKey<>("kasuga_lib/model-group/frame", ModelGroupFrame.class);

    private final AnimationRenderPort logicalPort;
    private final ModelGroupFrameState frames;
    private final Object publicationLock;

    ModelGroupRenderPort(
            AnimationRenderPort logicalPort,
            ModelGroupFrameState frames,
            Object publicationLock
    ) {
        this.logicalPort = Objects.requireNonNull(logicalPort, "logicalPort");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.publicationLock = Objects.requireNonNull(publicationLock, "publicationLock");
    }

    @Override
    public void sample(RenderSampleContext context) {
        Objects.requireNonNull(context, "context");
        synchronized (publicationLock) {
            ModelGroupFrameState.Publication published = frames.snapshot();
            ModelGroupFrame frame = published.frame();
            RenderSampleContext joined = published.frameInput() && frame != null
                    ? new RenderSampleContext(
                            context.frameToken(),
                            context.interpolationAlpha(),
                            context.frameInputs().with(FRAME_INPUT, frame))
                    : context;
            logicalPort.sample(joined);
        }
    }
}
