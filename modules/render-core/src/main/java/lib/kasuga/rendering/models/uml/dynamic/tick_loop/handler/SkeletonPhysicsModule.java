package lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler;

import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.structure.Model;

/** Integrates the standard skeleton's constrained relaxation state, or submits it to a shared barrier. */
public class SkeletonPhysicsModule implements ModelTickLoopModule {
    @Override
    public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float deltaTime) {
        var skeleton = loop.getInstance().getSkeletonInstance();
        var relaxation = skeleton.getRelaxation();
        if (relaxation == null || !relaxation.enabled()) return;
        relaxation.captureAnimationTarget();
        if (!loop.isSharedPhysics()) relaxation.stepPrepared(deltaTime);
    }

    @Override
    public void beforePhysicsStep(Model model, ModelTickLoop loop) {
        var skeleton = loop.getInstance().getSkeletonInstance();
        var drive = skeleton.getPoseDrive();
        var relaxation = skeleton.getRelaxation();
        if (drive == null || drive.weight() <= 0f || relaxation == null || !relaxation.enabled()) return;
        lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonPoseDrive.apply(relaxation, java.util.Set.of(),
                drive.frequencyHz(), drive.dampingRatio(), drive.maximumForce(), drive.maximumTorque(), drive.weight());
    }

    @Override
    public void afterSharedPhysics(Model model, PendingTransform[] transforms,
                                   ModelTickLoop loop, float deltaTime) {
        var relaxation = loop.getInstance().getSkeletonInstance().getRelaxation();
        if (relaxation != null && relaxation.enabled()) relaxation.finishSharedStep();
    }

    @Override public void destroy(Model model) {}
}
