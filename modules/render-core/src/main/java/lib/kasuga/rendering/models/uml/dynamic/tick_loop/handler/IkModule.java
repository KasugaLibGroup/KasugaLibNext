package lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.structure.Model;

/**
 * Evaluates the skeleton hierarchy and solves PMX IK once per tick.
 *
 * <p>This stage is skipped while physics owns the pose — the ragdoll's
 * kinematic-target evaluation performs the same animation + IK solve against
 * identical inputs, so solving here would only duplicate the work.</p>
 */
public class IkModule implements ModelTickLoopModule {

    @Override
    public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float deltaTime) {
        ModelInstance instance = loop.getInstance();
        MmdRagdoll ragdoll = instance.getRagdoll();
        if (ragdoll != null && ragdoll.enabled()) return;
        instance.getSkeletonInstance().updateTransform();
    }

    @Override
    public void destroy(Model model) {}
}
