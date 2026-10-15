package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;

public interface Animator {
    void animate(PendingTransform[] transforms, float deltaTime);
}
