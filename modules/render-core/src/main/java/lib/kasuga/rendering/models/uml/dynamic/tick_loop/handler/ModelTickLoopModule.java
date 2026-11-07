package lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler;

import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.structure.Model;

public interface ModelTickLoopModule {

    /**
     * Processes the model-root transform at index {@code 0} and the transform
     * for {@code Skeleton.getBones()[i]} at index {@code i + 1}.
     */
    void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float deltaTime);

    /** Fixed-step controller hook; animation, IK and frame callbacks are not repeated here. */
    default void beforePhysicsStep(Model model, ModelTickLoop loop) {}

    /** Physics-slot commit hook after a shared-world barrier, before downstream observers run. */
    default void afterSharedPhysics(Model model, PendingTransform[] transforms,
                                    ModelTickLoop loop, float deltaTime) {}

    void destroy(Model model);
}
