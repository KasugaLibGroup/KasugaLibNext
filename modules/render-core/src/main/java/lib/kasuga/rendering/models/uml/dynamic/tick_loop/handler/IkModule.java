package lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler;

import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.structure.Model;

/** Evaluates one constrained animation/IK target for every standard skeleton motion state. */
public class IkModule implements ModelTickLoopModule {
    @Override
    public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float deltaTime) {
        loop.getInstance().getSkeletonInstance().evaluatePoseTarget();
    }
    @Override public void destroy(Model model) {}
}
