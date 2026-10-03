package lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.MmdRagdoll;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.structure.Model;

/**
 * Advances the instance's attached ragdoll and lets it write the simulated
 * pose back to the skeleton. Pure physics: all integration, collision and
 * joint solving happen inside native Box3D; ordering is owned by
 * {@link ModelTickLoop}, not by this module.
 *
 * <p>No-op when the instance has no ragdoll or physics is disabled, so the
 * module can safely stay mounted for the lifetime of the loop.</p>
 */
public class RagdollModule implements ModelTickLoopModule {

    @Override
    public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float deltaTime) {
        ModelInstance instance = loop.getInstance();
        MmdRagdoll ragdoll = instance.getRagdoll();
        if (ragdoll == null || !ragdoll.enabled()) return;
        instance.getMorph().update();
        ragdoll.step(deltaTime);
    }

    @Override
    public void destroy(Model model) {}
}
