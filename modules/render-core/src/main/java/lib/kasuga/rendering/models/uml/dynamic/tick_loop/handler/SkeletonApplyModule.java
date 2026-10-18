package lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.TransformLimitation;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;

/**
 * Flushes the tick loop's pending transforms into the skeleton.
 *
 * <p>Only non-identity slots are written, so bones driven purely by a
 * {@code PoseDriver} keep their sampled locals untouched. Evaluation of the
 * hierarchy is NOT performed here — that is the IK stage's responsibility
 * ({@link IkModule}), keeping "write" and "solve" independently mountable.</p>
 */
public class SkeletonApplyModule implements ModelTickLoopModule {

    private final TransformLimitation limitation;

    public SkeletonApplyModule() {
        this(new TransformLimitation());
    }

    public SkeletonApplyModule(TransformLimitation limitation) {
        this.limitation = limitation;
    }

    @Override
    public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float deltaTime) {
        ModelInstance instance = loop.getInstance();
        // Alloc-free identity screening: only touched slots allocate a Transform.
        if (!transforms[0].isIdentity()) {
            instance.getSkeletonInstance().transformRoot(
                    transforms[0].process(new Transform(), limitation));
        }

        Bone[] bones = model.getSkeleton().getBones();
        for (int i = 0; i < bones.length; i++) {
            PendingTransform pending = transforms[i + 1];
            if (pending.isIdentity()) continue;
            instance.getSkeletonInstance().transform(bones[i], pending.process(new Transform(), limitation));
        }
    }

    @Override
    public void destroy(Model model) {}
}
