package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;

public final class AnimationInstance implements Animator {
    private final EventTrack<KeyframeInstance> track;

    public AnimationInstance(EventTrack<KeyframeInstance> track) {
        this.track = track;
    }

    @Override
    public void animate(PendingTransform[] transforms, float deltaTime) {

    }
}
